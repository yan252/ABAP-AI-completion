package com.sap.abap.ai.completion.ui;

import java.net.URL;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.action.ControlContribution;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

/**
 * Status line contribution that shows the ABAP AI Completion icon in the
 * <b>main status line row</b> (full height) of the Eclipse status bar.
 *
 * <p>This contribution is added programmatically to the workbench window's
 * {@link org.eclipse.jface.action.IStatusLineManager} (see
 * {@link com.sap.abap.ai.completion.Activator#earlyStartup()}), NOT via a
 * {@code menuContribution} to {@code toolbar:org.eclipse.ui.trim.status}.</p>
 *
 * <p>Reason: {@code toolbar:org.eclipse.ui.trim.status} creates a separate
 * trim <b>row</b> whose height is only ~1/3 of the status bar (Eclipse
 * Bug 471313). Contributing to the main status line manager instead places
 * the icon inside the primary status line row, which has the full status bar
 * height, matching how other plugins (e.g. Copilot) render their status bar
 * icons.</p>
 *
 * <p>Clicking the icon shows a popup menu with:
 *   1. Preferences (opens the ABAP AI Completion preference page)
 *   2. Enable / Disable plugin (toggles the PLUGIN_ENABLED preference)
 *   3. Code Completion (triggers the ABAP AI completion command, same as Ctrl+Shift+.)
 *   4. Help (opens the project's GitHub page in the system browser)</p>
 */
public class AbapAIStatusLineContribution extends ControlContribution {

    private static final String ICON_PATH = "icons/SAPLogo.ico";
    private static final String HELP_URL = "https://github.com/yan252/ABAP-AI-completion";
    private static final String COMMAND_ID = "com.sap.abap.ai.completion.completeCommand";
    private static final int ICON_SIZE = 16;

    private Control iconControl;
    private Image logoImage;

    /**
     * Creates a new status line contribution with the given id.
     * Public so it can be instantiated from {@link Activator} (no-arg + setId
     * is not available on {@link ControlContribution}).
     */
    public AbapAIStatusLineContribution(String id) {
        super(id);
    }

    @Override
    protected Control createControl(Composite parent) {
        logoImage = loadImageScaled(ICON_SIZE, parent.getDisplay());

        // A Canvas lets us paint the icon vertically+horizontally centered
        // within whatever height the main status line row provides. The main
        // status line row has the full status bar height, so the 16x16 icon
        // renders at its native size, centered - no clipping, no white gap.
        Canvas canvas = new Canvas(parent, SWT.NONE) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                int w = (wHint != SWT.DEFAULT) ? wHint : ICON_SIZE;
                int h = (hHint != SWT.DEFAULT) ? hHint : ICON_SIZE;
                return new Point(w, h);
            }
        };

        canvas.addPaintListener(e -> {
            Rectangle cb = canvas.getClientArea();
            if (cb.width <= 0 || cb.height <= 0) return;
            if (logoImage != null && !logoImage.isDisposed()) {
                Rectangle ib = logoImage.getBounds();
                // Center the icon in the canvas; cap at ICON_SIZE so we never
                // upscale beyond the native resolution.
                int target = Math.min(Math.min(cb.width, cb.height), ICON_SIZE);
                if (target < 1) target = 1;
                int x = cb.x + (cb.width - target) / 2;
                int y = cb.y + (cb.height - target) / 2;
                try {
                    if (ib.width == target && ib.height == target) {
                        e.gc.drawImage(logoImage, x, y);
                    } else {
                        e.gc.drawImage(logoImage, 0, 0, ib.width, ib.height,
                                x, y, target, target);
                    }
                } catch (Exception ignored) {
                    try {
                        e.gc.drawImage(logoImage,
                                cb.x + (cb.width - ib.width) / 2,
                                cb.y + (cb.height - ib.height) / 2);
                    } catch (Exception ignored2) {
                    }
                }
            } else {
                GC gc = e.gc;
                gc.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                gc.drawText("AI", 2, 1);
            }
        });

        canvas.addListener(SWT.Resize, e -> canvas.redraw());

        canvas.setToolTipText("ABAP AI Completion - click for options");
        canvas.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                showPopupMenu();
            }
        });

        iconControl = canvas;
        return canvas;
    }

    /**
     * Loads the icon image and force-scales it to the given square size.
     *
     * <p>ICO files: {@link ImageDescriptor#getImageData()} may return null for
     * multi-image ICOs, and {@link ImageDescriptor#createImage()} can yield an
     * image whose actual dimensions differ from the ICO header. Loading the
     * {@link ImageData} directly from the bundle stream and scaling it is the
     * most reliable approach.</p>
     */
    private Image loadImageScaled(int size, org.eclipse.swt.widgets.Display display) {
        if (Activator.getDefault() != null) {
            try (java.io.InputStream is = org.eclipse.core.runtime.FileLocator.openStream(
                    Activator.getDefault().getBundle(),
                    new org.eclipse.core.runtime.Path(ICON_PATH),
                    false)) {
                ImageData data = new ImageData(is);
                if (data.width != size || data.height != size) {
                    data = data.scaledTo(size, size);
                }
                return new Image(display, data);
            } catch (Exception ignored) {
            }
        }
        try {
            ImageDescriptor desc = Activator.getImageDescriptor(ICON_PATH);
            if (desc != null) {
                ImageData data = desc.getImageData();
                if (data != null) {
                    if (data.width != size || data.height != size) {
                        data = data.scaledTo(size, size);
                    }
                    return new Image(display, data);
                }
                return desc.createImage();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Builds and shows the popup menu with the four actions.
     */
    private void showPopupMenu() {
        if (iconControl == null || iconControl.isDisposed()) return;

        Menu popup = new Menu(iconControl);
        try {
            // ---- 1. Preferences ----
            MenuItem prefsItem = new MenuItem(popup, SWT.PUSH);
            prefsItem.setText("Preferences...");
            prefsItem.setToolTipText("Open ABAP AI Completion preference page");
            prefsItem.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    openPreferences();
                }
            });

            new MenuItem(popup, SWT.SEPARATOR);

            // ---- 2. Enable / Disable ----
            final MenuItem enableItem = new MenuItem(popup, SWT.CHECK);
            enableItem.setText("Enable");
            boolean enabled = isPluginEnabled();
            enableItem.setSelection(enabled);
            enableItem.setToolTipText("Enable or disable the ABAP AI Completion plugin");
            enableItem.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    boolean newVal = enableItem.getSelection();
                    setPluginEnabled(newVal);
                }
            });

            new MenuItem(popup, SWT.SEPARATOR);

            // ---- 3. Code Completion ----
            MenuItem completeItem = new MenuItem(popup, SWT.PUSH);
            completeItem.setText("Code Completion\tCtrl+Shift+.");
            completeItem.setToolTipText("Trigger ABAP AI code completion in the active editor");
            completeItem.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    triggerCompletion();
                }
            });

            new MenuItem(popup, SWT.SEPARATOR);

            // ---- 4. Help ----
            MenuItem helpItem = new MenuItem(popup, SWT.PUSH);
            helpItem.setText("Help");
            helpItem.setToolTipText("Open the project page: " + HELP_URL);
            helpItem.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    openHelpUrl();
                }
            });

            popup.setVisible(true);
        } catch (Exception ex) {
            try { popup.dispose(); } catch (Exception ignored) {}
        }
    }

    /**
     * Opens the Eclipse Preferences dialog filtered to the ABAP AI Completion page.
     */
    private void openPreferences() {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                org.eclipse.ui.IWorkbenchWindow[] windows =
                        PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows.length > 0) window = windows[0];
            }
            if (window == null) return;
            final org.eclipse.swt.widgets.Shell shell = window.getShell();

            final String pageId = "com.sap.abap.ai.completion.preferencePage";

            PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                try {
                    Class<?> dialogCls =
                            Class.forName("org.eclipse.ui.dialogs.PreferencesUtil");
                    java.lang.reflect.Method createDialog = dialogCls.getMethod(
                            "createPreferenceDialogOn",
                            org.eclipse.swt.widgets.Shell.class,
                            String.class,
                            String[].class,
                            Object.class);
                    Object dialog = createDialog.invoke(null, shell, pageId, null, null);
                    if (dialog instanceof org.eclipse.jface.dialogs.Dialog) {
                        ((org.eclipse.jface.dialogs.Dialog) dialog).open();
                    }
                } catch (Exception fallback) {
                    try {
                        java.lang.reflect.Method m = PlatformUI.getWorkbench().getClass()
                                .getMethod("getPreferenceManager");
                        Object pm = m.invoke(PlatformUI.getWorkbench());
                        Object dlg = Class.forName("org.eclipse.ui.dialogs.WorkbenchPreferenceDialog")
                                .getConstructor(org.eclipse.swt.widgets.Shell.class,
                                        Class.forName("org.eclipse.jface.preference.IPreferenceManager"))
                                .newInstance(shell, pm);
                        if (dlg instanceof org.eclipse.jface.dialogs.Dialog) {
                            ((org.eclipse.jface.dialogs.Dialog) dlg).open();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    // =====================================================================
    // Action helpers
    // =====================================================================

    private boolean isPluginEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_PLUGIN_ENABLED;
            return store.getBoolean(PreferenceConstants.PLUGIN_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_PLUGIN_ENABLED;
        }
    }

    private void setPluginEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.PLUGIN_ENABLED, enabled);
            try {
                Activator.getDefault().savePluginPreferences();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Triggers the ABAP AI completion command programmatically,
     * equivalent to pressing Ctrl+Shift+.
     */
    private void triggerCompletion() {
        try {
            IHandlerService hs = PlatformUI.getWorkbench().getService(IHandlerService.class);
            ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (hs == null || cs == null) return;

            Command command = cs.getCommand(COMMAND_ID);
            if (command == null || !command.isDefined()) return;

            ExecutionEvent event = hs.createExecutionEvent(command, null);
            command.executeWithChecks(event);
        } catch (Exception ex) {
            try {
                IHandlerService hs = PlatformUI.getWorkbench().getService(IHandlerService.class);
                if (hs != null) {
                    hs.executeCommand(COMMAND_ID, null);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Opens the GitHub project page in the default system browser.
     */
    private void openHelpUrl() {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                    .getExternalBrowser()
                    .openURL(new URL(HELP_URL));
        } catch (Exception e) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(HELP_URL));
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void dispose() {
        if (logoImage != null && !logoImage.isDisposed()) {
            logoImage.dispose();
            logoImage = null;
        }
        super.dispose();
    }
}
