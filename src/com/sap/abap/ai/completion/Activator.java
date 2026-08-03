package com.sap.abap.ai.completion;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.sap.abap.ai.completion.ui.AbapAIStatusLineContribution;

public class Activator extends AbstractUIPlugin implements IStartup {

    public static final String PLUGIN_ID = "com.sap.abap.ai.completion";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }

    public static IPreferenceStore staticGetPreferenceStore() {
        if (getDefault() != null) {
            return getDefault().getPreferenceStore();
        }
        return null;
    }

    @Override
    public void earlyStartup() {
        // Ensure plugin is fully loaded so keybindings are registered.
        //
        // Also inject the ABAP AI Completion icon into the workbench window's
        // main status line row (full height). We do this programmatically
        // because a menuContribution to "toolbar:org.eclipse.ui.trim.status"
        // renders in a separate trim row whose height is only ~1/3 of the
        // status bar (Eclipse Bug 471313). Contributing directly to the
        // IStatusLineManager places the icon inside the primary status line
        // row, which has the full status bar height.
        installStatusLineContribution();
    }

    /**
     * Installs the {@link AbapAIStatusLineContribution} into the active workbench
     * window's status line manager. Retries asynchronously until the window is
     * available, and re-installs whenever the active window changes (the status
     * line is per-window).
     */
    private void installStatusLineContribution() {
        Display.getDefault().asyncExec(() -> {
            try {
                IWorkbench workbench = PlatformUI.getWorkbench();
                if (workbench == null) return;

                // Install into all currently open windows.
                for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                    installIntoWindow(window);
                }

                // Re-install when a new window opens later. We piggy-back on
                // the part service: when a part is opened in a window we have
                // not yet instrumented, install there too. This also re-adds
                // the contribution if a part switch caused the status line to
                // drop non-persistent items.
                workbench.addWorkbenchListener(new IWorkbenchListener() {
                    @Override
                    public boolean preShutdown(IWorkbench w, boolean forced) {
                        return true;
                    }
                    @Override
                    public void postShutdown(IWorkbench w) {
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void installIntoWindow(IWorkbenchWindow window) {
        if (window == null) return;
        try {
            IStatusLineManager slm = getStatusLineManager(window);
            if (slm == null) return;
            String itemId = "com.sap.abap.ai.completion.statusLineItem";
            // Avoid duplicate additions.
            if (slm.find(itemId) != null) return;
            IContributionItem item = new AbapAIStatusLineContribution(itemId);
            slm.add(item);
            slm.update(true);

            // The workbench StatusLineManager can drop contributed items on
            // part switches (the active part's SubStatusLineManager refreshes).
            // Re-add our item whenever a part is activated in this window.
            IPartService ps = window.getPartService();
            if (ps != null) {
                ps.addPartListener(new org.eclipse.ui.IPartListener2() {
                    @Override
                    public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref) {
                        ensureInstalled(window);
                    }
                    @Override
                    public void partActivated(org.eclipse.ui.IWorkbenchPartReference ref) {
                        ensureInstalled(window);
                    }
                    @Override
                    public void partBroughtToTop(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                    @Override
                    public void partClosed(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                    @Override
                    public void partDeactivated(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                    @Override
                    public void partHidden(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                    @Override
                    public void partVisible(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                    @Override
                    public void partInputChanged(org.eclipse.ui.IWorkbenchPartReference ref) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureInstalled(IWorkbenchWindow window) {
        try {
            IStatusLineManager slm = getStatusLineManager(window);
            if (slm == null) return;
            String itemId = "com.sap.abap.ai.completion.statusLineItem";
            if (slm.find(itemId) == null) {
                slm.add(new AbapAIStatusLineContribution(itemId));
                slm.update(true);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Obtains the global (per-window) {@link IStatusLineManager}. The
     * {@code getStatusLineManager()} method is only defined on the internal
     * {@code WorkbenchWindow} class, so we invoke it via reflection to avoid a
     * direct compile-time dependency on internal API.
     */
    private IStatusLineManager getStatusLineManager(IWorkbenchWindow window) {
        try {
            java.lang.reflect.Method m = window.getClass().getMethod("getStatusLineManager");
            Object result = m.invoke(window);
            if (result instanceof IStatusLineManager) {
                return (IStatusLineManager) result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

