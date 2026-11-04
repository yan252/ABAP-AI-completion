package com.sap.abap.ai.completion.codesearch;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoTable;

/**
 * Reads ABAP source code and INCLUDE relationships from the SAP backend that
 * the currently open ADT project is connected to (the JCo destination ADT
 * already established — no extra URL / user / password needed).
 *
 * <p>Source code is read via the standard SAP function module
 * {@code RPY_PROGRAM_READ}: its import parameter name and its result table
 * differ between releases, so the parameter names are discovered from the
 * function meta data at runtime instead of being hard coded.</p>
 *
 * <p>Note on tables: {@code REPOSRC} only holds program attributes plus a
 * compressed (LRAW) payload — {@code RFC_READ_TABLE} cannot read that column,
 * so it is only used to answer "which entries are includes"
 * ({@code SUBC = 'I' AND APPL = ''}).</p>
 */
public final class AdtSourceReader {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_ADT_PROJECT = "com.sap.adt.project";
    private static final String CLS_FACTORY = "com.sap.adt.project.AdtCoreProjectServiceFactory";
    private static final String CLS_SERVICE = "com.sap.adt.project.IAdtCoreProjectService";

    private static final String FM_PROGRAM_READ = "RPY_PROGRAM_READ";

    /** Matches an ABAP INCLUDE statement. */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("(?:^|[\\s])INCLUDE[\\s]+([A-Za-z0-9_/$]+)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Function signatures already written to the error log (avoid flooding it). */
    private static final Set<String> LOGGED_SIGNATURES = new HashSet<>();

    /** DD03L column cache. */
    private static final Map<String, List<String>> COLUMN_CACHE = new HashMap<>();

    private AdtSourceReader() {
    }

    // ====================================================================
    // Public API
    // ====================================================================

    /** Reads the full source of one program / include. */
    public static String readSource(IProject project, String programName) throws SourceException {
        JCoDestination destination = requireDestination(project);
        String src = readFromBackend(destination, programName);
        if (src == null || src.isEmpty()) {
            throw new SourceException("Program '" + programName + "' not found in the SAP backend");
        }
        return src;
    }

    /** Batch read; programs that cannot be read are omitted from the result. */
    public static Map<String, String> readSources(IProject project, List<String> programNames)
            throws SourceException {
        JCoDestination destination = requireDestination(project);
        Set<String> wanted = new LinkedHashSet<>();
        for (String n : programNames) {
            if (n != null && !n.trim().isEmpty()) wanted.add(n.trim().toUpperCase());
        }
        Map<String, String> result = new HashMap<>();
        for (String name : wanted) {
            String src = readFromBackend(destination, name);
            if (src != null && !src.isEmpty()) result.put(name, src);
        }
        log("readSources requested=" + wanted.size() + " resolved=" + result.size()
                + " missing=" + difference(wanted, result.keySet()));
        return result;
    }

    /**
     * Returns every INCLUDE of the given main program, implementing the SQL
     * confirmed to work in SE38:
     * <pre>
     * SELECT D010INC~INCLUDE
     *   FROM D010INC
     *   INNER JOIN REPOSRC ON D010INC~INCLUDE = REPOSRC~PROGNAME
     *  WHERE D010INC~MASTER = mainProgram
     *    AND REPOSRC~SUBC = 'I'
     *    AND REPOSRC~APPL = ''
     * </pre>
     * RFC_READ_TABLE cannot join, so it runs in two steps:
     * (1) D010INC takes the candidates, (2) REPOSRC answers which of them are
     * really include programs (SUBC = 'I', APPL = empty).
     */
    public static List<String> includesOfMainProgram(IProject project, String mainProgram)
            throws SourceException {
        JCoDestination destination = requireDestination(project);
        String master = mainProgram.toUpperCase();

        // ---- Primary: RPY_PROGRAM_READ with INCLUDE_TAB ----
        List<String> fromFunction = readIncludeListFromFunction(destination, master);
        if (!fromFunction.isEmpty()) {
            log("includesOfMainProgram(" + master + ") via INCLUDE_TAB ("
                    + fromFunction.size() + ")=" + fromFunction);
            return fromFunction;
        }

        // ---- Fallback: D010INC (RFC_READ_TABLE) ----
        List<String> candidates = new ArrayList<>();
        try {
            callRfcReadTable(destination, "D010INC",
                    "MASTER = '" + escape(padRight(master, 40)) + "'",
                    new String[] { "INCLUDE" },
                    row -> {
                        String name = cleanName(row.get("INCLUDE"));
                        // D010INC also lists generated / system entries such as
                        // 'ZAPP====...P', '%_CABAP', '<REPINI>'. Only plain
                        // program names are real includes.
                        if (name != null && name.matches("[A-Z0-9_/$]+")) addIfAbsent(candidates, name);
                    });
        } catch (Throwable t) {
            log("includesOfMainProgram(" + master + ") D010INC failed: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
        }
        log("includesOfMainProgram(" + master + ") fallback D010INC (" + candidates.size() + ")="
                + candidates);
        return candidates;
    }

    /** Reads the include list from RPY_PROGRAM_READ's INCLUDE_TAB. */
    private static List<String> readIncludeListFromFunction(JCoDestination destination, String programName) {
        List<String> result = new ArrayList<>();
        try {
            JCoFunction fn = destination.getRepository().getFunction(FM_PROGRAM_READ);
            if (fn == null) return result;
            logSignature(FM_PROGRAM_READ, fn);

            JCoParameterList imp = fn.getImportParameterList();
            String param = findImportParam(fn, "PROGRAM_NAME", "PROG_NAME", "PROGRAM", "PROG");
            if (param == null) return result;
            imp.setValue(param, programName);

            List<String> imports = recordNames(imp);
            if (imports.contains("WITH_INCLUDELIST")) imp.setValue("WITH_INCLUDELIST", "X");
            if (imports.contains("ONLY_SOURCE")) imp.setValue("ONLY_SOURCE", "X");

            fn.execute(destination);

            JCoParameterList tables = fn.getTableParameterList();
            if (tables == null || !recordNames(tables).contains("INCLUDE_TAB")) return result;

            JCoTable includeTab = tables.getTable("INCLUDE_TAB");
            List<String> columns = recordNames(includeTab);
            String nameColumn = columns.contains("INCLNAME") ? "INCLNAME" : (columns.isEmpty() ? null : columns.get(0));
            if (nameColumn == null) return result;

            for (int i = 0; i < includeTab.getNumRows(); i++) {
                includeTab.setRow(i);
                addIfAbsent(result, cleanName(includeTab.getString(nameColumn)));
            }
        } catch (Throwable t) {
            log("readIncludeListFromFunction(" + programName + ") failed: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
        }
        return result;
    }

    /** Finds a main program of the given include. */
    public static String resolveMainProgram(IProject project, String includeName) throws SourceException {
        JCoDestination destination = requireDestination(project);
        String include = includeName.toUpperCase();

        List<String> mains = new ArrayList<>();
        try {
            callRfcReadTable(destination, "D010INC",
                    "INCLUDE = '" + escape(padRight(include, 40)) + "'",
                    new String[] { "MASTER" },
                    row -> addIfAbsent(mains, cleanName(row.get("MASTER"))));
        } catch (Throwable t) {
            log("resolveMainProgram(" + include + ") D010INC failed: " + t.getMessage());
        }
        if (!mains.isEmpty()) {
            log("resolveMainProgram(" + include + ") D010INC masters ("
                    + mains.size() + ")=" + mains + " picking=" + mains.get(0));
            return mains.get(0);
        }

        // Fallback: RPY_GET_MAINPROGRAM
        try {
            JCoFunction fn = destination.getRepository().getFunction("RPY_GET_MAINPROGRAM");
            if (fn != null) {
                String param = findImportParam(fn, "PROGRAM_NAME", "PROG_NAME", "PROGRAM", "PROG");
                if (param != null) {
                    fn.getImportParameterList().setValue(param, include);
                    fn.execute(destination);
                    String main = readExportString(fn, "MAINPROGRAM", "MAIN_PROGRAM", "MASTER");
                    if (main != null && !main.trim().isEmpty()) {
                        return main.trim().toUpperCase();
                    }
                }
            }
        } catch (Throwable t) {
            log("resolveMainProgram(" + include + ") RPY_GET_MAINPROGRAM failed: " + t.getMessage());
        }
        throw new SourceException("Cannot resolve main program of INCLUDE '" + includeName + "'");
    }

    /** Extracts INCLUDE names from source text via regex. */
    public static List<String> extractIncludeNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        if (source == null) return new ArrayList<>();
        Matcher m = INCLUDE_PATTERN.matcher(source);
        while (m.find()) names.add(m.group(1).toUpperCase());
        return new ArrayList<>(names);
    }

    // ====================================================================
    // Source reading: RPY_PROGRAM_READ (primary)
    // ====================================================================

    private static String readFromBackend(JCoDestination destination, String programName) {
        try {
            String src = tryRpyProgramRead(destination, programName);
            if (src != null && !src.isEmpty()) {
                log("readFromBackend(" + programName + ") OK via RPY_PROGRAM_READ, "
                        + src.length() + " chars, " + countLines(src) + " lines");
                return src;
            }
            log("readFromBackend(" + programName + ") RPY_PROGRAM_READ returned no source");
        } catch (Throwable t) {
            log("readFromBackend(" + programName + ") RPY_PROGRAM_READ failed: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
        }
        return null;
    }

    private static String tryRpyProgramRead(JCoDestination destination, String programName)
            throws SourceException {
        try {
            JCoFunction fn = destination.getRepository().getFunction(FM_PROGRAM_READ);
            if (fn == null) {
                log("RPY_PROGRAM_READ is not available in this system");
                return null;
            }
            logSignature(FM_PROGRAM_READ, fn);

            // Discover the import parameter that takes the program name.
            String param = findImportParam(fn, "PROGRAM_NAME", "PROG_NAME", "PROGRAM", "PROG", "NAME");
            if (param == null) {
                return null;
            }
            fn.getImportParameterList().setValue(param, programName.toUpperCase());

            // Keep the original upper/lower case of the source. Without this
            // flag RPY_PROGRAM_READ returns the source in upper case, which
            // makes the result labels differ from what the editor shows.
            JCoParameterList importList = fn.getImportParameterList();
            if (recordNames(importList).contains("WITH_LOWERCASE")) {
                importList.setValue("WITH_LOWERCASE", "X");
            }

            fn.execute(destination);

            // Scan the output tables, preferring the well known source tables.
            JCoParameterList tables = fn.getTableParameterList();
            if (tables != null) {
                List<String> tableNames = recordNames(tables);
                String[] preferred = { "SOURCE", "SOURCE_EXTENDED", "PROGRAM", "SOURCE_CODE" };
                for (String preferredName : preferred) {
                    if (!tableNames.contains(preferredName)) continue;
                    String source = extractSourceFromTable(tables.getTable(preferredName), preferredName);
                    if (source != null && !source.isEmpty()) {
                        log("RPY_PROGRAM_READ(" + programName + ") source taken from table '"
                                + preferredName + "'");
                        return source;
                    }
                }
                for (String tableName : tableNames) {
                    if (Arrays.asList(preferred).contains(tableName)) continue;
                    if (tableName.equals("INCLUDE_TAB") || tableName.equals("TEXTELEMENTS")) continue;
                    String source;
                    try {
                        source = extractSourceFromTable(tables.getTable(tableName), tableName);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (source != null && !source.isEmpty()) {
                        log("RPY_PROGRAM_READ(" + programName + ") source taken from table '"
                                + tableName + "'");
                        return source;
                    }
                }
            }

            // Fallback: a plain string export parameter.
            JCoParameterList exports = fn.getExportParameterList();
            if (exports != null) {
                int exportCount = exports.getFieldCount();
                for (int i = 0; i < exportCount; i++) {
                    String name = exports.getField(i).getName();
                    String upper = name.toUpperCase();
                    if (!upper.contains("SOURCE") && !upper.contains("TEXT") && !upper.contains("CODE")) {
                        continue;
                    }
                    try {
                        String value = exports.getString(name);
                        if (value != null && !value.isEmpty()) {
                            log("RPY_PROGRAM_READ(" + programName + ") source taken from export '"
                                    + name + "'");
                            return value;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            return null;
        } catch (Throwable t) {
            throw wrap(t, FM_PROGRAM_READ + "(" + programName + ")");
        }
    }

    /**
     * Rebuilds the source text from a result table that holds one row per line.
     *
     * <p>{@code RPY_PROGRAM_READ} exposes two layouts:
     * <ul>
     *   <li>{@code SOURCE} / {@code SOURCE_EXTENDED} — a single column named
     *       {@code LINE} whose value already is the source line text;</li>
     *   <li>older layouts — a line number column plus a text column
     *       ({@code SOURCE} / {@code TEXT} / {@code CODE}).</li>
     * </ul>
     */
    private static String extractSourceFromTable(JCoTable table, String tableName) {
        if (table == null || table.getNumRows() <= 0) return null;

        List<String> columns = recordNames(table);
        log("extractSourceFromTable(" + tableName + ") rows=" + table.getNumRows()
                + " columns=" + columns);

        String lineColumn = findColumn(columns, "ZEILE", "LINENR", "LINENO", "LINE_NUMBER", "SEGMENT");
        String textColumn = findColumn(columns, "SOURCE", "TEXT", "CODE", "SEGL", "CONTENT", "LINE_SOURCE");

        // SOURCE / SOURCE_EXTENDED store the text in the single column 'LINE'.
        if (textColumn == null && columns.size() == 1 && columns.get(0).equalsIgnoreCase("LINE")) {
            textColumn = columns.get(0);
            lineColumn = null;
        }
        if (textColumn == null) return null;

        Map<Integer, String> lines = new HashMap<>();
        int ordinal = 1;
        int rowCount = table.getNumRows();
        for (int r = 0; r < rowCount; r++) {
            table.setRow(r);
            String text;
            try {
                text = table.getString(textColumn);
            } catch (Throwable t) {
                // The column may be a nested structure — read its text sub field.
                text = readStructureText(table, textColumn);
            }
            if (text == null) continue;
            int lineNumber = ordinal++;
            if (lineColumn != null) {
                try {
                    lineNumber = Integer.parseInt(table.getString(lineColumn).trim());
                } catch (Throwable ignored) { }
            }
            lines.put(lineNumber, text);
        }
        if (lines.isEmpty()) return null;

        int max = lines.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max <= 0 || max > 2000000) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= max; i++) {
            sb.append(lines.getOrDefault(i, "")).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Reads the source text out of a nested (structured) table column. */
    private static String readStructureText(JCoTable table, String column) {
        try {
            com.sap.conn.jco.JCoStructure structure = table.getStructure(column);
            List<String> names = recordNames(structure);
            for (String name : names) {
                if (name.equalsIgnoreCase("SOURCE") || name.equalsIgnoreCase("TEXT")
                        || name.equalsIgnoreCase("CODE")) {
                    return structure.getString(name);
                }
            }
            if (names.size() == 1) return structure.getString(names.get(0));
        } catch (Throwable ignored) { }
        return null;
    }

    /** Logs the import / export / table parameter names of a function module (once). */
    private static void logSignature(String functionName, JCoFunction fn) {
        if (!LOGGED_SIGNATURES.add(functionName)) return;
        try {
            StringBuilder sb = new StringBuilder("signature " + functionName);
            JCoParameterList imp = fn.getImportParameterList();
            sb.append(" | IMPORT=").append(recordNames(imp));
            JCoParameterList exp = fn.getExportParameterList();
            sb.append(" | EXPORT=").append(recordNames(exp));
            JCoParameterList tables = fn.getTableParameterList();
            sb.append(" | TABLES=").append(recordNames(tables));
            log(sb.toString());

            if (tables != null) {
                int count = tables.getFieldCount();
                for (int i = 0; i < count; i++) {
                    String tableName = tables.getField(i).getName();
                    try {
                        JCoTable table = tables.getTable(tableName);
                        if (table != null) {
                            table.setRow(0);
                            log("signature " + functionName + " table '" + tableName
                                    + "' columns=" + recordNames(table));
                        }
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            log("logSignature(" + functionName + ") failed: " + t.getMessage());
        }
    }

    private static List<String> recordNames(com.sap.conn.jco.JCoRecord record) {
        List<String> names = new ArrayList<>();
        if (record == null) return names;
        int count = record.getFieldCount();
        for (int i = 0; i < count; i++) names.add(record.getField(i).getName());
        return names;
    }

    private static String findImportParam(JCoFunction fn, String... hints) {
        List<String> names = recordNames(fn.getImportParameterList());
        return findColumn(names, hints);
    }

    private static String readExportString(JCoFunction fn, String... hints) {
        List<String> names = recordNames(fn.getExportParameterList());
        String name = findColumn(names, hints);
        if (name == null) return null;
        try {
            return fn.getExportParameterList().getString(name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ====================================================================
    // RFC_READ_TABLE — generic chunked reader
    // ====================================================================

    @FunctionalInterface
    private interface RowHandler {
        void accept(Map<String, String> row) throws Exception;
    }

    private static void callRfcReadTable(JCoDestination destination, String table, String options,
            String[] fields, RowHandler handler) throws SourceException {
        log("RFC_READ_TABLE " + table + " | options=" + truncate(options, 300)
                + " | fields=" + Arrays.toString(fields));
        try {
            JCoFunction function = destination.getRepository().getFunction("RFC_READ_TABLE");
            if (function == null) {
                throw new SourceException("RFC_READ_TABLE not available in this SAP system");
            }
            JCoParameterList imp = function.getImportParameterList();
            imp.setValue("QUERY_TABLE", table);
            imp.setValue("DELIMITER", "|");
            try { imp.setValue("AUTHORITY_CHECK", " "); } catch (Throwable ignored) { }

            JCoTable optTable = function.getTableParameterList().getTable("OPTIONS");
            int pos = 0;
            while (pos < options.length()) {
                optTable.appendRow();
                optTable.setValue("TEXT", options.substring(pos, Math.min(pos + 72, options.length())));
                pos += 72;
            }

            JCoTable fieldsTable = function.getTableParameterList().getTable("FIELDS");
            for (String f : fields) {
                if (f == null || f.isEmpty()) continue;
                fieldsTable.appendRow();
                fieldsTable.setValue("FIELDNAME", f);
            }

            int offset = 0;
            final int batch = 5000;
            int total = 0;
            while (true) {
                imp.setValue("ROWSKIPS", offset);
                imp.setValue("ROWCOUNT", batch);
                function.execute(destination);
                JCoTable data = function.getTableParameterList().getTable("DATA");
                int rows = data == null ? 0 : data.getNumRows();
                total += rows;
                if (rows == 0) break;
                for (int i = 0; i < rows; i++) {
                    data.setRow(i);
                    String wa = data.getString("WA");
                    if (wa == null) continue;
                    String[] parts = wa.split("\\|", -1);
                    Map<String, String> row = new HashMap<>();
                    for (int j = 0; j < fields.length && j < parts.length; j++) {
                        row.put(fields[j], parts[j]);
                    }
                    handler.accept(row);
                }
                offset += rows;
                if (rows < batch) break;
            }
            log("RFC_READ_TABLE " + table + " DONE — rows=" + total);
        } catch (SourceException e) {
            throw e;
        } catch (Throwable t) {
            throw wrap(t, "RFC_READ_TABLE(" + table + ")");
        }
    }

    /** Reads the column list of a DDIC table via DD03L (cached). */
    private static List<String> probeTableColumns(JCoDestination destination, String table) {
        String upper = table.toUpperCase();
        List<String> cached = COLUMN_CACHE.get(upper);
        if (cached != null) return cached;

        List<String> columns = new ArrayList<>();
        try {
            JCoFunction function = destination.getRepository().getFunction("RFC_READ_TABLE");
            if (function == null) {
                COLUMN_CACHE.put(upper, columns);
                return columns;
            }
            JCoParameterList imp = function.getImportParameterList();
            imp.setValue("QUERY_TABLE", "DD03L");
            imp.setValue("DELIMITER", "|");
            try { imp.setValue("AUTHORITY_CHECK", " "); } catch (Throwable ignored) { }

            JCoTable optTable = function.getTableParameterList().getTable("OPTIONS");
            optTable.appendRow();
            optTable.setValue("TEXT", "TABNAME = '" + escape(upper) + "'");

            JCoTable fieldsTable = function.getTableParameterList().getTable("FIELDS");
            fieldsTable.appendRow();
            fieldsTable.setValue("FIELDNAME", "FIELDNAME");

            imp.setValue("ROWSKIPS", 0);
            imp.setValue("ROWCOUNT", 5000);
            function.execute(destination);
            JCoTable data = function.getTableParameterList().getTable("DATA");
            int rows = data == null ? 0 : data.getNumRows();
            for (int i = 0; i < rows; i++) {
                data.setRow(i);
                String wa = data.getString("WA");
                if (wa == null || wa.isEmpty()) continue;
                String name = wa.split("\\|", -1)[0].trim();
                if (!name.isEmpty()) columns.add(name.toUpperCase());
            }
        } catch (Throwable t) {
            log("probeTableColumns(" + table + ") failed (non-fatal): "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
        }
        log("probeTableColumns(" + table + ") → " + columns.size() + " columns");
        COLUMN_CACHE.put(upper, columns);
        return columns;
    }

    // ====================================================================
    // Small helpers
    // ====================================================================

    /**
     * Finds a column name matching the hints; exact matches win, then prefix /
     * contains matches — but never a column that merely embeds the hint
     * (e.g. MAXLINELN must not be taken as the LINE column).
     */
    private static String findColumn(List<String> columns, String... hints) {
        for (String hint : hints) {
            for (String col : columns) {
                if (col.equalsIgnoreCase(hint)) return col;
            }
        }
        for (String hint : hints) {
            for (String col : columns) {
                String upper = col.toUpperCase();
                if (upper.startsWith(hint.toUpperCase()) && !upper.startsWith("MAX")) return col;
            }
        }
        return null;
    }

    /** Trims blanks and SAP's CHAR padding characters from a table value. */
    private static String cleanName(String value) {
        if (value == null) return null;
        String name = value.trim().toUpperCase();
        while (!name.isEmpty() && (name.charAt(name.length() - 1) == '='
                || Character.isWhitespace(name.charAt(name.length() - 1)))) {
            name = name.substring(0, name.length() - 1);
        }
        return name.isEmpty() ? null : name;
    }

    private static void addIfAbsent(List<String> list, String value) {
        if (value != null && !value.isEmpty() && !list.contains(value)) list.add(value);
    }

    private static List<String> difference(Set<String> wanted, Set<String> found) {
        List<String> missing = new ArrayList<>();
        for (String w : wanted) {
            if (!found.contains(w)) missing.add(w);
        }
        return missing;
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String padRight(String value, int length) {
        String s = value == null ? "" : value;
        if (s.length() >= length) return s.substring(0, length);
        StringBuilder sb = new StringBuilder(length);
        sb.append(s);
        while (sb.length() < length) sb.append(' ');
        return sb.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "(null)";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    // ====================================================================
    // Destination resolution
    // ====================================================================

    private static JCoDestination requireDestination(IProject project) throws SourceException {
        String destinationId = resolveDestinationId(project);
        if (destinationId == null || destinationId.isEmpty()) {
            throw new SourceException("No ADT connection for project '" + project.getName()
                    + "'. Open an ABAP project in the ABAP Development Tools first.");
        }
        try {
            return JCoDestinationManager.getDestination(destinationId);
        } catch (Throwable t) {
            throw wrap(t, "getDestination(" + destinationId + ")");
        }
    }

    private static String resolveDestinationId(IProject project) {
        try {
            Bundle bundle = Platform.getBundle(BUNDLE_ADT_PROJECT);
            if (bundle == null) return null;
            Class<?> factoryClass = bundle.loadClass(CLS_FACTORY);
            Class<?> serviceInterface = bundle.loadClass(CLS_SERVICE);
            Object service = factoryClass.getMethod("createCoreProjectService").invoke(null);
            if (service == null) return null;
            Method getDestinationId = serviceInterface.getMethod("getDestinationId", IProject.class);
            Object id = getDestinationId.invoke(service, project);
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable t) {
            log("resolveDestinationId(" + project.getName() + ") failed: " + t.getMessage());
            return null;
        }
    }

    private static SourceException wrap(Throwable t, String context) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String message = cause.getMessage();
        return new SourceException(context + ": " + cause.getClass().getSimpleName()
                + (message != null ? " — " + message : ""));
    }

    /** Writes a diagnostic line to the Eclipse error log (visible to the user). */
    static void log(String message) {
        try {
            LOG.log(new Status(Status.WARNING, Activator.PLUGIN_ID, "[CodeSearch] " + message));
        } catch (Exception ignored) {
        }
    }

    /** Checked exception thrown when source reading fails. */
    public static final class SourceException extends Exception {
        private static final long serialVersionUID = 1L;

        public SourceException(String message) {
            super(message);
        }

        public SourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
