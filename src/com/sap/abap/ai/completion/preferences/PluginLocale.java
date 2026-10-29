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

    /** 插件语言是否以中文为目标（决定写入 eclipse.ini 的 -nl 值）。 */
    public static String nlCode(String lang) {
        return PreferenceConstants.LANG_ZH.equals(lang) ? "zh_CN" : "en";
    }

    /**
     * 把插件语言写入 eclipse.ini 的 {@code -nl} 项，使下一次启动时 Equinox
     * 用正确语言解析 plugin.xml 中 {@code %prefpage.*} 本地化标签
     * （即首选项树里的配置页名称）。
     *
     * <p>eclipse.ini 中的程序参数在 Eclipse launcher 启动阶段、早于 OSGi/Equinox
     * 初始化时读取；不写的话，手工冷启动会按操作系统语言解析并缓存这些标签，
     * 与插件语言偏好不一致（表现为"设置英文、配置页名称却是中文"）。
     * ini 不存在或不可写时静默跳过。</p>
     */
    public static void syncEclipseIniNl(String lang) {
        try {
            String launcher = System.getProperty("eclipse.launcher");
            if (launcher == null || launcher.isEmpty()) return;
            java.io.File exe = new java.io.File(launcher);
            String baseName = exe.getName().replaceFirst("(?i)\\.exe$", "");
            java.io.File ini = new java.io.File(exe.getParentFile(), baseName + ".ini");
            if (!ini.exists() || !ini.canWrite()) return;

            String nlCode = nlCode(lang);
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    ini.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            int vmargsIdx = -1, nlIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if ("-vmargs".equals(t)) { vmargsIdx = i; break; }
                if ("-nl".equals(t)) nlIdx = i;
            }
            if (nlIdx >= 0) {
                if (nlIdx + 1 < lines.size()
                        && (vmargsIdx < 0 || nlIdx + 1 < vmargsIdx)) {
                    if (nlCode.equals(lines.get(nlIdx + 1).trim())) return;   // 无需改动
                    lines.set(nlIdx + 1, nlCode);
                } else {
                    lines.add(nlIdx + 1, nlCode);
                }
            } else {
                int insertAt = vmargsIdx >= 0 ? vmargsIdx : lines.size();
                lines.add(insertAt, nlCode);
                lines.add(insertAt, "-nl");
            }
            java.nio.file.Files.write(ini.toPath(), lines,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ini 不可写等场景：不影响插件运行
        }
    }
}
