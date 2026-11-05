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
    /**
     * true iff the editor document holds the source of {@link #programName}
     * itself. False for function modules: the document shows the function
     * source while the searched main program is SAPL&lt;group&gt;, which must
     * be read from the backend.
     */
    public final boolean editorShowsMain;
    public final IEditorPart editor;

    private CodeSearchContext(IEditorPart editor, IProject project, String programName,
            String programType, boolean editorShowsMain) {
        this.editor = editor;
        this.project = project;
        this.programName = programName;
        this.programType = programType;
        this.includeEntry = "PROG/I".equalsIgnoreCase(programType);
        this.editorShowsMain = editorShowsMain;
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
        if (type.startsWith("FUGR/")) {
            // Function modules live in an include L<group>U<nn> of the
            // function group's main program SAPL<group>.
            String group = resolveFunctionGroup(info);
            if (group == null) {
                return null;
            }
            return new CodeSearchContext(editor, info.project,
                    "SAPL" + group.toUpperCase(), "PROG/P", false);
        }
        if (type.startsWith("CLAS/")) {
            // The ADT class editor document already holds the entire class
            // (all sections and method implementations), so it is the single
            // "main program" search target and offsets match exactly.
            return new CodeSearchContext(editor, info.project, info.name.toUpperCase(), type, true);
        }
        if (!type.startsWith("PROG/")) {
            return null;
        }
        return new CodeSearchContext(editor, info.project, info.name.toUpperCase(), type,
                !"PROG/I".equals(type));
    }

    /**
     * Determines the function group of a FUGR object. The group name is taken
     * from the ADT URI ({@code …/functions/groups/<group>/…}), falling back
     * to the workspace file path ({@code .adt/functions/groups/<group>/…}).
     */
    private static String resolveFunctionGroup(AdtObjectResolver.AdtObjectInfo info) {
        if ("FUGR/F".equalsIgnoreCase(info.type) && info.name != null
                && !info.name.trim().isEmpty()) {
            return info.name;
        }
        String group = groupFromPath(info.uri);
        if (group == null) {
            group = groupFromPath(info.filePath);
        }
        return group;
    }

    private static String groupFromPath(String path) {
        if (path == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("functions/groups/([^/]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(path);
        return m.find() ? m.group(1) : null;
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
