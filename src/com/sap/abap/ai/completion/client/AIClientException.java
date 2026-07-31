package com.sap.abap.ai.completion.client;

/**
 * Exception thrown when AI API interaction fails.
 */
public class AIClientException extends Exception {

    private static final long serialVersionUID = 1L;

    public AIClientException(String message) {
        super(message);
    }

    public AIClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
