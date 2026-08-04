package com.sap.abap.ai.completion.editor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.IWorkbenchPage;

import com.sap.abap.ai.completion.client.AIClient;
import com.sap.abap.ai.completion.client.AIClientException;
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
                    WorkspaceCodeCollector collector =
                            new WorkspaceCodeCollector(fileName, fileExt, fileLimit, maxChars, workbenchPage);
                    workspaceCodeRef = collector.collectWorkspaceCode();
                }

                // Build prompts
                String codeType = detectCodeType(file);
                String systemPrompt = getEffectiveSystemPrompt(codeType);
                String userPrompt = buildUserPrompt(context.buildPromptContext(),
                        parentCtx != null ? parentCtx.buildPromptContext() : "",
                        workspaceCodeRef,
                        codeBefore, codeAfter, codeType);

                // 接口日志: 记录请求
                final String fileName = file != null ? file.getName() : "<unknown>";
                AILogger.logRequest(fileName, systemPrompt, userPrompt);
                final long startTs = System.currentTimeMillis();

                // Call AI
                String result = AIClient.complete(systemPrompt, userPrompt);

                // 接口日志: 记录响应
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

    private static String buildUserPrompt(String codeContext, String parentProgramContext,
                                           String workspaceCodeRef,
                                           String textBeforeCursor, String textAfterCursor,
                                           String codeType) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Current ABAP Program (with INCLUDES) ===\n");
        sb.append(codeContext);
        sb.append("\n");

        if (parentProgramContext != null && !parentProgramContext.isEmpty()) {
            sb.append("\n=== Parent Programs (calling context, truncated) ===\n");
            sb.append(parentProgramContext);
        }

        if (workspaceCodeRef != null && !workspaceCodeRef.isEmpty()) {
            sb.append(workspaceCodeRef);
        }

        String skillContent = AIConfiguration.loadSkillContents(codeType);
        if (!skillContent.isEmpty()) {
            sb.append("\n=== Available Skill Files (").append(codeType != null ? codeType : "ALL").append(") ===\n");
            sb.append(skillContent);
        }

        sb.append("\n=== Cursor Context ===\n");

        String[] lines = textBeforeCursor.split("\n");
        int contextLines = Math.min(15, lines.length);
        if (contextLines > 0) {
            sb.append("Before cursor:\n");
            for (int i = lines.length - contextLines; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
        }

        sb.append(">>> CURSOR <<<\n");

        String[] afterLines = textAfterCursor.split("\n");
        contextLines = Math.min(5, afterLines.length);
        if (contextLines > 0 && !textAfterCursor.trim().isEmpty()) {
            sb.append("After cursor:\n");
            for (int i = 0; i < contextLines; i++) {
                sb.append(afterLines[i]).append("\n");
            }
        }

        sb.append("\nGenerate only the ABAP code to insert at the cursor position.");
        return sb.toString();
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
