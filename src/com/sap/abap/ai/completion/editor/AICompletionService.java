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
            } else if (result != null && !result.trim().isEmpty()) {
                String cleaned = cleanupCompletion(result);
                if (callback != null) {
                    callback.accept(cleaned);
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
                String cleaned = cleanupCompletion(result);
                if (callback != null) callback.accept(cleaned);
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
     * 4. 当前光标所在程序(含 INCLUDE 展开)
     * 5. 程序文本标题属性描述等信息
     * 6. 当前光标位置信息及上下文(前后N行)
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

        // ===== 节点1: SKILL 文件内容 =====
        if (skillContent != null && !skillContent.isEmpty()) {
            StringBuilder skillSb = new StringBuilder();
            skillSb.append("[SKILL FILES - 节点1/6] 代码风格参考与最佳实践示例 (");
            skillSb.append(codeType != null ? codeType : "ALL");
            skillSb.append(")\n\n");
            // 节点1 不压缩，直接发送 SKILL 完整内容
            skillSb.append(skillContent);
            messages.add(new ChatMessage("user", skillSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[SKILL FILES - 节点1/6] 无已加载的 SKILL 文件，使用系统默认 ABAP 编码规范。"));
        }

        // ===== 节点2: 深度搜索到的相关程序(父程序调用上下文) =====
        if (parentProgramContext != null && !parentProgramContext.isEmpty()) {
            StringBuilder parentSb = new StringBuilder();
            parentSb.append("[PARENT PROGRAMS - 节点2/6] 深度搜索到的相关程序（调用当前 INCLUDE 的父级程序上下文，已截断）\n\n");
            parentSb.append(cacheManager.compressContent(parentProgramContext));
            messages.add(new ChatMessage("user", parentSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[PARENT PROGRAMS - 节点2/6] 未找到父级调用程序，当前文件为独立程序或父级查找功能未启用。"));
        }

        // ===== 节点3: 当前工作区打开的相关程序 =====
        if (workspaceCodeRef != null && !workspaceCodeRef.isEmpty()) {
            StringBuilder wsSb = new StringBuilder();
            wsSb.append("[WORKSPACE OPEN FILES - 节点3/6] 当前 Eclipse 工作区中打开的其他 ABAP 程序（作为代码风格参考与上下文）\n\n");
            // 与节点1、2 一致，通过公共压缩逻辑 compressContent 处理；
            // 传入用户配置的最大工作区字符数作为压缩上限。
            wsSb.append(cacheManager.compressContent(workspaceCodeRef, workspaceMaxChars));
            messages.add(new ChatMessage("user", wsSb.toString()));
        } else {
            messages.add(new ChatMessage("user",
                    "[WORKSPACE OPEN FILES - 节点3/6] 工作区中未找到其他已打开的 ABAP 程序，或该功能未启用。"));
        }

        // ===== 节点4: 当前光标所在程序(含 INCLUDE 展开) =====
        StringBuilder currentSb = new StringBuilder();
        currentSb.append("[CURRENT PROGRAM - 节点4/6] 当前光标所在的 ABAP 程序（已展开 INCLUDE）\n\n");
        currentSb.append("文件名: ").append(fileName).append("\n");
        currentSb.append("代码类型: ").append(codeType != null ? codeType : "AUTO-DETECT").append("\n\n");
        currentSb.append("--- 程序完整代码（含 INCLUDE 展开） ---\n");
        currentSb.append(codeContext);
        messages.add(new ChatMessage("user", currentSb.toString()));

        // ===== 节点5: 程序文本标题属性描述等信息 =====
        StringBuilder metaSb = new StringBuilder();
        metaSb.append("[PROGRAM METADATA - 节点5/6] 程序文本标题与属性描述信息\n\n");
        metaSb.append("文件名: ").append(fileName).append("\n");
        metaSb.append("代码类型: ").append(codeType != null ? codeType : "UNKNOWN").append("\n");
        metaSb.append("检测到的 INCLUDE 数量: ").append(
                (codeContext != null && codeContext.contains("INCLUDE"))
                        ? "已展开（见节点4）" : "未检测到 INCLUDE 语句").append("\n");
        metaSb.append("父级程序解析: ").append(
                (parentProgramContext != null && !parentProgramContext.isEmpty())
                        ? "已找到（见节点2）" : "未找到或未启用").append("\n");
        metaSb.append("工作区参考文件: ").append(
                (workspaceCodeRef != null && !workspaceCodeRef.isEmpty())
                        ? "已加载（见节点3）" : "无其他打开文件").append("\n");
        metaSb.append("SKILL 加载: ").append(
                (skillContent != null && !skillContent.isEmpty())
                        ? "已加载（见节点1）" : "无 SKILL 文件").append("\n");
        metaSb.append("\n提示: 请综合以上所有上下文信息，在节点6的光标位置生成正确的 ABAP 代码。");
        messages.add(new ChatMessage("user", metaSb.toString()));

        // ===== 节点6: 当前光标位置信息及上下文(前后N行) =====
        StringBuilder cursorSb = new StringBuilder();
        cursorSb.append("[CURSOR CONTEXT - 节点6/6] 当前光标位置信息及上下文（前后N行）\n\n");

        String[] lines = textBeforeCursor.split("\n");
        int totalLines = lines.length;
        int cursorLineNum = totalLines;
        int cursorCol = 0;
        if (totalLines > 0) {
            cursorCol = lines[totalLines - 1].length() + 1;
        }

        cursorSb.append("光标位置: 第 ").append(cursorLineNum).append(" 行, 第 ").append(cursorCol).append(" 列\n\n");

        int beforeLines = Math.min(15, lines.length);
        if (beforeLines > 0) {
            cursorSb.append("--- 光标前 ").append(beforeLines).append(" 行 ---\n");
            for (int i = lines.length - beforeLines; i < lines.length; i++) {
                cursorSb.append(String.format("%5d: ", i + 1)).append(lines[i]).append("\n");
            }
        }

        cursorSb.append(String.format("%5d: ", cursorLineNum))
                .append(lines.length > 0 ? lines[lines.length - 1] : "")
                .append("[[[CURSOR_HERE]]]").append("\n");

        String[] afterLines = textAfterCursor.split("\n");
        int afterCount = Math.min(5, afterLines.length);
        if (afterCount > 0 && !textAfterCursor.trim().isEmpty()) {
            cursorSb.append("--- 光标后 ").append(afterCount).append(" 行 ---\n");
            for (int i = 0; i < afterCount; i++) {
                cursorSb.append(String.format("%5d: ", cursorLineNum + i + 1))
                        .append(afterLines[i]).append("\n");
            }
        }

        cursorSb.append("\n请在 [[[CURSOR_HERE]]] 位置生成插入的 ABAP 代码。输出 ONLY the code to insert - no explanations, no markdown.");
        messages.add(new ChatMessage("user", cursorSb.toString()));

        return messages;
    }

    private static String cleanupCompletion(String completion) {
        if (completion == null) return null;
        String result = completion.trim();
        if (result.contains("```")) {
            result = result.replaceAll("```[a-zA-Z]*\\n?", "");
            result = result.replaceAll("\\n?```", "");
            result = result.trim();
        }
        return result;
    }

}
