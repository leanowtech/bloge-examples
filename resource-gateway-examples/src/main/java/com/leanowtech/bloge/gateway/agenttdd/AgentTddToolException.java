package com.leanowtech.bloge.gateway.agenttdd;

import java.util.Map;

/** Application-level tool failure carrying a stable code and payload-free structured details. */
final class AgentTddToolException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    AgentTddToolException(String code, String message) {
        this(code, message, Map.of());
    }

    AgentTddToolException(String code, String message, Map<String, Object> details) {
        super(message == null ? "Resource Gateway tool failed." : message);
        this.code = code == null || code.isBlank() ? "GATE_REJECTED" : code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    String code() {
        return code;
    }

    Map<String, Object> details() {
        return details;
    }
}
