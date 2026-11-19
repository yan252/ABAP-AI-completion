package com.sap.abap.ai.completion.client;

/**
 * Exception thrown when AI API interaction fails.
 */
public class AIClientException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 服务端返回的完整原始响应体（仅 HTTP 错误时提供，供日志记录用）。 */
    private final String rawResponse;

    public AIClientException(String message) {
        super(message);
        this.rawResponse = null;
    }

    /**
     * @param message     面向用户的简短错误信息
     * @param rawResponse 服务端返回的完整原始响应体（写入日志，不显示在界面）
     */
    public AIClientException(String message, String rawResponse) {
        super(message);
        this.rawResponse = rawResponse;
    }

    public AIClientException(String message, Throwable cause) {
        super(message, cause);
        this.rawResponse = null;
    }

    /** 服务端返回的完整原始响应体；可能为 {@code null}。 */
    public String getRawResponse() {
        return rawResponse;
    }
}
