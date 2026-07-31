package com.sap.abap.ai.completion;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

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
        // Ensure plugin is fully loaded so keybindings are registered
    }
}
