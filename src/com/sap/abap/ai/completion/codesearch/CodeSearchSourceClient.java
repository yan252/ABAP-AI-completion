package com.sap.abap.ai.completion.codesearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

/**
 * Orchestrates source collection for the ABAP Code Search feature.
 *
 * <p>The main program source is supplied by the caller (read directly from
 * the already-open ADT editor document so the user sees the latest
 * uncommitted code). INCLUDE list comes from two sources that we merge:
 * <ol>
 *   <li>{@code D010INC} table — SAP's official MASTER↔INCLUDE mapping
 *       ({@code D010INC.MASTER = mainProgram}).</li>
 *   <li>Regex scan of the main program source — catches INCLUDE statements
 *       that may not yet be reflected in D010INC.</li>
 * </ol>
 * All INCLUDE sources are then read from the SAP backend through
 * {@code RPY_PROGRAM_READ} (see {@link AdtSourceReader}).</p>
 *
 * <p>If the user is editing an INCLUDE, we first resolve its owning main
 * program via {@code D010INC.INCLUDE} = current INCLUDE, then collect all
 * INCLUDEs of that main program.</p>
 */
public final class CodeSearchSourceClient {

    /**
     * Hard cap on how many objects we fetch in total to keep the search
     * bounded. Large function pools reach ~180 includes (e.g. SAPLYMMT_*),
     * so the cap must stay well above that.
     */
    private static final int MAX_OBJECTS = 500;

    private CodeSearchSourceClient() {
    }

    /** One include program of the prepared scope. */
    public static final class IncludeProgram {
        public final String name;
        public final String source;
        /** Function module name when this include is an L<group>U<nn> function include. */
        public final String functionName;
        /** Function group of {@link #functionName}, e.g. "ZFG_DZ" or "/ABC/FG". */
        public final String functionGroup;
        /** Friendly label for class local-type includes ("Test Classes" etc.). */
        public final String displayName;

        IncludeProgram(String name, String source) {
            this(name, source, null, null, null);
        }

        IncludeProgram(String name, String source, String functionName, String functionGroup) {
            this(name, source, functionName, functionGroup, null);
        }

        IncludeProgram(String name, String source, String functionName, String functionGroup,
                String displayName) {
            this.name = name;
            this.source = source;
            this.functionName = functionName;
            this.functionGroup = functionGroup;
            this.displayName = displayName;
        }
    }

    /** The main program plus every include program discovered in it. */
    public static final class PreparedSources {
        public final String mainName;
        public final String mainType;
        public final String mainSource;
        public final List<IncludeProgram> includes;

        PreparedSources(String mainName, String mainType, String mainSource,
                List<IncludeProgram> includes) {
            this.mainName = mainName;
            this.mainType = mainType;
            this.mainSource = mainSource;
            this.includes = Collections.unmodifiableList(includes);
        }
    }

    /**
     * Collects main program source + every INCLUDE source into a single result.
     *
     * @param project             ADT project (supplies the JCo destination)
     * @param programName         current program name reported by the active editor
     * @param programType         current program type, e.g. "PROG/P" or "PROG/I"
     * @param mainSource          source text of the program the user is currently editing
     *                            (read directly from the editor document — includes uncommitted changes)
     * @param includeEntry        true iff the active editor shows an INCLUDE
     * @param classPages          all class editor pages' sources (global class +
     *                            CCDEF/CCIMP/CCMAC/CT), collected from the
     *                            multi-page class editor. Non-null only for
     *                            class searches; when present it is used
     *                            directly instead of querying the backend
     *                            (whose RPY_PROGRAM_READ does not return
     *                            class-pool include sources).
     */
    public static PreparedSources prepare(IProject project, String programName, String programType,
            String mainSource, boolean includeEntry,
            java.util.List<CodeSearchContext.ClassPage> classPages) throws IOException {
        try {
            String mainType = (programType != null && !programType.trim().isEmpty())
                    ? programType.toUpperCase() : "PROG/P";

            // Determine the "owning main program" for INCLUDE lookup
            String effectiveMainName;
            if (includeEntry) {
                String resolved = AdtSourceReader.resolveMainProgram(project, programName);
                effectiveMainName = (resolved != null && !resolved.isEmpty())
                        ? resolved.toUpperCase() : programName.toUpperCase();
            } else {
                effectiveMainName = programName.toUpperCase();
            }

            // ---- Step 1: Collect INCLUDE names ----
            Set<String> includeSet = new LinkedHashSet<>();

            // 1a. D010INC + REPOSRC: SAP's official MASTER→INCLUDE mapping
            List<String> fromTable = Collections.emptyList();
            try {
                fromTable = AdtSourceReader.includesOfMainProgram(project, effectiveMainName);
            } catch (AdtSourceReader.SourceException e) {
                AdtSourceReader.log("prepare: D010INC/REPOSRC lookup failed for "
                        + effectiveMainName + ": " + e.getMessage());
            }
            for (String s : fromTable) includeSet.add(s);

            // 1b. Regex-scan main source (catches non-standard layouts and
            //     includes that are not yet reflected in D010INC)
            if (mainSource != null && !mainSource.trim().isEmpty()) {
                for (String s : AdtSourceReader.extractIncludeNames(mainSource)) includeSet.add(s);
            }

            // If editing an INCLUDE, make sure it itself is available as a search target
            if (includeEntry) {
                includeSet.add(programName.toUpperCase());
            }

            // 1c. Classes: when the multi-page class editor is available, every
            //     page's source (global class + CCDEF/CCIMP/CCMAC/CT) is read
            //     directly from the editor. This avoids the backend, whose
            //     RPY_PROGRAM_READ does not return class-pool include sources,
            //     and ensures unsaved edits are searched.
            boolean classSearch = mainType.startsWith("CLAS/");
            boolean useEditorClassPages = classSearch
                    && classPages != null && !classPages.isEmpty();
            if (classSearch && !useEditorClassPages) {
                List<String> localTypes = Collections.emptyList();
                try {
                    localTypes = AdtSourceReader.classPoolLocalTypeIncludes(
                            project, effectiveMainName);
                } catch (AdtSourceReader.SourceException e) {
                    AdtSourceReader.log("prepare: class pool include lookup failed for "
                            + effectiveMainName + ": " + e.getMessage());
                }
                for (String s : localTypes) includeSet.add(s);
            }
            AdtSourceReader.log("prepare: main=" + effectiveMainName
                    + " includeEntry=" + includeEntry
                    + " classPages=" + (useEditorClassPages ? classPages.size() : 0)
                    + " candidates(fromTable=" + fromTable.size() + ", merged="
                    + includeSet.size() + ")=" + includeSet);

            // Apply size cap
            List<String> includesOrdered = new ArrayList<>();
            for (String s : includeSet) {
                if (includesOrdered.size() >= MAX_OBJECTS) break;
                includesOrdered.add(s);
            }

            // ---- Step 2: Read every INCLUDE source from the backend ----
            // Function group main programs (SAPL<group>) carry their function
            // modules in L<group>U<nn> includes. Detect them so the result can
            // name and open the function module instead of the raw include
            // (the raw include opens read-only in ADT).
            String functionGroup = functionGroupOf(effectiveMainName);
            List<IncludeProgram> includePrograms = new ArrayList<>();

            if (useEditorClassPages) {
                // Build the include list straight from the editor pages. The
                // global class page becomes the main program source; the
                // local-type pages become includes.
                for (CodeSearchContext.ClassPage page : classPages) {
                    if (page.isGlobalClass() || page.source == null
                            || page.source.trim().isEmpty()) {
                        continue;
                    }
                    String partLabel = classLocalPartLabel(
                            classPoolPrefix(effectiveMainName), page.name);
                    includePrograms.add(new IncludeProgram(page.name, page.source,
                            null, null, partLabel));
                }
                AdtSourceReader.log("prepare: class includes from editor: "
                        + includePrograms.size());
            } else if (!includesOrdered.isEmpty()) {
                Map<String, String> sources = AdtSourceReader.readSources(project, includesOrdered);
                for (String name : includesOrdered) {
                    // The include currently open in the editor wins: it holds
                    // the user's uncommitted changes.
                    String src;
                    if (includeEntry && name.equalsIgnoreCase(programName)) {
                        src = mainSource;
                    } else {
                        src = sources.get(name);
                    }
                    if (src != null && !src.isEmpty()) {
                        String fmName = detectFunctionName(functionGroup, name, src);
                        String partLabel = classSearch
                                ? classLocalPartLabel(effectiveMainName, name) : null;
                        includePrograms.add(new IncludeProgram(name, src, fmName,
                                fmName == null ? null : functionGroup, partLabel));
                    }
                }
            }
            AdtSourceReader.log("prepare: includes resolved " + includePrograms.size()
                    + " of " + includesOrdered.size());

            // ---- Step 3: Main program source ----
            // When the editor shows an INCLUDE, the document is that include and
            // not the main program. The same applies to function modules, whose
            // document shows the function source while the searched main program
            // is SAPL<group>. In both cases the main program (no editor source
            // supplied) is read from the backend.
            // For classes, the global class page source comes from the editor
            // (useEditorClassPages), so no backend read is needed.
            String mainProgramSource = mainSource == null ? "" : mainSource;
            String reportedMainType = mainType;
            if (useEditorClassPages) {
                for (CodeSearchContext.ClassPage page : classPages) {
                    if (page.isGlobalClass() && page.source != null
                            && !page.source.trim().isEmpty()) {
                        mainProgramSource = page.source;
                        break;
                    }
                }
            } else if (includeEntry || mainSource == null || mainSource.trim().isEmpty()) {
                reportedMainType = "PROG/P";
                try {
                    String fetched = AdtSourceReader.readSource(project, effectiveMainName);
                    if (fetched != null && !fetched.isEmpty()) mainProgramSource = fetched;
                } catch (AdtSourceReader.SourceException e) {
                    AdtSourceReader.log("prepare: cannot read main program source '"
                            + effectiveMainName + "': " + e.getMessage());
                }
            }

            return new PreparedSources(effectiveMainName, reportedMainType,
                    mainProgramSource, includePrograms);
        } catch (AdtSourceReader.SourceException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Local-type page suffixes of a class pool (searched for classes). */
    private static final String[] CLASS_LOCAL_SUFFIXES = { "CCDEF", "CCIMP", "CCMAC", "CT" };

    /**
     * If {@code name} is a class-pool include whose name ends with one of the
     * local-type page suffixes (e.g. {@code ZCL_FOO===============CCIMP}),
     * returns the bare class name (everything before the first '=').
     * Otherwise returns {@code null}.
     *
     * <p>The ADT class editor exposes the active page as an IAdtObjectReference
     * whose name is the padded include name rather than the class name. The
     * search must recover the class name so that all four pages are fetched.</p>
     */
    public static String classNameOfClassInclude(String name) {
        if (name == null) {
            return null;
        }
        String upper = name.toUpperCase();
        int eq = upper.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String suffix = upper.substring(upper.lastIndexOf('=') + 1);
        for (String candidate : CLASS_LOCAL_SUFFIXES) {
            if (candidate.equals(suffix)) {
                return upper.substring(0, eq);
            }
        }
        return null;
    }

    /**
     * Class-pool include prefix: the class name padded with '=' to 28
     * characters (e.g. {@code CL_SALV_TABLE===============}), shared by every
     * include of the class pool (&lt;prefix&gt;CP / CU / CMnnn / CCDEF / CCIMP /
     * CCMAC / CT).
     */
    static String classPoolPrefix(String className) {
        StringBuilder sb = new StringBuilder(className.toUpperCase());
        while (sb.length() < 28) {
            sb.append('=');
        }
        return sb.toString();
    }

    /** Localized label of a class local-types include, or null. */
    private static String classLocalPartLabel(String classPrefix, String includeName) {
        String upper = includeName.toUpperCase();
        if (!upper.startsWith(classPrefix)) {
            return null;
        }
        String suffix = upper.substring(classPrefix.length());
        switch (suffix) {
            case "CCDEF":
                return Messages.get("classpart.ccdef");
            case "CCIMP":
                return Messages.get("classpart.ccimp");
            case "CCMAC":
                return Messages.get("classpart.ccmac");
            case "CT":
                return Messages.get("classpart.ct");
            default:
                return null;
        }
    }

    /**
     * The function group of a function pool main program, e.g.
     * {@code SAPLZFG_DZ -> ZFG_DZ} and {@code /ABC/SAPLFG -> /ABC/FG}.
     * Returns {@code null} for ordinary programs.
     */
    private static String functionGroupOf(String mainName) {
        if (mainName == null) {
            return null;
        }
        String upper = mainName.toUpperCase();
        int idx = upper.lastIndexOf("SAPL");
        // The SAPL prefix must lead the name or follow a namespace slash,
        // otherwise ordinary programs containing "SAPL" would false-positive.
        if (idx < 0 || (idx > 0 && upper.charAt(idx - 1) != '/')) {
            return null;
        }
        return upper.substring(0, idx) + upper.substring(idx + 4);
    }

    /**
     * Returns the function module implemented by an L<group>U<nn> include, or
     * {@code null} when the include is not a function include. The FUNCTION
     * statement is only allowed inside function groups, so a line-start match
     * is a safe indicator. ({@code FUNCTION-POOL} cannot match because of the
     * mandatory whitespace.)
     */
    private static String detectFunctionName(String functionGroup, String includeName, String source) {
        if (functionGroup == null || includeName == null || source == null) {
            return null;
        }
        String expectedPrefix = (functionGroup.contains("/")
                ? functionGroup.substring(0, functionGroup.lastIndexOf('/') + 1) + "L"
                        + functionGroup.substring(functionGroup.lastIndexOf('/') + 1)
                : "L" + functionGroup);
        String upper = includeName.toUpperCase();
        if (!upper.startsWith(expectedPrefix)
                || !upper.substring(expectedPrefix.length()).matches("U\\d{2}")) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*FUNCTION\\s+([A-Z0-9_/]+)\\s*\\.",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                                | java.util.regex.Pattern.MULTILINE)
                .matcher(source);
        return m.find() ? m.group(1).toUpperCase() : null;
    }
}
