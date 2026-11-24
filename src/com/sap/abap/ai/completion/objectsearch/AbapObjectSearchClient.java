package com.sap.abap.ai.completion.objectsearch;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.sap.SapConnectionManager;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoTable;

/**
 * Executes the ABAP object search through RFC_READ_TABLE on the ADT
 * destination of the project.
 *
 * <p>The ADT repository search service requires a fully initialized ADT
 * Discovery (HTTP based). In installations where only the RFC/JCo connection
 * is usable, ADT search fails with "Unable to initialize the ADT Discovery".
 * The repository catalog tables are therefore queried directly via RFC — the
 * same transport the code search already uses. Descriptions come from the
 * corresponding DDIC/SAPscript text tables in the logon language.</p>
 *
 * <p>Queried tables per category:</p>
 * <ul>
 *   <li>Program: TRDIR + TRDIRT</li>
 *   <li>Database table / structure: DD02L + DD02T</li>
 *   <li>View: DD25L + DD25T</li>
 *   <li>CDS view: TADIR (OBJECT=DDLS) + DD02T</li>
 *   <li>Data element: DD04L + DD04T</li>
 *   <li>Domain: DD01L + DD01T</li>
 *   <li>Class / interface: SEOCLASS + SEOCLASSTX</li>
 *   <li>Method: SEOCOMPO + SEOCOMPOTX</li>
 *   <li>Message: T100A + T100</li>
 * </ul>
 */
final class AbapObjectSearchClient {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_ADT_PROJECT = "com.sap.adt.project";
    private static final String CLS_PROJECT_FACTORY =
            "com.sap.adt.project.AdtCoreProjectServiceFactory";
    private static final String CLS_PROJECT_SERVICE =
            "com.sap.adt.project.IAdtCoreProjectService";

    /** Hard upper bound per single table query. */
    private static final int MAX_ROWS_PER_QUERY = 2000;

    private AbapObjectSearchClient() {
    }

    /**
     * Runs the object search and returns the matching objects of the selected
     * categories.
     */
    static List<AbapObjectItem> search(IProject project, String pattern,
            Set<AbapObjectType> selectedTypes, IProgressMonitor monitor) throws SearchException {
        if (project == null) {
            throw new SearchException(Messages.get("error.noProject"));
        }
        JCoDestination destination = destination(project);
        String language = preferredLanguage(destination);

        Map<String, AbapObjectItem> items = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int succeeded = 0;

        // Descriptions shared by tables, structures and CDS views.
        Map<String, String> dd02Texts = Collections.emptyMap();
        boolean needsDd02Text = selectedTypes.contains(AbapObjectType.DATABASE_TABLE)
                || selectedTypes.contains(AbapObjectType.STRUCTURE)
                || selectedTypes.contains(AbapObjectType.CDS_VIEW);
        if (needsDd02Text) {
            try {
                dd02Texts = readTextMap(destination, "DD02T", "TABNAME", "DDLANGUAGE",
                        "DDTEXT", "AS4LOCAL EQ 'A'", condition("TABNAME", pattern),
                        language, monitor);
            } catch (Throwable t) {
                log("DD02T texts not readable: " + describe(t));
            }
        }

        if (selectedTypes.contains(AbapObjectType.PROGRAM)) {
            try {
                searchPrograms(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("TRDIR/TRDIRT: " + describe(t));
                log("searchPrograms failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.DATABASE_TABLE)
                || selectedTypes.contains(AbapObjectType.STRUCTURE)) {
            try {
                searchDdicTables(project, destination, pattern, monitor, dd02Texts,
                        selectedTypes, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("DD02L: " + describe(t));
                log("searchDdicTables failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.VIEW)) {
            try {
                searchViews(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("DD25L/DD25T: " + describe(t));
                log("searchViews failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.CDS_VIEW)) {
            try {
                searchCdsViews(project, destination, pattern, monitor, dd02Texts, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("TADIR(DDLS): " + describe(t));
                log("searchCdsViews failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.DATA_ELEMENT)) {
            try {
                searchDataElements(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("DD04L/DD04T: " + describe(t));
                log("searchDataElements failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.DOMAIN)) {
            try {
                searchDomains(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("DD01L/DD01T: " + describe(t));
                log("searchDomains failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.CLASS_INTERFACE)) {
            try {
                searchClasses(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("SEOCLASS/SEOCLASSTX: " + describe(t));
                log("searchClasses failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.METHOD)) {
            try {
                searchMethods(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("SEOCOMPO/SEOCOMPOTX: " + describe(t));
                log("searchMethods failed: " + describe(t));
            }
        }
        if (selectedTypes.contains(AbapObjectType.MESSAGE)) {
            try {
                searchMessages(project, destination, pattern, language, monitor, items);
                succeeded++;
            } catch (Throwable t) {
                errors.add("T100A/T100: " + describe(t));
                log("searchMessages failed: " + describe(t));
            }
        }

        if (succeeded == 0 && !errors.isEmpty()) {
            throw new SearchException(Messages.format("error.searchFailed", errors.get(0)));
        }
        log("search(" + pattern + ") categories=" + selectedTypes + " hits=" + items.size());
        return new ArrayList<>(items.values());
    }

    // ====================================================================
    // Category searches
    // ====================================================================

    private static void searchPrograms(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> subc = new LinkedHashMap<>();
        boolean namesRead = false;
        Throwable firstError = null;
        try {
            List<Map<String, String>> rows = readRows(destination, "TRDIR",
                    new String[] { "NAME", "SUBC" },
                    Collections.singletonList(condition("NAME", pattern)), monitor);
            namesRead = true;
            for (Map<String, String> row : rows) {
                String name = value(row, "NAME");
                if (!name.isEmpty()) {
                    subc.put(name, value(row, "SUBC"));
                }
            }
        } catch (Throwable t) {
            firstError = t;
            log("TRDIR not readable: " + describe(t));
        }

        Map<String, String> texts = Collections.emptyMap();
        try {
            texts = readTextMap(destination, "TRDIRT", "NAME", "SPRSL", "TEXT", null,
                    condition("NAME", pattern), language, monitor);
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("TRDIRT not readable: " + describe(t));
        }

        if (subc.isEmpty()) {
            for (String name : texts.keySet()) {
                subc.put(name, "");
            }
        }
        if (!namesRead && subc.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }

        for (Map.Entry<String, String> entry : subc.entrySet()) {
            String name = entry.getKey();
            boolean include = "I".equalsIgnoreCase(entry.getValue());
            String adtType = include ? "PROG/I" : "PROG/P";
            String uri = "/sap/bc/adt/programs/"
                    + (include ? "includes/" : "programs/") + encode(name);
            add(items, project, AbapObjectType.PROGRAM, name, adtType, texts.get(name), uri);
        }
    }

    private static void searchDdicTables(IProject project, JCoDestination destination,
            String pattern, IProgressMonitor monitor, Map<String, String> texts,
            Set<AbapObjectType> selectedTypes, Map<String, AbapObjectItem> items)
            throws Exception {
        List<Map<String, String>> rows = readRows(destination, "DD02L",
                new String[] { "TABNAME", "TABCLASS" },
                Arrays.asList(condition("TABNAME", pattern), "AS4LOCAL EQ 'A'"), monitor);
        for (Map<String, String> row : rows) {
            String name = value(row, "TABNAME");
            String tableClass = value(row, "TABCLASS");
            if (name.isEmpty()) {
                continue;
            }
            if (selectedTypes.contains(AbapObjectType.STRUCTURE)
                    && ("INTTAB".equals(tableClass) || "APPEND".equals(tableClass))) {
                add(items, project, AbapObjectType.STRUCTURE, name, "TABL/DS",
                        texts.get(name), "/sap/bc/adt/ddic/structures/" + encode(name));
            } else if (selectedTypes.contains(AbapObjectType.DATABASE_TABLE)
                    && ("TRANSP".equals(tableClass) || "CLUSTER".equals(tableClass)
                            || "POOL".equals(tableClass) || "GTT".equals(tableClass))) {
                // The exit URI is only a fallback: navigation resolves the
                // canonical URI of the object type through ADT Discovery.
                add(items, project, AbapObjectType.DATABASE_TABLE, name, "TABL/DT",
                        texts.get(name), "/sap/bc/adt/ddic/tables/" + encode(name));
            }
        }
    }

    private static void searchViews(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "DD25T", "VIEWNAME", "DDLANGUAGE", "DDTEXT",
                    "AS4LOCAL EQ 'A'", condition("VIEWNAME", pattern), language, monitor);
        } catch (Throwable t) {
            firstError = t;
            log("DD25T not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "DD25L", new String[] { "VIEWNAME" },
                    Arrays.asList(condition("VIEWNAME", pattern), "AS4LOCAL EQ 'A'"), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("DD25L not readable: " + describe(t));
        }

        if (rows.isEmpty()) {
            for (String name : texts.keySet()) {
                add(items, project, AbapObjectType.VIEW, name, "VIEW/DV", texts.get(name),
                        "/sap/bc/adt/ddic/views/" + encode(name));
            }
        } else {
            for (Map<String, String> row : rows) {
                String name = value(row, "VIEWNAME");
                if (!name.isEmpty()) {
                    add(items, project, AbapObjectType.VIEW, name, "VIEW/DV", texts.get(name),
                            "/sap/bc/adt/ddic/views/" + encode(name));
                }
            }
        }
        if (!namesRead && texts.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }
    }

    private static void searchCdsViews(IProject project, JCoDestination destination,
            String pattern, IProgressMonitor monitor, Map<String, String> texts,
            Map<String, AbapObjectItem> items) throws Exception {
        List<Map<String, String>> rows = readRows(destination, "TADIR",
                new String[] { "OBJ_NAME" },
                Arrays.asList("PGMID EQ 'R3TR'", "OBJECT EQ 'DDLS'",
                        condition("OBJ_NAME", pattern)),
                monitor);
        for (Map<String, String> row : rows) {
            String name = value(row, "OBJ_NAME");
            if (!name.isEmpty()) {
                add(items, project, AbapObjectType.CDS_VIEW, name, "DDLS/DF", texts.get(name),
                        "/sap/bc/adt/ddic/ddl/sources/" + encode(name));
            }
        }
    }

    private static void searchDataElements(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "DD04T", "ROLLNAME", "DDLANGUAGE", "DDTEXT",
                    "AS4LOCAL EQ 'A'", condition("ROLLNAME", pattern), language, monitor);
        } catch (Throwable t) {
            firstError = t;
            log("DD04T not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "DD04L", new String[] { "ROLLNAME" },
                    Arrays.asList(condition("ROLLNAME", pattern), "AS4LOCAL EQ 'A'"), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("DD04L not readable: " + describe(t));
        }

        if (rows.isEmpty()) {
            for (String name : texts.keySet()) {
                add(items, project, AbapObjectType.DATA_ELEMENT, name, "DTEL/DE",
                        texts.get(name), "/sap/bc/adt/ddic/datatypes/" + encode(name));
            }
        } else {
            for (Map<String, String> row : rows) {
                String name = value(row, "ROLLNAME");
                if (!name.isEmpty()) {
                    add(items, project, AbapObjectType.DATA_ELEMENT, name, "DTEL/DE",
                            texts.get(name), "/sap/bc/adt/ddic/datatypes/" + encode(name));
                }
            }
        }
        if (!namesRead && texts.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }
    }

    private static void searchDomains(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "DD01T", "DOMNAME", "DDLANGUAGE", "DDTEXT",
                    "AS4LOCAL EQ 'A'", condition("DOMNAME", pattern), language, monitor);
        } catch (Throwable t) {
            firstError = t;
            log("DD01T not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "DD01L", new String[] { "DOMNAME" },
                    Arrays.asList(condition("DOMNAME", pattern), "AS4LOCAL EQ 'A'"), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("DD01L not readable: " + describe(t));
        }

        if (rows.isEmpty()) {
            for (String name : texts.keySet()) {
                add(items, project, AbapObjectType.DOMAIN, name, "DOMA/DD", texts.get(name),
                        "/sap/bc/adt/ddic/domains/" + encode(name));
            }
        } else {
            for (Map<String, String> row : rows) {
                String name = value(row, "DOMNAME");
                if (!name.isEmpty()) {
                    add(items, project, AbapObjectType.DOMAIN, name, "DOMA/DD",
                            texts.get(name), "/sap/bc/adt/ddic/domains/" + encode(name));
                }
            }
        }
        if (!namesRead && texts.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }
    }

    private static void searchClasses(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "SEOCLASSTX", "CLSNAME", "LANGU", "DESCRIPT",
                    null, condition("CLSNAME", pattern), language, monitor);
        } catch (Throwable t) {
            firstError = t;
            log("SEOCLASSTX not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "SEOCLASS", new String[] { "CLSNAME", "CLSTYPE" },
                    Collections.singletonList(condition("CLSNAME", pattern)), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("SEOCLASS not readable: " + describe(t));
        }

        if (rows.isEmpty()) {
            for (String name : texts.keySet()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("CLSNAME", name);
                row.put("CLSTYPE", "0");
                rows.add(row);
            }
        }
        for (Map<String, String> row : rows) {
            String name = value(row, "CLSNAME");
            String classType = value(row, "CLSTYPE");
            if (name.isEmpty()) {
                continue;
            }
            boolean isInterface = "1".equals(classType) || "I".equalsIgnoreCase(classType);
            if (isInterface) {
                add(items, project, AbapObjectType.CLASS_INTERFACE, name, "INTF/OI",
                        texts.get(name), "/sap/bc/adt/oo/interfaces/" + encode(name));
            } else {
                add(items, project, AbapObjectType.CLASS_INTERFACE, name, "CLAS/OC",
                        texts.get(name), "/sap/bc/adt/oo/classes/" + encode(name));
            }
        }
        if (!namesRead && texts.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }
    }

    private static void searchMethods(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "SEOCOMPOTX", "CMPNAME", "LANGU", "DESCRIPT",
                    null, condition("CMPNAME", pattern), language, monitor,
                    "CLSNAME", "CMPNAME");
        } catch (Throwable t) {
            firstError = t;
            log("SEOCOMPOTX not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "SEOCOMPO",
                    new String[] { "CLSNAME", "CMPNAME", "CMPTYPE" },
                    Arrays.asList(condition("CMPNAME", pattern), "CMPTYPE EQ 'M'"), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("SEOCOMPO not readable: " + describe(t));
        }

        for (Map<String, String> row : rows) {
            String className = value(row, "CLSNAME");
            String methodName = value(row, "CMPNAME");
            if (className.isEmpty() || methodName.isEmpty()) {
                continue;
            }
            String displayName = className + "=>" + methodName;
            String description = texts.get(className + "|" + methodName);
            // The class is opened; the method itself has no standalone ADT URI.
            add(items, project, AbapObjectType.METHOD, displayName, "CLAS/OC", description,
                    "/sap/bc/adt/oo/classes/" + encode(className));
        }
        if (!namesRead && firstError != null) {
            throw new Exception(firstError);
        }
    }

    private static void searchMessages(IProject project, JCoDestination destination,
            String pattern, String language, IProgressMonitor monitor,
            Map<String, AbapObjectItem> items) throws Exception {
        Map<String, String> texts = Collections.emptyMap();
        Throwable firstError = null;
        try {
            texts = readTextMap(destination, "T100", "ARBGB", "SPRSL", "TEXT", null,
                    condition("ARBGB", pattern), language, monitor);
        } catch (Throwable t) {
            firstError = t;
            log("T100 not readable: " + describe(t));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        boolean namesRead = false;
        try {
            rows = readRows(destination, "T100A", new String[] { "ARBGB" },
                    Collections.singletonList(condition("ARBGB", pattern)), monitor);
            namesRead = true;
        } catch (Throwable t) {
            if (firstError == null) {
                firstError = t;
            }
            log("T100A not readable: " + describe(t));
        }

        if (rows.isEmpty()) {
            for (String name : texts.keySet()) {
                add(items, project, AbapObjectType.MESSAGE, name, "MSAG/N", texts.get(name),
                        "/sap/bc/adt/messageclass/" + encode(name));
            }
        } else {
            for (Map<String, String> row : rows) {
                String name = value(row, "ARBGB");
                if (!name.isEmpty()) {
                    add(items, project, AbapObjectType.MESSAGE, name, "MSAG/N",
                            texts.get(name), "/sap/bc/adt/messageclass/" + encode(name));
                }
            }
        }
        if (!namesRead && texts.isEmpty() && firstError != null) {
            throw new Exception(firstError);
        }
    }

    // ====================================================================
    // RFC helpers
    // ====================================================================

    @FunctionalInterface
    private interface RowConsumer {
        void accept(Map<String, String> row) throws Exception;
    }

    /** Reads rows of one table and returns them as field-name to value maps. */
    private static List<Map<String, String>> readRows(JCoDestination destination, String table,
            String[] fields, List<String> options, IProgressMonitor monitor) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        readTable(destination, table, fields, options, monitor, rows::add);
        return rows;
    }

    /** Reads a language dependent text table and returns name to description. */
    private static Map<String, String> readTextMap(JCoDestination destination, String table,
            String nameField, String languageField, String textField, String extraOption,
            String nameCondition, String language, IProgressMonitor monitor)
            throws Exception {
        return readTextMap(destination, table, nameField, languageField, textField,
                extraOption, nameCondition, language, monitor, null, null);
    }

    /**
     * Variant for text tables whose key consists of two fields (for example
     * SEOCOMPOTX: CLSNAME + CMPNAME). The returned map is keyed by both values
     * separated by '|'.
     */
    private static Map<String, String> readTextMap(JCoDestination destination, String table,
            String nameField, String languageField, String textField, String extraOption,
            String nameCondition, String language, IProgressMonitor monitor,
            String keyField1, String keyField2) throws Exception {
        List<String> options = new ArrayList<>();
        options.add(nameCondition);
        if (extraOption != null && !extraOption.isEmpty()) {
            options.add(extraOption);
        }
        if (language != null && !language.isEmpty()) {
            options.add(languageField + " EQ '" + escape(language) + "'");
        }

        String[] fields;
        if (keyField1 != null && keyField2 != null) {
            fields = new String[] { keyField1, keyField2, textField };
        } else {
            fields = new String[] { nameField, textField };
        }

        Map<String, String> texts = new LinkedHashMap<>();
        readTable(destination, table, fields, options, monitor, row -> {
            String text = value(row, textField);
            if (text.isEmpty()) {
                return;
            }
            String key;
            if (keyField1 != null && keyField2 != null) {
                String first = value(row, keyField1);
                String second = value(row, keyField2);
                if (first.isEmpty() || second.isEmpty()) {
                    return;
                }
                key = first + "|" + second;
            } else {
                key = value(row, nameField);
                if (key.isEmpty()) {
                    return;
                }
            }
            texts.putIfAbsent(key, text);
        });
        return texts;
    }

    /** Executes RFC_READ_TABLE once and forwards every returned row. */
    private static void readTable(JCoDestination destination, String table, String[] fields,
            List<String> options, IProgressMonitor monitor, RowConsumer consumer) throws Exception {
        if (monitor != null && monitor.isCanceled()) {
            return;
        }
        JCoFunction function = destination.getRepository().getFunction("RFC_READ_TABLE");
        if (function == null) {
            throw new SearchException(Messages.get("error.rfcUnavailable"));
        }
        JCoParameterList imp = function.getImportParameterList();
        imp.setValue("QUERY_TABLE", table);
        imp.setValue("DELIMITER", "|");
        imp.setValue("ROWSKIPS", 0);
        imp.setValue("ROWCOUNT", MAX_ROWS_PER_QUERY);
        try {
            // Same handling as the code search reader; the parameter does not
            // exist in every release.
            imp.setValue("AUTHORITY_CHECK", " ");
        } catch (Throwable ignored) {
            // optional parameter
        }

        JCoTable optTable = function.getTableParameterList().getTable("OPTIONS");
        // RFC_READ_TABLE concatenates the OPTIONS rows verbatim — it inserts no
        // blank and no AND. The rows must therefore form one single valid WHERE
        // clause; a bare per-line "SPRSL EQ 'E'" makes the parser report
        // "SPRSL is invalid here" (CX_SY_DYNAMIC_OSQL_SYNTAX).
        List<String> conditionLines = splitOptions(buildWhereClause(options));
        for (String line : conditionLines) {
            optTable.appendRow();
            optTable.setValue("TEXT", line);
        }

        JCoTable fieldsTable = function.getTableParameterList().getTable("FIELDS");
        for (String field : fields) {
            fieldsTable.appendRow();
            fieldsTable.setValue("FIELDNAME", field);
        }

        function.execute(destination);
        JCoTable data = function.getTableParameterList().getTable("DATA");
        int rowCount = data == null ? 0 : data.getNumRows();
        for (int i = 0; i < rowCount; i++) {
            if (monitor != null && monitor.isCanceled()) {
                return;
            }
            data.setRow(i);
            String record = data.getString("WA");
            if (record == null) {
                continue;
            }
            String[] parts = record.split("\\|", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < fields.length && j < parts.length; j++) {
                row.put(fields[j], parts[j] == null ? "" : parts[j].trim());
            }
            consumer.accept(row);
        }
    }

    // ====================================================================
    // Conditions / values
    // ====================================================================

    /** Maximum length of one OPTIONS row accepted by RFC_READ_TABLE. */
    private static final int OPTIONS_LINE_LENGTH = 72;

    /** Joins the single conditions into one WHERE clause (without "WHERE"). */
    private static String buildWhereClause(List<String> options) {
        StringBuilder where = new StringBuilder();
        for (String option : options) {
            if (option == null || option.trim().isEmpty()) {
                continue;
            }
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(option.trim());
        }
        return where.toString();
    }

    /**
     * Splits a WHERE clause into RFC_READ_TABLE OPTIONS rows of at most 72
     * characters. The rows are concatenated verbatim by the function module,
     * so the split must not lose a blank: when the last character of a chunk
     * is a blank it is moved to the start of the next chunk instead, because
     * trailing blanks of a row are not preserved reliably.
     */
    private static List<String> splitOptions(String where) {
        List<String> lines = new ArrayList<>();
        int pos = 0;
        while (pos < where.length()) {
            int end = Math.min(pos + OPTIONS_LINE_LENGTH, where.length());
            if (end < where.length() && where.charAt(end - 1) == ' ') {
                end--;
            }
            lines.add(where.substring(pos, end));
            pos = end;
        }
        return lines;
    }

    /**
     * Builds an Open SQL condition from the user pattern. {@code *} matches
     * any sequence of characters; without a wildcard an exact match is used.
     */
    private static String condition(String field, String pattern) {
        String value = pattern == null ? "" : pattern.trim().toUpperCase();
        if (value.indexOf('*') < 0) {
            return field + " EQ '" + escape(value) + "'";
        }
        StringBuilder like = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            like.append(c == '*' ? '%' : c);
        }
        return field + " LIKE '" + escape(like.toString()) + "'";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String value(Map<String, String> row, String field) {
        String value = row.get(field);
        return value == null ? "" : value.trim();
    }

    private static String encode(String name) {
        return name == null ? "" : name.toLowerCase().replace("/", "%2F");
    }

    private static void add(Map<String, AbapObjectItem> items, IProject project,
            AbapObjectType type, String name, String adtType, String description, String uri) {
        String key = type.name() + '|' + name.toUpperCase();
        items.putIfAbsent(key, new AbapObjectItem(project, name, adtType,
                description == null ? "" : description, uri, type));
    }

    // ====================================================================
    // Connection / environment
    // ====================================================================

    private static JCoDestination destination(IProject project) throws SearchException {
        String destinationId = resolveDestinationId(project);
        Throwable failure = null;

        if (destinationId != null && !destinationId.isEmpty()) {
            try {
                return JCoDestinationManager.getDestination(destinationId);
            } catch (Throwable t) {
                failure = t;
            }
            // ADT registers its JCo destinations lazily. After a fresh IDE
            // start (or -clean) the destination is unknown to JCo until ADT's
            // own JCo registry has pushed it. Register it here and retry.
            registerAdtDestination(destinationId);
            try {
                return JCoDestinationManager.getDestination(destinationId);
            } catch (Throwable t) {
                failure = t;
            }
            // ADT's JCo bridge may also be inactive; activating it registers
            // ADT's global DestinationDataProvider.
            try {
                SapConnectionManager.ensureDestinationRegistered();
                return JCoDestinationManager.getDestination(destinationId);
            } catch (Throwable t) {
                failure = t;
            }
        }

        // Last resort: the SAP connection configured in this plugin's
        // preferences. Only use it when it is really configured (host, system
        // number and client present), otherwise its own JCo error hides the
        // real cause.
        if (SapConnectionManager.isConfigured()) {
            try {
                JCoDestination fallback = SapConnectionManager.getDestination();
                log("destination(" + project.getName() + "): ADT destination '" + destinationId
                        + "' unavailable (" + describe(failure) + "), using plugin SAP connection");
                return fallback;
            } catch (Throwable t) {
                log("destination(" + project.getName() + "): plugin SAP connection failed: "
                        + describe(t));
            }
        }
        log("destination(" + project.getName() + "): no usable connection, ADT destination '"
                + destinationId + "' (" + describe(failure) + ")");
        throw new SearchException(Messages.format("error.noConnection", project.getName()),
                failure != null ? failure : null);
    }

    /**
     * Makes ADT register its JCo destination data for the given destination id.
     *
     * <p>ADT keeps the JCo data of its destinations in
     * {@code JCoDestinationRegistry}; only after {@code register(...)} the
     * global JCo {@code DestinationDataProvider} can resolve the name. ADT does
     * that lazily when one of its own JCo features is used, which is why RFC
     * fails right after an IDE restart. Registering the effective destination
     * data here is equivalent and idempotent.</p>
     */
    private static void registerAdtDestination(String destinationId) {
        try {
            Bundle bundle = Platform.getBundle("com.sap.adt.communication");
            if (bundle == null) {
                return;
            }
            Class<?> registryClass = bundle.loadClass(
                    "com.sap.adt.communication.internal.jco.JCoDestinationRegistry");
            Object registry = registryClass.getMethod("getInstance").invoke(null);
            if (registry == null) {
                return;
            }
            Boolean registered = (Boolean) registryClass
                    .getMethod("isRegistered", String.class).invoke(registry, destinationId);
            if (Boolean.TRUE.equals(registered)) {
                return;
            }
            Object data = registryClass.getMethod("getEffectiveDestinationData", String.class)
                    .invoke(registry, destinationId);
            if (data == null) {
                log("registerAdtDestination(" + destinationId + "): no destination data");
                return;
            }
            ClassLoader loader = registry.getClass().getClassLoader();
            Class<?> dataClass = Class.forName(
                    "com.sap.adt.destinations.model.IDestinationData", true, loader);
            Class<?> tokenClass = Class.forName(
                    "com.sap.adt.destinations.model.IAuthenticationToken", true, loader);
            Object status = registryClass.getMethod("register", dataClass, tokenClass)
                    .invoke(registry, data, null);
            log("registerAdtDestination(" + destinationId + "): " + status);
        } catch (Throwable t) {
            log("registerAdtDestination(" + destinationId + ") failed: " + describe(t));
        }
    }

    /** Logon language of the destination (SAP language key, default E). */
    private static String preferredLanguage(JCoDestination destination) {
        try {
            String language = destination.getAttributes().getLanguage();
            if (language == null || language.trim().isEmpty()) {
                return "E";
            }
            String value = language.trim().toUpperCase();
            if (value.length() == 1) {
                return value;
            }
            switch (value) {
                case "EN": return "E";
                case "DE": return "D";
                case "ZH": return "1";
                case "FR": return "F";
                case "ES": return "S";
                case "IT": return "I";
                case "PT": return "P";
                case "JA": return "J";
                case "KO": return "3";
                case "RU": return "R";
                default: return value.substring(0, 1);
            }
        } catch (Throwable t) {
            return "E";
        }
    }

    /** Resolves the JCo destination ID that ADT uses for the project. */
    private static String resolveDestinationId(IProject project) {
        try {
            Bundle bundle = Platform.getBundle(BUNDLE_ADT_PROJECT);
            if (bundle == null) {
                return null;
            }
            Class<?> factoryClass = bundle.loadClass(CLS_PROJECT_FACTORY);
            Class<?> serviceInterface = bundle.loadClass(CLS_PROJECT_SERVICE);
            Object service = factoryClass.getMethod("createCoreProjectService").invoke(null);
            if (service == null) {
                return null;
            }
            Method getDestinationId = serviceInterface.getMethod("getDestinationId", IProject.class);
            Object id = getDestinationId.invoke(service, project);
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable t) {
            log("resolveDestinationId(" + project.getName() + ") failed: " + describe(t));
            return null;
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : " - " + message);
    }

    private static void log(String message) {
        try {
            LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, "[ObjectSearch] " + message));
        } catch (Throwable ignored) {
            // logging must never break the search
        }
    }

    /** Checked exception carrying a user-facing message. */
    static final class SearchException extends Exception {
        private static final long serialVersionUID = 1L;

        SearchException(String message) {
            super(message);
        }

        SearchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
