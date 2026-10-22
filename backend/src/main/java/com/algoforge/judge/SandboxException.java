package com.algoforge.judge;

/** Infrastructure failure (docker unavailable, container died). Never caused by the submitted program itself. */
public class SandboxException extends RuntimeException {
    public SandboxException(String message) { super(message); }
    public SandboxException(String message, Throwable cause) { super(message, cause); }
}
