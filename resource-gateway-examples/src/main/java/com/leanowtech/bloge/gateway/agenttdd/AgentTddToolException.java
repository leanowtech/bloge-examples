package com.leanowtech.bloge.gateway.agenttdd;

import java.util.Map;

/** Application-level tool failure carrying a stable code and payload-free structured details. */
public final class AgentTddToolException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final boolean retryable;

    public AgentTddToolException(String code, String message) {
        this(code, message, Map.of(), false);
    }

    public AgentTddToolException(String code, String message, Map<String, Object> details) {
        this(code, message, details, false);
    }

    /** Creates a stable application failure with an explicit retry contract. */
    public AgentTddToolException(String code, String message, Map<String, Object> details, boolean retryable) {
        super(message == null ? "Resource Gateway tool failed." : message);
        this.code = code == null || code.isBlank() ? "GATE_REJECTED" : code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public boolean retryable() {
        return retryable;
    }
}
