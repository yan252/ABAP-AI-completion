package com.sap.abap.ai.completion.editor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.parser.AbapLanguageDetector;
import com.sap.abap.ai.completion.logging.AILogger;
import com.sap.abap.ai.completion.preferences.AIConfiguration;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

/**
 * Listens to document changes and triggers AI code completion.
 * Shows results in a floating overlay (like Copilot).
 * Tab to accept, any other key to dismiss.
 *
 * Uses TWO mechanisms for triggering auto-completion:
 * 1. IDocumentListener - when editors fire document change events
 * 2. Content hashing via polling - detects any document change by comparing
 *    content hash every 800ms (works for ALL editors including ABAP)
 */
public class AICompletionListener implements IDocumentListener, IPartListener {

    private static final ILog PLATFORM_LOG = Platform.getLog(
            Platform.getBundle("com.sap.abap.ai.completion"));

    private static void plog(String msg) {
        PLATFORM_LOG.log(new Status(IStatus.INFO, "com.sap.abap.ai.completion", msg));
    }

    private ITextEditor editor;
    private IDocument document;
    private IProject currentProject;
    private IFile currentFile;
    private ITextViewer viewer;

    private CompletableFuture<?> currentRequest;
    private long lastModifiedTime = 0;
    private String lastContentHash = "";

    private final AIOverlayManager overlayManager = new AIOverlayManager();

    /** Background polling for auto-completion. */
    private Thread pollThread;
    private volatile boolean polling = false;
    private static final int POLL_INTERVAL_MS = 400;

    /** 上一次补全时的光标 offset，用于去重判断 */
    private int lastTriggerOffset = -1;
    /** 上一次补全时的内容 hash，用于去重判断 */
    private String lastTriggerHash = "";
    /** 上一次补全的光标 offset + 结果（5项需求） */
    private int lastCompletionOffset = -1;
    /** 补全结果: 0=无,1=ESC取消,2=确认,3=其它 */
    private int lastCompletionResult = PreferenceConstants.COMPLETION_RESULT_NONE;

    /**
     * Attaches this listener to the currently active editor.
     */
    public boolean attachToActiveEditor() {
        detach();

        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                plog("attachToActiveEditor: no active workbench window");
                return false;
            }

            IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                plog("attachToActiveEditor: no active page");
                return false;
            }

            page.addPartListener(this);

            IEditorPart activeEditor = page.getActiveEditor();
            if (activeEditor == null) {
                plog("attachToActiveEditor: no active editor");
                return false;
            }
            plog("attachToActiveEditor: active editor class="
                    + activeEditor.getClass().getName());
            attachToEditorPart(activeEditor);
            return true;
        } catch (Exception e) {
            plog("attachToActiveEditor: exception " + e.getMessage());
            return false;
        }
    }

    public void attachToEditorPart(IEditorPart editorPart) {
        ITextEditor te = null;
        if (editorPart instanceof ITextEditor) {
            te = (ITextEditor) editorPart;
        } else if (editorPart != null) {
            // 兜底：尝试通过 adapter 获取 ITextEditor（部分 ADT 编辑器可能不直接实现该接口）
            te = editorPart.getAdapter(ITextEditor.class);
        }
        if (te == null) {
            plog("attachToEditorPart: cannot obtain ITextEditor from "
                    + (editorPart == null ? "null" : editorPart.getClass().getName()));
            return;
        }
        // ABAP 门控: 非 ABAP 编辑器不附加监听
        if (!isAbapEditor(te)) {
            plog("attachToEditorPart: not an ABAP editor, id=" + te.getSite().getId());
            return;
        }
        plog("attachToEditorPart: ABAP editor detected, attaching");
        attachToEditor(te);
    }

    /**
     * 判断文本编辑器是否为 ABAP 上下文。
     * 通过编辑器 ID、文件扩展名、文档分区类型组合判断;
     * 内容启发式作为最后兜底。
     */
    private boolean isAbapEditor(ITextEditor te) {
        try {
            IEditorInput input = te.getEditorInput();
            org.eclipse.core.resources.IFile file = null;
            if (input instanceof FileEditorInput) {
                file = ((FileEditorInput) input).getFile();
            }
            IDocument doc = null;
            IDocumentProvider dp = te.getDocumentProvider();
            if (dp != null && input != null) {
                doc = dp.getDocument(input);
            }
            return AbapLanguageDetector.isAbapContext(te, file, doc);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attaches to a specific text editor.
     */
    public void attachToEditor(ITextEditor textEditor) {
        detach();
        this.editor = textEditor;

        if (textEditor == null) return;

        IDocumentProvider docProvider = textEditor.getDocumentProvider();
        if (docProvider == null) {
            plog("attachToEditor: documentProvider is null");
            return;
        }

        IEditorInput input = textEditor.getEditorInput();
        this.document = docProvider.getDocument(input);
        if (this.document == null) {
            plog("attachToEditor: document is null, input="
                    + (input == null ? "null" : input.getName()));
            return;
        }

        // Get file and project（含 ADT 远程文件的反射适配）
        this.currentFile = getFile(input);
        if (this.currentFile != null) {
            this.currentProject = this.currentFile.getProject();
        }

        // Try to get the text viewer
        this.viewer = textEditor.getAdapter(ITextViewer.class);

        String fileName = currentFile != null ? currentFile.getName() : "unknown";
        AILogger.logDiagnostic("AutoCompletion",
                "attached to ABAP editor: " + fileName
                        + ", autoCompletionEnabled=" + AIConfiguration.isAutoCompletionEnabled()
                        + ", pluginEnabled=" + AIConfiguration.isPluginEnabled()
                        + ", delay=" + AIConfiguration.getAutoCompleteDelay() + "ms"
                        + ", activeTriggerChars=" + AIConfiguration.getActiveTriggerChars().size());

        // Record initial content hash
        try {
            this.lastContentHash = computeHash(document.get());
        } catch (Exception e) {
            this.lastContentHash = "";
        }
        this.lastModifiedTime = System.currentTimeMillis();

        // 重置本次编辑会话的补全触发状态并绑定补全结果回调
        lastCompletionOffset = -1;
        lastCompletionResult = PreferenceConstants.COMPLETION_RESULT_NONE;
        lastTriggerOffset = -1;
        lastTriggerHash = "";
        overlayManager.setCompletionResultListener(result -> lastCompletionResult = result);

        // Register document listener
        this.document.addDocumentListener(this);

        // Start polling for ALL editors (catches ABAP editor which may not fire events)
        startPolling();
    }

    /**
     * Detaches from the current editor.
     */
    public void detach() {
        stopPolling();
        if (document != null) {
            document.removeDocumentListener(this);
            document = null;
        }
        overlayManager.hideOverlay();
        overlayManager.setCompletionResultListener(null);
        editor = null;
        viewer = null;
        currentFile = null;
        currentProject = null;
        cancelCurrentRequest();
    }

    /**
     * Disposes this listener completely.
     */
    public void dispose() {
        detach();
        overlayManager.dispose();
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null && window.getActivePage() != null) {
                window.getActivePage().removePartListener(this);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Manually triggers a completion (from handler).
     */
    public void triggerManualCompletion(ITextEditor editor, IDocument document,
                                         ITextViewer viewer, IFile file, IProject project) {
        if (!AIConfiguration.isPluginEnabled()) return;

        this.editor = editor;
        this.document = document;
        this.viewer = viewer;
        this.currentFile = file;
        this.currentProject = project;

        cancelCurrentRequest();
        overlayManager.hideOverlay();

        int cursorOffset = getCursorOffset();
        if (cursorOffset < 0) return;

        String textBefore = "";
        String textAfter = "";
        try {
            textBefore = document.get(0, Math.min(cursorOffset, document.getLength()));
            int afterStart = Math.min(cursorOffset, document.getLength());
            textAfter = document.get(afterStart, document.getLength() - afterStart);
        } catch (Exception e) {
            return;
        }
        String fullDocument = document.get();

        triggerCompletion(file, textBefore, textAfter, fullDocument, project, cursorOffset);
    }

    // ==================== IDocumentListener ====================

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        // no-op
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        if (!AIConfiguration.isPluginEnabled() || !AIConfiguration.isAutoCompletionEnabled()) {
            return;
        }

        // Hide overlay when user types
        if (overlayManager.isOverlayVisible()) {
            overlayManager.hideOverlay();
        }

        markContentChanged();
    }

    // ==================== Polling Timer ====================

    /**
     * Background thread that polls the document content by comparing
     * a hash of the full content. This DETECTS changes even when
     * IDocumentListener does NOT fire (e.g. ABAP Editor).
     */
    private void startPolling() {
        stopPolling();
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                if (!polling) break;

                // Check if auto-complete is enabled
                if (!AIConfiguration.isPluginEnabled() || !AIConfiguration.isAutoCompletionEnabled()) {
                    // Even when disabled, we still need to detect re-enable
                    // So just skip triggering but still check hash
                    pollCheckHashOnly();
                    continue;
                }

                // Polling auto-completion check
                plog("poll tick: elapsed=" + (System.currentTimeMillis() - lastModifiedTime)
                        + "ms, delay=" + AIConfiguration.getAutoCompleteDelay() + "ms");
                performPollingCheck();
            }
        }, "ai-completion-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null && pollThread.isAlive()) {
            pollThread.interrupt();
            try { pollThread.join(1000); } catch (InterruptedException e) { }
            pollThread = null;
        }
    }

    /**
     * Checks if document content has changed and triggers auto-completion
     * after the configured delay.
     */
    private void performPollingCheck() {
        // Detect content change by comparing hash
        String currentHash = pollCheckHashOnly();
        if (currentHash == null) return; // document not available

        long now = System.currentTimeMillis();
        long elapsed = now - lastModifiedTime;

        int delay = AIConfiguration.getAutoCompleteDelay();

        // If enough time has passed since last change, trigger completion
        if (elapsed >= delay) {
            // Don't trigger if overlay is already showing something
            if (overlayManager.isOverlayVisible()) {
                return;
            }
            Display.getDefault().asyncExec(this::triggerPollingCompletion);
        }
    }

    /**
     * Computes hash of current document content.
     * Returns the hash string, or null if document is not available.
     */
    private String pollCheckHashOnly() {
        if (document == null) return null;
        try {
            String content = document.get();
            String hash = computeHash(content);

            // Compare with last known hash
            if (!hash.equals(lastContentHash)) {
                // Content changed! Update tracking
                lastContentHash = hash;
                lastModifiedTime = System.currentTimeMillis();

                // Hide overlay when content changes
                if (overlayManager.isOverlayVisible()) {
                    Display.getDefault().asyncExec(() -> overlayManager.hideOverlay());
                }
            }
            return hash;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Marks content as changed (called from IDocumentListener).
     */
    public void markContentChanged() {
        lastModifiedTime = System.currentTimeMillis();
        // Hash will be updated by the next poll cycle
    }

    // ==================== Completion Trigger ====================

    private void triggerPollingCompletion() {
        // currentFile 可以为 null（SAP ADT 远程文件），AICompletionService 能处理
        if (editor == null || document == null) return;

        plog("triggerPollingCompletion entered, cursorOffset=" + getCursorOffset());

        // 提示窗口还没关闭 -> 直接退出（不再重复发送补全）
        if (overlayManager.isOverlayVisible()) return;
        // 规则3：上一次补全请求尚未结束 -> 本次补全不能开始。
        // 不取消旧请求，待其结束后的下一轮轮询再评估。
        if (currentRequest != null && !currentRequest.isDone()) {
            debugLog("skip trigger: previous completion still in progress");
            return;
        }

        // 有选中文本时不触发自动补全（用户正在选择代码）
        if (hasSelection()) return;

        int cursorOffset = getCursorOffset();
        if (cursorOffset < 0) return;

        try {
            String textBefore = document.get(0, Math.min(cursorOffset, document.getLength()));
            int afterStart = Math.min(cursorOffset, document.getLength());
            String textAfter = document.get(afterStart, document.getLength() - afterStart);
            String fullDocument = document.get();
            String currentHash = computeHash(fullDocument);

            // Update hash to current content
            lastContentHash = currentHash;

            // 触发条件判断（规则3：光标前为空/全空格触发；规则4：活动触发字符后缀匹配触发；
            // 规则6：光标前后都有非空字符则退出）
            if (!shouldTriggerAutoCompletion(textBefore, textAfter)) {
                int lastNl = textBefore.lastIndexOf('\n');
                String beforeLine = textBefore.substring(lastNl + 1);
                debugLog("skip trigger: condition not met, linePrefix='"
                        + beforeLine.trim() + "'");
                return;
            }

            // 用行内容（trim 后）+ offset 做去重，避免"X = "（光标在空格后）因 hash 未变而被误判为重复
            int lastNl = textBefore.lastIndexOf('\n');
            String lineContent = textBefore.substring(lastNl + 1);
            String dedupKey = cursorOffset + "|" + lineContent.trim();

            if (dedupKey.equals(lastTriggerHash)) {
                debugLog("skip duplicate trigger: offset=" + cursorOffset
                        + " lastResult=" + lastCompletionResult);
                return;
            }

            // 记录本次补全的 offset 与触发上下文（结果由 overlay 回调更新）
            lastTriggerOffset = cursorOffset;
            lastTriggerHash = dedupKey;
            lastCompletionOffset = cursorOffset;
            lastCompletionResult = PreferenceConstants.COMPLETION_RESULT_NONE;

            int newlineIdx = textBefore.lastIndexOf('\n');
            String linePrefix = textBefore.substring(newlineIdx + 1);
            debugLog("trigger auto-completion: offset=" + cursorOffset
                    + " linePrefix='" + linePrefix + "'");

            triggerCompletion(currentFile, textBefore, textAfter, fullDocument, currentProject, cursorOffset);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 自动补全触发条件判断。
     *   - 规则6: 光标位置前、后都有非空字符 -> 退出（不补全）
     *   - 规则3: 光标前为空或全为空格 -> 触发
     *   - 规则4: 否则遍历活动触发字符，光标前内容以某触发字符结尾时触发
     */
    private boolean shouldTriggerAutoCompletion(String textBefore, String textAfter) {
        // 规则6
        if (isCursorInMiddleOfLine(textBefore, textAfter)) {
            debugLog("rule6 blocked: cursor in middle of line");
            return false;
        }

        int lastNewlineBefore = textBefore.lastIndexOf('\n');
        String beforeOnLine = textBefore.substring(lastNewlineBefore + 1);

        // 规则3
        if (beforeOnLine.trim().isEmpty()) {
            return true;
        }

        // 规则4：trim 末尾空格后匹配触发字符，使 "X = "（光标在空格后）也能触发
        String trimmedBefore = beforeOnLine.replaceAll("\\s+$", "");
        List<String> active = AIConfiguration.getActiveTriggerChars();
        plog("rule4 check: trimmedBefore='" + trimmedBefore + "', activeChars=" + active.size()
                + ", activeList=" + active);
        for (String ch : active) {
            if (ch != null && !ch.isEmpty() && trimmedBefore.endsWith(ch)) {
                plog("rule4 matched: '" + ch + "'");
                return true;
            }
        }
        if (!trimmedBefore.isEmpty()) {
            char lastChar = trimmedBefore.charAt(trimmedBefore.length() - 1);
            plog("rule4 last char: '" + lastChar + "' (U+" + Integer.toHexString(lastChar).toUpperCase() + ")");
            // 硬编码兼容：= 是最常见的触发字符，确保配置缺失时也能触发
            if (lastChar == '=') {
                plog("rule4 hardcoded match: '='");
                return true;
            }
        }
        plog("rule4 no match: '" + trimmedBefore + "'");
        return false;
    }

    /**
     * 判断光标是否位于一行的中间，即光标前后在同一行内都有非空白字符。
     * 若返回 true，说明光标在已有代码中间，此时不应调用 AI 代码补全。
     */
    private boolean isCursorInMiddleOfLine(String textBefore, String textAfter) {
        int lastNewlineBefore = textBefore.lastIndexOf('\n');
        String beforeOnLine = textBefore.substring(lastNewlineBefore + 1);

        int firstNewlineAfter = textAfter.indexOf('\n');
        String afterOnLine = firstNewlineAfter >= 0
                ? textAfter.substring(0, firstNewlineAfter)
                : textAfter;

        return !beforeOnLine.trim().isEmpty() && !afterOnLine.trim().isEmpty();
    }

    // ==================== IPartListener ====================

    @Override
    public void partActivated(IWorkbenchPart part) {
        if (part instanceof IEditorPart) {
            IEditorPart ep = (IEditorPart) part;
            plog("partActivated: editor=" + ep.getSite().getId()
                    + " class=" + ep.getClass().getName());
            attachToEditorPart(ep);
        }
    }

    @Override
    public void partBroughtToTop(IWorkbenchPart part) {
        // no-op
    }

    @Override
    public void partClosed(IWorkbenchPart part) {
        if (part == editor) {
            overlayManager.hideOverlay();
            detach();
        }
    }

    @Override
    public void partDeactivated(IWorkbenchPart part) {
        if (part == editor) {
            overlayManager.hideOverlay();
        }
    }

    @Override
    public void partOpened(IWorkbenchPart part) {
        // no-op
    }

    // ==================== Completion Logic ====================

    private void triggerCompletion(IFile file, String textBefore, String textAfter,
                                    String fullDocument, IProject project, int cursorOffset) {
        // file 可以为 null（SAP ADT 远程文件），AICompletionService 能处理
        cancelCurrentRequest();

        // 在 UI 线程捕获 IWorkbenchPage
        IWorkbenchPage workbenchPage = null;
        if (editor != null && editor.getSite() != null) {
            workbenchPage = editor.getSite().getPage();
        }

        currentRequest = AICompletionService.requestCompletion(
                file, textBefore, textAfter,
                fullDocument, project, workbenchPage,
                completion -> {
                    Display.getDefault().asyncExec(() -> {
                        if (this.editor != null && this.document != null) {
                            showOverlay(completion, cursorOffset);
                        }
                    });
                },
                error -> {
                    // Silently ignore auto-completion errors
                });
    }

    private void showOverlay(String completionText, int cursorOffset) {
        if (completionText == null || completionText.trim().isEmpty()) return;
        if (viewer == null) {
            if (editor != null) {
                viewer = editor.getAdapter(ITextViewer.class);
            }
            if (viewer == null) return;
        }

        overlayManager.showOverlay(viewer, document, completionText, cursorOffset);

        // 自动补全经网络异步返回后才显示提示，期间焦点可能已离开编辑器。
        // 必须显式把焦点交回编辑器控件，overlay 的 TAB/Enter/Esc 按键拦截器
        // 才能收到按键事件（与手动 CTRL+ALT+. 触发路径行为一致）。
        StyledText widget = viewer.getTextWidget();
        if (widget != null && !widget.isDisposed()) {
            widget.setFocus();
        }
    }

    private int getCursorOffset() {
        if (editor == null) return -1;
        ISelectionProvider selProvider = editor.getSelectionProvider();
        if (selProvider == null) return -1;

        if (selProvider.getSelection() instanceof ITextSelection) {
            return ((ITextSelection) selProvider.getSelection()).getOffset();
        }
        return 0;
    }

    /**
     * 检查编辑器中是否有选中文本（selection length > 0）。
     * 有选中文本时不触发自动补全。
     */
    private boolean hasSelection() {
        if (editor == null) return false;
        ISelectionProvider selProvider = editor.getSelectionProvider();
        if (selProvider == null) return false;
        if (selProvider.getSelection() instanceof ITextSelection) {
            ITextSelection sel = (ITextSelection) selProvider.getSelection();
            return sel.getLength() > 0;
        }
        return false;
    }

    private static void debugLog(String message) {
        try { AILogger.logDebug("AutoCompletion", message); } catch (Exception ignored) {}
    }

    private void cancelCurrentRequest() {
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }
        currentRequest = null;
    }

    // ==================== Hash Utility ====================

    /**
     * Fast content hash using simple string XOR folding (fast).
     * No MessageDigest dependency needed.
     */
    private static String computeHash(String content) {
        if (content == null || content.isEmpty()) return "";
        long h1 = 0, h2 = 0;
        for (int i = 0; i < content.length(); i++) {
            int c = content.charAt(i);
            if (i % 2 == 0) {
                h1 = h1 * 31 + c;
            } else {
                h2 = h2 * 31 + c;
            }
        }
        return Long.toHexString(h1) + Long.toHexString(h2);
    }

    /**
     * 从 IEditorInput 获取 IFile，含 ADT 远程文件的反射适配。
     * 与 AICompletionHandler.getFile 逻辑一致。
     */
    private static IFile getFile(IEditorInput input) {
        if (input == null) return null;
        if (input instanceof FileEditorInput) return ((FileEditorInput) input).getFile();
        IFile file = input.getAdapter(IFile.class);
        if (file != null) return file;
        try {
            java.lang.reflect.Method m = input.getClass().getMethod("getFile");
            Object result = m.invoke(input);
            if (result instanceof IFile) return (IFile) result;
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method m = input.getClass().getMethod("getIFile");
            Object result = m.invoke(input);
            if (result instanceof IFile) return (IFile) result;
        } catch (Exception ignored) {}
        return null;
    }
}
