package com.sap.abap.ai.completion.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

import com.sap.abap.ai.completion.Activator;

/**
 * Represents a single AI connection configuration entry.
 */
public class AIConnectionEntry {

    public String name;
    public String baseUrl;
    public String model;
    public String apiKey;
    public String maxTokens;
    public String temperature;
    public boolean isDefault;

    public AIConnectionEntry() {
        this.name = "";
        this.baseUrl = PreferenceConstants.DEFAULT_API_BASE_URL;
        this.model = PreferenceConstants.DEFAULT_API_MODEL;
        this.apiKey = "";
        this.maxTokens = PreferenceConstants.DEFAULT_MAX_TOKENS;
        this.temperature = PreferenceConstants.DEFAULT_TEMPERATURE;
        this.isDefault = false;
    }

    public AIConnectionEntry(String name, String baseUrl, String model, String apiKey,
                             String maxTokens, String temperature, boolean isDefault) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.isDefault = isDefault;
    }

    /**
     * 从偏好存储中加载所有连接条目。
     * 若无已保存列表，则返回包含一个默认空条目的列表（向后兼容）。
     */
    public static List<AIConnectionEntry> loadAll(IPreferenceStore store) {
        String json = store.getString(PreferenceConstants.AI_CONNECTIONS);
        if (json == null || json.trim().isEmpty()) {
            // 向后兼容：从旧的单连接字段迁移一条默认条目
            AIConnectionEntry defaultEntry = new AIConnectionEntry();
            defaultEntry.name = "Default";
            defaultEntry.baseUrl = store.getString(PreferenceConstants.API_BASE_URL);
            defaultEntry.model = store.getString(PreferenceConstants.API_MODEL);
            defaultEntry.apiKey = store.getString(PreferenceConstants.API_KEY);
            defaultEntry.maxTokens = store.getString(PreferenceConstants.MAX_TOKENS);
            defaultEntry.temperature = store.getString(PreferenceConstants.TEMPERATURE);
            defaultEntry.isDefault = true;
            List<AIConnectionEntry> list = new ArrayList<>();
            list.add(defaultEntry);
            saveAll(list, store);
            return list;
        }
        return parseConnections(json);
    }

    /**
     * 将所有连接条目保存至偏好存储（JSON 格式）。
     */
    public static void saveAll(List<AIConnectionEntry> entries, IPreferenceStore store) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            AIConnectionEntry e = entries.get(i);
            sb.append("{").append("\"name\":")
              .append(escapeJson(e.name)).append(",")
              .append("\"baseUrl\":")
              .append(escapeJson(e.baseUrl)).append(",")
              .append("\"model\":")
              .append(escapeJson(e.model)).append(",")
              .append("\"apiKey\":")
              .append(escapeJson(e.apiKey)).append(",")
              .append("\"maxTokens\":")
              .append(escapeJson(e.maxTokens)).append(",")
              .append("\"temperature\":")
              .append(escapeJson(e.temperature)).append(",")
              .append("\"isDefault\":").append(e.isDefault)
              .append("}");
        }
        sb.append("]");
        store.setValue(PreferenceConstants.AI_CONNECTIONS, sb.toString());
        // 同步更新默认连接名
        String defaultName = findDefault(entries);
        store.setValue(PreferenceConstants.AI_DEFAULT_CONNECTION_NAME,
                defaultName != null ? defaultName : "");
    }

    /**
     * 返回当前默认连接的名称；若列表中无默认条目，返回第一个条目的名称。
     */
    public static String findDefault(List<AIConnectionEntry> entries) {
        if (entries == null) return null;
        for (AIConnectionEntry e : entries) {
            if (e.isDefault) return e.name;
        }
        return entries.isEmpty() ? null : entries.get(0).name;
    }

    /**
     * 根据名称查找条目（用于恢复选中的编辑状态）。
     */
    public static AIConnectionEntry findBy_name(List<AIConnectionEntry> entries, String name) {
        if (entries == null || name == null) return null;
        for (AIConnectionEntry e : entries) {
            if (name.equals(e.name)) return e;
        }
        return null;
    }

    private static List<AIConnectionEntry> parseConnections(String json) {
        List<AIConnectionEntry> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        try {
            json = json.trim();
            if (!json.startsWith("[")) return list;
            if (!json.endsWith("]")) return list;
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return list;

            // 简单手动解析 JSON 数组（每项为 { ... }）
            int depth = 0;
            int start = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String item = json.substring(start, i + 1);
                        list.add(parseEntry(item));
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败返回空列表
        }
        return list;
    }

    private static AIConnectionEntry parseEntry(String item) {
        AIConnectionEntry e = new AIConnectionEntry();
        e.name = extractString(item, "name");
        e.baseUrl = extractString(item, "baseUrl");
        e.model = extractString(item, "model");
        e.apiKey = extractString(item, "apiKey");
        e.maxTokens = extractString(item, "maxTokens");
        e.temperature = extractString(item, "temperature");
        e.isDefault = extractBoolean(item, "isDefault");
        return e;
    }

    private static String extractString(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return "";
        int valStart = idx + searchKey.length();
        // skip whitespace
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length()) return "";
        char c = json.charAt(valStart);
        if (c == '"') {
            // 字符串值
            int end = findStringEnd(json, valStart + 1);
            if (end < 0) return "";
            return unescapeJson(json.substring(valStart + 1, end));
        } else {
            // 非字符串（数字/布尔等），继续查找下一个逗号或}
            int end = json.indexOf(',', valStart);
            if (end < 0) end = json.indexOf('}', valStart);
            return json.substring(valStart, end).trim();
        }
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
