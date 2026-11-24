package com.sap.abap.ai.completion.objectsearch;

import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.AbstractTextSearchViewPage;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.PartInitException;

/**
 * Search view page for {@link AbapObjectSearchResult}: one row per found
 * object with its type icon, name and description. Double-click (or Enter)
 * opens the object in its ADT editor.
 */
public final class AbapObjectSearchResultPage extends AbstractTextSearchViewPage {

    public AbapObjectSearchResultPage() {
        super(AbstractTextSearchViewPage.FLAG_LAYOUT_FLAT);
    }

    @Override
    protected void configureTreeViewer(TreeViewer viewer) {
        // the result is always shown in the flat table layout
    }

    @Override
    protected void configureTableViewer(TableViewer viewer) {
        viewer.setUseHashlookup(true);
        Table table = viewer.getTable();
        if (table.getColumnCount() == 0) {
            final TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(Messages.get("result.column"));
            column.setWidth(600);
            viewer.setColumnProperties(new String[] { "object" });
            table.addListener(SWT.Resize, event -> {
                if (!column.isDisposed() && table.getClientArea().width > 80) {
                    column.setWidth(table.getClientArea().width - 2);
                }
            });
        }
        viewer.setContentProvider(new ObjectContentProvider());
        viewer.setLabelProvider(new ObjectLabelProvider());
        viewer.addDoubleClickListener(event -> open(event.getSelection()));
        table.addListener(SWT.DefaultSelection, event -> open(viewer.getSelection()));
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
        // objects are opened by the double click listener, not by matches
    }

    @Override
    protected void showMatch(Match match, int currentOffset, int currentLength, boolean activate)
            throws PartInitException {
        // objects are opened by the double click listener, not by matches
    }

    /** Opens the first selected object; shows a warning when that fails. */
    private void open(ISelection selection) {
        if (!(selection instanceof IStructuredSelection)) {
            return;
        }
        Object first = ((IStructuredSelection) selection).getFirstElement();
        if (!(first instanceof AbapObjectItem)) {
            return;
        }
        AbapObjectItem item = (AbapObjectItem) first;
        if (!AbapObjectNavigator.open(item)
                && getControl() != null && !getControl().isDisposed()
                && getControl().getShell() != null) {
            MessageDialog.openWarning(getControl().getShell(), Messages.get("dialog.title"),
                    Messages.format("error.openFailed", item.getName()));
        }
    }

    private static final class ObjectContentProvider implements IStructuredContentProvider {

        private static final Comparator<Object> ELEMENT_ORDER = (left, right) -> {
            if (!(left instanceof AbapObjectItem) || !(right instanceof AbapObjectItem)) {
                return 0;
            }
            AbapObjectItem first = (AbapObjectItem) left;
            AbapObjectItem second = (AbapObjectItem) right;
            int byType = Integer.compare(first.getObjectType().ordinal(),
                    second.getObjectType().ordinal());
            return byType != 0 ? byType
                    : first.getName().compareToIgnoreCase(second.getName());
        };

        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof AbstractTextSearchResult) {
                Object[] elements = ((AbstractTextSearchResult) inputElement).getElements();
                // The search result stores elements in a concurrent map, so
                // restore the category/name order for display here.
                Arrays.sort(elements, ELEMENT_ORDER);
                return elements;
            }
            return new Object[0];
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            // the result object itself is the input
        }

        @Override
        public void dispose() {
            // nothing to release
        }
    }

    /** Renders icon, object name (bold) and description. */
    private static final class ObjectLabelProvider extends StyledCellLabelProvider {

        @Override
        public void update(ViewerCell cell) {
            Object element = cell.getElement();
            if (!(element instanceof AbapObjectItem)) {
                cell.setText("");
                cell.setImage(null);
                cell.setStyleRanges(null);
                return;
            }
            AbapObjectItem item = (AbapObjectItem) element;
            String text = item.getDisplayText();
            cell.setText(text);
            cell.setImage(AbapObjectIcons.get(item.getObjectType()));

            int nameLength = item.getName() == null ? 0 : item.getName().length();
            if (nameLength > 0 && text.length() > nameLength) {
                StyleRange boldName = new StyleRange();
                boldName.start = 0;
                boldName.length = nameLength;
                boldName.font = JFaceResources.getFontRegistry()
                        .getBold(JFaceResources.DIALOG_FONT);
                cell.setStyleRanges(new StyleRange[] { boldName });
            } else {
                cell.setStyleRanges(null);
            }
        }
    }
}
