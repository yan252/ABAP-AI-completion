package com.sap.abap.ai.completion.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.sap.abap.ai.completion.Activator;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        store.setDefault(PreferenceConstants.API_BASE_URL, PreferenceConstants.DEFAULT_API_BASE_URL);
        store.setDefault(PreferenceConstants.API_MODEL, PreferenceConstants.DEFAULT_API_MODEL);
        store.setDefault(PreferenceConstants.API_KEY, "");
        store.setDefault(PreferenceConstants.MAX_TOKENS, PreferenceConstants.DEFAULT_MAX_TOKENS);
        store.setDefault(PreferenceConstants.TEMPERATURE, PreferenceConstants.DEFAULT_TEMPERATURE);

        store.setDefault(PreferenceConstants.PLUGIN_ENABLED, PreferenceConstants.DEFAULT_PLUGIN_ENABLED);
        store.setDefault(PreferenceConstants.AUTO_COMPLETION_ENABLED, PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED);
        store.setDefault(PreferenceConstants.AUTO_COMPLETE_DELAY, PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY);

        store.setDefault(PreferenceConstants.COMPLETION_COLOR, PreferenceConstants.DEFAULT_COMPLETION_COLOR);
        store.setDefault(PreferenceConstants.MANUAL_COMPLETION_MODE, PreferenceConstants.DEFAULT_MANUAL_COMPLETION_MODE);
        store.setDefault(PreferenceConstants.OVERLAY_OPACITY, PreferenceConstants.DEFAULT_OVERLAY_OPACITY);

        store.setDefault(PreferenceConstants.SKILL_ENABLED,
                PreferenceConstants.DEFAULT_SKILL_ENABLED);
        store.setDefault(PreferenceConstants.SKILL_DIR, PreferenceConstants.DEFAULT_SKILL_DIR);
        store.setDefault(PreferenceConstants.SYSTEM_PROMPT, PreferenceConstants.DEFAULT_SYSTEM_PROMPT);

        store.setDefault(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED,
                PreferenceConstants.DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED);
        store.setDefault(PreferenceConstants.ABAP_SEARCH_DEPTH,
                PreferenceConstants.DEFAULT_ABAP_SEARCH_DEPTH);
        store.setDefault(PreferenceConstants.MAX_CONTEXT_CHARS,
                PreferenceConstants.DEFAULT_MAX_CONTEXT_CHARS);

        store.setDefault(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED,
                PreferenceConstants.DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED);
        store.setDefault(PreferenceConstants.MAX_WORKSPACE_CODE_CHARS,
                PreferenceConstants.DEFAULT_MAX_WORKSPACE_CODE_CHARS);
        store.setDefault(PreferenceConstants.WORKSPACE_CODE_FILE_LIMIT,
                PreferenceConstants.DEFAULT_WORKSPACE_CODE_FILE_LIMIT);

        store.setDefault(PreferenceConstants.INTERFACE_LOGGING_ENABLED,
                PreferenceConstants.DEFAULT_INTERFACE_LOGGING_ENABLED);

        store.setDefault(PreferenceConstants.PROMPT_CACHE_ENABLED,
                PreferenceConstants.DEFAULT_PROMPT_CACHE_ENABLED);
    }
}
