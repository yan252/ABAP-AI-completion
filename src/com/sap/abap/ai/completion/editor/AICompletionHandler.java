package com.sap.abap.ai.completion.editor;

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
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

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
        if (file == null) {
            showStatus(event, "Cannot determine file.");
            return null;
        }
        IProject project = file.getProject();

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

        ITextViewer viewer = editor.getAdapter(ITextViewer.class);

        showStatus(event, "AI completion in progress...");

        AICompletionService.requestCompletion(
            file, textBefore, textAfter, fullDoc, project,
            result -> Display.getDefault().asyncExec(() -> {
                if (result == null || result.trim().isEmpty()) {
                    clearStatus(event);
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
        if (input instanceof FileEditorInput) return ((FileEditorInput) input).getFile();
        return input.getAdapter(IFile.class);
    }
}
