package com.sap.abap.ai.completion.objectsearch;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.sap.abap.ai.completion.preferences.PluginLocale;

/**
 * Message bundle for the ABAP Object Search UI. The language follows the plugin
 * language preference (see {@link PluginLocale}); falls back to the OS locale.
 */
final class Messages {

    private static final String BUNDLE_NAME = "com.sap.abap.ai.completion.objectsearch.messages";

    private Messages() {
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE_NAME, PluginLocale.current(),
                Messages.class.getClassLoader());
    }

    static String get(String key) {
        try {
            return bundle().getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    static String format(String key, Object... args) {
        return java.text.MessageFormat.format(get(key), args);
    }
}
