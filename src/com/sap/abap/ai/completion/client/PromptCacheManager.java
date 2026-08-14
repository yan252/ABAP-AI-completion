package com.sap.abap.ai.completion.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import org.eclipse.core.runtime.Platform;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.logging.AILogger;

/**
 * Prompt Cache 管理器（三节点独立缓存版）。
 *
 * 为节点1（SKILL）、节点2（深度搜索相关程序）、节点3（工作区打开程序）分别维护
 * 独立的缓存键，配合 AI 服务的 prompt_cache_keys / prompt_cache_breakpoint 机制
 * 减少重复传输的 TOKEN 量。
 *
 * 工作流程:
 *   1. 预热阶段（第一次补全前）: 最多3次独立调用 AI，每次只传一个节点的完整内容，
 *      分别建立3个独立缓存，得到3个 cacheKey。
 *   2. 正式补全阶段: 一次请求中携带3个 cacheKey，3个节点传简短占位符，
 *      AI 服务端根据各 cacheKey 复用对应缓存内容。
 *   3. 内容变化时: 对应节点的 cacheKey 改变，自动触发该节点重新预热。
 *   4. 日期变化（跨天）: 所有 cacheKey 改变，全部重新预热。
 *
 * 持久化: 3个缓存键持久化到插件 state area 的文件中，
 *        确保 Eclipse 重启后仍能识别已建立的缓存。
 *
 * 线程安全: 使用 volatile + synchronized 保护缓存状态。
 */
public class PromptCacheManager {

    private static final PromptCacheManager INSTANCE = new PromptCacheManager();

    /** 缓存键前缀 */
    private static final String CACHE_KEY_PREFIX = "abap-pc-";
    private static final String SKILL_PREFIX = "sk-";
    private static final String PARENT_PREFIX = "pa-";
    private static final String WORKSPACE_PREFIX = "ws-";

    /** 缓存状态文件名 */
    private static final String CACHE_STATE_FILE = "prompt_cache_state.txt";

    /** 文件内部分隔符 */
    private static final String SECTION_SEP = "===SECTION===";

    /** 节点1缓存：SKILL 内容长度阈值，超过此值才启用缓存 */
    private static final int SKILL_CACHE_THRESHOLD = 100;

    // ==================== 会话 ID ====================

    /**
     * 每次 Eclipse 启动时生成新的会话 ID。
     * 持久化时记录到文件，重启后与新 sessionId 比较，
     * 不同则废弃旧缓存状态，强制重新预热。
     */
    private final String currentSessionId;

    /** 从磁盘读取的上次会话 ID */
    private volatile String loadedSessionId = null;

    // ==================== 三个独立缓存键 ====================

    /** 节点1（SKILL）已建立的缓存键 */
    private volatile String skillCachedKey = null;
    /** 节点2（父级程序）已建立的缓存键 */
    private volatile String parentCachedKey = null;
    /** 节点3（工作区程序）已建立的缓存键 */
    private volatile String workspaceCachedKey = null;

    /** 统计 */
    private volatile int cacheHitCount = 0;
    private volatile int cacheMissCount = 0;
    private volatile int warmupCount = 0;

    private PromptCacheManager() {
        // 生成当前会话 ID（基于启动时间 + 随机数，每次重启都不同）
        currentSessionId = Long.toHexString(System.currentTimeMillis())
                + "-" + Integer.toHexString((int)(Math.random() * 0xFFFFFF));
        loadCacheState();
        // 如果会话 ID 不同（即 Eclipse 重启了），废弃旧缓存，强制重新预热
        if (loadedSessionId != null && !loadedSessionId.equals(currentSessionId)) {
            skillCachedKey = null;
            parentCachedKey = null;
            workspaceCachedKey = null;
            // 不保存到磁盘——等预热完成后 markXxxCacheReady 再覆盖
        }
    }

    public static PromptCacheManager getInstance() {
        return INSTANCE;
    }

    // ==================== 持久化 ====================

    private Path getCacheStatePath() {
        try {
            return Platform.getStateLocation(Activator.getDefault().getBundle())
                    .append(CACHE_STATE_FILE)
                    .toFile()
                    .toPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从磁盘加载3个缓存键和会话 ID。
     * 文件格式: sessionId===SECTION===skillKey===SECTION===parentKey===SECTION===workspaceKey
     */
    private void loadCacheState() {
        Path path = getCacheStatePath();
        if (path == null || !Files.exists(path)) return;
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (content == null || content.trim().isEmpty()) return;
            String[] parts = content.split(SECTION_SEP);
            if (parts.length >= 1) loadedSessionId = nullIfEmpty(parts[0].trim());
            if (parts.length >= 2) skillCachedKey = nullIfEmpty(parts[1].trim());
            if (parts.length >= 3) parentCachedKey = nullIfEmpty(parts[2].trim());
            if (parts.length >= 4) workspaceCachedKey = nullIfEmpty(parts[3].trim());
        } catch (IOException e) {
            // 读取失败忽略
        }
    }

    /**
     * 将3个缓存键和会话 ID 保存到磁盘。
     * 格式: sessionId===SECTION===skillKey===SECTION===parentKey===SECTION===workspaceKey
     */
    private void saveCacheState() {
        Path path = getCacheStatePath();
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append(currentSessionId);
            sb.append(SECTION_SEP);
            sb.append(skillCachedKey != null ? skillCachedKey : "");
            sb.append(SECTION_SEP);
            sb.append(parentCachedKey != null ? parentCachedKey : "");
            sb.append(SECTION_SEP);
            sb.append(workspaceCachedKey != null ? workspaceCachedKey : "");
            Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 写入失败忽略
        }
    }

    // ==================== 缓存键生成 ====================

    /** 生成日期部分（yyyyMMdd），每天自动更新 */
    private static String getDatePart() {
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("yyyyMMdd");
        synchronized (dateFmt) {
            return dateFmt.format(new java.util.Date());
        }
    }

    /** 文件名部分（去除扩展名） */
    private static String getFilePart(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unknown";
        int dot = fileName.lastIndexOf('.');
        String filePart = dot > 0 ? fileName.substring(0, dot) : fileName;
        return filePart.isEmpty() ? "unknown" : filePart;
    }

    /**
     * 生成节点1（SKILL）缓存键。
     * 基于日期 + SKILL 内容哈希（不含文件名，SKILL 是全局的）。
     */
    public String generateSkillCacheKey(String skillContent) {
        String datePart = getDatePart();
        int hash = safe(skillContent).hashCode();
        return CACHE_KEY_PREFIX + SKILL_PREFIX + datePart + "-" + Integer.toHexString(hash);
    }

    /**
     * 生成节点2（父级程序）缓存键。
     * 基于文件名 + 日期 + 父级内容哈希。
     */
    public String generateParentCacheKey(String fileName, String parentContent) {
        String datePart = getDatePart();
        String filePart = getFilePart(fileName);
        int hash = safe(parentContent).hashCode();
        return CACHE_KEY_PREFIX + PARENT_PREFIX + filePart + "-" + datePart + "-" + Integer.toHexString(hash);
    }

    /**
     * 生成节点3（工作区程序）缓存键。
     * 基于文件名 + 日期 + 工作区内容哈希。
     */
    public String generateWorkspaceCacheKey(String fileName, String workspaceContent) {
        String datePart = getDatePart();
        String filePart = getFilePart(fileName);
        int hash = safe(workspaceContent).hashCode();
        return CACHE_KEY_PREFIX + WORKSPACE_PREFIX + filePart + "-" + datePart + "-" + Integer.toHexString(hash);
    }

    // ==================== 判断是否需要预热 ====================

    /**
     * 判断节点1（SKILL）是否需要预热。
     * 条件：SKILL 内容长度 > 阈值 且 缓存未建立或 key 不匹配。
     */
    public boolean needsSkillWarmup(String skillContent, String skillKey) {
        if (skillContent == null || skillContent.length() <= SKILL_CACHE_THRESHOLD) return false;
        return !isSkillCacheReady(skillKey);
    }

    /**
     * 判断节点2（父级程序）是否需要预热。
     * 条件：有父级内容 且 缓存未建立或 key 不匹配。
     */
    public boolean needsParentWarmup(String parentContent, String parentKey) {
        if (parentContent == null || parentContent.isEmpty()) return false;
        return !isParentCacheReady(parentKey);
    }

    /**
     * 判断节点3（工作区程序）是否需要预热。
     * 条件：有工作区内容 且 缓存未建立或 key 不匹配。
     */
    public boolean needsWorkspaceWarmup(String workspaceContent, String workspaceKey) {
        if (workspaceContent == null || workspaceContent.isEmpty()) return false;
        return !isWorkspaceCacheReady(workspaceKey);
    }

    // ==================== 缓存状态判断 ====================

    public boolean isSkillCacheReady(String key) {
        if (key == null) return false;
        synchronized (this) {
            return key.equals(skillCachedKey);
        }
    }

    public boolean isParentCacheReady(String key) {
        if (key == null) return false;
        synchronized (this) {
            return key.equals(parentCachedKey);
        }
    }

    public boolean isWorkspaceCacheReady(String key) {
        if (key == null) return false;
        synchronized (this) {
            return key.equals(workspaceCachedKey);
        }
    }

    // ==================== 标记缓存就绪 ====================

    public void markSkillCacheReady(String key) {
        if (key == null) return;
        synchronized (this) {
            skillCachedKey = key;
        }
        saveCacheState();
    }

    public void markParentCacheReady(String key) {
        if (key == null) return;
        synchronized (this) {
            parentCachedKey = key;
        }
        saveCacheState();
    }

    public void markWorkspaceCacheReady(String key) {
        if (key == null) return;
        synchronized (this) {
            workspaceCachedKey = key;
        }
        saveCacheState();
    }

    /**
     * 重置所有缓存状态。
     */
    public void resetAllCache() {
        synchronized (this) {
            skillCachedKey = null;
            parentCachedKey = null;
            workspaceCachedKey = null;
        }
        saveCacheState();
    }

    /**
     * 重置指定节点的缓存状态（仅重置一个节点）。
     * @param nodeType 1=skill, 2=parent, 3=workspace
     */
    public void resetCache(int nodeType) {
        synchronized (this) {
            switch (nodeType) {
                case 1: skillCachedKey = null; break;
                case 2: parentCachedKey = null; break;
                case 3: workspaceCachedKey = null; break;
            }
        }
        saveCacheState();
    }

    // ==================== 统计 ====================

    public void recordCacheHit() {
        synchronized (this) { cacheHitCount++; }
    }

    public void recordCacheMiss() {
        synchronized (this) { cacheMissCount++; }
    }

    public void recordWarmup() {
        synchronized (this) { warmupCount++; }
    }

    public String getCacheStats() {
        synchronized (this) {
            return "skill=" + skillCachedKey + ", parent=" + parentCachedKey
                    + ", workspace=" + workspaceCachedKey
                    + ", hits=" + cacheHitCount + ", misses=" + cacheMissCount
                    + ", warmups=" + warmupCount;
        }
    }

    // ==================== 内容压缩 ====================

    /** 压缩阈值：内容长度小于此值时直接返回原内容，不做压缩 */
    private static final int COMPRESS_THRESHOLD = 500;

    /** 默认最大输入字符数（用户未配置时使用）：约 240K tokens × 4 */
    private static final int DEFAULT_MAX_INPUT_CHARS = 960000;

    /** 块状态: 当前所在代码块类型 */
    private enum BlockState {
        NORMAL,              // 普通模式（全局作用域），逐行判断
        IN_DEFINE,           // DEFINE ... END-OF-DEFINITION 宏定义内，所有行全部保留
        IN_FORM_METHOD       // FORM/METHOD/FUNCTION 签名内，只保留签名参数行和结束关键字
    }

    /**
     * 节点2、3内容压缩处理（使用默认最大输入字符数）。
     * 若内容未超过最大输入字符数，则直接返回原内容不做压缩。
     *
     * @param content 原始 ABAP 代码
     * @return 压缩后的代码，长度大幅减少但保留语义
     */
    public String compressContent(String content) {
        return compressContent(content, DEFAULT_MAX_INPUT_CHARS);
    }

    /**
     * 节点2、3内容压缩处理。
     *
     * 压缩策略（ABAP 代码）:
     *   - 内容长度 < 500 字符：直接返回原内容，不做压缩
     *   - 内容长度 < maxInputChars：直接返回原内容，不做压缩（未超输入上限）
     *   - 内容长度 >= maxInputChars 且 >= 500：执行智能压缩
     *   - 块感知: DEFINE...END-OF-DEFINITION 内的所有内容全部保留
     *   - 块感知: CLASS...DEFINITION 内的所有 METHODS/DATA 声明保留
     *   - 全局声明行全部保留 (TABLES:/TYPES:/DATA:/DEFINE 等)
     *   - 控制流关键字保留，主体代码用省略标记替换
     *
     * @param content 原始 ABAP 代码
     * @param maxInputChars 最大输入字符数，超过此值才压缩
     * @return 压缩后的代码，长度大幅减少但保留语义
     */
    public String compressContent(String content, int maxInputChars) {
        if (content == null || content.isEmpty()) return content;
        // 内容较短或未超输入上限时，不压缩直接返回全量内容
        if (content.length() < COMPRESS_THRESHOLD) return content;
        if (maxInputChars > 0 && content.length() <= maxInputChars) {
            return content;
        }

        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        StringBuilder sb = new StringBuilder(content.length() / 3);
        boolean inSkippedBlock = false;
        int consecutiveSkipped = 0;
        int keptLines = 0;

        BlockState blockState = BlockState.NORMAL;
        // 是否仍处于 FORM/METHOD/FUNCTION 签名阶段（签名以含 "." 的语句行结束）
        boolean inSignature = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String trimmedUpper = trimmed.toUpperCase();

            // === 检测块状态切换 ===

            // 进入 DEFINE 宏定义块（全局声明，内容全部保留）
            if (blockState == BlockState.NORMAL && trimmedUpper.startsWith("DEFINE ")) {
                blockState = BlockState.IN_DEFINE;
            }
            // 退出 DEFINE 宏定义块
            if (blockState == BlockState.IN_DEFINE
                    && trimmedUpper.startsWith("END-OF-DEFINITION")) {
                blockState = BlockState.NORMAL;
            }

            // 进入 FORM/METHOD/FUNCTION 体（全局作用域遇到 FORM/METHOD/FUNCTION 定义）
            if (blockState == BlockState.NORMAL
                    && (trimmedUpper.startsWith("FORM ")
                        || trimmedUpper.startsWith("METHOD ")
                        || trimmedUpper.startsWith("FUNCTION "))) {
                blockState = BlockState.IN_FORM_METHOD;
            }
            // 退出 FORM/METHOD/FUNCTION 体
            if (blockState == BlockState.IN_FORM_METHOD
                    && (trimmedUpper.startsWith("ENDFORM")
                        || trimmedUpper.startsWith("ENDMETHOD")
                        || trimmedUpper.startsWith("ENDFUNCTION"))) {
                blockState = BlockState.NORMAL;
            }

            // 判断当前行是否保留
            boolean keep = false;

            // ===== 块内模式处理 =====

            // DEFINE 内部所有行全部保留（宏定义体）
            if (blockState == BlockState.IN_DEFINE) {
                keep = true;
            }

            // FORM/METHOD/FUNCTION 内部：只保留自身签名（输入/输出变量定义）
            // 及注释和结束关键字；删除内部 DATA 声明、实现代码、CALL FUNCTION 等
            if (blockState == BlockState.IN_FORM_METHOD) {
                boolean isComment = trimmed.startsWith("\"") || trimmed.startsWith("*");

                // 保留注释
                if (isComment) {
                    keep = true;
                }

                // FORM/METHOD/FUNCTION 自身的定义行（签名第一行）
                if (!keep && (trimmedUpper.startsWith("FORM ")
                        || trimmedUpper.startsWith("METHOD ")
                        || trimmedUpper.startsWith("FUNCTION "))) {
                    keep = true;
                    // 若定义行已含句点，则签名在同一行结束；否则进入签名阶段
                    inSignature = !trimmed.endsWith(".");
                }

                // 签名阶段：保留 FORM/METHOD/FUNCTION 自身的输入/输出参数行
                // （USING/IMPORTING/EXPORTING/CHANGING/TABLES/RECEIVING/VALUE 及 TYPE/LIKE 定义行）
                if (!keep && inSignature
                        && (trimmedUpper.matches("^(USING|IMPORTING|EXPORTING|CHANGING|TABLES|RECEIVING|VALUE\\()(.|\\s)*")
                            || trimmedUpper.matches("^\\w+\\s+(TYPE|LIKE|STRUCTURE)\\s.*"))) {
                    keep = true;
                }

                // 签名结束：签名阶段遇到非注释、以 "." 结尾的语句行（非新的 FORM/METHOD/FUNCTION 定义）
                if (inSignature && !isComment && trimmed.endsWith(".")
                        && !(trimmedUpper.startsWith("FORM ")
                            || trimmedUpper.startsWith("METHOD ")
                            || trimmedUpper.startsWith("FUNCTION "))) {
                    inSignature = false;
                }

                // 保留结束关键字
                if (!keep && isEndKeywordLine(trimmedUpper)) {
                    keep = true;
                }
            }

            // ===== NORMAL 模式（全局作用域）：按规则判断 =====
            if (!keep && blockState == BlockState.NORMAL) {
                // 1. 保留所有注释（行注释 " 和段落注释 *）
                if (trimmed.startsWith("\"") || trimmed.startsWith("*")) {
                    keep = true;
                }

                // 2. 保留关键字声明和定义（DATA/CONSTANTS/TABLES/TYPES 等全局声明）
                if (!keep && isDeclarationLine(trimmedUpper)) {
                    keep = true;
                }

                // 3. 保留控制流/核心逻辑语句（全局级）
                if (!keep && isControlFlowLine(trimmedUpper)) {
                    keep = true;
                }

                // 4. 保留类/接口定义和实现中的签名行
                if (!keep && isClassSignatureLine(trimmedUpper)) {
                    keep = true;
                }

                // 5. 保留方法/函数/FORM 的签名行
                if (!keep && isMethodSignatureLine(trimmedUpper, trimmed)) {
                    keep = true;
                }

                // 6. 保留结构化数据的定义行（BEGIN OF, INCLUDE STRUCTURE 等）
                if (!keep && isStructureDefinitionLine(trimmedUpper)) {
                    keep = true;
                }

                // 7. 保留 SELECT 语句的字段列表
                if (!keep && isSelectFieldLine(trimmedUpper)) {
                    keep = true;
                }

                // 8. 保留 ENDxxx 结束关键字
                if (!keep && isEndKeywordLine(trimmedUpper)) {
                    keep = true;
                }

                // 9. 保留空白行和短行（结构分隔）
                if (!keep && (trimmed.isEmpty() || trimmed.length() <= 3)) {
                    keep = true;
                }
            }

            if (keep) {
                if (inSkippedBlock) {
                    if (consecutiveSkipped > 3) {
                        sb.append("...     [skipped ").append(consecutiveSkipped)
                          .append(" lines of implementation]\n");
                    }
                    inSkippedBlock = false;
                    consecutiveSkipped = 0;
                }
                sb.append(line).append("\n");
                keptLines++;
            } else {
                inSkippedBlock = true;
                consecutiveSkipped++;
            }
        }

        if (consecutiveSkipped > 3) {
            sb.append("...     [skipped ").append(consecutiveSkipped)
              .append(" lines of implementation]\n");
        }

        // 调试日志：记录压缩统计
        String result = sb.toString();
        String[] resultLines = result.split("\n", -1);
        AILogger.logDiagnostic("PromptCacheManager",
                String.format("compressContent: totalLines=%d, kept=%d, resultLines=%d, resultLength=%d (%.1f%% reduction)",
                totalLines, keptLines, resultLines.length, result.length(),
                (1.0 - (double)result.length() / content.length()) * 100));
        return result;
    }

    /**
     * 判断是否为变量/常量/类型等声明行。
     * 保留 DATA/CONSTANTS/TABLES/TYPES/FIELD-SYMBOLS/STATICS/PARAMETERS/
     * SELECT-OPTIONS/RANGES/CLASS-DATA/INSTANCE-DATA/DEFINE/TYPE-POOLS/
     * REPORT/PROGRAM 等。
     * 同时支持冒号形式（如 TABLES: / TYPES: / DATA: / FIELD-SYMBOLS: 等）。
     */
    private static boolean isDeclarationLine(String trimmedUpper) {
        // 匹配 关键字+空格 或 关键字+冒号 两种形式
        return trimmedUpper.matches("^(DATA|CONSTANTS|TABLES|TYPES|FIELD-SYMBOLS|STATICS|"
                + "PARAMETERS|SELECT-OPTIONS|RANGES|CLASS-DATA|INSTANCE-DATA|"
                + "DEFINE|TYPE-POOLS|REPORT|PROGRAM|CLASS-METHODS|EVENTS|INTERFACES|ALIASES)"
                + "(\\s|:).*")
                || trimmedUpper.matches("^DATA\\(.*")
                || trimmedUpper.matches("^(CLASS|INTERFACE)\\s+\\w+\\s+(DEFINITION|IMPLEMENTATION).*");
    }

    /**
     * 判断是否为控制流/核心逻辑语句行。
     * 保留 SELECT、LOOP、CALL、IF、WHILE、CASE、DO、TRY、CATCH、CLEANUP、
     * WHEN、ASSIGN、CREATE、MODIFY、DELETE、INSERT、UPDATE、READ TABLE 等。
     */
    private static boolean isControlFlowLine(String trimmedUpper) {
        return trimmedUpper.matches("^(SELECT|LOOP|CALL|IF|WHILE|CASE|DO|TRY|CATCH|CLEANUP|"
                + "WHEN|ASSIGN|CREATE|MODIFY|DELETE|INSERT|UPDATE|"
                + "READ\\s+TABLE|AT\\s+|ON\\s+|CHECK|EXIT|RETURN|CONTINUE|"
                + "RAISE|THROW|MESSAGE|COMMIT|ROLLBACK|"
                + "ENHANCEMENT|ENHANCEMENT-SECTION)\\s.*")
                || trimmedUpper.matches("^(ELSEIF|ELSE|ENDIF|ENDLOOP|ENDWHILE|ENDCASE|ENDFORM|"
                        + "ENDMETHOD|ENDCLASS|ENDINTERFACE|ENDEXEC|ENDTRY|"
                        + "ENDMODULE|ENDDO|ENDFUNCTION|ENDENHANCEMENT|ENDENHANCEMENT-SECTION|"
                        + "END-OF-DEFINITION).*");
    }

    /**
     * 判断是否为类/接口定义中的方法签名行。
     * 保留 METHODS、CLASS-METHODS 的定义行（含参数）。
     */
    private static boolean isClassSignatureLine(String trimmedUpper) {
        // METHODS method_name IMPORTING/EXPORTING/CHANGING...
        return trimmedUpper.matches("^(METHODS|CLASS-METHODS)\\s+.*")
                && trimmedUpper.contains("METHODS");
    }

    /**
     * 判断是否为方法/函数/FORM 的签名行。
     * 保留 METHOD/FORM/FUNCTION/MODULE 的定义行（含带参数的行）。
     * 使用多种模式匹配以适应不同编码风格。
     */
    private static boolean isMethodSignatureLine(String trimmedUpper, String trimmed) {
        // 模式1: 标准定义行（含空格缩进）
        if (trimmedUpper.startsWith("METHOD ") || trimmedUpper.startsWith("FORM ")
                || trimmedUpper.startsWith("FUNCTION ") || trimmedUpper.startsWith("MODULE ")) {
            // 不保留纯实现块的方法体，但保留签名行
            return true;
        }

        // 模式2: 使用正则兜底匹配行首的 FORM/METHOD/FUNCTION/MODULE 关键字
        // 处理 Tab 缩进等特殊情况
        if (trimmedUpper.matches("^\\s*(METHOD|FORM|FUNCTION|MODULE)\\s+\\w+.*")) {
            return true;
        }

        // 保留方法/函数/FORM 的参数声明（IMPORTING/EXPORTING/CHANGING/TABLES/USING/CHANGING）
        return trimmedUpper.matches("^\\s*(IMPORTING|EXPORTING|CHANGING|TABLES|USING|VALUE\\(|"
                + "RECEIVING|EXCEPTIONS)\\s+.*");
    }

    /**
     * 判断是否为 ENDxxx 结束关键字行。
     * 保留 ENDIF/ENDLOOP/ENDWHILE/ENDCASE/ENDFORM/ENDMETHOD/ENDCLASS/ENDINTERFACE/
     * ENDEXEC/ENDTRY/ENDMODULE/ENDDO/ENDFUNCTION/ENDENHANCEMENT 等。
     * 同时保留 END-OF-DEFINITION（DEFINE 宏的结束标记）。
     */
    private static boolean isEndKeywordLine(String trimmedUpper) {
        return trimmedUpper.matches("^\\s*(ENDIF|ENDLOOP|ENDWHILE|ENDCASE|ENDFORM|ENDMETHOD|"
                + "ENDCLASS|ENDINTERFACE|ENDEXEC|ENDTRY|ENDMODULE|ENDDO|ENDFUNCTION|"
                + "ENDENHANCEMENT|ENDENHANCEMENT-SECTION|END-OF-DEFINITION)"
                + "(\\..*)?$");
    }

    /**
     * 判断是否为结构化数据定义行。
     */
    private static boolean isStructureDefinitionLine(String trimmedUpper) {
        return trimmedUpper.matches("^(BEGIN\\s+OF|INCLUDE\\s+(STRUCTURE|TYPE)|"
                + "END\\s+OF)\\s+.*");
    }

    /**
     * 判断是否为 SELECT 语句的字段列表行。
     */
    private static boolean isSelectFieldLine(String trimmedUpper) {
        // SELECT 之后的非 INTO/WHERE/ORDER BY/GROUP BY/HAVING 的行，通常是字段列表
        return trimmedUpper.matches("^\\s+\\w+\\s+(AS\\s+\\w+)?,?")
                && !trimmedUpper.contains(" INTO ")
                && !trimmedUpper.contains(" WHERE ")
                && !trimmedUpper.contains(" ORDER BY ")
                && !trimmedUpper.contains(" GROUP BY ")
                && !trimmedUpper.contains(" HAVING ")
                && !trimmedUpper.contains(" UP TO ")
                && !trimmedUpper.contains(" FOR ALL ENTRIES ");
    }

    // ==================== 简短占位内容生成 ====================

    /**
     * 生成缓存命中时节点使用的简短占位内容。
     */
    public String buildSlimContent(String nodeTitle, String cacheKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(nodeTitle).append("]\n");
        sb.append("[CACHED] 内容已在 AI 服务端缓存，此处省略完整代码以减少 TOKEN。\n");
        sb.append("cacheKey: ").append(cacheKey).append("\n");
        sb.append("请直接复用缓存中的程序上下文进行补全。");
        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
