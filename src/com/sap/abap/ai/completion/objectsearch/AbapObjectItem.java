package com.sap.abap.ai.completion.objectsearch;

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
