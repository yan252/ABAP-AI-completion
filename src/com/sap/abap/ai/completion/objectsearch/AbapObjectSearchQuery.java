package com.sap.abap.ai.completion.objectsearch;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.text.Match;

import com.sap.abap.ai.completion.Activator;

/**
 * Eclipse Search query of the ABAP Object Search tab. Every found object is
 * added as one {@link Match} so that the standard Search view infrastructure
 * displays it; the result page renders the object itself, not a text range.
 */
public final class AbapObjectSearchQuery implements ISearchQuery {

    private final IProject project;
    private final String pattern;
    private final Set<AbapObjectType> objectTypes;
    private final AbapObjectSearchResult result;

    AbapObjectSearchQuery(IProject project, String pattern, Set<AbapObjectType> objectTypes) {
        this.project = project;
        this.pattern = pattern;
        this.objectTypes = EnumSet.copyOf(objectTypes);
        this.result = new AbapObjectSearchResult(this);
    }

    String getPattern() {
        return pattern;
    }

    Set<AbapObjectType> getObjectTypes() {
        return objectTypes;
    }

    @Override
    public IStatus run(IProgressMonitor monitor) {
        result.removeAll();
        try {
            List<AbapObjectItem> items = AbapObjectSearchClient.search(project, pattern,
                    objectTypes, monitor);
            for (AbapObjectItem item : items) {
                if (monitor.isCanceled()) {
                    break;
                }
                result.addMatch(new Match(item, 0, 0));
            }
            return Status.OK_STATUS;
        } catch (AbapObjectSearchClient.SearchException e) {
            if (monitor.isCanceled()) {
                return Status.CANCEL_STATUS;
            }
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
        } catch (Throwable t) {
            if (monitor.isCanceled()) {
                return Status.CANCEL_STATUS;
            }
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    Messages.format("error.searchFailed", message), t);
        }
    }

    @Override
    public String getLabel() {
        return Messages.format("query.label", pattern);
    }

    @Override
    public boolean canRerun() {
        return true;
    }

    @Override
    public boolean canRunInBackground() {
        return true;
    }

    @Override
    public ISearchResult getSearchResult() {
        return result;
    }
}
