package com.sap.abap.ai.completion.editor;

import java.lang.reflect.Method;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.parser.AbapLanguageDetector;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * Handler for "ABAP AI completion" command.
 */
public class AICompletionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            return doExecute(event);
        } catch (Exception e) {
            showMessage("Handler Error: " + e.getMessage());
            return null;
        }
    }

    private Object doExecute(ExecutionEvent event) {
        if (!AIConfiguration.isPluginEnabled()) {
            showStatus(event, "Plugin disabled. Enable in Preferences.");
            return null;
        }

        ITextEditor editor = getTextEditor(event);
        if (editor == null) {
            showStatus(event, "Not in a text editor.");
            showMessage("Please open an ABAP code editor first.");
            return null;
        }

        IDocumentProvider dp = editor.getDocumentProvider();
        if (dp == null) return null;

        IEditorInput input = editor.getEditorInput();
        IDocument doc = dp.getDocument(input);
        if (doc == null) return null;

        IFile file = getFile(input);
        IProject project = null;
        if (file == null) {
            // SAP ADT 远程文件(如 $tmp)可能没有本地 IFile
            // 仍然尝试通过编辑器和文档判断是否为 ABAP
            if (!AbapLanguageDetector.isAbapContext(editor, null, doc)) {
                showStatus(event, "Not an ABAP source.");
                showMessage("ABAP AI Completion only runs in ABAP source files.");
                return null;
            }
            // file 为 null 时继续执行(后续流程不严格依赖 file/project)
        } else {
            project = file.getProject();

            // ABAP 门控: 非 ABAP 源码不触发补全
            if (!AbapLanguageDetector.isAbapContext(editor, file, doc)) {
                showStatus(event, "Not an ABAP source.");
                showMessage("ABAP AI Completion only runs in ABAP source files.");
                return null;
            }
        }

        ISelection sel = editor.getSelectionProvider().getSelection();
        int cursorOffset = (sel instanceof ITextSelection)
                ? ((ITextSelection) sel).getOffset() : 0;

        String textBefore = "", textAfter = "", fullDoc = "";
        try {
            int len = doc.getLength();
            textBefore = doc.get(0, Math.min(cursorOffset, len));
            int afterStart = Math.min(cursorOffset, len);
            textAfter = doc.get(afterStart, len - afterStart);
            fullDoc = doc.get();
        } catch (Exception e) {
            return null;
        }

        // 门控: 光标所在行内、光标之后已有代码（行中间或行首）时，不触发 AI 代码补全。
        // 直接退出（不执行补全、不显示任何提示，包括状态栏提示）
        if (isCursorInMiddleOfLine(textAfter)) {
            return null;
        }

        ITextViewer viewer = editor.getAdapter(ITextViewer.class);

        // 在 UI 线程捕获 IWorkbenchPage,传递给后台线程使用
        IWorkbenchPage workbenchPage = editor.getSite().getPage();

        showStatus(event, "AI completion in progress...");

        AICompletionService.requestCompletion(
            file, textBefore, textAfter, fullDoc, project, workbenchPage,
            result -> Display.getDefault().asyncExec(() -> {
                if (result == null || result.trim().isEmpty()) {
                    // 去重后为空或无可用补全时，提示无可用补全，而不再停留在“AI 补全进行中”状态
                    showStatus(event, "No completion code available");
                    return;
                }
                if (viewer != null && doc != null) {
                    AIOverlayManager.getInstance().showOverlay(viewer, doc, result, cursorOffset);
                    if (viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
                        viewer.getTextWidget().setFocus();
                    }
                }
                clearStatus(event);
            }),
            error -> Display.getDefault().asyncExec(() -> {
                showStatus(event, "AI error: " + error);
            })
        );

        return null;
    }

    /**
     * 判断光标是否位于一行的"非行尾"位置，即光标所在行内、光标之后存在非空白字符。
     * 若返回 true，说明光标后面同侧已有代码（行中间或行首），此时不应触发 AI 代码补全；
     * 只有光标之后同行为空（即光标位于行尾或空行）时才允许执行补全。
     *
     * @param textAfter 光标后的全文
     * @return 光标后同一行内有非空白字符时为 true
     */
    private boolean isCursorInMiddleOfLine(String textAfter) {
        int firstNewlineAfter = textAfter.indexOf('\n');
        String afterOnLine = firstNewlineAfter >= 0
                ? textAfter.substring(0, firstNewlineAfter)
                : textAfter;

        return !afterOnLine.trim().isEmpty();
    }

    private void showStatus(ExecutionEvent event, String msg) {
        try {
            ITextEditor ed = (event != null) ? getTextEditor(event) : null;
            if (ed != null && ed.getEditorSite() != null) {
                ed.getEditorSite().getActionBars().getStatusLineManager().setMessage(msg);
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void clearStatus(ExecutionEvent event) {
        showStatus(event, null);
    }

    private void showMessage(String msg) {
        Display.getDefault().asyncExec(() -> {
            Shell shell = Display.getDefault().getActiveShell();
            if (shell != null) {
                MessageDialog.openInformation(shell, "ABAP AI Completion", msg);
            }
        });
    }

    private ITextEditor getTextEditor(ExecutionEvent event) {
        // Strategy 1: From event
        if (event != null) {
            IEditorPart ep = HandlerUtil.getActiveEditor(event);
            if (ep instanceof ITextEditor) return (ITextEditor) ep;
        }

        // Strategy 2: From workbench
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (w == null || w.getActivePage() == null) return null;

        IEditorPart active = w.getActivePage().getActiveEditor();
        if (active instanceof ITextEditor) return (ITextEditor) active;
        if (active != null) {
            ITextEditor adapted = active.getAdapter(ITextEditor.class);
            if (adapted != null) return adapted;
        }
        return null;
    }

    private IFile getFile(IEditorInput input) {
        if (input == null) return null;
        if (input instanceof FileEditorInput) return ((FileEditorInput) input).getFile();
        IFile file = input.getAdapter(IFile.class);
        if (file != null) return file;
        // 尝试通过反射获取 SAP ADT 文件对象
        try {
            Method getFileMethod = input.getClass().getMethod("getFile");
            Object result = getFileMethod.invoke(input);
            if (result instanceof IFile) return (IFile) result;
        } catch (Exception ignored) {
            // ignore
        }
        // 尝试通过反射获取 getIFile 方法
        try {
            Method getIFileMethod = input.getClass().getMethod("getIFile");
            Object result = getIFileMethod.invoke(input);
            if (result instanceof IFile) return (IFile) result;
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }
}
