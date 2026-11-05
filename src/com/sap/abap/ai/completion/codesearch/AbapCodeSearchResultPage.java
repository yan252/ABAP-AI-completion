package com.sap.abap.ai.completion.codesearch;

import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.AbstractTextSearchViewPage;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * Search view page for {@link AbapCodeSearchResult}: programs/includes as
 * expandable root nodes and their matched code lines as children.
 */
public final class AbapCodeSearchResultPage extends AbstractTextSearchViewPage {

    public AbapCodeSearchResultPage() {
        super(AbstractTextSearchViewPage.FLAG_LAYOUT_TREE);
    }

    @Override
    protected void configureTreeViewer(TreeViewer viewer) {
        viewer.setUseHashlookup(true);
        viewer.setContentProvider(new ProgramContentProvider());
        viewer.setLabelProvider(new ProgramLabelProvider());
        viewer.addDoubleClickListener(event -> {
            Object selected = event.getSelection() instanceof IStructuredSelection
                    ? ((IStructuredSelection) event.getSelection()).getFirstElement() : null;
            if (selected instanceof AbapCodeProgram) {
                AbapCodeProgram program = (AbapCodeProgram) selected;
                AbstractTextSearchResult input = getInput();
                Match[] matches = input == null ? new Match[0] : input.getMatches(program);
                AbapEditorNavigator.show(program, matches.length > 0 ? matches[0] : null,
                        matches, matcher());
            } else if (selected instanceof Match) {
                showCodeMatch((Match) selected);
            }
        });
    }

    @Override
    protected void configureTableViewer(TableViewer viewer) {
        // flat layout is not used by this result page
    }

    @Override
    protected void elementsChanged(Object[] updatedElements) {
        if (getViewer() != null) {
            getViewer().refresh();
        }
    }

    @Override
    protected void clear() {
        if (getViewer() != null) {
            getViewer().refresh();
        }
    }

    @Override
    protected void showMatch(Match match, int currentOffset, int currentLength)
            throws PartInitException {
        showCodeMatch(match);
    }

    @Override
    protected void showMatch(Match match, int currentOffset, int currentLength, boolean activate)
            throws PartInitException {
        showCodeMatch(match);
    }

    private void showCodeMatch(Match match) {
        Object element = match.getElement();
        if (!(element instanceof AbapCodeProgram)) {
            return;
        }
        AbapCodeProgram program = (AbapCodeProgram) element;
        AbstractTextSearchResult input = getInput();
        Match[] allMatches = input == null ? new Match[] { match } : input.getMatches(program);
        AbapEditorNavigator.show(program, match, allMatches, matcher());
    }

    /** The matcher of the current query, used to re-locate matches in the editor. */
    CodeSearchMatcher matcher() {
        AbstractTextSearchResult input = getInput();
        if (input == null) {
            return null;
        }
        Object query = input.getQuery();
        if (query instanceof AbapCodeSearchQuery) {
            try {
                return ((AbapCodeSearchQuery) query).createMatcher();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public IRegion getCurrentMatchLocation(Match match) {
        return new Region(match.getOffset(), match.getLength());
    }

    private static final class ProgramContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof AbstractTextSearchResult) {
                return ((AbstractTextSearchResult) inputElement).getElements();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            AbstractTextSearchResult result = currentResult();
            if (parentElement instanceof AbapCodeProgram && result != null) {
                return result.getMatches(parentElement);
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            if (element instanceof Match) {
                return ((Match) element).getElement();
            }
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            AbstractTextSearchResult result = currentResult();
            return element instanceof AbapCodeProgram && result != null
                    && result.getMatchCount(element) > 0;
        }

        private AbstractTextSearchResult currentResult;

        private AbstractTextSearchResult currentResult() {
            return currentResult;
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            currentResult = newInput instanceof AbstractTextSearchResult
                    ? (AbstractTextSearchResult) newInput : null;
        }

        @Override
        public void dispose() {
            currentResult = null;
        }
    }

    /**
     * Renders the elements with styled text so the matched characters are
     * shaded inside the line shown for a {@link Match}.
     */
    private final class ProgramLabelProvider extends StyledCellLabelProvider {

        private final Color matchBackground =
                new Color(Display.getDefault(), 208, 208, 208);

        @Override
        public void update(ViewerCell cell) {
            Object element = cell.getElement();
            StyleRange[] styles = null;
            if (element instanceof AbapCodeProgram) {
                AbapCodeProgram program = (AbapCodeProgram) element;
                AbstractTextSearchResult result = getInput();
                int count = result == null ? 0 : result.getMatchCount(program);
                cell.setText(count == 1
                        ? Messages.format("element.label.one", program.getDisplayName(), program.getTypeLabel())
                        : Messages.format("element.label", program.getDisplayName(), program.getTypeLabel(), count));
            } else if (element instanceof Match) {
                styles = showMatch((Match) element, cell);
            } else {
                cell.setText(element == null ? "" : element.toString());
            }
            cell.setImage(getImage(element));
            cell.setStyleRanges(styles);
        }

        /**
         * Sets the text of a match cell and returns the style that shades the
         * matched characters inside the shown code line.
         */
        private StyleRange[] showMatch(Match match, ViewerCell cell) {
            Object parent = match.getElement();
            if (!(parent instanceof AbapCodeProgram)) {
                cell.setText("");
                return null;
            }
            AbapCodeProgram program = (AbapCodeProgram) parent;
            int column = program.getColumn(match.getOffset());
            String prefix = Messages.format("match.prefix",
                    program.getLineNumber(match.getOffset()), column) + " ";
            String raw = program.getLineText(match.getOffset());
            String line = raw.trim();
            int leading = 0;
            while (leading < raw.length() && Character.isWhitespace(raw.charAt(leading))) {
                leading++;
            }
            int start = Math.max(0, Math.min(line.length(), column - 1 - leading));
            int length = Math.max(0, Math.min(match.getLength(), line.length() - start));
            cell.setText(prefix + line);
            if (length <= 0) {
                return null;
            }
            StyleRange style = new StyleRange();
            style.start = prefix.length() + start;
            style.length = length;
            style.background = matchBackground;
            return new StyleRange[] { style };
        }

        public Image getImage(Object element) {
            ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
            if (element instanceof AbapCodeProgram) {
                return images.getImage(ISharedImages.IMG_OBJ_FILE);
            }
            return images.getImage(ISharedImages.IMG_OBJ_ELEMENT);
        }

        @Override
        public void dispose() {
            if (!matchBackground.isDisposed()) {
                matchBackground.dispose();
            }
            super.dispose();
        }
    }
}
