package com.sap.abap.ai.completion.codesearch;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.recent.AdtObjectResolver;

/**
 * Opens the editor of a searched program/include, applies the gray highlight
 * annotations to every match of that program and reveals the requested match.
 *
 * <p>The matches were computed on the text read from the SAP backend, which can
 * differ from the text the editor finally shows (case, blanks, inactive
 * version, unsaved changes). Absolute offsets are therefore unreliable.</p>
 *
 * <p>Instead, the pattern is searched again in the real editor document and the
 * found occurrences are paired with the searched matches: directly by index when
 * both counts are equal, otherwise by the smallest line/column distance. This
 * keeps the highlight on the right text even when the backend and the editor
 * disagree on the line layout.</p>
 */
public final class AbapEditorNavigator {

    /**
     * The annotation type used by the Eclipse Search framework itself
     * ({@code AnnotationHighlighter} creates plain annotations of this type).
     * The ADT editors paint it, while a private annotation type declared by
     * this plugin is not rendered at all.
     */
    static final String ANNOTATION_TYPE = "org.eclipse.search.results";

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));
    private static final String BUNDLE_ADT_CORE_UI = "com.sap.adt.tools.core.ui";
    private static final String CLS_NAVIGATION_FACTORY =
            "com.sap.adt.tools.core.ui.navigation.AdtNavigationServiceFactory";

    private static final List<AppliedAnnotation> APPLIED = new ArrayList<>();

    private AbapEditorNavigator() {
    }

    private static final class AppliedAnnotation {
        final IAnnotationModel model;
        final Annotation annotation;

        AppliedAnnotation(IAnnotationModel model, Annotation annotation) {
            this.model = model;
            this.annotation = annotation;
        }
    }

    /**
     * Shows the program with the given match selected and every match of that
     * program highlighted.
     *
     * @param match      the match to reveal, may be {@code null} (program node)
     * @param allMatches every match of that program
     * @param matcher    matcher of the search, used to find the occurrences in
     *                   the editor document
     */
    public static void show(AbapCodeProgram program, Match match, Match[] allMatches,
            CodeSearchMatcher matcher) {
        try {
            IEditorPart part = findOrOpen(program);
            if (part == null) {
                log("show: could not open editor for " + program.getName());
                return;
            }
            activate(part);
            Match[] sorted = sortByOffset(allMatches);
            int index = match == null ? -1 : indexOf(sorted, match);
            applyWithRetry(part, program, sorted, index, -1, -1, matcher, 0);
        } catch (Throwable t) {
            log("show failed for " + program.getName() + ": " + t.getMessage());
        }
    }

    /** Simple variant used by the Replace flow: open and reveal an offset. */
    public static void show(AbapCodeProgram program, int offset, int length, Match[] allMatches) {
        try {
            IEditorPart part = findOrOpen(program);
            if (part == null) {
                log("show: could not open editor for " + program.getName());
                return;
            }
            activate(part);
            applyWithRetry(part, program, sortByOffset(allMatches), -1, offset, length, null, 0);
        } catch (Throwable t) {
            log("show failed for " + program.getName() + ": " + t.getMessage());
        }
    }

    private static Match[] sortByOffset(Match[] matches) {
        if (matches == null) {
            return new Match[0];
        }
        Match[] sorted = matches.clone();
        Arrays.sort(sorted, Comparator.comparingInt(Match::getOffset));
        return sorted;
    }

    private static int indexOf(Match[] matches, Match match) {
        for (int i = 0; i < matches.length; i++) {
            if (matches[i] == match) {
                return i;
            }
        }
        return -1;
    }

    private static void activate(final IEditorPart part) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        Runnable r = () -> {
            try {
                IWorkbenchPage page = part.getSite() == null ? null : part.getSite().getPage();
                if (page != null) {
                    page.activate(part);
                }
                part.setFocus();
            } catch (Throwable t) {
                log("activate failed: " + t.getMessage());
            }
        };
        if (display.getThread() == Thread.currentThread()) {
            r.run();
        } else {
            display.asyncExec(r);
        }
    }

    private static void applyWithRetry(final IEditorPart part, final AbapCodeProgram program,
            final Match[] allMatches, final int targetIndex, final int offset,
            final int length, final CodeSearchMatcher matcher, final int attempt) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (part.getSite() == null || part.getSite().getShell() == null
                    || part.getSite().getShell().isDisposed()) {
                return;
            }
            ITextEditor editor = part.getAdapter(ITextEditor.class);
            if (editor == null && part instanceof ITextEditor) {
                editor = (ITextEditor) part;
            }
            // A freshly opened ADT editor is still initializing: the text editor
            // adapter, the document and the annotation model only show up after
            // the editor has been created. Retrying instead of giving up keeps
            // the highlight working for editors that were not open at search
            // time.
            EditorTarget target = editor == null ? null : resolveTarget(editor);
            if (target == null || target.document.getLength() == 0) {
                if (attempt < 12) {
                    display.timerExec(500, () -> applyWithRetry(part, program, allMatches,
                            targetIndex, offset, length, matcher, attempt + 1));
                } else {
                    log("applyHighlight(" + program.getName()
                            + "): editor not ready after retries");
                }
                return;
            }
            applyHighlight(editor, target, program, allMatches, targetIndex, offset, length,
                    matcher);
        });
    }

    private static void applyHighlight(ITextEditor editor, EditorTarget target,
            AbapCodeProgram program, Match[] allMatches, int targetIndex,
            int offset, int length, CodeSearchMatcher matcher) {

        IDocument document = target.document;
        IAnnotationModel model = target.model;
        clearAnnotations();

        List<int[]> occurrences = findOccurrences(document, matcher);
        int[] mapping = mapMatches(document, program, allMatches, occurrences);

        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < allMatches.length; i++) {
            int[] range = mapping[i] >= 0 ? occurrences.get(mapping[i]) : null;
            if (range == null) {
                int start = allMatches[i].getOffset();
                int len = allMatches[i].getLength();
                if (start >= 0 && len > 0 && start + len <= document.getLength()) {
                    range = new int[] { start, len };
                }
            }
            ranges.add(range);
        }

        int annotated = 0;
        for (int[] range : ranges) {
            if (range == null) {
                continue;
            }
            // Annotations are added to the viewer model and, when it exists,
            // also to the document provider model: depending on the ADT editor
            // only one of them is the model that is actually painted.
            List<IAnnotationModel> targets = new ArrayList<>();
            if (model != null) {
                targets.add(model);
            }
            if (target.altModel != null && target.altModel != model) {
                targets.add(target.altModel);
            }
            for (IAnnotationModel targetModel : targets) {
                Annotation annotation = new Annotation(ANNOTATION_TYPE, false, "");
                try {
                    targetModel.addAnnotation(annotation, new Position(range[0], range[1]));
                    APPLIED.add(new AppliedAnnotation(targetModel, annotation));
                    annotated++;
                } catch (Throwable ignored) {
                    // keep the remaining matches
                }
            }
        }

        int revealOffset = -1;
        int revealLength = 0;
        if (targetIndex >= 0 && targetIndex < ranges.size() && ranges.get(targetIndex) != null) {
            int[] range = ranges.get(targetIndex);
            revealOffset = range[0];
            revealLength = range[1];
        } else if (offset >= 0) {
            revealOffset = offset;
            revealLength = length;
        }
        if (revealOffset >= 0) {
            int safeOffset = Math.max(0, Math.min(revealOffset, document.getLength()));
            int safeLength = Math.max(0, Math.min(revealLength, document.getLength() - safeOffset));
            if (target.viewer != null) {
                target.viewer.setSelectedRange(safeOffset, safeLength);
                target.viewer.revealRange(safeOffset, safeLength);
            } else {
                editor.selectAndReveal(safeOffset, safeLength);
            }
        }
        StringBuilder detail = new StringBuilder();
        for (int[] range : ranges) {
            detail.append(' ').append(range == null
                    ? "null"
                    : range[0] + "+" + range[1] + "='" + preview(document, range[0], range[1]) + "'");
        }
        log("applyHighlight(" + program.getName() + ") matches=" + allMatches.length
                + " occurrences=" + occurrences.size() + " annotated=" + annotated
                + " reveal=" + revealOffset
                + " viewer=" + (target.viewer != null)
                + " lines=" + document.getNumberOfLines()
                + " docLen=" + document.getLength()
                + " delim=" + describeDelimiter(document)
                + " ranges=[" + detail.toString().trim() + "]"
                + " viewerModel=" + (model != null)
                + " altModel=" + (target.altModel != null));
    }

    private static String preview(IDocument document, int offset, int length) {
        try {
            int start = Math.max(0, offset);
            int end = Math.min(document.getLength(), offset + Math.max(0, length));
            return document.get(start, end - start)
                    .replace("\r", "\\r").replace("\n", "\\n");
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Document and annotation model of the widget that is really displayed.
     *
     * <p>For the ADT editors {@code getDocumentProvider().getDocument(input)}
     * returns a copy of the file with CR/LF line delimiters, while the text
     * widget shows a document built by ADT with LF delimiters. Offsets taken
     * from the provider document are shifted by one character per line once the
     * annotation is painted. Positions and annotations must therefore always
     * come from the viewer.</p>
     */
    private static final class EditorTarget {
        final ISourceViewer viewer;
        final IDocument document;
        final IAnnotationModel model;
        final IAnnotationModel altModel;

        EditorTarget(ISourceViewer viewer, IDocument document, IAnnotationModel model,
                IAnnotationModel altModel) {
            this.viewer = viewer;
            this.document = document;
            this.model = model;
            this.altModel = altModel;
        }
    }

    private static EditorTarget resolveTarget(ITextEditor editor) {
        IAnnotationModel providerModel = providerAnnotationModel(editor);
        ISourceViewer viewer = viewerOf(editor);
        if (viewer != null) {
            IDocument document = viewer.getDocument();
            if (document != null) {
                return new EditorTarget(viewer, document, viewer.getAnnotationModel(),
                        providerModel);
            }
        }
        try {
            IEditorInput input = editor.getEditorInput();
            IDocument document = editor.getDocumentProvider().getDocument(input);
            if (document != null) {
                return new EditorTarget(null, document, providerModel, null);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static IAnnotationModel providerAnnotationModel(ITextEditor editor) {
        try {
            IEditorInput input = editor.getEditorInput();
            return input == null ? null : editor.getDocumentProvider().getAnnotationModel(input);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Text viewer of the editor: adapter first, then the usual getter names. */
    private static ISourceViewer viewerOf(ITextEditor editor) {
        try {
            Object adapter = editor.getAdapter(ITextViewer.class);
            if (adapter instanceof ISourceViewer) {
                return (ISourceViewer) adapter;
            }
            adapter = editor.getAdapter(ISourceViewer.class);
            if (adapter instanceof ISourceViewer) {
                return (ISourceViewer) adapter;
            }
        } catch (Throwable ignored) {
            // use the getters below
        }
        for (String name : new String[] { "getSourceViewer", "getTextViewer", "getViewer" }) {
            try {
                Class<?> type = editor.getClass();
                while (type != null) {
                    Method method = null;
                    try {
                        method = type.getDeclaredMethod(name);
                    } catch (NoSuchMethodException ignored) {
                        type = type.getSuperclass();
                        continue;
                    }
                    method.setAccessible(true);
                    Object value = method.invoke(editor);
                    if (value instanceof ISourceViewer) {
                        return (ISourceViewer) value;
                    }
                    break;
                }
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    private static String describeDelimiter(IDocument document) {
        try {
            String delimiter = document.getLineDelimiter(0);
            return delimiter == null
                    ? "?"
                    : delimiter.replace("\r", "\\r").replace("\n", "\\n");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** All pattern occurrences of the editor document, in document order. */
    private static List<int[]> findOccurrences(IDocument document, CodeSearchMatcher matcher) {
        List<int[]> ranges = new ArrayList<>();
        if (matcher == null || document == null) {
            return ranges;
        }
        try {
            Pattern pattern = matcher.getCaseInsensitivePattern();
            java.util.regex.Matcher m = pattern.matcher(document.get());
            int guard = 0;
            while (m.find() && guard++ < 100000) {
                if (m.end() > m.start()) {
                    ranges.add(new int[] { m.start(), m.end() - m.start() });
                }
            }
        } catch (Throwable t) {
            log("findOccurrences failed: " + t.getMessage());
        }
        return ranges;
    }

    /**
     * Pairs every searched match with an occurrence of the editor document.
     * Equal counts are paired 1:1, otherwise the closest line/column wins.
     *
     * @return index into {@code occurrences} per match, or -1 when unmapped
     */
    private static int[] mapMatches(IDocument document, AbapCodeProgram program,
            Match[] allMatches, List<int[]> occurrences) {
        int count = allMatches.length;
        int[] mapping = new int[count];
        Arrays.fill(mapping, -1);
        if (count == 0 || occurrences.isEmpty()) {
            return mapping;
        }
        if (occurrences.size() == count) {
            for (int i = 0; i < count; i++) {
                mapping[i] = i;
            }
            return mapping;
        }
        for (int i = 0; i < count; i++) {
            int wantLine = program.getLineNumber(allMatches[i].getOffset());
            int wantColumn = program.getColumn(allMatches[i].getOffset());
            int best = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int j = 0; j < occurrences.size(); j++) {
                int[] candidate = occurrences.get(j);
                int line = lineOf(document, candidate[0]);
                int column = candidate[0] - lineStart(document, line) + 1;
                int score = Math.abs(line - wantLine) * 1000 + Math.abs(column - wantColumn);
                if (score < bestScore) {
                    bestScore = score;
                    best = j;
                }
            }
            mapping[i] = best;
        }
        log("mapMatches: matches=" + count + " occurrences=" + occurrences.size()
                + " firstMapping=" + mapping[0]);
        return mapping;
    }

    /** 1 based line number of an offset in the document. */
    private static int lineOf(IDocument document, int offset) {
        try {
            return Math.min(document.getNumberOfLines(), Math.max(1,
                    document.getLineOfOffset(Math.max(0, Math.min(offset, document.getLength())))));
        } catch (Throwable t) {
            return 1;
        }
    }

    private static int lineStart(IDocument document, int line) {
        try {
            return document.getLineOffset(line - 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void clearAnnotations() {
        for (AppliedAnnotation applied : APPLIED) {
            try {
                applied.model.removeAnnotation(applied.annotation);
            } catch (Throwable ignored) {
                // editor may already be closed
            }
        }
        APPLIED.clear();
    }

    private static IDocument getDocument(ITextEditor editor) {
        try {
            IEditorInput input = editor.getEditorInput();
            if (input == null) {
                return null;
            }
            return editor.getDocumentProvider().getDocument(input);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Opens the program editor (or reuses an already open one). */
    public static ITextEditor openProgramEditor(AbapCodeProgram program) {
        IEditorPart part = findOrOpen(program);
        if (part == null) {
            return null;
        }
        ITextEditor editor = part.getAdapter(ITextEditor.class);
        if (editor == null && part instanceof ITextEditor) {
            editor = (ITextEditor) part;
        }
        return editor;
    }

    static IDocument documentOf(ITextEditor editor) {
        return getDocument(editor);
    }

    private static IEditorPart findOrOpen(AbapCodeProgram program) {
        IEditorPart open = findOpenEditor(program);
        return open != null ? open : openViaAdt(program);
    }

    private static IEditorPart findOpenEditor(AbapCodeProgram program) {
        try {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                for (IWorkbenchPage page : window.getPages()) {
                    for (IEditorPart editor : page.getEditors()) {
                        AdtObjectResolver.AdtObjectInfo info = AdtObjectResolver.getAdtObject(editor);
                        if (info == null || info.name == null) {
                            continue;
                        }
                        if (info.name.equalsIgnoreCase(program.getIdentityName())) {
                            if (info.type != null && info.type.equalsIgnoreCase(program.getIdentityType())) {
                                return editor;
                            }
                            if (!program.isFunctionInclude() && info.type != null
                                    && info.type.startsWith("PROG")
                                    && program.isInclude() == info.type.toUpperCase().contains("/I")) {
                                return editor;
                            }
                        }
                        // The raw include may be open even though we address
                        // its function module (and vice versa).
                        if (program.isFunctionInclude()
                                && info.name.equalsIgnoreCase(program.getName())
                                && info.type != null && info.type.startsWith("PROG")) {
                            return editor;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("findOpenEditor failed: " + t.getMessage());
        }
        return null;
    }

    private static IEditorPart openViaAdt(AbapCodeProgram program) {
        IProject project = program.getProject();
        if (project == null) {
            log("openViaAdt: no project for " + program.getName());
            return null;
        }
        try {
            Class<?> factoryClass = loadClass(BUNDLE_ADT_CORE_UI, CLS_NAVIGATION_FACTORY);
            if (factoryClass == null) {
                log("openViaAdt: ADT navigation factory not available");
                return null;
            }
            Object service = factoryClass.getMethod("createNavigationService").invoke(null);
            Method navigate = findNavigateMethod(service.getClass());
            if (navigate == null) {
                log("openViaAdt: navigate method not found");
                return null;
            }
            // Function includes open the function module editor (editable);
            // the raw include opens read-only and is only a fallback.
            if (program.isFunctionInclude()) {
                IEditorPart part = navigate(service, navigate, project,
                        program.getFunctionName(), program.getIdentityType(),
                        functionUri(program, false));
                if (part == null) {
                    part = navigate(service, navigate, project,
                            program.getFunctionName(), program.getIdentityType(),
                            functionUri(program, true));
                }
                if (part != null) {
                    return part;
                }
                log("openViaAdt: function module editor unavailable for "
                        + program.getFunctionName() + ", falling back to include");
            }
            return navigate(service, navigate, project,
                    program.getName(), program.getType(), objectUri(program));
        } catch (Throwable t) {
            log("openViaAdt failed for " + program.getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static IEditorPart navigate(Object service, Method navigate, IProject project,
            String name, String type, String uri) {
        try {
            Class<?> refClass = navigate.getParameterTypes()[1];
            Class<?> coreFactoryClass = Class.forName(
                    "com.sap.adt.tools.core.model.adtcore.IAdtCoreFactory", true,
                    refClass.getClassLoader());
            Object factory = coreFactoryClass.getField("eINSTANCE").get(null);
            Object reference = coreFactoryClass.getMethod("createAdtObjectReference").invoke(factory);
            refClass.getMethod("setName", String.class).invoke(reference, name);
            refClass.getMethod("setType", String.class).invoke(reference, type);
            refClass.getMethod("setUri", String.class).invoke(reference, uri);
            Object result = navigate.invoke(service, project, reference, Boolean.FALSE);
            return result instanceof IEditorPart ? (IEditorPart) result : null;
        } catch (Throwable t) {
            log("navigate failed for " + name + " (" + type + ", " + uri + "): " + t.getMessage());
            return null;
        }
    }

    private static Method findNavigateMethod(Class<?> serviceClass) {
        for (Method method : serviceClass.getMethods()) {
            if ("navigate".equals(method.getName()) && method.getParameterCount() == 3
                    && IProject.class.equals(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }

    private static String objectUri(AbapCodeProgram program) {
        String name = program.getName().toLowerCase();
        String encoded = name.replace("/", "%2F");
        return program.isInclude()
                ? "/sap/bc/adt/programs/includes/" + encoded
                : "/sap/bc/adt/programs/programs/" + encoded;
    }

    /**
     * ADT URI of a function module, e.g.
     * {@code /sap/bc/adt/functions/groups/zfg_dz/fmodules/zfm_tr_dz_attachment}.
     * The {@code withSourceMain} variant appends {@code /source/main}, matching
     * the URI ADT reports for the open function source editor.
     */
    private static String functionUri(AbapCodeProgram program, boolean withSourceMain) {
        String group = program.getFunctionGroup() == null
                ? "" : program.getFunctionGroup().toLowerCase().replace("/", "%2F");
        String fm = program.getFunctionName().toLowerCase().replace("/", "%2F");
        return "/sap/bc/adt/functions/groups/" + group + "/fmodules/" + fm
                + (withSourceMain ? "/source/main" : "");
    }

    private static Class<?> loadClass(String bundleId, String className) {
        try {
            org.osgi.framework.Bundle bundle = Platform.getBundle(bundleId);
            return bundle == null ? null : bundle.loadClass(className);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String message) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID, message));
        } catch (Throwable ignored) {
            // logging must never break the search flow
        }
    }
}
