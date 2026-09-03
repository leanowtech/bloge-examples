package com.leanowtech.bloge.gateway.agenttdd;

/** Application-level tool failure carrying only a stable code and payload-free safe message. */
final class AgentTddToolException extends RuntimeException {
    private final String code;

    AgentTddToolException(String code, String message) {
        super(message == null ? "Resource Gateway tool failed." : message);
        this.code = code == null || code.isBlank() ? "GATE_REJECTED" : code;
    }

    String code() {
        return code;
    }
}
