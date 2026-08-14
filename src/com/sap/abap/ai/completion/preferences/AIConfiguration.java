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

    /**
     * 获取覆盖层透明度(0-100,百分比)。
     * 默认 80,转换为 SWT alpha 值 (0-255)。
     */
    public static int getOverlayOpacity() {
        try {
            int pct = Integer.parseInt(getStore().getString(PreferenceConstants.OVERLAY_OPACITY));
            pct = Math.max(10, Math.min(100, pct));
            return (int) Math.round(pct * 255.0 / 100.0);
        } catch (NumberFormatException e) {
            return (int) Math.round(Integer.parseInt(PreferenceConstants.DEFAULT_OVERLAY_OPACITY) * 255.0 / 100.0);
        }
    }

    /**
     * 获取覆盖层透明度百分比(0-100)。
     */
    public static int getOverlayOpacityPercent() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.OVERLAY_OPACITY));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_OVERLAY_OPACITY);
        }
    }

    // === Skill & Prompt ===

    /**
     * 是否启用 Skill 参考功能。
     */
    public static boolean isSkillEnabled() {
        return getStore().getBoolean(PreferenceConstants.SKILL_ENABLED);
    }

    /**
     * 获取 Skill 目录路径。
     * 如果用户未配置(空字符串),则返回默认路径:
     * <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/skills
     */
    public static String getSkillDirectory() {
        String configured = getStore().getString(PreferenceConstants.SKILL_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        // 动态计算默认路径
        return getDefaultSkillDirectory();
    }

    /**
     * 计算默认 Skill 目录路径(公开方法,供 UI 显示默认值使用)。
     */
    public static String getDefaultSkillDirectory() {
        try {
            // 通过 Platform 获取工作区根路径
            org.eclipse.core.resources.IWorkspace workspace =
                    org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
            if (workspace != null && workspace.getRoot() != null) {
                org.eclipse.core.runtime.IPath workspacePath = workspace.getRoot().getLocation();
                if (workspacePath != null) {
                    java.io.File skillDir = new java.io.File(
                            workspacePath.toFile(),
                            ".metadata/.plugins/" + com.sap.abap.ai.completion.Activator.PLUGIN_ID + "/skills");
                    return skillDir.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            // fallback
        }
        // 回退到用户主目录
        String userHome = System.getProperty("user.home");
        return new java.io.File(userHome, ".abap-ai-completion/skills").getAbsolutePath();
    }

    /**
     * 确保 Skill 目录存在(如果未配置则创建默认目录)。
     */
    public static void ensureSkillDirectoryExists() {
        String skillDirPath = getSkillDirectory();
        if (skillDirPath != null && !skillDirPath.trim().isEmpty()) {
            java.io.File skillDir = new java.io.File(skillDirPath);
            if (!skillDir.exists()) {
                try {
                    skillDir.mkdirs();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    public static String getSystemPrompt() {
        return getStore().getString(PreferenceConstants.SYSTEM_PROMPT);
    }

    /**
     * 加载 Skill 内容(无类型筛选,加载所有 skill)。
     */
    public static String loadSkillContents() {
        return loadSkillContents(null);
    }

    /**
     * 加载 Skill 内容,按代码类型筛选。
     *
     * 规则:
     * 1. 若只有一个子目录 → 直接使用该目录(不筛选类型)
     * 2. 若有多个子目录 → 按代码类型筛选匹配的目录
     * 3. 若无子目录(只有文件) → 读取当前目录下的 SKILL.md 作为 Skill
     *
     * @param codeType 代码类型,如 "ABAP"、"CDS"、"AI" 等;
     *                 为 null 时不筛选,加载所有 skill
     */
    public static String loadSkillContents(String codeType) {
        // 若未启用 Skill 功能,直接返回空
        if (!isSkillEnabled()) {
            return "";
        }

        String dirPath = getSkillDirectory();
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return "";
        }

        File skillDir = new File(dirPath);
        if (!skillDir.exists() || !skillDir.isDirectory()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 查找所有子目录(每个子目录为一个 skill)
        File[] subDirs = skillDir.listFiles(File::isDirectory);

        if (subDirs != null && subDirs.length == 1) {
            // 只有一个子目录 → 直接使用,无需筛选
            loadSkillFromDirectory(subDirs[0], sb);
        } else if (subDirs != null && subDirs.length > 1) {
            // 多个子目录 → 按代码类型筛选匹配的目录
            for (File subDir : subDirs) {
                if (!matchesCodeType(subDir.getName(), codeType)) {
                    continue;
                }
                loadSkillFromDirectory(subDir, sb);
            }
        } else {
            // 无子目录(只有文件) → 读取当前目录下的 SKILL.md 作为 Skill
            loadSkillFromRootDirectory(skillDir, sb);
        }

        return sb.toString();
    }

    /**
     * 从根目录加载 Skill(无子目录时使用)。
     * 优先读取 SKILL.md,然后读取其他参考文件。
     */
    private static void loadSkillFromRootDirectory(File skillDir, StringBuilder sb) {
        // 1. 优先读取 SKILL.md
        File skillMd = new File(skillDir, "SKILL.md");
        if (skillMd.exists() && skillMd.isFile()) {
            try {
                String content = new String(
                        java.nio.file.Files.readAllBytes(skillMd.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                sb.append("=== Skill: ").append(skillDir.getName()).append(" ===\n");
                sb.append("--- SKILL.md ---\n");
                sb.append(content).append("\n");

                // 2. 同目录下的其他参考文件
                File[] refFiles = skillDir.listFiles((d, name) -> {
                    if (name.equalsIgnoreCase("SKILL.md")) return false;
                    String lower = name.toLowerCase();
                    return lower.endsWith(".abap")
                            || lower.endsWith(".txt")
                            || lower.endsWith(".skill")
                            || lower.endsWith(".md")
                            || lower.endsWith(".xml")
                            || lower.endsWith(".json")
                            || lower.endsWith(".sql");
                });
                if (refFiles != null) {
                    for (File f : refFiles) {
                        try {
                            String c = new String(
                                    java.nio.file.Files.readAllBytes(f.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            sb.append("--- ").append(f.getName()).append(" ---\n");
                            sb.append(c).append("\n");
                        } catch (Exception e) {
                            // skip
                        }
                    }
                }
            } catch (Exception e) {
                // skip
            }
        } else {
            // 无 SKILL.md → 回退到扁平文件模式
            loadSkillsFromFlatFiles(skillDir, sb);
        }
    }

    /**
     * 判断 skill 目录名称是否匹配代码类型。
     * 匹配规则: 目录名称(大写)包含代码类型关键字(大写)。
     *
     * @param skillDirName skill 目录名称
     * @param codeType     代码类型(如 "ABAP"、"CDS"),为 null 时返回 true
     */
    private static boolean matchesCodeType(String skillDirName, String codeType) {
        if (codeType == null || codeType.trim().isEmpty()) {
            return true; // 无筛选,加载所有
        }
        if (skillDirName == null) {
            return false;
        }
        String upperDir = skillDirName.toUpperCase();
        String upperType = codeType.toUpperCase();

        // 特殊映射: "ABAP" 类型也匹配 "CLEAN-ABAP" 等包含 ABAP 的目录
        // "CDS" 类型匹配包含 "CDS" 的目录
        return upperDir.contains(upperType);
    }

    /**
     * 从单个 skill 子目录加载内容。
     * 优先读取 SKILL.md 作为主描述,然后读取其他参考文件。
     */
    private static void loadSkillFromDirectory(File skillDir, StringBuilder sb) {
        String skillName = skillDir.getName();
        sb.append("=== Skill: ").append(skillName).append(" ===\n");

        // 1. 优先读取 SKILL.md (标准 SKILL 描述文件)
        File skillMd = new File(skillDir, "SKILL.md");
        if (skillMd.exists() && skillMd.isFile()) {
            try {
                String content = new String(
                        java.nio.file.Files.readAllBytes(skillMd.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                sb.append("--- SKILL.md ---\n");
                sb.append(content).append("\n");
            } catch (Exception e) {
                // skip
            }
        }

        // 2. 读取子目录下的所有参考文件(排除 SKILL.md 本身)
        File[] refFiles = skillDir.listFiles((d, name) -> {
            if (name.equalsIgnoreCase("SKILL.md")) return false;
            String lower = name.toLowerCase();
            return lower.endsWith(".abap")
                    || lower.endsWith(".txt")
                    || lower.endsWith(".skill")
                    || lower.endsWith(".md")
                    || lower.endsWith(".xml")
                    || lower.endsWith(".json")
                    || lower.endsWith(".sql");
        });

        if (refFiles != null) {
            for (File f : refFiles) {
                try {
                    String content = new String(
                            java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    sb.append("--- ").append(f.getName()).append(" ---\n");
                    sb.append(content).append("\n");
                } catch (Exception e) {
                    // skip unreadable files
                }
            }
        }

        sb.append("\n");
    }

    /**
     * 扁平文件模式(兼容旧结构): 直接从 skill 目录读取文件。
     */
    private static void loadSkillsFromFlatFiles(File skillDir, StringBuilder sb) {
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
    }

    // === Parent Program Resolution ===

    public static boolean isParentProgramResolutionEnabled() {
        return getStore().getBoolean(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED);
    }

    public static int getAbapSearchDepth() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.ABAP_SEARCH_DEPTH));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_ABAP_SEARCH_DEPTH);
        }
    }

    public static int getMaxContextChars() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.MAX_CONTEXT_CHARS));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_MAX_CONTEXT_CHARS);
        }
    }

    // === Workspace Code Reference ===

    public static boolean isWorkspaceCodeReferenceEnabled() {
        return getStore().getBoolean(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED);
    }

    public static int getMaxWorkspaceCodeChars() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.MAX_WORKSPACE_CODE_CHARS));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_MAX_WORKSPACE_CODE_CHARS);
        }
    }

    public static int getWorkspaceCodeFileLimit() {
        try {
            return Integer.parseInt(getStore().getString(PreferenceConstants.WORKSPACE_CODE_FILE_LIMIT));
        } catch (NumberFormatException e) {
            return Integer.parseInt(PreferenceConstants.DEFAULT_WORKSPACE_CODE_FILE_LIMIT);
        }
    }

    // === Interface Logging ===

    public static boolean isInterfaceLoggingEnabled() {
        return getStore().getBoolean(PreferenceConstants.INTERFACE_LOGGING_ENABLED);
    }

    // === Prompt Cache ===

    /**
     * 是否启用 Prompt Cache（节点2、3缓存）。
     * 启用后，第一次调用完整传入节点2、3内容并建立缓存，
     * 后续调用（内容未变）仅传简短占位符，大幅减少 TOKEN。
     */
    public static boolean isPromptCacheEnabled() {
        return getStore().getBoolean(PreferenceConstants.PROMPT_CACHE_ENABLED);
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
