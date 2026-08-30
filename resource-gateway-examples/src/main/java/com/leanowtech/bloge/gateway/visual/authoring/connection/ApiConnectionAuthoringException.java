package com.leanowtech.bloge.gateway.visual.authoring.connection;

/** Safe domain failure for pure Connection authoring decisions. */
public final class ApiConnectionAuthoringException extends RuntimeException {
    /** Stable protocol categories. Scope-protected references use NOT_FOUND. */
    public enum Code { NOT_FOUND, ALREADY_EXISTS, CAS_MISMATCH, VALIDATION }

    private final Code code;

    public ApiConnectionAuthoringException(Code code, String message) {
        super(safeMessage(message));
        this.code = code;
    }

    public Code code() { return code; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[code=" + code + ", message=" + getMessage() + "]";
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "connection authoring failed";
        return message.replaceAll("(?i)(vault://[^\\s,;]+|secret(?: value| reference)?[=:]\\S+)", "[REDACTED]");
    }
}
