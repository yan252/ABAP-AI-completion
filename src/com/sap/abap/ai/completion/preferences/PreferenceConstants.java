package com.sap.abap.ai.completion.preferences;

import org.eclipse.swt.graphics.RGB;

/**
 * Constants for preference keys used in AI Completion configuration.
 */
public final class PreferenceConstants {

    private PreferenceConstants() {
    }

    // === AI Connection Settings ===
    public static final String API_BASE_URL = "aiApiBaseUrl";
    public static final String API_MODEL = "aiApiModel";
    public static final String API_KEY = "aiApiKey";
    public static final String MAX_TOKENS = "aiMaxTokens";
    public static final String TEMPERATURE = "aiTemperature";

    // === Feature Switches ===
    public static final String PLUGIN_ENABLED = "pluginEnabled";
    public static final String AUTO_COMPLETION_ENABLED = "autoCompletionEnabled";

    // === Auto Completion Settings ===
    public static final String AUTO_COMPLETE_DELAY = "autoCompleteDelay";

    // === Completion Style ===
    public static final String COMPLETION_COLOR = "completionColor";
    public static final String MANUAL_COMPLETION_MODE = "manualCompletionMode";
    public static final String OVERLAY_OPACITY = "overlayOpacity";

    // === Skill & Prompt ===
    public static final String SKILL_ENABLED = "skillEnabled";
    public static final String SKILL_DIR = "skillDirectory";
    public static final String SYSTEM_PROMPT = "systemPrompt";

    // === Parent Program Resolution ===
    public static final String PARENT_PROGRAM_RESOLUTION_ENABLED = "parentProgramResolutionEnabled";
    public static final String ABAP_SEARCH_DEPTH = "abapSearchDepth";
    public static final String MAX_CONTEXT_CHARS = "maxContextChars";

    // === Workspace Code Reference ===
    public static final String WORKSPACE_CODE_REFERENCE_ENABLED = "workspaceCodeReferenceEnabled";
    public static final String MAX_WORKSPACE_CODE_CHARS = "maxWorkspaceCodeChars";
    public static final String WORKSPACE_CODE_FILE_LIMIT = "workspaceCodeFileLimit";

    // === Interface Logging ===
    public static final String INTERFACE_LOGGING_ENABLED = "interfaceLoggingEnabled";

    // === Default Values ===
    public static final String DEFAULT_API_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_API_MODEL = "gpt-4";
    public static final String DEFAULT_MAX_TOKENS = "256";
    public static final String DEFAULT_TEMPERATURE = "0.3";
    public static final boolean DEFAULT_PLUGIN_ENABLED = true;
    public static final boolean DEFAULT_AUTO_COMPLETION_ENABLED = false;
    public static final String DEFAULT_AUTO_COMPLETE_DELAY = "2000";
    public static final String DEFAULT_COMPLETION_COLOR = "0,128,0";
    public static final String DEFAULT_MANUAL_COMPLETION_MODE = "direct";
    public static final String DEFAULT_OVERLAY_OPACITY = "80";
    public static final boolean DEFAULT_SKILL_ENABLED = false;
    public static final String DEFAULT_SKILL_DIR = ""; // 默认使用 <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/skills
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are an expert SAP ABAP developer assistant. Analyze the provided ABAP code context "
            + "(including referenced INCLUDE programs and available SKILL files) and suggest the next "
            + "most appropriate code at the cursor position.\n\n"
            + "Rules:\n"
            + "1. Only output the code to insert - no explanations, no markdown.\n"
            + "2. PRIORITIZE using code patterns, templates, and examples from the SKILL files - they are your primary reference for coding style and patterns.\n"
            + "3. Follow SAP ABAP best practices and the coding patterns from the SKILL files above all else.\n"
            + "4. Consider the context from INCLUDE programs and skill examples.\n"
            + "5. Keep suggestions concise and directly insertable at cursor.\n"
            + "6. If the cursor is inside a comment, suggest the corresponding code implementation.\n"
            + "7. Pay attention to code comments that describe what should be implemented next.\n"
            + "8. Use ABAP-specific patterns: DATA declarations, LOOPs, SELECTs, FORM routines, etc.\n"
            + "9. Maintain consistent naming conventions with the existing code.\n"
            + "10. When SKILL files are provided, prefer their patterns over generic ABAP code suggestions.";

    // === Defaults for Parent Program Resolution ===
    public static final boolean DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED = true;
    public static final String DEFAULT_ABAP_SEARCH_DEPTH = "0";
    public static final String DEFAULT_MAX_CONTEXT_CHARS = "8000";

    // === Defaults for Workspace Code Reference ===
    public static final boolean DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED = false;
    public static final String DEFAULT_MAX_WORKSPACE_CODE_CHARS = "50000";
    public static final String DEFAULT_WORKSPACE_CODE_FILE_LIMIT = "20";

    // === Defaults for Interface Logging ===
    public static final boolean DEFAULT_INTERFACE_LOGGING_ENABLED = false;
}
