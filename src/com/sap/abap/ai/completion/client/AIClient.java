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

    /**
     * Represents a single chat message.
     */
    public static final class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static String complete(String systemPrompt, String userPrompt) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", userPrompt));
        return callChatCompletions(null, null, null, messages, 0, 0, true, null, null);
    }

    public static String completeWithMessages(String systemPrompt, java.util.List<ChatMessage> userMessages) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        if (userMessages != null) {
            messages.addAll(userMessages);
        }
        return callChatCompletions(null, null, null, messages, 0, 0, true, null, null);
    }

    /**
     * 带单个缓存键的多消息调用（兼容旧接口）。
     */
    public static String completeWithMessages(String systemPrompt,
                                               java.util.List<ChatMessage> userMessages,
                                               String promptCacheKey,
                                               int[] breakpointIndices) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        if (userMessages != null) {
            messages.addAll(userMessages);
        }
        String[] keys = (promptCacheKey != null) ? new String[]{promptCacheKey} : null;
        return callChatCompletions(null, null, null, messages, 0, 0, true, keys, breakpointIndices);
    }

    /**
     * 带多个缓存键的多消息调用（三节点独立缓存版）。
     *
     * @param systemPrompt      系统提示
     * @param userMessages      user 消息列表
     * @param cacheKeys         缓存键数组（节点1/2/3各自的 key，null 表示不使用缓存）
     * @param breakpointIndices 缓存断点索引数组，与 cacheKeys 一一对应
     * @return AI 补全结果
     */
    public static String completeWithMultiCache(String systemPrompt,
                                                 java.util.List<ChatMessage> userMessages,
                                                 String[] cacheKeys,
                                                 int[] breakpointIndices) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        if (userMessages != null) {
            messages.addAll(userMessages);
        }
        return callChatCompletions(null, null, null, messages, 0, 0, true, cacheKeys, breakpointIndices);
    }

    /**
     * 预热缓存：仅传 system + 单个节点内容，建立 AI 服务端缓存。
     * max_tokens=1，结果丢弃，只为建立缓存。
     *
     * @param systemPrompt 系统提示
     * @param content      节点完整内容
     * @param cacheKey     缓存键
     * @return AI 返回的结果（通常丢弃）
     */
    public static String warmupCache(String systemPrompt, String content, String cacheKey) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", content));
        String[] keys = (cacheKey != null) ? new String[]{cacheKey} : null;
        // max_tokens=16 (最小值), temperature=0, breakpoint=[1]
        return callChatCompletions(null, null, null, messages, 16, 0.0, true, keys, new int[]{1});
    }

    /**
     * 构建预热请求的 JSON body（仅用于日志记录，不实际发送）。
     * 供调用方在发送预热请求前，先记录报文内容。
     * 预热请求: max_tokens=16（最小输出）、temperature=0、单个缓存键、断点[1]。
     * 注意：输入截断使用用户配置的 max_tokens 计算，与实际 warmupCache 请求一致。
     */
    public static String buildWarmupRequestBody(String systemPrompt, String content, String cacheKey) {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", content));
        // 先构建完整 JSON（用用户配置的 max_tokens 计算输入上限）
        String[] keys = (cacheKey != null) ? new String[]{cacheKey} : null;
        String model = AIConfiguration.getModel();
        int userMaxTokens = AIConfiguration.getMaxTokens();
        String fullJson = buildRequestBody(model != null ? model : "gpt-4",
                messages, userMaxTokens, 0.0, keys, new int[]{1});
        // 将 max_tokens 值替换为 16（预热只需要最小输出）
        // 格式: "max_tokens":240640 → "max_tokens":16
        return fullJson.replaceAll(
                "\"max_tokens\":\\d+",
                "\"max_tokens\":16");
    }

    /**
     * 缓存校验结果。
     */
    public static class CacheVerifyResult {
        /** AI 服务端检测到被命中的缓存 token 数；>0 表示本地内容与 AI 中该 key 缓存的内容一致 */
        public final int cachedTokens;
        /** 校验请求是否发生了缓存失效/未命中错误 */
        public final boolean cacheError;
        /** 校验请求的原始错误信息（若有） */
        public final String error;

        CacheVerifyResult(int cachedTokens, boolean cacheError, String error) {
            this.cachedTokens = cachedTokens;
            this.cacheError = cacheError;
            this.error = error;
        }
    }

    /**
     * 连接 AI 校验某个缓存键是否仍然有效且与当前内容一致。
     *
     * 实现方式：用传入的 cacheKey + 完整 content 发起一次最小输出请求，
     * AI 服务端会复用该 key 对应的缓存。若缓存内容与传入内容一致，
     * 服务端命中缓存并返回 cached_tokens > 0（无报错）；若缓存已失效或内容不匹配，
     * 服务端会报缓存错误，或返回 cached_tokens = 0。
     *
     * @param systemPrompt 系统提示
     * @param content      节点本地当前完整内容
     * @param cacheKey     需要校验的缓存键
     * @return 校验结果（缓存命中 token 数 / 是否缓存错误 / 错误信息）
     */
    public static CacheVerifyResult verifyCache(String systemPrompt, String content, String cacheKey) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", content));
        String[] keys = (cacheKey != null) ? new String[]{cacheKey} : null;
        // max_tokens=16（最小值）、temperature=0、breakpoint=[1]，仅为校验缓存，输出丢弃
        String raw = callAndReturnRaw(null, null, null, messages, 16, 0.0, true, keys, new int[]{1});
        return parseCacheVerify(raw);
    }

    /**
     * 与 callChatCompletions 相同，但返回原始 JSON（供缓存校验解析 usage）。
     */
    private static String callAndReturnRaw(
            String overrideBaseUrl, String overrideModel, String overrideApiKey,
            java.util.List<ChatMessage> messages,
            int overrideMaxTokens, double overrideTemperature,
            boolean usePreferences,
            String[] cacheKeys, int[] breakpointIndices) throws AIClientException {

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

        String normalizedUrl = baseUrl.trim();
        if (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        if (normalizedUrl.endsWith("/chat/completions")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - "/chat/completions".length());
        }

        try {
            String jsonBody = buildRequestBody(model, messages, maxTokens, temperature,
                    cacheKeys, breakpointIndices);

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
            if (responseCode >= 200 && responseCode < 300) {
                return readStream(conn.getInputStream());
            }
            String errorBody = readStream(conn.getErrorStream());
            // 缓存失效/未命中类错误：归类为 cacheError，交由上层判断
            if (isCacheErrorBody(errorBody)) {
                return "{\"cacheError\":true,\"error\":" + quoteJson(truncate(errorBody, 500)) + "}";
            }
            throw new AIClientException("HTTP " + responseCode + ": " + errorBody);

        } catch (AIClientException e) {
            throw e;
        } catch (Exception e) {
            throw new AIClientException("Failed to call AI API: " + e.getMessage(), e);
        }
    }

    /**
     * 判断响应体是否属于缓存失效/未命中类错误。
     */
    private static boolean isCacheErrorBody(String body) {
        if (body == null || body.isEmpty()) return false;
        String lower = body.toLowerCase();
        return lower.contains("cache") && (lower.contains("not found")
                || lower.contains("expired")
                || lower.contains("invalid")
                || lower.contains("doesn't exist")
                || lower.contains("does not exist")
                || lower.contains("unrecognized")
                || lower.contains("mismatch")
                || lower.contains("no cache"));
    }

    /**
     * 从 AI 响应原始 JSON 中解析缓存校验结果。
     * 读取 usage.prompt_tokens_details.cached_tokens。
     */
    private static CacheVerifyResult parseCacheVerify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new CacheVerifyResult(0, false, "empty response");
        }
        // 缓存错误标记（由 callAndReturnRaw 注入）
        String cacheErrorFlag = "\"cacheError\":true";
        if (raw.contains(cacheErrorFlag)) {
            String err = extractJsonString(raw, "\"error\"");
            return new CacheVerifyResult(0, true, err);
        }
        // 检测 usage 中是否有报错字段
        String errorKey = "\"error\"";
        int errorIdx = raw.indexOf(errorKey);
        if (errorIdx >= 0 && raw.indexOf("\"usage\"") < 0) {
            String err = extractJsonString(raw, "\"message\"");
            if (err == null) err = extractJsonString(raw, "\"error\"");
            return new CacheVerifyResult(0, true, err);
        }
        // 解析 cached_tokens
        int cached = 0;
        int ctIdx = raw.indexOf("cached_tokens");
        if (ctIdx >= 0) {
            int colonIdx = raw.indexOf(':', ctIdx);
            if (colonIdx >= 0) {
                int endIdx = colonIdx + 1;
                while (endIdx < raw.length()
                        && (Character.isDigit(raw.charAt(endIdx))
                            || raw.charAt(endIdx) == '-'
                            || raw.charAt(endIdx) == '.')) {
                    endIdx++;
                }
                if (endIdx > colonIdx + 1) {
                    try {
                        cached = (int) Double.parseDouble(raw.substring(colonIdx + 1, endIdx).trim());
                    } catch (NumberFormatException ignored) {
                        cached = 0;
                    }
                }
            }
        }
        return new CacheVerifyResult(cached, false, null);
    }

    /**
     * 从 JSON 中提取指定 key 的字符串值（返回解码后内容）；找不到返回 null。
     */
    private static String extractJsonString(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endIdx = findStringEnd(json, startQuote + 1);
        if (endIdx < 0) return null;
        return unescapeJson(json.substring(startQuote + 1, endIdx));
    }

    /** 将字符串转成 JSON 字符串字面量（含引号）。 */
    private static String quoteJson(String s) {
        return "\"" + escapeJson(s == null ? "" : s) + "\"";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    public static String testConnection(
            String baseUrl, String model, String apiKey,
            int maxTokens, double temperature) throws AIClientException {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", "You are a helpful assistant."));
        messages.add(new ChatMessage("user", "Reply with only the word: OK"));
        return callChatCompletions(baseUrl, model, apiKey, messages, maxTokens, temperature, false, null, null);
    }

    private static String callChatCompletions(
            String overrideBaseUrl, String overrideModel, String overrideApiKey,
            java.util.List<ChatMessage> messages,
            int overrideMaxTokens, double overrideTemperature,
            boolean usePreferences,
            String[] cacheKeys, int[] breakpointIndices) throws AIClientException {

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
            String jsonBody = buildRequestBody(model, messages, maxTokens, temperature,
                    cacheKeys, breakpointIndices);

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

    private static String buildRequestBody(String model, java.util.List<ChatMessage> messages,
                                           int maxTokens, double temperature,
                                           String[] cacheKeys, int[] breakpointIndices) {
        // 限制 max_tokens 在合理范围内,避免配置过大导致上下文窗口溢出
        // 这里 max_tokens 是输出上限，代码补全场景下 4096 足够
        int safeMaxTokens = maxTokens;
        if (safeMaxTokens > MAX_TOKENS_CAP) {
            // 仅在接口日志中记录
            AILogger.logDiagnostic("AIClient",
                    "[INFO] max_tokens configured=" + maxTokens
                    + " exceeds code completion cap " + MAX_TOKENS_CAP
                    + ", auto-capped to " + MAX_TOKENS_CAP + ".");
            safeMaxTokens = MAX_TOKENS_CAP;
        }
        if (safeMaxTokens < MAX_TOKENS_MIN) {
            safeMaxTokens = MAX_TOKENS_MIN;
        }

        // 输入过长时截断(兜底保护,避免上下文窗口溢出)
        // 输入上限基于用户配置的 max_tokens 按 4:1 字符/token 比例估算
        // 例如 max_tokens=240640 → 输入上限 = 240640 * 4 = 962560 字符 ≈ 960K
        // 但上限不能超过 1200000（约 300K tokens），防止内存溢出
        int maxInputChars = Math.min(maxTokens * 4, 1200000);
        int totalChars = 0;
        boolean truncated = false;
        java.util.List<ChatMessage> safeMessages = new java.util.ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                String safeContent = msg.content;
                if (safeContent != null && totalChars + safeContent.length() > maxInputChars) {
                    int remaining = maxInputChars - totalChars;
                    if (remaining > 100) {
                        safeContent = safeContent.substring(0, remaining)
                                + "\n\n... [truncated: input too long]";
                        truncated = true;
                    } else {
                        break; // 跳过剩余消息
                    }
                }
                // system prompt 单独限制 20000 字符
                if ("system".equals(msg.role) && safeContent != null && safeContent.length() > 20000) {
                    safeContent = safeContent.substring(0, 20000);
                }
                if (safeContent != null) {
                    totalChars += safeContent.length();
                }
                safeMessages.add(new ChatMessage(msg.role, safeContent));
            }
        }
        if (truncated) {
            AILogger.logError("AIClient",
                    "[WARN] Total prompt length=" + totalChars
                    + " exceeds " + maxInputChars + " chars. Truncated to avoid context window overflow.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"max_tokens\":").append(safeMaxTokens).append(",");
        sb.append("\"temperature\":").append(temperature).append(",");
        sb.append("\"messages\":[");
        for (int i = 0; i < safeMessages.size(); i++) {
            ChatMessage msg = safeMessages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(escapeJson(msg.role)).append("\",");
            sb.append("\"content\":\"").append(escapeJson(msg.content)).append("\"}");
        }
        sb.append("]");

        // === Prompt Cache 参数 ===
        // 多个缓存键时用 prompt_cache_keys 数组，单个时用 prompt_cache_key
        if (cacheKeys != null && cacheKeys.length > 0) {
            if (cacheKeys.length == 1) {
                // 单 key: 用 prompt_cache_key
                sb.append(",\"prompt_cache_key\":\"").append(escapeJson(cacheKeys[0])).append("\"");
            } else {
                // 多 key: 用 prompt_cache_keys 数组（节点1/2/3各自独立缓存）
                sb.append(",\"prompt_cache_keys\":[");
                for (int i = 0; i < cacheKeys.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(cacheKeys[i])).append("\"");
                }
                sb.append("]");
            }
        }

        // 缓存断点: 指定在哪些消息索引处设置缓存断点
        // 多 key 时与 cacheKeys 一一对应，每个 breakpoint 标记一段缓存的结束位置
        if (breakpointIndices != null && breakpointIndices.length > 0) {
            sb.append(",\"prompt_cache_breakpoint\":[");
            for (int i = 0; i < breakpointIndices.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(breakpointIndices[i]);
            }
            sb.append("]");
        }

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
