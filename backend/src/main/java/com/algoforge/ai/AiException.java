package com.algoforge.ai;

public class AiException extends Exception {
    public enum Kind { TIMEOUT, RETRYABLE, FATAL, INVALID_OUTPUT }

    private final Kind kind;

    public AiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
