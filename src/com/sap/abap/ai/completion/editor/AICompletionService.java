package com.sap.abap.ai.completion.editor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.sap.abap.ai.completion.client.AIClient;
import com.sap.abap.ai.completion.client.AIClientException;
import com.sap.abap.ai.completion.parser.AbapIncludeResolver;
import com.sap.abap.ai.completion.parser.AbapIncludeResolver.IncludeContext;
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
     * @param callback      called on success with completion text
     * @param errorCallback called on error with error message
     * @return a CompletableFuture that can be cancelled
     */
    public static CompletableFuture<String> requestCompletion(
            IFile file, String codeBefore, String codeAfter,
            String fullDocument, IProject project,
            Consumer<String> callback,
            Consumer<String> errorCallback) {

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Resolve includes
                String currentCode = fullDocument;
                AbapIncludeResolver resolver = new AbapIncludeResolver(project);
                IncludeContext context = resolver.resolveAllIncludes(currentCode);

                // Build prompts
                String systemPrompt = getEffectiveSystemPrompt();
                String userPrompt = buildUserPrompt(context.buildPromptContext(),
                        codeBefore, codeAfter);

                // Call AI
                return AIClient.complete(systemPrompt, userPrompt);
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

    private static String getEffectiveSystemPrompt() {
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

    private static String buildUserPrompt(String codeContext, String textBeforeCursor, String textAfterCursor) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Current ABAP Program (with INCLUDES) ===\n");
        sb.append(codeContext);
        sb.append("\n");

        String skillContent = AIConfiguration.loadSkillContents();
        if (!skillContent.isEmpty()) {
            sb.append("\n=== Available Skill Files (reference patterns) ===\n");
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
