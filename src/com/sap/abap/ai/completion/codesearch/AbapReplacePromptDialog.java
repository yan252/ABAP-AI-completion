package com.sap.abap.ai.completion.codesearch;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

/**
 * Small modal prompt shown for every match of the interactive replace flow.
 *
 * <p>It offers the three actions of an SE38-style replace: replace the current
 * occurrence (Shift+F5), skip it and continue with the next one (Ctrl+Shift+F6)
 * and replace every remaining occurrence from the current position without
 * further prompts (Ctrl+Shift+F7). Plus a stop button to leave the flow.</p>
 */
final class AbapReplacePromptDialog extends Dialog {

    static final int REPLACE = 1001;
    static final int NEXT = 1002;
    static final int ALL = 1003;
    static final int STOP = IDialogConstants.CANCEL_ID;

    private final String message;

    private final Listener shortcutFilter = new Listener() {
        @Override
        public void handleEvent(Event event) {
            boolean ctrlShift = (event.stateMask & (SWT.CTRL | SWT.SHIFT))
                    == (SWT.CTRL | SWT.SHIFT);
            if (event.keyCode == SWT.F5 && (event.stateMask & SWT.SHIFT) != 0) {
                choose(REPLACE);
                event.doit = false;
            } else if (event.keyCode == SWT.F6 && ctrlShift) {
                choose(NEXT);
                event.doit = false;
            } else if (event.keyCode == SWT.F7 && ctrlShift) {
                choose(ALL);
                event.doit = false;
            } else if (event.keyCode == SWT.ESC) {
                choose(STOP);
                event.doit = false;
            }
        }
    };

    AbapReplacePromptDialog(Shell parentShell, String message) {
        super(parentShell);
        this.message = message;
    }

    @Override
    public int open() {
        final Display display = getParentShell() != null
                ? getParentShell().getDisplay() : Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.addFilter(SWT.KeyDown, shortcutFilter);
        }
        try {
            return super.open();
        } finally {
            if (display != null && !display.isDisposed()) {
                display.removeFilter(SWT.KeyDown, shortcutFilter);
            }
        }
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.get("replace.title"));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label label = new Label(content, SWT.WRAP);
        label.setText(message);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 300;
        label.setLayoutData(data);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // Icon-only buttons keep the prompt small. The action text (including
        // the keyboard shortcut) is shown as tooltip when hovering the button.
        Button replace = createButton(parent, REPLACE, "", true);
        Button next = createButton(parent, NEXT, "", false);
        Button all = createButton(parent, ALL, "", false);
        Button stop = createButton(parent, STOP, "", false);

        configureButton(replace, "replace.prompt.replace", ISharedImages.IMG_TOOL_REDO);
        configureButton(next, "replace.prompt.next", ISharedImages.IMG_TOOL_FORWARD);
        configureButton(all, "replace.prompt.all", ISharedImages.IMG_TOOL_COPY);
        configureButton(stop, "replace.prompt.stop", ISharedImages.IMG_TOOL_DELETE);
    }

    private void configureButton(Button button, String labelKey, String imageId) {
        String label = Messages.get(labelKey);
        button.setToolTipText(label);
        try {
            Image image = PlatformUI.getWorkbench().getSharedImages().getImage(imageId);
            if (image != null) {
                button.setImage(image);
            } else {
                button.setText(label);
            }
        } catch (Throwable ignored) {
            // icons are decorative only; fall back to the short text label
            button.setText(label);
        }
    }

    @Override
    protected void buttonPressed(int buttonId) {
        setReturnCode(buttonId);
        close();
    }

    private void choose(int buttonId) {
        if (getShell() == null || getShell().isDisposed() || !getShell().isVisible()) {
            return;
        }
        buttonPressed(buttonId);
    }
}
