package com.sap.abap.ai.completion.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.IWorkbenchPage;

import com.sap.abap.ai.completion.client.AIClient;
import com.sap.abap.ai.completion.client.AIClient.ChatMessage;
import com.sap.abap.ai.completion.client.AIClientException;
import com.sap.abap.ai.completion.client.PromptCacheManager;
import com.sap.abap.ai.completion.logging.AILogger;
import com.sap.abap.ai.completion.parser.AbapIncludeResolver;
import com.sap.abap.ai.completion.parser.AbapIncludeResolver.IncludeContext;
import com.sap.abap.ai.completion.parser.ParentProgramContext;
import com.sap.abap.ai.completion.parser.ParentProgramResolver;
import com.sap.abap.ai.completion.parser.WorkspaceCodeCollector;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * Background service for AI completions.
 * Calls the AI API asynchronously and delivers results via callback.
 */
public class AICompletionService {

    /**
     * Requests a completion asynchronously.
     *
     * @param file          the current file being edited
     * @param codeBefore    code before cursor
     * @param codeAfter     code after cursor
     * @param fullDocument  the full document text for include resolution
     * @param project       the current project
     * @param workbenchPage the current workbench page (captured on UI thread, passed to background thread)
     * @param callback      called on success with completion text
     * @param errorCallback called on error with error message
     * @return a CompletableFuture that can be cancelled
     */
    public static CompletableFuture<String> requestCompletion(
            IFile file, String codeBefore, String codeAfter,
            String fullDocument, IProject project,
            IWorkbenchPage workbenchPage,
            Consumer<String> callback,
            Consumer<String> errorCallback) {

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Resolve includes
                String currentCode = fullDocument;
                AbapIncludeResolver resolver = new AbapIncludeResolver(project);
                IncludeContext context = resolver.resolveAllIncludes(currentCode);

                // 反向解析上级程序(当当前文件是 INCLUDE 时,获取调用方上下文)
                ParentProgramContext parentCtx = null;
                int depth = AIConfiguration.getAbapSearchDepth();
                if (AIConfiguration.isParentProgramResolutionEnabled() && depth > 0) {
                    int maxChars = AIConfiguration.getMaxContextChars();
                    ParentProgramResolver parentResolver =
                            new ParentProgramResolver(project, depth, maxChars);
                    parentCtx = parentResolver.resolveParents(file);
                }

                // 工作区代码参考(当前打开的其他 ABAP 文件)
                String workspaceCodeRef = "";
                int workspaceMaxChars = 0;
                boolean wsEnabled = AIConfiguration.isWorkspaceCodeReferenceEnabled();
                if (wsEnabled) {
                    String fileName = file != null ? file.getName() : "";
                    String fileExt = "";
                    if (file != null) {
                        int dot = fileName.lastIndexOf('.');
                        if (dot >= 0) fileExt = fileName.substring(dot);
                    }
                    int maxChars = AIConfiguration.getMaxWorkspaceCodeChars();
                    int fileLimit = AIConfiguration.getWorkspaceCodeFileLimit();
                    // 每个工作区文件的截断上限与节点2（父程序上下文）保持一致（maxContextChars）
                    int wsFileMaxChars = AIConfiguration.getMaxContextChars();
                    WorkspaceCodeCollector collector =
                            new WorkspaceCodeCollector(fileName, fileExt, fileLimit,
                                    wsFileMaxChars, workbenchPage);
                    workspaceCodeRef = collector.collectWorkspaceCode();
                    workspaceMaxChars = maxChars;
                }

                // Build prompts - 拆分为多个独立消息节点
                String codeType = detectCodeType(file);
                String systemPrompt = getEffectiveSystemPrompt(codeType);
                final String fileName = file != null ? file.getName() : "<unknown>";

                // === 三节点上下文（已取消预热功能，改为补全时直接压缩发送） ===
                // 不再单独调用 AI 预热建立缓存，而是在构建消息时对每个节点内容直接
                // 调用 compressContent 压缩，随补全请求一并发送给 AI。
                String parentContent = parentCtx != null ? parentCtx.buildPromptContext() : "";
                String workspaceContent = workspaceCodeRef;
                String skillContent = AIConfiguration.loadSkillContents(codeType);

                // 4. 构建6个独立的 user 消息节点（各节点内容在构建时直接压缩）
                List<ChatMessage> userMessages = buildUserMessages(
                        fileName, codeType,
                        context.buildPromptContext(),
                        parentContent, workspaceContent, skillContent,
                        workspaceMaxChars,
                        codeBefore, codeAfter);

                // 注意：不再预热、不再使用 prompt_cache_keys，直接发送完整内容。
                // 这里仅保留多缓存接口的调用形态，但传入 null（不使用缓存）。
                final long startTs = System.currentTimeMillis();

                // 接口日志: 记录请求（含6个独立 user 消息节点内容）
                AILogger.logRequestMessages(fileName, systemPrompt, userMessages,
                        false, false, false, false,
                        null, null, null);

                // 5. Call AI（直接压缩后发送，不使用缓存）
                String result;
                try {
                    result = AIClient.completeWithMultiCache(systemPrompt, userMessages,
                            null, null);
                } catch (AIClientException aiEx) {
                    throw aiEx;
                }

                // 6. 接口日志: 记录响应
                AILogger.logResponse(fileName, result, System.currentTimeMillis() - startTs);
                return result;
            } catch (AIClientException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get completion: " + e.getMessage(), e);
            }
        });

        future = future.orTimeout(30, TimeUnit.SECONDS);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                if (ex instanceof CancellationException) {
                    return;
                }
                String msg = (ex.getCause() instanceof AIClientException)
                        ? ex.getCause().getMessage()
                        : ex.getMessage();
                AILogger.logError(file != null ? file.getName() : "<unknown>", msg);
                if (errorCallback != null) {
                    errorCallback.accept(msg);
                }
            } else {
                // 无论是否为空都通知调用方：为空表示无可用补全（AI 未返回内容或去重后为空），
                // 由调用方（handler）据此显示“没有可用补全代码”，避免状态栏停留“补全进行中”。
                // codeBefore/codeAfter 均参与去重：先去掉与光标前重复的字符级前缀，再去掉与光标后重复的行级前缀
                String cleaned = cleanupCompletion(result, codeAfter, codeBefore);
                if (callback != null) {
                    callback.accept(cleaned != null ? cleaned : "");
                }
            }
        });

        return future;
    }

    /**
     * Requests a quick inline completion (for auto-complete mode).
     * Uses a shorter timeout.
     */
    public static CompletableFuture<String> requestQuickCompletion(
            String currentLineContext, IProject project,
            Consumer<String> callback, Consumer<String> errorCallback) {

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                String sysPrompt = "You are an ABAP code completion assistant. "
                        + "Suggest only the next ABAP code fragment based on the context. "
                        + "Output ONLY the code, no explanations.";

                String userPrompt = "Complete the following ABAP code. Output ONLY the next likely code:\n\n"
                        + currentLineContext;

                return AIClient.complete(sysPrompt, userPrompt);
            } catch (AIClientException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get completion: " + e.getMessage(), e);
            }
        });

        future = future.orTimeout(10, TimeUnit.SECONDS);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                if (ex instanceof CancellationException) return;
                String msg = (ex.getCause() instanceof AIClientException)
                        ? ex.getCause().getMessage()
                        : ex.getMessage();
                if (errorCallback != null) errorCallback.accept(msg);
            } else if (result != null && !result.trim().isEmpty()) {
                String cleaned = cleanupCompletion(result, null, null);
                if (callback != null && cleaned != null && !cleaned.trim().isEmpty()) {
                    callback.accept(cleaned);
                }
            }
        });

        return future;
    }

    /**
     * 检测代码类型(根据文件扩展名)。
     *
     * @param file 当前文件
     * @return 代码类型,如 "ABAP"、"CDS",无法判断时返回 null
     */
    private static String detectCodeType(IFile file) {
        if (file == null) return null;
        String name = file.getName().toLowerCase();
        if (name.endsWith(".abap") || name.endsWith(".abapinc")
                || name.endsWith(".asinc") || name.endsWith(".txt")) {
            return "ABAP";
        }
        if (name.endsWith(".cds") || name.endsWith(".ddl")
                || name.endsWith(".dcl") || name.endsWith(".hdbdd")) {
            return "CDS";
        }
        // 文件名以 Y/Z 开头(ABAP 命名约定)
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.startsWith("y") || base.startsWith("z")
                || base.startsWith("sap") || base.startsWith("r")) {
            return "ABAP";
        }
        return null;
    }

    private static String getEffectiveSystemPrompt(String codeType) {
        String customPrompt = AIConfiguration.getSystemPrompt();
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            return customPrompt;
        }
        return "You are an expert SAP ABAP developer assistant. Analyze the provided ABAP code context "
            + "(including referenced INCLUDE programs and available skill files) and suggest the next "
            + "most appropriate code at the cursor position.\n\n"
            + "Rules:\n"
            + "1. Only output the code to insert - no explanations, no markdown.\n"
            + "2. Follow SAP ABAP best practices and coding patterns from the skill files.\n"
            + "3. Consider INCLUDE code context and skill examples.\n"
            + "4. Keep suggestions concise and directly insertable at cursor.\n"
            + "5. If the cursor is inside a comment, suggest corresponding implementation.\n"
            + "6. Use proper ABAP patterns: DATA, LOOP, SELECT, FORM, METHOD, etc.\n"
            + "7. Maintain consistent naming with the existing code.\n"
            + "8. Use modern ABAP syntax where appropriate.";
    }

    /**
     * 构建6个独立的 user 消息节点（已取消预热功能，各节点内容直接压缩后发送）:
     * 1. SKILL 文件内容
     * 2. 深度搜索到的相关程序(父程序调用上下文)
     * 3. 当前工作区打开的相关程序
     * 4. 程序文本标题属性描述等信息
     * 5. 当前光标所在程序(含 INCLUDE 展开)，并在光标位置插入 [[[CURSOR_HERE]]] 标记
     *    （即原“节点4 完整代码”与“节点6 光标上下文”合并为新的节点5）
     *
     * 节点1-3 均通过 {@link PromptCacheManager#compressContent(String)} 直接压缩完整内容，
     * 不再使用 AI 服务端缓存 / 占位符。
     */
    private static List<ChatMessage> buildUserMessages(
            String fileName, String codeType,
            String codeContext, String parentProgramContext,
            String workspaceCodeRef, String skillContent,
            int workspaceMaxChars,
            String textBeforeCursor, String textAfterCursor) {

        List<ChatMessage> messages = new ArrayList<>();
        PromptCacheManager cacheManager = PromptCacheManager.getInstance();

        // ===== Node 1: SKILL file content =====
        if (skillContent != null && !skillContent.isEmpty()) {
            StringBuilder skillSb = new StringBuilder();
            skillSb.append("[SKILL FILES - Node 1/5] Code style references and best-practice examples (");
            skillSb.append(codeType != null ? codeType : "ALL");
            skillSb.append(")\n\n");
            // Node 1 is not compressed; SKILL full content is sent directly
            skillSb.append(skillContent);
            messages.add(new ChatMessage("user", skillSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[SKILL FILES - Node 1/5] No SKILL file has been loaded. Using the system default ABAP coding standards."));
        }

        // ===== Node 2: Deep-search related programs (parent program call context) =====
        if (parentProgramContext != null && !parentProgramContext.isEmpty()) {
            StringBuilder parentSb = new StringBuilder();
            parentSb.append("[PARENT PROGRAMS - Node 2/5] Deep-searched related programs (context of the parent programs that call the current INCLUDE, truncated)\n\n");
            // 与节点3一致：先压缩，再按 workspaceMaxChars(默认5万字符) 截断
            parentSb.append(cacheManager.compressContent(parentProgramContext, workspaceMaxChars));
            messages.add(new ChatMessage("user", parentSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[PARENT PROGRAMS - Node 2/5] No parent calling program found. The current file is a standalone program or the parent lookup feature is disabled."));
        }

        // ===== Node 3: Related programs open in the current workspace =====
        if (workspaceCodeRef != null && !workspaceCodeRef.isEmpty()) {
            StringBuilder wsSb = new StringBuilder();
            wsSb.append("[WORKSPACE OPEN FILES - Node 3/5] Other ABAP programs open in the current Eclipse workspace (used as code style references and context)\n\n");
            // Consistent with nodes 1 and 2, processed through the shared compression logic compressContent;
            // the user-configured maximum workspace character count is passed as the compression limit.
            wsSb.append(cacheManager.compressContent(workspaceCodeRef, workspaceMaxChars));
            messages.add(new ChatMessage("user", wsSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[WORKSPACE OPEN FILES - Node 3/5] No other ABAP programs were found open in the workspace, or this feature is disabled."));
        }

        // ===== Node 4: Program text title, attributes and description info =====
        StringBuilder metaSb = new StringBuilder();
        metaSb.append("[PROGRAM METADATA - Node 4/5] Program text title and attribute description info\n\n");
        metaSb.append("File name: ").append(fileName).append("\n");
        metaSb.append("Code type: ").append(codeType != null ? codeType : "UNKNOWN").append("\n");
        metaSb.append("Detected INCLUDE count: ").append(
                (codeContext != null && codeContext.contains("INCLUDE"))
                        ? "Expanded (see Node 5)" : "No INCLUDE statements detected").append("\n");
        metaSb.append("Parent program resolution: ").append(
                (parentProgramContext != null && !parentProgramContext.isEmpty())
                        ? "Found (see Node 2)" : "Not found or disabled").append("\n");
        metaSb.append("Workspace reference files: ").append(
                (workspaceCodeRef != null && !workspaceCodeRef.isEmpty())
                        ? "Loaded (see Node 3)" : "No other open files").append("\n");
        metaSb.append("SKILL loading: ").append(
                (skillContent != null && !skillContent.isEmpty())
                        ? "Loaded (see Node 1)" : "No SKILL file").append("\n");
        metaSb.append("\nHint: Please combine all of the above context information and generate the correct ABAP code at the cursor position in Node 5.");
        messages.add(new ChatMessage("user", metaSb.toString()));

        // ===== Node 5: Current program at cursor (with INCLUDE expansion) + cursor position (merged from the original Node 4 + Node 6) =====
        StringBuilder currentSb = new StringBuilder();
        currentSb.append("[CURRENT PROGRAM - Node 5/5] The current ABAP program at the cursor (INCLUDEs expanded, cursor position marked with [[[CURSOR_HERE]]])\n\n");
        currentSb.append("File name: ").append(fileName).append("\n");
        currentSb.append("Code type: ").append(codeType != null ? codeType : "AUTO-DETECT").append("\n\n");
        currentSb.append("--- Full program code (with INCLUDE expansion; [[[CURSOR_HERE]]] is the current cursor position) ---\n");
        currentSb.append(insertCursorMarkerInCode(codeContext, textBeforeCursor));
        currentSb.append("\n\nPlease generate the ABAP code to be inserted at the [[[CURSOR_HERE]]] position. Note: the code already shown after [[[CURSOR_HERE]]] already exists in the document; only output the new code to be inserted at the cursor position, do not repeat this existing code, do not output explanations or markdown.");
        messages.add(new ChatMessage("user", currentSb.toString()));

        return messages;
    }

    /**
     * 在完整程序代码中，于光标位置插入 [[[CURSOR_HERE]]] 标记。
     *
     * codeContext 由 {@link com.sap.abap.ai.completion.parser.AbapIncludeResolver.IncludeContext#buildPromptContext()}
     * 生成，其结构为固定的前缀头部 "=== Current ABAP Program ===\n" + 完整主源码(全文) + 已解析的 INCLUDE 代码。
     * 光标位于主源码内，其字符偏移 = 头部前缀长度 + 光标前文本长度。
     */
    private static String insertCursorMarkerInCode(String codeContext, String textBeforeCursor) {
        final String header = "=== Current ABAP Program ===\n";
        int prefixLen = header.length();
        int insertAt = prefixLen + (textBeforeCursor != null ? textBeforeCursor.length() : 0);
        insertAt = Math.max(0, Math.min(insertAt, codeContext.length()));
        return codeContext.substring(0, insertAt)
                + "[[[CURSOR_HERE]]]"
                + codeContext.substring(insertAt);
    }

    /**
     * 补全代码显示前的处理统一入口。
     * <p>
     * 旧的处理逻辑（字符级前缀去重 dedupePrefixWithCodeBefore、行级前缀去重
     * dedupeWithCodeAfter）存在问题，已全部删除。改为在此处按多种情况分别处理：
     * <ul>
     *   <li>Case 1：去除前重复行 —— 补全首行与光标所在行（或向上取到的代码行）内容相同时删除该行；</li>
     *   <li>Case 2：当前行部分提示 —— 光标行前已有部分片段与补全首行开头重复时，去掉重复前缀；</li>
     *   <li>Case 3：其它 —— 后续新增处理情况统一在此追加。</li>
     * </ul>
     * 各子处理逻辑的详细说明见对应方法的注释。
     *
     * @param completion AI 返回的原始补全内容
     * @param codeAfter  光标后的全部文本（当前未使用，保留参数以便扩展）
     * @param codeBefore 光标前的全部文本（用于与光标上下文做去重判断）
     * @return 处理后的补全内容；若为空，由调用方决定不展示补全
     */
    private static String cleanupCompletion(String completion, String codeAfter, String codeBefore) {
        if (completion == null) return null;
        // 只去除首尾的空白行与尾部空白，保留首行代码的前导缩进（空格），避免显示/插入时丢失首行缩进
        String result = trimStartEndWhitespace(completion);
        // 去除 AI 返回内容中的 markdown 代码块标记（```...```）
        if (result.contains("```")) {
            result = result.replaceAll("```[a-zA-Z]*\\n?", "");
            result = result.replaceAll("\\n?```", "");
            result = trimStartEndWhitespace(result);
        }
        if (result.isEmpty()) return result;

        // === 补全方法显示前的多情况分步处理 ===

        // Case 1：去除前重复行 —— 若补全首行与光标所在行（或向上取到的代码行）内容相同，
        //         视为该行在提示窗口中重复，删除此行不显示。
        result = handleRemoveDuplicateFirstLine(result, codeBefore);

        // Case 2：当前行部分提示 —— 光标所在行前已存在部分字符，且与补全首行开头相同时，
        //         去掉重复开头，只提示不重复的其余部分。
        result = handleCurrentLinePartialHint(result, codeBefore);

        // Case 3：其它 —— 后续需要新增的处理情况在此继续追加、分别处理。

        return result;
    }

    /**
     * 将一行文本规范化，用于后续比较：
     * <ul>
     *   <li>TAB 转换为空格；</li>
     *   <li>多个连续空格合并为单个空格；</li>
     *   <li>去除首尾空白。</li>
     * </ul>
     *
     * @param s 原始文本
     * @return 规范化后的文本
     */
    private static String normalize(String s) {
        if (s == null) return "";
        s = s.replace('\t', ' ');       // TAB → 空格
        s = s.replaceAll(" +", " ");    // 多空格 → 单空格
        return s.trim();
    }

    /**
     * 生成一行的“比较键”，用于判断两行代码内容是否相同（忽略格式与注释差异）：
     * <ul>
     *   <li>去除 * 号及其之后的内容（ABAP 行内注释）；</li>
     *   <li>去除 " 号（ABAP 字符串引号，避免因引号差异误判）；</li>
     *   <li>整体规范化（TAB→空格、多空格→单空格、去首尾空白）。</li>
     * </ul>
     *
     * @param line 原始代码行
     * @return 用于比较内容的键
     */
    private static String getComparisonKey(String line) {
        if (line == null) return "";
        // 去除 * 及其之后的内容
        int starIdx = line.indexOf('*');
        if (starIdx >= 0) line = line.substring(0, starIdx);
        // 去除 " 号
        line = line.replace("\"", "");
        return normalize(line);
    }

    /**
     * [Case 1] 去除前重复行逻辑：
     * <p>
     * 按行，取提示代码的第一行；与光标位置所在行进行比较。若光标所在行当前为空，
     * 则向上取前一行，直到取到代码行为止。两侧均先做规范化（多空格→单空格、TAB→空格），
     * 同时在比较前去除 " 号、以及 * 号之后的内容（ABAP 行内注释）。若内容相同，则认为
     * 提示的第一行与光标前行内容重复，在提示窗口中删除此行、不显示。
     *
     * @param completion 补全内容
     * @param codeBefore 光标前的全部文本
     * @return 处理后的补全内容
     */
    private static String handleRemoveDuplicateFirstLine(String completion, String codeBefore) {
        if (completion == null || completion.isEmpty()) return completion;
        if (codeBefore == null || codeBefore.isEmpty()) return completion;

        // 取提示代码的第一行
        String firstLine = completion.split("\n", 2)[0];

        // 取光标位置所在行（光标前最后一行）；若当前行为空，向上取前一行直到取到代码行
        String cursorLine = getCodeLineBeforeCursor(codeBefore);
        if (cursorLine == null || cursorLine.trim().isEmpty()) return completion;

        // 生成比较键：规范化 + 去除 " 号、* 后的内容
        String firstKey = getComparisonKey(firstLine);
        String cursorKey = getComparisonKey(cursorLine);
        if (firstKey.isEmpty() || cursorKey.isEmpty()) return completion;

        // 内容相同 → 提示第一行与光标前行内容重复，删除此行，不显示
        if (firstKey.equals(cursorKey)) {
            int nl = completion.indexOf('\n');
            if (nl < 0) {
                // 补全仅一行且与光标前行重复：整段均重复，返回空
                return "";
            }
            return trimStartEndWhitespace(completion.substring(nl + 1));
        }
        return completion;
    }

    /**
     * 去除字符串首尾的空白行与尾部空白，但保留首行代码的前导缩进（空格/制表符）。
     * <p>
     * 与 {@link String#trim()} 不同，本方法不会把第一个有效行左侧的前导空格一起去掉，
     * 从而保证补全代码第一行在显示和插入时仍保持正确的缩进。
     *
     * @param s 原始文本
     * @return 处理后的文本
     */
    private static String trimStartEndWhitespace(String s) {
        if (s == null) return "";
        // 先去掉尾部所有空白（含末尾换行与空格）
        String r = s.replaceAll("\\s+$", "");
        if (r.isEmpty()) return "";
        // 去掉开头的空白行（仅含空白字符的行），但保留第一个有效行自身的前导缩进
        int start = 0;
        while (start < r.length()) {
            int lineEnd = r.indexOf('\n', start);
            String line = (lineEnd < 0) ? r.substring(start) : r.substring(start, lineEnd);
            if (!line.trim().isEmpty()) {
                break;
            }
            if (lineEnd < 0) {
                start = r.length();
                break;
            }
            start = lineEnd + 1;
        }
        return r.substring(start);
    }

    /**
     * 取光标位置所在行（仅光标前）的有效代码行。
     * 若光标所在行为空（空白行），则向上取前一行，直到取到代码行为止。
     *
     * @param codeBefore 光标前的全部文本
     * @return 找到的代码行；若没有则返回 null
     */
    private static String getCodeLineBeforeCursor(String codeBefore) {
        String[] lines = codeBefore.split("\n", -1);
        // 光标位于 codeBefore 末尾，当前行即最后一行；自下而上寻找第一个非空行
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return lines[i];
            }
        }
        return null;
    }

    /**
     * [Case 2] 当前行部分提示处理逻辑：
     * <p>
     * 仅当光标所在行前面有字符时处理。取光标所在位置行前面所有字符（只取光标所在行），
     * 多空格转单空格、TAB 转空格后作为变量 A；AI 返回的取第一行，同样规范化后作为变量 B。
     * 若 A 字符串与 B 字符串的开头部分相同，则去除 B 开头与 A 相同的内容，只返回不重复的
     * 这部分内容作为提示代码的第一行。
     *
     * @param completion 补全内容
     * @param codeBefore 光标前的全部文本
     * @return 处理后的补全内容
     */
    private static String handleCurrentLinePartialHint(String completion, String codeBefore) {
        if (completion == null || completion.isEmpty()) return completion;
        if (codeBefore == null || codeBefore.isEmpty()) return completion;

        // A = 光标所在行光标前的所有字符（只取光标所在行），规范化
        int lastNewline = codeBefore.lastIndexOf('\n');
        String lineBefore = lastNewline >= 0
                ? codeBefore.substring(lastNewline + 1)
                : codeBefore;
        String A = normalize(lineBefore);
        // 仅当光标所在行前面有字符时处理
        if (A.isEmpty()) return completion;

        // B = AI 返回内容的第一行，规范化
        String firstLine = completion.split("\n", 2)[0];
        String B = normalize(firstLine);
        if (B.isEmpty()) return completion;

        // 若 A 与 B 的开头部分相同，则去除 B 开头与 A 相同的内容
        if (!B.startsWith(A)) return completion;
        String remainder = B.substring(A.length()).trim();

        // 用不重复的部分替换提示代码的第一行
        int nl = completion.indexOf('\n');
        if (nl < 0) {
            // 补全仅一行：直接返回不重复的这部分内容
            return remainder;
        }
        // 拼接：不重复的首行 + 原补全的其余行
        String rest = completion.substring(nl + 1);
        return remainder.isEmpty() ? rest : remainder + "\n" + rest;
    }

}
