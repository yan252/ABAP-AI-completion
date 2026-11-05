package com.sap.abap.ai.completion.codesearch;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * One page of the ADT multi-page class editor. The editor holds every
     * page's document in memory, so we can read all class pages directly
     * instead of going through the backend (whose {@code RPY_PROGRAM_READ}
     * does not return class-pool include sources).
     */
    public static final class ClassPage {
        /** Include name (e.g. {@code ZCL_FOO===============CCIMP}) or the class name for the global page. */
        public final String name;
        /** Source text of this page. */
        public final String source;
        /** Page suffix: {@code null} for the global class page, else CCDEF/CCIMP/CCMAC/CT. */
        public final String suffix;

        public ClassPage(String name, String source, String suffix) {
            this.name = name;
            this.source = source;
            this.suffix = suffix;
        }

        public boolean isGlobalClass() {
            return suffix == null;
        }
    }

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
    /**
     * All class editor pages' sources, collected from the multi-page class
     * editor. Non-null only when the active editor is a class. Used so the
     * search covers the global class page plus every local-types page
     * (CCDEF / CCIMP / CCMAC / CT) regardless of which page is currently
     * open, and so unsaved edits are included.
     */
    public final List<ClassPage> classPages;

    private CodeSearchContext(IEditorPart editor, IProject project, String programName,
            String programType, boolean editorShowsMain) {
        this(editor, project, programName, programType, editorShowsMain, null);
    }

    private CodeSearchContext(IEditorPart editor, IProject project, String programName,
            String programType, boolean editorShowsMain, List<ClassPage> classPages) {
        this.editor = editor;
        this.project = project;
        this.programName = programName;
        this.programType = programType;
        this.includeEntry = "PROG/I".equalsIgnoreCase(programType);
        this.editorShowsMain = editorShowsMain;
        this.classPages = classPages;
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
            // Fallback: ADT object unknown — search the current editor document
            // only (supports plain text editors, CDS, non-ADT editors, etc.).
            log("fromActiveEditor: ADT object not resolved, trying editor-only fallback"
                    + " editorClass=" + editor.getClass().getName()
                    + " title=" + editor.getTitle());
            CodeSearchContext fb = fallbackEditorOnly(editor, null, null);
            if (fb != null) return fb;
            return null;
        }
        String type = info.type.toUpperCase();
        String name = info.name.toUpperCase();
        log("fromActiveEditor: resolved " + name + " (" + type + ")");

        // The ADT class editor exposes the active local-types page as an
        // IAdtObjectReference whose name is the padded class-pool include
        // (e.g. ZCL_FOO===============CCIMP) instead of the bare class name.
        // Recover the class name so the search covers the whole class.
        String className = CodeSearchSourceClient.classNameOfClassInclude(name);

        if (type.startsWith("CLAS/") || className != null) {
            String clsName = className != null ? className : name;
            // Collect every class editor page's source directly from the
            // multi-page editor. RPY_PROGRAM_READ does not return class-pool
            // include sources, so the backend cannot supply them; the editor,
            // however, holds all pages in memory.
            List<ClassPage> pages = collectClassPages(editor, clsName);
            if (pages != null && !pages.isEmpty()) {
                log("fromActiveEditor: class " + clsName + ", pages=" + pages.size());
                return new CodeSearchContext(editor, info.project, clsName, "CLAS/OC",
                        true, pages);
            }
            // Fallback: could not enumerate pages. Search the current editor
            // document at least (the page the user has open).
            log("fromActiveEditor: class " + clsName
                    + " — page enumeration unavailable, using current page only");
            return new CodeSearchContext(editor, info.project, clsName, "CLAS/OC",
                    true, null);
        }

        if (type.startsWith("FUGR/")) {
            // Function modules live in an include L<group>U<nn> of the
            // function group's main program SAPL<group>.
            String group = resolveFunctionGroup(info);
            if (group == null) {
                log("fromActiveEditor: FUGR but group unresolved, editor-only fallback");
                CodeSearchContext fb = fallbackEditorOnly(editor, info.project, info.name);
                if (fb != null) return fb;
                return null;
            }
            return new CodeSearchContext(editor, info.project,
                    "SAPL" + group.toUpperCase(), "PROG/P", false);
        }
        if (!type.startsWith("PROG/")) {
            // Unsupported ADT type (DDIC table/structure, message class, etc.)
            // — search the current editor document only.
            log("fromActiveEditor: unsupported type " + type + " for " + name
                    + ", editor-only fallback");
            CodeSearchContext fb = fallbackEditorOnly(editor, info.project, name);
            if (fb != null) return fb;
            return null;
        }
        return new CodeSearchContext(editor, info.project, name, type,
                !"PROG/I".equals(type));
    }

    /**
     * Fallback context when the active editor cannot be identified as a known
     * ABAP program type (CLASS/PROG/FUGR). Uses the current editor document
     * directly as the sole search source. ADT backend lookups (D010INC,
     * RPY_PROGRAM_READ) are skipped because the program identity is unknown.
     *
     * @param editor      active editor
     * @param project     ADT project, or {@code null} when not available
     * @param fallbackName name to use in the context (e.g. the ADT object name),
     *                    or {@code null} to derive from the editor title
     * @return a context covering only the current editor document, or
     *         {@code null} when the editor does not provide any text document
     */
    private static CodeSearchContext fallbackEditorOnly(IEditorPart editor,
            IProject project, String fallbackName) {
        if (editor == null) return null;
        ITextEditor te = textEditorOf(editor);
        if (te == null) {
            log("fallbackEditorOnly: no ITextEditor available on "
                    + editor.getClass().getName());
            return null;
        }
        try {
            IEditorInput input = te.getEditorInput();
            if (input == null || te.getDocumentProvider() == null) {
                return null;
            }
            IDocument doc = te.getDocumentProvider().getDocument(input);
            if (doc == null || doc.get() == null || doc.get().trim().isEmpty()) {
                return null;
            }
        } catch (Throwable t) {
            log("fallbackEditorOnly: doc read failed: " + t.getMessage());
            return null;
        }
        String name = fallbackName != null ? fallbackName
                : (editor.getTitle() != null ? editor.getTitle().trim() : "");
        if (name.isEmpty()) {
            name = "EDITOR";
        }
        log("fallbackEditorOnly: using editor-only context name=" + name);
        return new CodeSearchContext(editor, project, name, "PROG/P", true);
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
            List<ClassPage> pages = collectClassPages(editor, name.toUpperCase());
            if (pages != null && !pages.isEmpty()) {
                return new CodeSearchContext(editor, project, name.toUpperCase(), "CLAS/OC",
                        true, pages);
            }
            // Fallback: search the current editor document only.
            return new CodeSearchContext(editor, project, name.toUpperCase(), "CLAS/OC",
                    true, null);
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

    /**
     * Collects the source of every page of the ADT multi-page class editor.
     *
     * <p>The class editor keeps each page's document in memory, so we can read
     * the global class page plus Class-relevant Local Types (CCDEF), Local
     * Types (CCIMP), Macros (CCMAC) and Test Classes (CT) directly — including
     * any unsaved edits. This is essential because {@code RPY_PROGRAM_READ}
     * does not return class-pool include sources.</p>
     *
     * @param editor    the active class editor
     * @param className the bare class name (upper case)
     * @return one entry per non-empty page, or an empty list if the editor is
     *         not a multi-page class editor
     */
    private static List<ClassPage> collectClassPages(IEditorPart editor, String className) {
        List<ClassPage> pages = new ArrayList<>();
        if (editor == null) {
            return pages;
        }
        try {
            log("collectClassPages: className=" + className
                    + " editorClass=" + editor.getClass().getName());

            // ===== Strategy A: ADT MultiPageClassEditor dedicated API =====
            // ADT's MultiPageClassEditor exposes typed getters for every page
            // (getMainClassEditor / getDefinitionsEditor / getImplementationsEditor /
            // getMacrosEditor / getTestClassesEditor). These all return valid
            // IClassSourcePage (extends ITextEditor) even when the MultiPageEditorPart
            // base-class getEditor(int) returns null for non-first pages.
            // Use this when available — it is by far the most reliable way to
            // enumerate class editor pages.
            if (tryAdtMultiPageClassEditor(editor, className, pages)) {
                log("collectClassPages: strategyA (ADT getters) collected=" + pages.size());
                return pages;
            }

            // ===== Strategy B: generic MultiPageEditorPart getEditor(int) =====
            Class<?> mpeClass = Class.forName("org.eclipse.ui.part.MultiPageEditorPart");
            boolean isMpe = mpeClass.isInstance(editor);
            if (!isMpe) {
                // Single-page editor: only the current document is available.
                ITextEditor te = textEditorOf(editor);
                if (te != null) {
                    String src = readSourceFromTextEditor(te);
                    if (src != null && !src.trim().isEmpty()) {
                        pages.add(new ClassPage(className, src, null));
                    }
                }
                log("collectClassPages: single-page editor, collected=" + pages.size());
                return pages;
            }

            java.lang.reflect.Method getEditor = null;
            try {
                getEditor = mpeClass.getDeclaredMethod("getEditor", int.class);
                getEditor.setAccessible(true);
            } catch (NoSuchMethodException ignored) { }

            int count = -1;
            try {
                java.lang.reflect.Method getPageCount =
                        mpeClass.getDeclaredMethod("getPageCount");
                getPageCount.setAccessible(true);
                count = (Integer) getPageCount.invoke(editor);
            } catch (Throwable ignored) { }

            java.lang.reflect.Method getPageText = null;
            try {
                getPageText = mpeClass.getDeclaredMethod("getPageText", int.class);
                getPageText.setAccessible(true);
            } catch (NoSuchMethodException ignored) { }

            String prefix = CodeSearchSourceClient.classPoolPrefix(className);
            int pageCount = count > 0 ? count : 50;
            int collected = 0;
            for (int index = 0; index < pageCount; index++) {
                IEditorPart pe = null;
                if (getEditor != null) {
                    Object rawEditor = getEditor.invoke(editor, index);
                    if (rawEditor instanceof IEditorPart) {
                        pe = (IEditorPart) rawEditor;
                    }
                }
                if (pe == null) {
                    break;
                }
                ITextEditor te = textEditorOf(pe);
                if (te == null) continue;
                String src = readSourceFromTextEditor(te);
                if (src == null || src.trim().isEmpty()) continue;

                String tabText = null;
                if (getPageText != null) {
                    try {
                        Object t = getPageText.invoke(editor, index);
                        if (t != null) tabText = t.toString();
                    } catch (Throwable ignored) { }
                }
                AdtObjectResolver.AdtObjectInfo pageInfo =
                        AdtObjectResolver.getAdtObject(pe);
                String suffix = classPageSuffix(pageInfo, pe, tabText);
                String name = (suffix == null) ? className : prefix + suffix;
                pages.add(new ClassPage(name, src, suffix));
                collected++;
            }
            log("collectClassPages: strategyB (getEditor) collected=" + collected);
        } catch (Throwable t) {
            log("collectClassPages failed: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        }
        return pages;
    }

    /**
     * Strategy A for ADT MultiPageClassEditor: call its typed getter methods
     * to obtain every class source page. Each returned page implements
     * ITextEditor (via IClassSourcePage → IAbapSourcePage → ITextEditor).
     *
     * <p>Returns {@code true} when the editor was recognised as an ADT class
     * editor and we attempted to collect pages (even if the result is empty).
     * Returns {@code false} when the editor is not an ADT class editor at all
     * (so the caller can fall back to generic strategies).</p>
     */
    private static boolean tryAdtMultiPageClassEditor(IEditorPart editor,
            String className, List<ClassPage> pages) {
        // Quick class-name check to avoid expensive reflection on every editor.
        String clsName = editor.getClass().getName();
        if (!clsName.contains("MultiPageClassEditor")) {
            return false;
        }
        log("collectClassPages: strategyA detected ADT MultiPageClassEditor");

        // Map of getter method name → (suffix, page display label).
        // suffix=null means "global class page".
        @SuppressWarnings("unchecked")
        String[][] getters = new String[][] {
                // methodName          suffix   (label is informational only)
                { "getMainClassEditor",     null,    "Global Class" },
                { "getDefinitionsEditor",   "CCDEF", "Class-relevant Local Types" },
                { "getImplementationsEditor","CCIMP", "Local Types" },
                { "getMacrosEditor",        "CCMAC", "Macros" },
                { "getTestClassesEditor",   "CT",    "Test Classes" },
        };

        // Optional newer getters — may or may not exist on the installed ADT.
        @SuppressWarnings("unchecked")
        String[][] optionalGetters = new String[][] {
                { "getLocalTypesEditor",    "CCIMP", "Local Types (alt)" },
        };

        String prefix = CodeSearchSourceClient.classPoolPrefix(className);
        java.util.Set<String> addedSuffixes = new java.util.HashSet<>();
        java.util.Set<String> seenSources = new java.util.HashSet<>();

        java.lang.reflect.Method getSourceMethod = null;
        try {
            getSourceMethod = editor.getClass().getClassLoader()
                    .loadClass("com.sap.adt.tools.abapsource.ui.sources.editors.IAbapSourcePage")
                    .getMethod("getSource");
        } catch (Throwable ignored) { }

        for (boolean optionalPass : new boolean[] { false, true }) {
            String[][] list = optionalPass ? optionalGetters : getters;
            for (String[] entry : list) {
                String methodName = entry[0];
                String suffix = entry[1];
                String label = entry[2];

                try {
                    java.lang.reflect.Method getter = findMethod(editor.getClass(), methodName);
                    if (getter == null) {
                        continue;
                    }
                    getter.setAccessible(true);
                    Object page = getter.invoke(editor);
                    if (page == null) {
                        log("collectClassPages: " + methodName + " → null (page not created yet)");
                        continue;
                    }

                    String src = null;
                    if (getSourceMethod != null && getSourceMethod.getDeclaringClass().isInstance(page)) {
                        try {
                            src = (String) getSourceMethod.invoke(page);
                        } catch (Throwable ignored) { }
                    }
                    if (src == null) {
                        // Fallback: treat as ITextEditor and read via document provider.
                        if (page instanceof ITextEditor) {
                            src = readSourceFromTextEditor((ITextEditor) page);
                        } else {
                            ITextEditor te = textEditorOf((IEditorPart) page);
                            src = te != null ? readSourceFromTextEditor(te) : null;
                        }
                    }
                    if (src == null || src.trim().isEmpty()) {
                        log("collectClassPages: " + methodName + " → empty source, skip");
                        continue;
                    }

                    // Deduplicate: the ADT local-types getter sometimes returns
                    // the same page as implementations. Skip identical source text.
                    String srcKey = src.trim();
                    if (seenSources.contains(srcKey)) {
                        log("collectClassPages: " + methodName + " → duplicate source, skip");
                        continue;
                    }
                    seenSources.add(srcKey);

                    // Also skip if we already have a page with the same suffix.
                    if (suffix != null && !addedSuffixes.add(suffix)) {
                        continue;
                    }

                    String name = (suffix == null) ? className : prefix + suffix;
                    pages.add(new ClassPage(name, src, suffix));
                    log("collectClassPages: " + methodName + " → " + label
                            + " name=" + name + " sourceLen=" + src.length());
                } catch (Throwable t) {
                    log("collectClassPages: " + methodName + " failed: " + t.getMessage());
                }
            }
        }
        return true;
    }

    /** Reads source text from an ITextEditor via its document provider. */
    private static String readSourceFromTextEditor(ITextEditor te) {
        if (te == null) return null;
        try {
            IEditorInput input = te.getEditorInput();
            if (input == null) return null;
            if (te.getDocumentProvider() == null) return null;
            org.eclipse.jface.text.IDocument doc = te.getDocumentProvider().getDocument(input);
            return doc == null ? null : doc.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds a public / declared method by name, walking up the class hierarchy. */
    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) { }
        }
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException ignored) { }
        return null;
    }

    /**
     * Determines the class page suffix (CCDEF / CCIMP / CCMAC / CT) of a class
     * editor page, or {@code null} for the global class page.
     *
     * <p>The tab label ({@code getPageText}) is the primary identifier. Falls
     * back to the page editor title, then to the ADT URI / type.</p>
     */
    private static String classPageSuffix(AdtObjectResolver.AdtObjectInfo info,
            IEditorPart pageEditor, String tabText) {
        // 1. Tab label (most reliable)
        if (tabText != null && !tabText.trim().isEmpty()) {
            String t = tabText.toLowerCase();
            if (t.contains("class-relevant")) return "CCDEF";
            if (t.contains("test class")) return "CT";
            if (t.contains("macro")) return "CCMAC";
            if (t.contains("local type")) return "CCIMP";
            if (t.contains("global")) return null;
        }
        // 2. Page editor title
        if (pageEditor != null) {
            try {
                String title = pageEditor.getTitle();
                if (title != null && !title.trim().isEmpty()) {
                    String t = title.toLowerCase();
                    if (t.contains("class-relevant")) return "CCDEF";
                    if (t.contains("test class")) return "CT";
                    if (t.contains("macro")) return "CCMAC";
                    if (t.contains("local type")) return "CCIMP";
                    if (t.contains("global")) return null;
                }
            } catch (Throwable ignored) { }
        }
        // 3. ADT URI / type
        if (info != null && info.uri != null) {
            String uri = info.uri.toLowerCase();
            if (uri.contains("/includes/")) {
                int idx = uri.lastIndexOf('/');
                if (idx >= 0) {
                    String s = uri.substring(idx + 1).toUpperCase();
                    if (s.equals("CCDEF") || s.equals("CCIMP") || s.equals("CCMAC")
                            || s.equals("CT")) {
                        return s;
                    }
                }
            }
        }
        if (info != null && info.type != null
                && "CLAS/OC".equalsIgnoreCase(info.type.trim())) {
            return null;
        }
        if (info != null && info.type != null
                && "CLAS/I".equalsIgnoreCase(info.type.trim())) {
            return "CCIMP";
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
