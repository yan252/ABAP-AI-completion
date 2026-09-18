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

    // === Completion Display Type ===
    /** 补全代码显示类型: 1-对话框显示(默认), 2-快捷显示(内联,类 Copilot,不真正插入) */
    public static final String COMPLETION_DISPLAY_TYPE = "completionDisplayType";
    public static final int COMPLETION_DISPLAY_DIALOG = 1;
    public static final int COMPLETION_DISPLAY_INLINE = 2;
    public static final int DEFAULT_COMPLETION_DISPLAY_TYPE = COMPLETION_DISPLAY_DIALOG;

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
    /** 接口日志记录等级: 0-不记录, 1-普通记录, 2-DEBUG调试记录 */
    public static final String INTERFACE_LOG_LEVEL = "interfaceLogLevel";

    // === Interface Log Level Values ===
    public static final int LOG_LEVEL_NONE = 0;
    public static final int LOG_LEVEL_NORMAL = 1;
    public static final int LOG_LEVEL_DEBUG = 2;

    // === Prompt Cache ===
    public static final String PROMPT_CACHE_ENABLED = "promptCacheEnabled";

    // === SAP Connection Settings ===
    /**
     * abap-cli(abapGit)连接 URL(可选)，如 https://s4devapp.app.com.cn:1443。
     *
     * <p>填写后，"Test Connection" 直接用它做 abapGit 方式探测，模板导入也会把
     * 它写进 abap-cli 的 {@code ~/.abap-cli/systems.json} profile（临时套用、导入后回滚）。
     * 留空则回退到 systems.json 里已有的 profile。</p>
     */
    public static final String SAP_ABAP_CLI_URL = "sapAbapCliUrl";

    // === SAP Connection Settings (JCo) ===
    /** SAP 应用服务器地址(如 10.0.0.1 或 host.example.com)。 */
    public static final String SAP_HOST = "sapHost";
    /** SAP 系统编号/SID 系统号(如 00)。 */
    public static final String SAP_SYSTEM_NUMBER = "sapSystemNumber";
    /** SAP Client。 */
    public static final String SAP_CLIENT = "sapClient";
    /** 登录语言(如 EN / ZH)。 */
    public static final String SAP_LANGUAGE = "sapLanguage";
    /** SAP 用户名。 */
    public static final String SAP_USER = "sapUser";
    /** SAP 用户密码。 */
    public static final String SAP_PASSWORD = "sapPassword";
    /** JCo native 库(sapjco3.dll / libsapjco3.so)所在目录(可选)。
     *  仅在 JCo 未由 OSGi fragment(com.sap.conn.jco.win32.x86_64)自动加载时需要。 */
    public static final String SAP_NATIVE_LIB_DIR = "sapNativeLibraryDir";

    // === Defaults for SAP Connection Settings ===
    public static final String DEFAULT_SAP_SYSTEM_NUMBER = "00";
    public static final String DEFAULT_SAP_CLIENT = "001";
    public static final String DEFAULT_SAP_LANGUAGE = "EN";
    /** abap-cli URL 默认为空(留空则回退到 ~/.abap-cli/systems.json 里的 profile)。 */
    public static final String DEFAULT_SAP_ABAP_CLI_URL = "";

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
    public static final boolean DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED = false;
    public static final String DEFAULT_ABAP_SEARCH_DEPTH = "1";
    public static final String DEFAULT_MAX_CONTEXT_CHARS = "8000";

    // === Defaults for Workspace Code Reference ===
    public static final boolean DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED = false;
    public static final String DEFAULT_MAX_WORKSPACE_CODE_CHARS = "50000";
    public static final String DEFAULT_WORKSPACE_CODE_FILE_LIMIT = "5";

    // === Defaults for Interface Logging ===
    public static final String DEFAULT_INTERFACE_LOG_LEVEL = "0";

    // === Defaults for Prompt Cache ===
    public static final boolean DEFAULT_PROMPT_CACHE_ENABLED = true;
}
