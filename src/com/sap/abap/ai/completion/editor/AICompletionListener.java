package com.sap.abap.ai.completion.editor;

import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
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
import com.sap.abap.ai.completion.preferences.AIConfiguration;

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

    /**
     * Attaches this listener to the currently active editor.
     */
    public void attachToActiveEditor() {
        detach();

        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;

            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            page.addPartListener(this);

            IEditorPart activeEditor = page.getActiveEditor();
            attachToEditorPart(activeEditor);
        } catch (Exception e) {
            // Plugin might not be fully initialized
        }
    }

    public void attachToEditorPart(IEditorPart editorPart) {
        if (editorPart instanceof ITextEditor) {
            ITextEditor te = (ITextEditor) editorPart;
            // ABAP 门控: 非 ABAP 编辑器不附加监听
            if (!isAbapEditor(te)) {
                return;
            }
            attachToEditor(te);
        }
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
        if (docProvider == null) return;

        IEditorInput input = textEditor.getEditorInput();
        this.document = docProvider.getDocument(input);
        if (this.document == null) return;

        // Get file and project
        if (input instanceof FileEditorInput) {
            this.currentFile = ((FileEditorInput) input).getFile();
            if (this.currentFile != null) {
                this.currentProject = this.currentFile.getProject();
            }
        }

        // Try to get the text viewer
        this.viewer = textEditor.getAdapter(ITextViewer.class);

        // Record initial content hash
        try {
            this.lastContentHash = computeHash(document.get());
        } catch (Exception e) {
            this.lastContentHash = "";
        }
        this.lastModifiedTime = System.currentTimeMillis();

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
        if (editor == null || document == null || currentFile == null) return;

        // Don't trigger if overlay is already showing
        if (overlayManager.isOverlayVisible()) return;

        int cursorOffset = getCursorOffset();
        if (cursorOffset < 0) return;

        try {
            String textBefore = document.get(0, Math.min(cursorOffset, document.getLength()));
            int afterStart = Math.min(cursorOffset, document.getLength());
            String textAfter = document.get(afterStart, document.getLength() - afterStart);
            String fullDocument = document.get();

            // Update hash to current content
            lastContentHash = computeHash(fullDocument);

            // 门控: 光标所在行前后（同一行内）都有字符时，不触发 AI 代码补全
            if (isCursorInMiddleOfLine(textBefore, textAfter)) {
                return;
            }

            triggerCompletion(currentFile, textBefore, textAfter, fullDocument, currentProject, cursorOffset);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 判断光标是否位于一行的中间，即光标前后在同一行内都有非空白字符。
     * 若返回 true，说明光标在已有代码中间，此时不应调用 AI 代码补全。
     *
     * @param textBefore 光标前的全文
     * @param textAfter  光标后的全文
     * @return 光标前后同侧都有字符时为 true
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
        if (part instanceof ITextEditor && part != editor) {
            ITextEditor te = (ITextEditor) part;
            // ABAP 门控: 切换到非 ABAP 编辑器时不附加监听
            if (!isAbapEditor(te)) {
                return;
            }
            attachToEditor(te);
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
        if (file == null) return;

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
}
