package com.sap.abap.ai.completion.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * 单个自动补全"触发字符"配置项。
 *
 * <p>触发字符由三列组成：</p>
 * <ol>
 *   <li><b>触发字符</b>(triggerChar)：真实的触发内容，可包含空格，但<em>不能全是空格</em>。</li>
 *   <li><b>是否激活</b>(enabled)：仅在激活时该触发字符才有效。</li>
 *   <li><b>功能描述</b>(description)：对该触发字符用途的说明。</li>
 * </ol>
 *
 * <p>所有触发字符以 JSON 数组形式持久化到偏好存储，key 见
 * {@link PreferenceConstants#AUTO_COMPLETE_TRIGGER_CHARS}。</p>
 */
public class TriggerChar {

    /** 触发字符（可含空格，但不能全是空格） */
    public String triggerChar;
    /** 是否激活 */
    public boolean enabled;
    /** 功能描述 */
    public String description;

    public TriggerChar() {
        this("", true, "");
    }

    public TriggerChar(String triggerChar, boolean enabled, String description) {
        this.triggerChar = triggerChar == null ? "" : triggerChar;
        this.enabled = enabled;
        this.description = description == null ? "" : description;
    }

    /**
     * 返回触发字符的默认列表。
     * 覆盖 ABAP 结构导航、语句终止符、赋值/比较、算术/字符串运算及特定上下文关键字等场景。
     */
    public static List<TriggerChar> defaults() {
        List<TriggerChar> list = new ArrayList<>();

        // 2.1 结构导航与组件访问符
        list.add(new TriggerChar("->", true, Messages.get("trigger.default.1")));
        list.add(new TriggerChar("=>", true, Messages.get("trigger.default.2")));
        list.add(new TriggerChar("~", true, Messages.get("trigger.default.3")));

        // 2.2 语句与语法终止符
        list.add(new TriggerChar(":", true, Messages.get("trigger.default.4")));
        list.add(new TriggerChar("(", true, Messages.get("trigger.default.5")));
        list.add(new TriggerChar("[", true, Messages.get("trigger.default.6")));
        list.add(new TriggerChar(",", true, Messages.get("trigger.default.7")));
        list.add(new TriggerChar("\"", true, Messages.get("trigger.default.8")));

        // 2.3 赋值与比较运算符（带空格，光标在空格后时触发）
        list.add(new TriggerChar("= ", true, Messages.get("trigger.default.9")));
        list.add(new TriggerChar("?= ", true, Messages.get("trigger.default.10")));
        list.add(new TriggerChar("<> ", true, Messages.get("trigger.default.11")));
        list.add(new TriggerChar("< ", true, Messages.get("trigger.default.12")));
        list.add(new TriggerChar("> ", true, Messages.get("trigger.default.13")));
        list.add(new TriggerChar("<= ", true, Messages.get("trigger.default.14")));
        list.add(new TriggerChar(">= ", true, Messages.get("trigger.default.15")));

        // 2.4 算术与字符串运算符（带空格，光标在空格后时触发）
        list.add(new TriggerChar("+ ", true, Messages.get("trigger.default.16")));
        list.add(new TriggerChar("- ", true, Messages.get("trigger.default.17")));
        list.add(new TriggerChar("* ", true, Messages.get("trigger.default.18")));
        list.add(new TriggerChar("/ ", true, Messages.get("trigger.default.19")));
        list.add(new TriggerChar("&& ", true, Messages.get("trigger.default.20")));

        // 2.5 特定上下文关键字（后需带空格的触发词）
        list.add(new TriggerChar("DATA ", true, Messages.get("trigger.default.21")));
        list.add(new TriggerChar("@DATA(", true, Messages.get("trigger.default.22")));
        list.add(new TriggerChar("TYPE ", true, Messages.get("trigger.default.23")));
        list.add(new TriggerChar("VALUE ", true, Messages.get("trigger.default.24")));
        list.add(new TriggerChar("CORRESPONDING ", true, Messages.get("trigger.default.25")));
        list.add(new TriggerChar("NEW ", true, Messages.get("trigger.default.26")));
        list.add(new TriggerChar("CONV ", true, Messages.get("trigger.default.27")));
        list.add(new TriggerChar("SELECT ", true, Messages.get("trigger.default.28")));
        list.add(new TriggerChar("FROM ", true, Messages.get("trigger.default.29")));
        list.add(new TriggerChar("WHERE ", true, Messages.get("trigger.default.30")));

        return list;
    }

    /**
     * 返回默认触发字符列表的 JSON 字符串（用于偏好默认值）。
     */
    public static String defaultsJson() {
        return toJson(defaults());
    }

    /**
     * 从偏好存储加载触发字符列表。
     * 若未保存（或解析失败/为空），返回默认列表。
     */
    public static List<TriggerChar> loadAll(IPreferenceStore store) {
        if (store == null) return defaults();
        String json = store.getString(PreferenceConstants.AUTO_COMPLETE_TRIGGER_CHARS);
        if (json == null || json.trim().isEmpty()) {
            return defaults();
        }
        List<TriggerChar> parsed = parseList(json);
        return parsed.isEmpty() ? defaults() : parsed;
    }

    /**
     * 将触发字符列表保存到偏好存储（JSON 格式）。
     */
    public static void saveAll(List<TriggerChar> list, IPreferenceStore store) {
        if (store == null) return;
        store.setValue(PreferenceConstants.AUTO_COMPLETE_TRIGGER_CHARS, toJson(list));
    }

    /**
     * 按当前语言的默认值刷新 trigger char 描述。
     * 仅更新那些 {@link #triggerChar} 匹配内置默认的条目；用户自行添加的
     * 条目（triggerChar 不在 defaults() 中）保持描述不变。
     * 调用前需确保 Java ResourceBundle 缓存已清除、Locale 已切换到新语言
     * （否则 defaults() 返回的仍是旧语言描述）。
     */
    public static void refreshDefaultDescriptions(List<TriggerChar> current) {
        if (current == null || current.isEmpty()) return;
        List<TriggerChar> newDefaults = defaults();
        for (TriggerChar entry : current) {
            for (TriggerChar def : newDefaults) {
                if (def.triggerChar.equals(entry.triggerChar)) {
                    entry.description = def.description;
                    break;
                }
            }
        }
    }

    /**
     * 将列表序列化为 JSON 数组字符串。
     */
    public static String toJson(List<TriggerChar> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            TriggerChar t = list.get(i);
            sb.append("{")
              .append("\"char\":").append(escapeJson(t.triggerChar)).append(",")
              .append("\"enabled\":").append(t.enabled).append(",")
              .append("\"description\":").append(escapeJson(t.description))
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 解析 JSON 数组字符串为触发字符列表。
     */
    public static List<TriggerChar> parseList(String json) {
        List<TriggerChar> list = new ArrayList<>();
        if (json == null) return list;
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return list;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return list;

        int depth = 0;
        int start = 0;
        try {
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String item = json.substring(start, i + 1);
                        list.add(parseItem(item));
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败返回已解析的部分
        }
        return list;
    }

    private static TriggerChar parseItem(String item) {
        TriggerChar t = new TriggerChar();
        t.triggerChar = extractString(item, "char");
        t.description = extractString(item, "description");
        t.enabled = extractBoolean(item, "enabled");
        return t;
    }

    private static String extractString(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return "";
        int valStart = idx + searchKey.length();
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length() || json.charAt(valStart) != '"') return "";
        int end = findStringEnd(json, valStart + 1);
        return end < 0 ? "" : unescapeJson(json.substring(valStart + 1, end));
    }

    private static boolean extractBoolean(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return false;
        int valStart = idx + searchKey.length();
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        return json.startsWith("true", valStart);
    }

    private static int findStringEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'b': sb.append('\b'); i++; break;
                    case 'f': sb.append('\f'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
