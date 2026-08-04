package com.sap.abap.ai.completion.client;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import com.sap.abap.ai.completion.logging.AILogger;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * Client for calling OpenAI Chat Completions API (and compatible endpoints).
 * Pure Java implementation - no external JSON library needed.
 */
public class AIClient {

    private static final String CHAT_ENDPOINT = "/chat/completions";

    public static String complete(String systemPrompt, String userPrompt) throws AIClientException {
        return callChatCompletions(null, null, null, systemPrompt, userPrompt, 0, 0, true);
    }

    public static String testConnection(
            String baseUrl, String model, String apiKey,
            int maxTokens, double temperature) throws AIClientException {
        return callChatCompletions(baseUrl, model, apiKey,
                "You are a helpful assistant.",
                "Reply with only the word: OK",
                maxTokens, temperature, false);
    }

    private static String callChatCompletions(
            String overrideBaseUrl, String overrideModel, String overrideApiKey,
            String systemPrompt, String userPrompt,
            int overrideMaxTokens, double overrideTemperature,
            boolean usePreferences) throws AIClientException {

        String baseUrl;
        String model;
        String apiKey;
        int maxTokens;
        double temperature;

        if (usePreferences) {
            baseUrl = AIConfiguration.getApiBaseUrl();
            model = AIConfiguration.getModel();
            apiKey = AIConfiguration.getApiKey();
            maxTokens = AIConfiguration.getMaxTokens();
            temperature = AIConfiguration.getTemperature();
        } else {
            baseUrl = overrideBaseUrl;
            model = overrideModel;
            apiKey = overrideApiKey;
            maxTokens = overrideMaxTokens;
            temperature = overrideTemperature;
        }

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new AIClientException("API Base URL is not configured.");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AIClientException("API Key is not configured.");
        }
        if (model == null || model.trim().isEmpty()) {
            model = "gpt-4";
        }

        // Normalize URL
        String normalizedUrl = baseUrl.trim();
        if (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        if (normalizedUrl.endsWith("/chat/completions")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - "/chat/completions".length());
        }

        try {
            // Build JSON request body manually (no Gson needed)
            String jsonBody = buildRequestBody(model, systemPrompt, userPrompt, maxTokens, temperature);

            URL url = URI.create(normalizedUrl + CHAT_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            byte[] requestBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(requestBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            String responseBody;
            if (responseCode >= 200 && responseCode < 300) {
                responseBody = readStream(conn.getInputStream());
            } else {
                String errorBody = readStream(conn.getErrorStream());
                throw new AIClientException("HTTP " + responseCode + ": " + errorBody);
            }

            // Parse JSON response manually (no Gson needed)
            return parseChatCompletionResponse(responseBody);

        } catch (AIClientException e) {
            throw e;
        } catch (Exception e) {
            throw new AIClientException("Failed to call AI API: " + e.getMessage(), e);
        }
    }

    // ==================== JSON Builder (no Gson) ====================

    // 代码补全场景下 max_tokens 的合理上限
    // 设置过大(如 240640)会导致 input + output 超过模型上下文窗口而报 HTTP 400
    private static final int MAX_TOKENS_CAP = 4096;
    private static final int MAX_TOKENS_MIN = 16;

    private static String buildRequestBody(String model, String systemPrompt,
                                           String userPrompt, int maxTokens,
                                           double temperature) {
        // 限制 max_tokens 在合理范围内,避免配置过大导致上下文窗口溢出
        int safeMaxTokens = maxTokens;
        if (safeMaxTokens > MAX_TOKENS_CAP) {
            AILogger.logError("AIClient",
                    "[WARN] Configured max_tokens=" + maxTokens
                    + " exceeds cap " + MAX_TOKENS_CAP
                    + " (code completion does not need that many output tokens). "
                    + "Auto-capping to " + MAX_TOKENS_CAP + ".");
            safeMaxTokens = MAX_TOKENS_CAP;
        }
        if (safeMaxTokens < MAX_TOKENS_MIN) {
            safeMaxTokens = MAX_TOKENS_MIN;
        }

        // 输入过长时截断(兜底保护,避免上下文窗口溢出)
        // 粗略估计: 4 字符 ≈ 1 token, 保留 32K tokens 给 system+output
        int maxInputChars = 120000;
        String safeUserPrompt = userPrompt;
        if (userPrompt != null && userPrompt.length() > maxInputChars) {
            safeUserPrompt = userPrompt.substring(0, maxInputChars)
                    + "\n\n... [truncated: input too long, kept first " + maxInputChars + " chars]";
            AILogger.logError("AIClient",
                    "[WARN] User prompt length=" + userPrompt.length()
                    + " exceeds " + maxInputChars + " chars. Truncated to avoid context window overflow.");
        }
        String safeSystemPrompt = systemPrompt;
        if (systemPrompt != null && systemPrompt.length() > 20000) {
            safeSystemPrompt = systemPrompt.substring(0, 20000);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"max_tokens\":").append(safeMaxTokens).append(",");
        sb.append("\"temperature\":").append(temperature).append(",");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"")
          .append(escapeJson(safeSystemPrompt)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":\"")
          .append(escapeJson(safeUserPrompt)).append("\"}");
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
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
        return sb.toString();
    }

    // ==================== JSON Parser (no Gson) ====================

    private static String parseChatCompletionResponse(String json) throws AIClientException {
        try {
            // Check for error
            String errorKey = "\"error\"";
            int errorIdx = json.indexOf(errorKey);
            if (errorIdx >= 0) {
                // Extract "message" from error object
                String msgKey = "\"message\"";
                int msgIdx = json.indexOf(msgKey, errorIdx);
                if (msgIdx >= 0) {
                    int colonIdx = json.indexOf(':', msgIdx);
                    int startIdx = json.indexOf('"', colonIdx + 1);
                    if (startIdx >= 0) {
                        int endIdx = findStringEnd(json, startIdx + 1);
                        if (endIdx >= 0) {
                            String errorMsg = json.substring(startIdx + 1, endIdx);
                            throw new AIClientException("API error: " + errorMsg);
                        }
                    }
                }
                throw new AIClientException("API returned an error");
            }

            // Try to extract from "choices" array
            String choicesKey = "\"choices\"";
            int choicesIdx = json.indexOf(choicesKey);
            if (choicesIdx < 0) {
                throw new AIClientException("API response: no choices found");
            }

            // Find the first object in the array
            int firstBrace = json.indexOf('{', choicesIdx);
            if (firstBrace < 0) {
                throw new AIClientException("API response: invalid choices format");
            }

            // Try "message"."content" (Chat Completions)
            String contentKey = "\"content\"";
            int contentIdx = json.indexOf(contentKey, firstBrace);
            if (contentIdx >= 0) {
                // Check if this content is inside a "message" object
                int messageKeyIdx = json.lastIndexOf("\"message\"", contentIdx);
                if (messageKeyIdx < 0 || messageKeyIdx < firstBrace) {
                    // content not inside message, check for "text" (old format)
                } else {
                    int colonIdx = json.indexOf(':', contentIdx);
                    int startQuote = json.indexOf('"', colonIdx + 1);
                    if (startQuote >= 0) {
                        int endIdx = findStringEnd(json, startQuote + 1);
                        if (endIdx >= 0) {
                            return unescapeJson(json.substring(startQuote + 1, endIdx));
                        }
                    }
                }
            }

            // Fallback: try "text" (old Completions format)
            String textKey = "\"text\"";
            int textIdx = json.indexOf(textKey, firstBrace);
            if (textIdx >= 0) {
                int colonIdx = json.indexOf(':', textIdx);
                int startQuote = json.indexOf('"', colonIdx + 1);
                if (startQuote >= 0) {
                    int endIdx = findStringEnd(json, startQuote + 1);
                    if (endIdx >= 0) {
                        return unescapeJson(json.substring(startQuote + 1, endIdx));
                    }
                }
            }

            throw new AIClientException("API response: could not parse completion text");
        } catch (AIClientException e) {
            throw e;
        } catch (Exception e) {
            throw new AIClientException("Failed to parse API response: " + e.getMessage());
        }
    }

    /**
     * Find the end of a JSON string (the closing quote, respecting escapes).
     */
    private static int findStringEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++; // skip escaped char
            } else if (s.charAt(i) == '"') {
                return i; // end of string
            }
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
                    case 'u':
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else {
                            sb.append(c);
                        }
                        break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readStream(java.io.InputStream stream) {
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}
