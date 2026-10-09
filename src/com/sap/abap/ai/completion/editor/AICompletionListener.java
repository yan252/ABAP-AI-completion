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
        try {
            if (AIConfiguration.isInterfaceLogDebugEnabled()) {
                PLATFORM_LOG.log(new Status(IStatus.INFO, "com.sap.abap.ai.completion", msg));
            }
        } catch (Exception ignored) {}
    }

    private ITextEditor editor;
    private IDocument document;
    private IProject currentProject;
    private IFile currentFile;
    private ITextViewer viewer;

    private CompletableFuture<?> currentRequest;
    private long lastModifiedTime = 0;
    private String lastContentHash = "";
    /**
     * 一次性触发标志：仅当文档内容真实变更（键盘输入字符、空格、退格、回车等）时置 true。
     * 等待延迟到达后触发一次并立即复位；没有新的内容变更不会再次触发。
     */
    private volatile boolean completionPending = false;

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
        // 打开编辑器时不武装：只有真实键盘输入导致内容变更后才开始等待
        this.completionPending = false;

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
        completionPending = false;
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

        triggerCompletion(file, textBefore, textAfter, fullDocument, project, cursorOffset, false);
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

        // 忽略补全覆盖层为内联提示插入/删除空行引起的文档变更，
        // 避免提示被误隐藏或误触发新的补全请求
        if (AIOverlayManager.isSuppressingContentChange()) {
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
                    // 禁用时仅静默跟踪 hash，不武装触发（避免重新启用后立刻误触发）
                    pollCheckHashOnly(false);
                    continue;
                }

                // 一次性等待检查：内容变更后等待延迟，到达后只触发一次
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
     * 一次性等待状态机：
     *   1. 先比对 hash，检测到内容真实变更（输入字符/空格/退格/回车等）时武装等待；
     *   2. 武装后且距变更超过配置延迟 -> 立即解除武装并触发一次补全评估；
     *   3. 未武装（打开编辑器、纯鼠标移动光标、已触发过且无新输入）-> 什么都不做。
     */
    private void performPollingCheck() {
        // 检测内容变更；hash 变化会武装等待并重置等待起点
        String currentHash = pollCheckHashOnly(true);
        if (currentHash == null) return; // document not available

        // 没有等待中的触发请求 -> 不做任何事，直到下一次内容变更
        if (!completionPending) return;

        long elapsed = System.currentTimeMillis() - lastModifiedTime;
        int delay = AIConfiguration.getAutoCompleteDelay();
        if (elapsed < delay) return; // 仍在等待窗口内

        // 等待时间到达：先解除武装（保证只触发一次），再投递触发
        completionPending = false;

        // 提示窗口显示中 -> 本次跳过（下一次输入才会重新武装）
        if (overlayManager.isOverlayVisible()) {
            return;
        }
        debugLog("wait elapsed (" + elapsed + "ms >= " + delay + "ms), fire one-shot trigger");
        Display.getDefault().asyncExec(this::triggerPollingCompletion);
    }

    /**
     * Computes hash of current document content.
     * Returns the hash string, or null if document is not available.
     *
     * @param armPending true 时检测到内容变更会武装一次性等待（{@link #completionPending}）；
     *                   false 时仅静默更新 hash（插件禁用期间）
     */
    private String pollCheckHashOnly(boolean armPending) {
        if (document == null) return null;
        try {
            String content = document.get();
            String hash = computeHash(content);

            // 补全覆盖层为内联提示插入/删除了空行：忽略该变更，保持内容基线不变，
            // 既不武装触发，也不隐藏提示
            if (AIOverlayManager.isSuppressingContentChange()) {
                return hash;
            }

            // Compare with last known hash
            if (!hash.equals(lastContentHash)) {
                // Content changed! Update tracking
                lastContentHash = hash;
                lastModifiedTime = System.currentTimeMillis();

                if (armPending) {
                    // 武装一次性等待：延迟后触发一次，期间继续输入会因 hash 再变而重置等待
                    completionPending = true;
                }

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
        completionPending = true;
        // Hash will be updated by the next poll cycle
    }

    // ==================== Completion Trigger ====================

    private void triggerPollingCompletion() {
        // currentFile 可以为 null（SAP ADT 远程文件），AICompletionService 能处理
        if (editor == null || document == null) return;

        debugLog("triggerPollingCompletion entered, cursorOffset=" + getCursorOffset());

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

            // 触发条件满足，开始自动代码补全
            plog("Start auto-completion...");
            triggerCompletion(currentFile, textBefore, textAfter, fullDocument, currentProject, cursorOffset, true);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 自动补全触发条件判断。
     *   - 规则6: 光标位置前、后都有非空字符 -> 退出（不补全）
     *   - 规则3: 整行去除空白后为空（光标前、后均无内容）-> 触发；
     *           光标前为空但同一行光标后还有代码 -> 不触发
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

        int firstNewlineAfter = textAfter.indexOf('\n');
        String afterOnLine = firstNewlineAfter >= 0
                ? textAfter.substring(0, firstNewlineAfter)
                : textAfter;

        // 规则3：仅当整行为空（光标前、后去除空白后都没有字符）时触发
        if (beforeOnLine.trim().isEmpty()) {
            if (afterOnLine.trim().isEmpty()) {
                return true;
            }
            debugLog("rule3 blocked: line has content after cursor, afterOnLine='" + afterOnLine + "'");
            return false;
        }

        // 规则4：取光标前行内容末尾与配置触发字符等长的子串，逐一比较。
        // 配置 "= " 需要行末尾正好是 "= " 才匹配，"=" 不匹配 "-> " 等。
        List<String> active = AIConfiguration.getActiveTriggerChars();
        debugLog("rule4 check: beforeOnLine='" + beforeOnLine + "', activeChars=" + active.size()
                + ", activeList=" + active);
        for (String ch : active) {
            if (ch == null || ch.isEmpty()) continue;
            int chLen = ch.length();
            if (beforeOnLine.length() < chLen) continue;
            // 取行内光标前内容末尾与触发字符等长的子串
            String tail = beforeOnLine.substring(beforeOnLine.length() - chLen);
            if (tail.equals(ch)) {
                debugLog("rule4 matched: '" + ch + "' (tail='" + tail + "')");
                return true;
            }
        }
        debugLog("rule4 no match: '" + beforeOnLine + "'");
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
                                    String fullDocument, IProject project, int cursorOffset,
                                    boolean isAutoTrigger) {
        // file 可以为 null（SAP ADT 远程文件），AICompletionService 能处理
        cancelCurrentRequest();

        // 在 UI 线程捕获 IWorkbenchPage
        IWorkbenchPage workbenchPage = null;
        if (editor != null && editor.getSite() != null) {
            workbenchPage = editor.getSite().getPage();
        }

        // 自动补全场景：调用 AI 接口前记录日志
        if (isAutoTrigger) {
            plog("Start auto-completion -- calling AI...");
        }

        currentRequest = AICompletionService.requestCompletion(
                file, textBefore, textAfter,
                fullDocument, project, workbenchPage,
                completion -> {
                    Display.getDefault().asyncExec(() -> {
                        if (this.editor != null && this.document != null) {
                            showOverlay(completion, cursorOffset);
                        } else {
                            debugLog("completion callback: editor/document is null, skip showing overlay");
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
        try { plog(message); } catch (Exception ignored) {}
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
