package com.sap.abap.ai.completion.preferences;

import java.util.Locale;

import com.sap.abap.ai.completion.Activator;

/**
 * Resolves the plugin UI language from the {@link PreferenceConstants#PLUGIN_LANGUAGE}
 * preference: {@code zh} forces Simplified Chinese, {@code en} forces English.
 * Falls back to the OS locale when the preference is unset or the plugin
 * context is not yet available.
 */
public final class PluginLocale {

    private PluginLocale() {
    }

    /**
     * @return the locale the plugin message bundles should be loaded with.
     */
    public static Locale current() {
        try {
            if (Activator.getDefault() != null) {
                String lang = Activator.getDefault().getPreferenceStore()
                        .getString(PreferenceConstants.PLUGIN_LANGUAGE);
                if (PreferenceConstants.LANG_ZH.equals(lang)) {
                    return Locale.SIMPLIFIED_CHINESE;
                }
                if (PreferenceConstants.LANG_EN.equals(lang)) {
                    return Locale.ENGLISH;
                }
            }
        } catch (Exception e) {
            // preference store not ready - fall through to the OS locale
        }
        // 未配置时跟随操作系统语言；系统语言取不到时回退英文
        Locale os = Locale.getDefault();
        if (os == null || os.getLanguage() == null || os.getLanguage().isEmpty()) {
            return Locale.ENGLISH;
        }
        return os;
    }
}
