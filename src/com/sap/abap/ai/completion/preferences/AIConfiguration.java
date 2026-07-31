package com.sap.abap.ai.completion.preferences;

import java.io.File;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.graphics.RGB;

import com.sap.abap.ai.completion.Activator;

/**
 * Reads the current AI configuration from the preference store.
 */
public final class AIConfiguration {

    private AIConfiguration() {
    }

    // === Connection Settings ===

    public static String getApiBaseUrl() {
        return getStore().getString(PreferenceConstants.API_BASE_URL);
    }

    public static String getModel() {
        return getStore().getString(PreferenceConstants.API_MODEL);
    }

    public static String getApiKey() {
        return getStore().getString(PreferenceConstants.API_KEY);
    }

    public static int getMaxTokens() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.MAX_TOKENS));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_MAX_TOKENS);
        }
    }

    public static double getTemperature() {
        try {
            return Double.parseDouble(getStore().getString(PreferenceConstants.TEMPERATURE));
        } catch (NumberFormatException e) {
            return Double.parseDouble(PreferenceConstants.DEFAULT_TEMPERATURE);
        }
    }

    // === Feature Switches ===

    public static boolean isPluginEnabled() {
        return getStore().getBoolean(PreferenceConstants.PLUGIN_ENABLED);
    }

    public static boolean isAutoCompletionEnabled() {
        return getStore().getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED);
    }

    // === Auto Completion Settings ===

    public static int getAutoCompleteDelay() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.AUTO_COMPLETE_DELAY));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY);
        }
    }

    // === Completion Style ===

    public static String getManualCompletionMode() {
        return getStore().getString(PreferenceConstants.MANUAL_COMPLETION_MODE);
    }

    public static RGB getCompletionColor() {
        String val = getStore().getString(PreferenceConstants.COMPLETION_COLOR);
        if (val == null || val.isEmpty()) {
            return parseRgb(PreferenceConstants.DEFAULT_COMPLETION_COLOR);
        }
        return parseRgb(val);
    }

    public static String rgbToString(RGB rgb) {
        return rgb.red + "," + rgb.green + "," + rgb.blue;
    }

    // === Skill & Prompt ===

    public static String getSkillDirectory() {
        return getStore().getString(PreferenceConstants.SKILL_DIR);
    }

    public static String getSystemPrompt() {
        return getStore().getString(PreferenceConstants.SYSTEM_PROMPT);
    }

    public static String loadSkillContents() {
        String dirPath = getSkillDirectory();
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return "";
        }

        File skillDir = new File(dirPath);
        if (!skillDir.exists() || !skillDir.isDirectory()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        File[] files = skillDir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".abap")
                || name.toLowerCase().endsWith(".txt")
                || name.toLowerCase().endsWith(".skill"));

        if (files != null) {
            for (File f : files) {
                try {
                    String content = new String(
                            java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    sb.append("=== Skill: ").append(f.getName()).append(" ===\n");
                    sb.append(content).append("\n\n");
                } catch (Exception e) {
                    // skip unreadable files
                }
            }
        }

        return sb.toString();
    }

    // === Helpers ===

    private static RGB parseRgb(String rgbStr) {
        try {
            String[] parts = rgbStr.split(",");
            return new RGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (Exception e) {
            return new RGB(0, 128, 0);
        }
    }

    private static IPreferenceStore getStore() {
        return Activator.getDefault().getPreferenceStore();
    }
}
