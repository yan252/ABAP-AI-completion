package com.sap.abap.ai.completion.parser;

/**
 * Token 控制: 保留 ABAP 结构行(声明/签名/段落标记及注释),
 * 截断实现细节(LOOP/SELECT/WRITE/IF 等)。
 *
 * 策略:
 *   1. 源码长度 <= maxChars,直接返回。
 *   2. 否则遍历每行,仅保留结构行,累计到 maxChars 停止。
 *   3. 在 FORM/FUNCTION/METHOD 等子过程内部,仅保留输入输出参数、签名行与注释,
 *      过滤局部变量声明(DATA/TYPES/FIELD-SYMBOLS/DEFINE 等),进一步节省 Token。
 *   4. 若没有结构行(纯实现代码),退化为头部截断。
 *   5. 截断后追加 "... [truncated]" 标记。
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
        // 标记当前是否位于某个子过程(FORM/FUNCTION/METHOD)内部。
        // 子过程内部的局部变量声明(DATA/TYPES/FIELD-SYMBOLS/DEFINE 等)不保留,
        // 仅保留输入输出参数、签名行与注释,以节省 Token。
        boolean inSubroutine = false;
        for (String line : lines) {
            String stripped = line.trim();
            String strippedUpper = stripped.toUpperCase();

            if (isSubroutineEnd(strippedUpper)) {
                // 保留子过程的结束标记,便于 AI 理解作用域
                if (kept.length() >= maxChars) break;
                kept.append(line).append("\n");
                inSubroutine = false;
                continue;
            }

            if (inSubroutine) {
                // 子过程内部: 只保留输入输出参数、签名关键字行及注释
                if (isComment(stripped) || isSubroutineStart(strippedUpper)
                        || isSubroutineSignatureLine(strippedUpper)) {
                    if (kept.length() >= maxChars) break;
                    kept.append(line).append("\n");
                }
                // 其余行(局部变量声明、实现代码等)一律丢弃
                continue;
            }

            // 更新子过程进出状态: 进入子过程前,其签名行本身也要保留
            if (isSubroutineStart(strippedUpper)) {
                inSubroutine = true;
                if (isComment(stripped) || isStructuralLine(strippedUpper)) {
                    if (kept.length() >= maxChars) break;
                    kept.append(line).append("\n");
                }
                continue;
            }

            if (isStructuralLine(strippedUpper) || isComment(stripped)) {
                if (kept.length() >= maxChars) break;
                kept.append(line).append("\n");
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
     * 判断一行是否为注释行。
     * ABAP 中整行注释以 * 或 " 开头。
     */
    static boolean isComment(String stripped) {
        if (stripped.isEmpty()) return false;
        return stripped.charAt(0) == '*'
                || stripped.charAt(0) == '"'
                || stripped.startsWith("\"!");
    }

    /**
     * 判断一行是否为"子过程开始"标记。
     * 即 FORM / FUNCTION / METHOD 的签名起始行。
     */
    static boolean isSubroutineStart(String strippedUpper) {
        if (strippedUpper == null || strippedUpper.isEmpty()) return false;
        return strippedUpper.startsWith("FORM ")
                || strippedUpper.startsWith("FUNCTION ")
                || strippedUpper.startsWith("METHOD ");
    }

    /**
     * 判断一行是否为"子过程结束"标记。
     */
    static boolean isSubroutineEnd(String strippedUpper) {
        if (strippedUpper == null || strippedUpper.isEmpty()) return false;
        if (strippedUpper.startsWith("ENDFORM")) return true;
        if (strippedUpper.startsWith("ENDFUNCTION")) return true;
        if (strippedUpper.startsWith("ENDMETHOD")) return true;
        return false;
    }

    /**
     * 判断一行(已 trim 并大写化)是否为子过程内部应保留的"参数/签名"行:
     *   - 输入输出参数关键字行: USING / IMPORTING / EXPORTING / CHANGING / TABLES /
     *     RECEIVING / VALUE(...)
     *   - 参数定义行: 如 "STYLE TYPE STRING"、"IT_STYLES TYPE LVC_T_STYL"
     *
     * 局部变量声明(以 DATA:/TYPES:/FIELD-SYMBOLS: 等开头)不会命中上面的模式,
     * 故在子过程内部会被过滤,仅保留输入输出参数。
     */
    static boolean isSubroutineSignatureLine(String strippedUpper) {
        if (strippedUpper == null || strippedUpper.isEmpty()) return false;
        // 参数关键字后必须紧跟空白(如 "USING x y"),避免误匹配 "TABLES:" 这类局部表声明
        if (strippedUpper.matches("^(USING|IMPORTING|EXPORTING|CHANGING|TABLES|RECEIVING)(?=\\s).*")) return true;
        if (strippedUpper.startsWith("VALUE(")) return true;
        if (strippedUpper.matches("^\\w+\\s+(TYPE|LIKE|STRUCTURE)\\s.*")) return true;
        return false;
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
        if (strippedUpper.startsWith("RANGES ") || strippedUpper.startsWith("RANGES:")) return true;
        if (strippedUpper.startsWith("CLASS-DATA ") || strippedUpper.startsWith("CLASS-DATA:")) return true;
        if (strippedUpper.startsWith("INSTANCE-DATA ") || strippedUpper.startsWith("INSTANCE-DATA:")) return true;
        if (strippedUpper.startsWith("DEFINE ")) return true;

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
        if (strippedUpper.startsWith("METHOD ")) return true;
        if (strippedUpper.startsWith("FORM ")) return true;
        // 保留 FORM/METHOD 内的参数声明关键字行
        if (strippedUpper.matches("^(USING|IMPORTING|EXPORTING|CHANGING|TABLES|RECEIVING|VALUE\\().*")) return true;
        // 保留像 C_MESSAGE TYPE C / I_PERCENT TYPE I 这样的参数定义行
        if (strippedUpper.matches("^\\w+\\s+(TYPE|LIKE|STRUCTURE)\\s.*")) return true;
        if (strippedUpper.startsWith("DATA ") || strippedUpper.startsWith("DATA:")) return true;

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
        if (strippedUpper.startsWith("END-OF-DEFINITION")) return true;

        // === INCLUDE 语句(用于追溯调用关系) ===
        if (strippedUpper.startsWith("INCLUDE ")) return true;

        return false;
    }
}
