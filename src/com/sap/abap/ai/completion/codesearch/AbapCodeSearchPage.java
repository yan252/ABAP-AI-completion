package com.sap.abap.ai.completion.codesearch;

import org.eclipse.jface.dialogs.DialogPage;
import org.eclipse.search.ui.ISearchPage;
import org.eclipse.search.ui.ISearchPageContainer;
import org.eclipse.swt.widgets.Composite;

/**
 * First tab of the standard Eclipse Search dialog (Ctrl+H). Reuses the same
 * form as the standalone ABAP Code Search dialog.
 */
public final class AbapCodeSearchPage extends DialogPage implements ISearchPage {

    private ISearchPageContainer container;
    private AbapCodeSearchPanel panel;

    @Override
    public void setContainer(ISearchPageContainer container) {
        this.container = container;
    }

    @Override
    public void createControl(Composite parent) {
        panel = new AbapCodeSearchPanel();
        Composite form = panel.create(parent);
        // The Search dialog has no message area, so attribute the feature to
        // the plugin inside the tab page itself.
        panel.showPluginInfo();
        setControl(form);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && panel != null) {
            panel.startPrepareIfNeeded();
            if (getControl() != null && !getControl().isDisposed()) {
                getControl().getDisplay().asyncExec(panel::focusPattern);
            }
        }
    }

    @Override
    public boolean performAction() {
        if (panel == null || getControl() == null || getControl().getShell() == null) {
            return false;
        }
        if (container != null) {
            container.setPerformActionEnabled(true);
        }
        return panel.performSearch(getControl().getShell());
    }

    @Override
    public void dispose() {
        if (panel != null) {
            panel.dispose();
        }
        super.dispose();
    }
}
