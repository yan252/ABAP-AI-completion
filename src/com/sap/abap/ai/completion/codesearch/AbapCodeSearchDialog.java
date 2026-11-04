package com.sap.abap.ai.completion.codesearch;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Standalone ABAP Code Search dialog opened by the menu entry and by the
 * Ctrl+Shift+H shortcut.
 */
public final class AbapCodeSearchDialog extends TitleAreaDialog {

    private static final int REPLACE_ID = 1001;

    private final AbapCodeSearchPanel panel = new AbapCodeSearchPanel();

    public AbapCodeSearchDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(getShellStyle() | org.eclipse.swt.SWT.RESIZE);
    }

    @Override
    public void create() {
        super.create();
        setTitle(Messages.get("dialog.title"));
        setMessage(Messages.get("dialog.message"));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite form = panel.create(area);
        form.setLayoutData(new org.eclipse.swt.layout.GridData(
                org.eclipse.swt.SWT.FILL, org.eclipse.swt.SWT.FILL, true, true));
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, Messages.get("btn.search"), true);
        createButton(parent, REPLACE_ID, Messages.get("btn.replace"), false);
        createButton(parent, IDialogConstants.CANCEL_ID, Messages.get("btn.cancel"), false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.OK_ID) {
            if (panel.performSearch(getShell())) {
                close();
            }
            return;
        }
        if (buttonId == REPLACE_ID) {
            panel.performReplace(getShell());
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected Control createContents(Composite parent) {
        Control control = super.createContents(parent);
        panel.setReplaceFinished(this::close);
        panel.startPrepareIfNeeded();
        getShell().setMinimumSize(480, 320);
        // The shell takes the focus only once it is activated, which happens
        // after createContents returned. Moving the caret on the first
        // activation is therefore the reliable way to start in the pattern
        // field (the asyncExec below is only a fallback).
        getShell().addShellListener(new ShellAdapter() {
            private boolean focused;

            @Override
            public void shellActivated(ShellEvent event) {
                if (!focused) {
                    focused = true;
                    panel.focusPattern();
                }
            }
        });
        control.getDisplay().asyncExec(panel::focusPattern);
        return control;
    }

    @Override
    public boolean close() {
        panel.dispose();
        return super.close();
    }
}
