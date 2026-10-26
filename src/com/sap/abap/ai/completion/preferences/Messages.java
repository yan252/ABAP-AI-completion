package com.sap.abap.ai.completion.preferences;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Package-local message bundle. The language follows the pluginLanguage
 * preference (Feature Settings page); falls back to the OS locale.
 *
 * <p>No custom cache — Java's built-in ResourceBundle cache is used. The
 * cache keys on both bundle name and Locale, so switching the Locale
 * (via {@link PluginLocale#current()}) makes {@code getBundle} return the
 * right language immediately. See {@link #clearCache()} for the method the
 * preference page invokes on a language change to purge stale entries.</p>
 */
public final class Messages {

    private static final String BUNDLE_NAME = "com.sap.abap.ai.completion.preference" + "s.messages";

    private Messages() {
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE_NAME, PluginLocale.current(),
                Messages.class.getClassLoader());
    }

    /**
     * Force-evict this bundle from Java's internal ResourceBundle cache.
     * Called after a language change so the very next {@link #get(String)}
     * reloads the bundle for the newly selected Locale.
     */
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
