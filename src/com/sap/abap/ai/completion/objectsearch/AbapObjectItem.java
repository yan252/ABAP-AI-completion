package com.sap.abap.ai.completion.objectsearch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;

/**
 * One ABAP object found by the object search: its name, ADT type,
 * description, canonical ADT URI and the category it belongs to.
 *
 * <p>The ADT URI returned by the repository search is kept because it is the
 * canonical object identity; it lets the double-click handler open the exact
 * object (including methods, which have no standalone URI of their own).</p>
 */
final class AbapObjectItem {

    /**
     * Suffix of an include generated for a function group: {@code TOP} or one
     * letter plus two digits ({@code U01}, {@code F01}, {@code I01} …).
     */
    private static final Pattern FUGR_INCLUDE_SUFFIX =
            Pattern.compile("(TOP|[A-Z]\\d{2})$");

    private final IProject project;
    private final String name;
    private final String type;
    private final String description;
    private final String uri;
    private final AbapObjectType objectType;

    AbapObjectItem(IProject project, String name, String type, String description,
            String uri, AbapObjectType objectType) {
        this.project = project;
        this.name = name;
        this.type = type;
        this.description = description;
        this.uri = uri;
        this.objectType = objectType;
    }

    IProject getProject() {
        return project;
    }

    String getName() {
        return name;
    }

    /** ADT workbench type, e.g. {@code PROG/P} or {@code TABL/DT}. */
    String getType() {
        return type;
    }

    /** Description in the logon language, may be empty. */
    String getDescription() {
        return description;
    }

    /** Canonical ADT URI returned by the search, may be empty. */
    String getUri() {
        return uri;
    }

    AbapObjectType getObjectType() {
        return objectType;
    }

    /**
     * The function group whose generated include {@code name} is:
     * {@code L<group>TOP}, {@code L<group>U01}, {@code L<group>F01} … (a
     * namespace prefix is kept, {@code /NS/LFGTOP -> /NS/FG}).
     *
     * <p>Returns {@code null} for includes that do not follow that naming
     * scheme (class includes, ordinary programs, includes created in SE38).</p>
     */
    static String functionGroupOfInclude(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String upper = name.toUpperCase();
        int start = 0;
        if (upper.charAt(0) == '/') {
            int second = upper.indexOf('/', 1);
            if (second < 0) {
                return null;
            }
            start = second + 1;
        }
        if (start >= upper.length() || upper.charAt(start) != 'L') {
            return null;
        }
        String rest = upper.substring(start + 1);
        Matcher suffix = FUGR_INCLUDE_SUFFIX.matcher(rest);
        if (!suffix.find()) {
            return null;
        }
        String group = rest.substring(0, suffix.start());
        return group.isEmpty() ? null : upper.substring(0, start) + group;
    }

    /**
     * The function group of a function pool main program, i.e. the group a
     * function module belongs to: {@code SAPLZFG_X -> ZFG_X} and
     * {@code /NS/SAPLFG -> /NS/FG}. Returns {@code null} for other programs.
     */
    static String functionGroupOfMainProgram(String mainProgram) {
        if (mainProgram == null || mainProgram.isEmpty()) {
            return null;
        }
        String upper = mainProgram.toUpperCase();
        int idx = upper.lastIndexOf("SAPL");
        // The SAPL prefix must lead the name or follow a namespace slash,
        // otherwise ordinary programs containing "SAPL" would false-positive.
        if (idx < 0 || (idx > 0 && upper.charAt(idx - 1) != '/')) {
            return null;
        }
        String group = upper.substring(0, idx) + upper.substring(idx + 4);
        return group.isEmpty() ? null : group;
    }

    /** Text shown in the result view: name followed by the description. */
    String getDisplayText() {
        if (description == null || description.trim().isEmpty()) {
            return name;
        }
        return name + "  -  " + description.trim();
    }

    @Override
    public String toString() {
        return name;
    }
}
