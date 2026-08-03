package com.sap.abap.ai.completion.ui;

import java.net.URL;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuDetectEvent;
import org.eclipse.swt.events.MenuDetectListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
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
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

/**
 * Status bar icon contribution for ABAP AI Completion.
 *
 * <p>Contributed via {@code toolbar:org.eclipse.ui.trim.status} so Eclipse
 * provides native drag-and-drop reordering. Eclipse 2026-06's TrimBarLayout
 * locks the trim line to ~1/3 of the status bar height after a drag, and
 * vertically centers children, making the icon appear clipped and shifted.</p>
 *
 * <p><b>Fix:</b> We listen to {@code SWT.Move} / {@code SWT.Resize} on our
 * composite, canvas, and the entire parent chain up to the shell. When any
 * layout change sets a height below TARGET_HEIGHT, we synchronously force the
 * offending composite back to TARGET_HEIGHT and reposition our composite to
 * y=0. A {@code correcting} flag prevents recursive layout.</p>
 *
 * <p>Left-click: distinguished from drag via {@code SWT.DragDetect}.
 * Right-click: popup menu via {@link MenuDetectListener}.</p>
 */
public class AbapAIStatusLineContribution extends WorkbenchWindowControlContribution {

    private static final String ICON_PATH = "icons/SAPLogo.ico";
    private static final String HELP_URL = "https://github.com/yan252/ABAP-AI-completion";
    private static final String COMMAND_ID = "com.sap.abap.ai.completion.completeCommand";

    /** Target height = full status bar height. 22 is a safe default. */
    private static final int TARGET_HEIGHT = 22;

    private Image logoImage;
    private Canvas canvas;
    private Composite comp;

    /** Guard flag to prevent recursive layout corrections. */
    private boolean correcting = false;

    public AbapAIStatusLineContribution() {
        super("com.sap.abap.ai.completion.trimstatus.control");
    }

    @Override
    protected Control createControl(Composite parent) {
        logoImage = loadImage(parent.getDisplay());

        // Composite wrapper that always requests the full target height.
        comp = new Composite(parent, SWT.NONE) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                int w = (wHint != SWT.DEFAULT) ? wHint : 22;
                int h = TARGET_HEIGHT;
                return new Point(w, h);
            }
        };
        comp.setLayout(null);

        // Canvas that fills the composite and paints the icon top-aligned.
        canvas = new Canvas(comp, SWT.NONE);
        Rectangle initial = comp.getClientArea();
        canvas.setBounds(0, 0, Math.max(initial.width, 1), TARGET_HEIGHT);

        canvas.addPaintListener(e -> {
            // Detect any drift caused by drag-induced relayout and fix synchronously.
            // Paint fires after every layout, so this is the most reliable hook.
            fixLayoutIfDrifted();

            Rectangle cb = canvas.getBounds();
            if (cb.width <= 0 || cb.height <= 0) return;

            if (logoImage != null && !logoImage.isDisposed()) {
                Rectangle ib = logoImage.getBounds();
                if (ib.width <= 0 || ib.height <= 0) return;

                int targetH = TARGET_HEIGHT;
                int targetW = ib.width * targetH / ib.height;
                if (targetW > cb.width) {
                    targetW = cb.width;
                    targetH = ib.height * targetW / ib.width;
                }
                if (targetH <= 0) targetH = 1;
                if (targetW <= 0) targetW = 1;

                int x = (cb.width - targetW) / 2;
                int y = 0;
                try {
                    e.gc.drawImage(logoImage, 0, 0, ib.width, ib.height,
                            x, y, targetW, targetH);
                } catch (Exception ignored) {
                }
            } else {
                e.gc.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                e.gc.drawText("AI", 2, 0);
            }
        });

        canvas.setToolTipText("ABAP AI Completion");

        // ====================================================================
        // LAYOUT CORRECTION: Listen to Move/Resize on our composite, canvas,
        // and the entire parent chain up to the shell. When TrimBarLayout
        // shrinks the trim line or repositions us after a drag, we synchronously
        // force the trim line height back to TARGET_HEIGHT and our composite
        // back to y=0. The `correcting` flag prevents infinite recursion.
        // ====================================================================
        installLayoutListeners(parent);

        // ---- Right-click: popup menu ----
        canvas.addMenuDetectListener(new MenuDetectListener() {
            @Override
            public void menuDetected(MenuDetectEvent e) {
                showPopupMenu();
            }
        });

        // ---- Left-click: distinguish click from drag ----
        final boolean[] dragged = { false };
        canvas.addListener(SWT.DragDetect, e -> dragged[0] = true);
        comp.addListener(SWT.DragDetect, e -> dragged[0] = true);
        canvas.addListener(SWT.MouseUp, e -> {
            if (e.button == 1 && !dragged[0]) {
                showPopupMenu();
            }
            dragged[0] = false;
        });

        // ---- Initial correction (async, covers first layout) ----
        parent.getDisplay().asyncExec(this::fixLayoutIfDrifted);

        return comp;
    }

    /**
     * Attach Move/Resize listeners to our composite, canvas, and every parent
     * composite up to (but not including) the workbench shell.
     */
    private void installLayoutListeners(Composite parent) {
        try {
            org.eclipse.swt.widgets.Shell shell = findShell();

            // Listen on our composite and canvas.
            comp.addListener(SWT.Move, e -> fixLayoutIfDrifted());
            comp.addListener(SWT.Resize, e -> fixLayoutIfDrifted());
            canvas.addListener(SWT.Move, e -> fixLayoutIfDrifted());
            canvas.addListener(SWT.Resize, e -> fixLayoutIfDrifted());

            // Listen on every parent up to the shell.
            Composite p = parent;
            while (p != null) {
                if (p == shell) break;
                final Composite current = p;
                current.addListener(SWT.Move, e -> fixLayoutIfDrifted());
                current.addListener(SWT.Resize, e -> fixLayoutIfDrifted());
                p = p.getParent();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Synchronously detect and correct any layout drift. Called from Move/Resize
     * listeners and from PaintListener. Walks up the parent chain forcing every
     * composite shorter than TARGET_HEIGHT to expand to TARGET_HEIGHT, then
     * repositions our composite to y=0 with full height.
     */
    private void fixLayoutIfDrifted() {
        if (correcting) return;
        if (comp == null || comp.isDisposed()) return;

        boolean needFix = false;

        // 1. Check parent chain for any composite shorter than TARGET_HEIGHT.
        try {
            org.eclipse.swt.widgets.Shell shell = findShell();
            Composite p = comp.getParent();
            while (p != null) {
                if (p == shell) break;
                Rectangle ca = p.getClientArea();
                if (ca.height > 0 && ca.height < TARGET_HEIGHT) {
                    needFix = true;
                    break;
                }
                p = p.getParent();
            }
        } catch (Exception ignored) {
        }

        // 2. Check our own composite position/size.
        Point loc = comp.getLocation();
        Point sz = comp.getSize();
        if (loc.y != 0 || sz.y < TARGET_HEIGHT) {
            needFix = true;
        }

        if (!needFix) return;

        correcting = true;
        try {
            // Force every parent in the chain up to TARGET_HEIGHT (expand only).
            forceParentHeights();

            // Force our composite to y=0 and full height.
            loc = comp.getLocation();
            sz = comp.getSize();
            if (loc.y != 0) {
                comp.setLocation(loc.x, 0);
            }
            if (sz.y < TARGET_HEIGHT) {
                comp.setSize(sz.x, TARGET_HEIGHT);
            }

            // Force canvas to fill the composite at (0, 0).
            Rectangle ca = comp.getClientArea();
            int w = Math.max(ca.width, 1);
            canvas.setBounds(0, 0, w, TARGET_HEIGHT);
            canvas.redraw();
        } catch (Exception ignored) {
        } finally {
            correcting = false;
        }
    }

    /**
     * Walk up the parent chain and force each enclosing composite's height to
     * at least TARGET_HEIGHT. Expand only, never shrink.
     */
    private void forceParentHeights() {
        try {
            org.eclipse.swt.widgets.Shell shell = findShell();
            Composite p = comp.getParent();
            while (p != null) {
                if (p == shell) break;

                Rectangle ca = p.getClientArea();
                Point sz = p.getSize();
                if (ca.height > 0 && ca.height < TARGET_HEIGHT) {
                    try {
                        // Expand the composite so its client area is TARGET_HEIGHT.
                        int newTotalHeight = TARGET_HEIGHT + (sz.y - ca.height);
                        p.setSize(sz.x, newTotalHeight);
                    } catch (Exception ignored) {
                    }
                }
                p = p.getParent();
            }
        } catch (Exception ignored) {
        }
    }

    private org.eclipse.swt.widgets.Shell findShell() {
        try {
            org.eclipse.ui.IWorkbenchWindow window = findWorkbenchWindow();
            return (window != null) ? window.getShell() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private org.eclipse.ui.IWorkbenchWindow findWorkbenchWindow() {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                org.eclipse.ui.IWorkbenchWindow[] windows =
                        PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows.length > 0) window = windows[0];
            }
            return window;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Image loadImage(org.eclipse.swt.widgets.Display display) {
        if (Activator.getDefault() != null) {
            try (java.io.InputStream is = org.eclipse.core.runtime.FileLocator.openStream(
                    Activator.getDefault().getBundle(),
                    new org.eclipse.core.runtime.Path(ICON_PATH),
                    false)) {
                return new Image(display, new ImageData(is));
            } catch (Exception ignored) {
            }
        }
        try {
            ImageDescriptor desc = Activator.getImageDescriptor(ICON_PATH);
            if (desc != null) {
                return desc.createImage();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showPopupMenu() {
        try {
            if (canvas == null || canvas.isDisposed()) return;

            Menu popup = new Menu(canvas);
            try {
                MenuItem prefsItem = new MenuItem(popup, SWT.PUSH);
                prefsItem.setText("Preferences...");
                prefsItem.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        openPreferences();
                    }
                });

                new MenuItem(popup, SWT.SEPARATOR);

                final MenuItem enableItem = new MenuItem(popup, SWT.CHECK);
                enableItem.setText("Enable");
                enableItem.setSelection(isPluginEnabled());
                enableItem.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        setPluginEnabled(enableItem.getSelection());
                    }
                });

                new MenuItem(popup, SWT.SEPARATOR);

                MenuItem completeItem = new MenuItem(popup, SWT.PUSH);
                completeItem.setText("Code Completion\tCtrl+Shift+.");
                completeItem.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        triggerCompletion();
                    }
                });

                new MenuItem(popup, SWT.SEPARATOR);

                MenuItem helpItem = new MenuItem(popup, SWT.PUSH);
                helpItem.setText("Help");
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
        } catch (Exception ignored) {
        }
    }

    private void openPreferences() {
        try {
            org.eclipse.ui.IWorkbenchWindow window = findWorkbenchWindow();
            if (window == null) return;
            final org.eclipse.swt.widgets.Shell shell = window.getShell();
            final String pageId = "com.sap.abap.ai.completion.preferencePage";

            PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                try {
                    Class<?> dialogCls = Class.forName("org.eclipse.ui.dialogs.PreferencesUtil");
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
        }
    }

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
        }
    }

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
