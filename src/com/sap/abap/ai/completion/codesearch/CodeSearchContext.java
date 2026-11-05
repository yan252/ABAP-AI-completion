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
            log("fromActiveEditor: no active editor");
            return null;
        }
        AdtObjectResolver.AdtObjectInfo info = AdtObjectResolver.getAdtObject(editor);
        if (info == null || info.name == null || info.type == null) {
            // The multi-page class editor (MultiPageClassEditor) exposes a
            // CLAS/I reference with an empty name, so the generic resolver
            // fails. Recover the class from the editor file path
            // (.adt/oo/classes/<name>/…).
            CodeSearchContext classContext = viaClassFilePath(editor);
            if (classContext != null) {
                log("fromActiveEditor: resolved class " + classContext.programName
                        + " via file path");
                return classContext;
            }
            log("fromActiveEditor: ADT object not resolved, editorClass="
                    + editor.getClass().getName() + ", title=" + editor.getTitle());
            return null;
        }
        String type = info.type.toUpperCase();
        log("fromActiveEditor: resolved " + info.name + " (" + type + ")");
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
            log("fromActiveEditor: unsupported type " + type + " for " + info.name);
            return null;
        }
        return new CodeSearchContext(editor, info.project, info.name.toUpperCase(), type,
                !"PROG/I".equals(type));
    }

    /**
     * Recovers a class context from the editor file path
     * ({@code .adt/oo/classes/<name>/…}). Used when the generic ADT resolver
     * cannot name the object (multi-page class editor).
     */
    private static CodeSearchContext viaClassFilePath(IEditorPart editor) {
        try {
            String name = classNameOf(editor);
            if (name == null) {
                // Last resort: ADT class editors use the bare class name as
                // their title.
                name = classNameFromTitle(editor.getTitle());
            }
            if (name == null) {
                return null;
            }
            org.eclipse.core.resources.IFile file =
                    AdtObjectResolver.getAdtFile(editor.getEditorInput());
            IProject project = file != null ? file.getProject()
                    : AdtObjectResolver.findProject(editor.getEditorInput(), editor);
            if (project == null) {
                return null;
            }
            return new CodeSearchContext(editor, project, name, "CLAS/OC", true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The class name from an editor title, or null when it is not a bare name. */
    private static String classNameFromTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.trim();
        return trimmed.matches("[A-Za-z0-9_/]{2,40}") ? trimmed.toUpperCase() : null;
    }

    /**
     * The class name from the editor file path, or {@code null} when the
     * editor does not show a class ({@code .adt/oo/classes/<name>/…}).
     */
    public static String classNameOf(IEditorPart editor) {
        try {
            org.eclipse.core.resources.IFile file =
                    AdtObjectResolver.getAdtFile(editor.getEditorInput());
            if (file == null) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/oo/classes/([^/]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(file.getFullPath().toString());
            return m.find() ? m.group(1).toUpperCase() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The text editor that actually holds the document of {@code editor}.
     * Multi-page editors (e.g. the ADT class editor) nest the source editor;
     * it is reached via {@code getActiveEditor()} / {@code getSelectedPage()}.
     */
    public static ITextEditor textEditorOf(IEditorPart editor) {
        if (editor == null) {
            return null;
        }
        if (editor instanceof ITextEditor) {
            return (ITextEditor) editor;
        }
        Object adapted = org.eclipse.core.runtime.Adapters.adapt(editor, ITextEditor.class);
        if (adapted instanceof ITextEditor) {
            return (ITextEditor) adapted;
        }
        for (String name : new String[] { "getActiveEditor", "getSelectedPage" }) {
            try {
                java.lang.reflect.Method method = null;
                for (Class<?> c = editor.getClass(); c != null && method == null; c = c.getSuperclass()) {
                    try {
                        method = c.getDeclaredMethod(name);
                    } catch (Throwable ignored) {
                    }
                }
                if (method == null) {
                    continue;
                }
                method.setAccessible(true);
                Object inner = method.invoke(editor);
                if (inner instanceof ITextEditor && inner != editor) {
                    return (ITextEditor) inner;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Writes a diagnostic entry to the Eclipse Error Log. */
    private static void log(String message) {
        try {
            org.eclipse.core.runtime.Platform
                    .getLog(org.eclipse.core.runtime.Platform.getBundle("com.sap.abap.ai.completion"))
                    .log(new org.eclipse.core.runtime.Status(
                            org.eclipse.core.runtime.IStatus.INFO,
                            "com.sap.abap.ai.completion", "[CodeSearch] " + message));
        } catch (Throwable ignored) {
        }
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
        ITextEditor te = textEditorOf(editor);
        if (te == null) {
            return null;
        }
        try {
            IEditorInput input = te.getEditorInput() != null ? te.getEditorInput()
                    : editor.getEditorInput();
            IDocument doc = te.getDocumentProvider() == null
                    ? null
                    : te.getDocumentProvider().getDocument(input);
            return doc == null ? null : doc.get();
        } catch (Throwable t) {
            return null;
        }
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
