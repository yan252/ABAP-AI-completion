package com.sap.abap.ai.completion.codesearch;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.IEditorMatchAdapter;
import org.eclipse.search.ui.text.IFileMatchAdapter;
import org.eclipse.search.ui.text.Match;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ISharedImages;

/**
 * Search result shown in the Eclipse Search view. Elements are
 * {@link AbapCodeProgram} instances; their matches are character ranges in
 * the program source.
 */
public final class AbapCodeSearchResult extends AbstractTextSearchResult {

    private final AbapCodeSearchQuery query;

    public AbapCodeSearchResult(AbapCodeSearchQuery query) {
        this.query = query;
    }

    public AbapCodeSearchQuery getCodeSearchQuery() {
        return query;
    }

    @Override
    public String getLabel() {
        return Messages.format("result.label", query.getPatternText(), getMatchCount(), getElementsCount());
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_OBJ_FILE);
    }

    @Override
    public String getTooltip() {
        return Messages.format("result.tooltip", query.getPatternText());
    }

    @Override
    public ISearchQuery getQuery() {
        return query;
    }

    @Override
    public IEditorMatchAdapter getEditorMatchAdapter() {
        return new IEditorMatchAdapter() {
            @Override
            public boolean isShownInEditor(Match match, IEditorPart editor) {
                Object element = match.getElement();
                return element instanceof AbapCodeProgram
                        && matchesEditor((AbapCodeProgram) element, editor);
            }

            @Override
            public Match[] computeContainedMatches(AbstractTextSearchResult result, IEditorPart editor) {
                Object[] elements = result.getElements();
                for (Object element : elements) {
                    if (element instanceof AbapCodeProgram
                            && matchesEditor((AbapCodeProgram) element, editor)) {
                        return result.getMatches(element);
                    }
                }
                return new Match[0];
            }
        };
    }

    @Override
    public IFileMatchAdapter getFileMatchAdapter() {
        return new IFileMatchAdapter() {
            @Override
            public Match[] computeContainedMatches(AbstractTextSearchResult result, IFile file) {
                return new Match[0];
            }

            @Override
            public IFile getFile(Object element) {
                return null;
            }
        };
    }

    private static boolean matchesEditor(AbapCodeProgram program, IEditorPart editor) {
        if (editor == null) {
            return false;
        }
        String title = editor.getTitle();
        boolean shown = title != null && title.toUpperCase().contains(program.getName().toUpperCase());
        log("matchesEditor title=" + title + " program=" + program.getName() + " -> " + shown);
        return shown;
    }

    private static void log(String message) {
        try {
            org.eclipse.core.runtime.Platform
                    .getLog(org.eclipse.core.runtime.Platform.getBundle("com.sap.abap.ai.completion"))
                    .log(new org.eclipse.core.runtime.Status(
                            org.eclipse.core.runtime.IStatus.INFO, "com.sap.abap.ai.completion", message));
        } catch (Throwable ignored) {
            // diagnostics must never break the search view
        }
    }
}
