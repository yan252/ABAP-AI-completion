package com.sap.abap.ai.completion.ui;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.sap.abap.ai.completion.preferences.PluginLocale;

/**
 * Message bundle for the ZIP template import services. The language follows
 * the pluginLanguage preference (Feature Settings page); falls back to the
 * OS locale.
 */
public final class ImportMessages {

    private static final String BUNDLE_NAME = "com.sap.abap.ai.completion.ui.messages" + "_import";

    private ImportMessages() {
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE_NAME, PluginLocale.current(),
                ImportMessages.class.getClassLoader());
    }

    public static void clearCache() {
        ResourceBundle.clearCache(ImportMessages.class.getClassLoader());
    }

    public static String get(String key) {
        try {
            return bundle().getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    public static String format(String key, Object... args) {
        return java.text.MessageFormat.format(get(key), args);
    }
}
