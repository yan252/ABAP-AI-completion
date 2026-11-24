package com.sap.abap.ai.completion.objectsearch;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.IEditorMatchAdapter;
import org.eclipse.search.ui.text.IFileMatchAdapter;
import org.eclipse.search.ui.text.Match;
import org.eclipse.ui.IEditorPart;

import com.sap.abap.ai.completion.Activator;

/**
 * Search result of the ABAP object search. Elements are {@link AbapObjectItem}
 * instances; the (zero length) matches only exist because the Eclipse Search
 * framework stores results per match.
 */
public final class AbapObjectSearchResult extends AbstractTextSearchResult {

    private static final Match[] NO_MATCHES = new Match[0];

    private static final IEditorMatchAdapter EDITOR_MATCH_ADAPTER = new IEditorMatchAdapter() {
        @Override
        public boolean isShownInEditor(Match match, IEditorPart editor) {
            return false;
        }

        @Override
        public Match[] computeContainedMatches(AbstractTextSearchResult result, IEditorPart editor) {
            return NO_MATCHES;
        }
    };

    private static final IFileMatchAdapter FILE_MATCH_ADAPTER = new IFileMatchAdapter() {
        @Override
        public Match[] computeContainedMatches(AbstractTextSearchResult result, IFile file) {
            return NO_MATCHES;
        }

        @Override
        public IFile getFile(Object element) {
            return null;
        }
    };

    private final AbapObjectSearchQuery query;

    public AbapObjectSearchResult(AbapObjectSearchQuery query) {
        this.query = query;
    }

    @Override
    public String getLabel() {
        return Messages.format("result.label", query.getPattern(), getElementsCount());
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return Activator.getImageDescriptor("icons/SAPLogo1.png");
    }

    @Override
    public String getTooltip() {
        return Messages.format("result.tooltip", query.getPattern());
    }

    @Override
    public ISearchQuery getQuery() {
        return query;
    }

    @Override
    public IEditorMatchAdapter getEditorMatchAdapter() {
        return EDITOR_MATCH_ADAPTER;
    }

    @Override
    public IFileMatchAdapter getFileMatchAdapter() {
        return FILE_MATCH_ADAPTER;
    }
}
