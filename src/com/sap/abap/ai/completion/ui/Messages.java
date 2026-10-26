package com.sap.abap.ai.completion.ui;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.sap.abap.ai.completion.preferences.PluginLocale;

/**
 * Package-local message bundle. The language follows the pluginLanguage
 * preference (Feature Settings page); falls back to the OS locale.
 */
public final class Messages {

    private static final String BUNDLE_NAME = "com.sap.abap.ai.completion.ui.messages";

    private Messages() {
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE_NAME, PluginLocale.current(),
                Messages.class.getClassLoader());
    }

    public static void clearCache() {
        ResourceBundle.clearCache(Messages.class.getClassLoader());
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
