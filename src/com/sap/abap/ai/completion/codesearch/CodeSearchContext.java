package com.sap.abap.ai.completion.codesearch;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.recent.AdtObjectResolver;

/** ABAP program currently shown in the active editor. */
public final class CodeSearchContext {

    public final IProject project;
    public final String programName;
    public final String programType;
    public final boolean includeEntry;
    public final IEditorPart editor;

    private CodeSearchContext(IEditorPart editor, IProject project, String programName, String programType) {
        this.editor = editor;
        this.project = project;
        this.programName = programName;
        this.programType = programType;
        this.includeEntry = "PROG/I".equalsIgnoreCase(programType);
    }

    /**
     * Resolves the program of the active editor.
     *
     * @return {@code null} when no supported ABAP program editor is active
     */
    public static CodeSearchContext fromActiveEditor() {
        IEditorPart editor = activeEditor();
        if (editor == null) {
            return null;
        }
        AdtObjectResolver.AdtObjectInfo info = AdtObjectResolver.getAdtObject(editor);
        if (info == null || info.name == null || info.type == null) {
            return null;
        }
        String type = info.type.toUpperCase();
        if (!type.startsWith("PROG/")) {
            return null;
        }
        return new CodeSearchContext(editor, info.project, info.name.toUpperCase(), type);
    }

    /** Reads the current editor's document text (latest, unsaved edits included). */
    public String readCurrentSource() {
        if (editor == null) return null;
        try {
            if (editor instanceof ITextEditor) {
                ITextEditor te = (ITextEditor) editor;
                IDocument doc = te.getDocumentProvider() == null
                        ? null
                        : te.getDocumentProvider().getDocument(editor.getEditorInput());
                if (doc != null) return doc.get();
            }
            // Fallback: try adapting to ITextEditor via Eclipse adapter
            Object adapted = org.eclipse.core.runtime.Adapters.adapt(editor, ITextEditor.class);
            if (adapted instanceof ITextEditor) {
                ITextEditor te = (ITextEditor) adapted;
                IEditorInput input = editor.getEditorInput();
                IDocument doc = te.getDocumentProvider() == null ? null : te.getDocumentProvider().getDocument(input);
                if (doc != null) return doc.get();
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    public static IEditorPart activeEditor() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        return page == null ? null : page.getActiveEditor();
    }
}
