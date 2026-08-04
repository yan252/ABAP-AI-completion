package com.sap.abap.ai.completion.parser;

/**
 * Token 控制: 保留 ABAP 结构行(声明/签名/段落标记),
 * 截断实现细节(LOOP/SELECT/WRITE/IF 等)。
 *
 * 策略:
 *   1. 源码长度 <= maxChars,直接返回。
 *   2. 否则遍历每行,仅保留结构行,累计到 maxChars 停止。
 *   3. 若没有结构行(纯实现代码),退化为头部截断。
 *   4. 截断后追加 "... [truncated]" 标记。
 *
 * 纯函数,无状态,便于单元测试。
 */
public final class AbapCodeTruncator {

    /** 默认每个上级程序的最大字符数 */
    public static final int DEFAULT_MAX_CHARS = 8000;

    /** 截断标记 */
    private static final String TRUNCATED_MARKER = "\n... [truncated, implementation details omitted]\n";

    private AbapCodeTruncator() {
    }

    /**
     * 智能截断: 优先保留结构行。
     *
     * @param source   原始源码
     * @param maxChars 最大字符数
     * @return 截断后的源码
     */
    public static String truncate(String source, int maxChars) {
        if (source == null || source.isEmpty()) return source;
        if (maxChars <= 0) return "";
        if (source.length() <= maxChars) return source;

        StringBuilder kept = new StringBuilder(maxChars + 64);
        String[] lines = source.split("\n", -1);
        for (String line : lines) {
            String strippedUpper = line.trim().toUpperCase();
            if (isStructuralLine(strippedUpper)) {
                kept.append(line).append("\n");
                if (kept.length() >= maxChars) break;
            }
        }

        if (kept.length() == 0) {
            // 无结构行(纯实现代码): 退化为头部截断
            int cut = Math.min(maxChars, source.length());
            return source.substring(0, cut) + TRUNCATED_MARKER;
        }

        if (kept.length() > maxChars) {
            kept = new StringBuilder(kept.substring(0, maxChars));
        }
        kept.append(TRUNCATED_MARKER);
        return kept.toString();
    }

    /**
     * 判断一行(已 trim 并大写化)是否为"结构行":
     * 声明、类/方法/FORM 签名、段落标记、INCLUDE 语句。
     *
     * 这些行能让 AI 推断数据结构、调用约定、段落布局,
     * 而无需消耗 Token 在具体实现细节上。
     */
    static boolean isStructuralLine(String strippedUpper) {
        if (strippedUpper == null || strippedUpper.isEmpty()) return false;

        // === 声明 ===
        if (strippedUpper.startsWith("DATA ") || strippedUpper.startsWith("DATA:")) return true;
        if (strippedUpper.startsWith("TYPES ") || strippedUpper.startsWith("TYPES:")) return true;
        if (strippedUpper.startsWith("CONSTANTS ") || strippedUpper.startsWith("CONSTANTS:")) return true;
        if (strippedUpper.startsWith("TABLES:") || strippedUpper.startsWith("TABLES ")) return true;
        if (strippedUpper.startsWith("PARAMETERS") || strippedUpper.startsWith("SELECT-OPTIONS")) return true;
        if (strippedUpper.startsWith("FIELD-SYMBOLS:") || strippedUpper.startsWith("FIELD-SYMBOLS ")) return true;
        if (strippedUpper.startsWith("TYPE-POOLS:") || strippedUpper.startsWith("TYPE-POOLS ")) return true;
        if (strippedUpper.startsWith("STATICS ") || strippedUpper.startsWith("STATICS:")) return true;

        // === 类/方法/FORM 签名 ===
        if (strippedUpper.startsWith("CLASS ")) {
            return strippedUpper.contains("DEFINITION") || strippedUpper.contains("SECTION");
        }
        if (strippedUpper.equals("PUBLIC SECTION")
                || strippedUpper.equals("PROTECTED SECTION")
                || strippedUpper.equals("PRIVATE SECTION")) {
            return true;
        }
        if (strippedUpper.startsWith("METHODS ") || strippedUpper.startsWith("METHODS:")) return true;
        if (strippedUpper.startsWith("METHOD ") && !strippedUpper.endsWith(".")) return true;
        if (strippedUpper.startsWith("FORM ") && !strippedUpper.endsWith(".")) return true;

        // === 段落标记 ===
        if (strippedUpper.startsWith("REPORT ") || strippedUpper.startsWith("PROGRAM ")) return true;
        if (strippedUpper.startsWith("START-OF-SELECTION")) return true;
        if (strippedUpper.startsWith("INITIALIZATION")) return true;
        if (strippedUpper.startsWith("AT SELECTION-SCREEN")) return true;
        if (strippedUpper.startsWith("END-OF-SELECTION")) return true;
        if (strippedUpper.startsWith("LOAD-OF-PROGRAM")) return true;
        if (strippedUpper.startsWith("AT USER-COMMAND")) return true;
        if (strippedUpper.startsWith("TOP-OF-PAGE")) return true;
        if (strippedUpper.startsWith("END-OF-PAGE")) return true;

        // === 结构结束标记(便于 AI 理解作用域) ===
        if (strippedUpper.startsWith("ENDCLASS")) return true;
        if (strippedUpper.startsWith("ENDMETHOD")) return true;
        if (strippedUpper.startsWith("ENDFORM")) return true;
        if (strippedUpper.startsWith("ENDINTERFACE")) return true;

        // === INCLUDE 语句(用于追溯调用关系) ===
        if (strippedUpper.startsWith("INCLUDE ")) return true;

        return false;
    }
}
