package com.leanowtech.bloge.gateway.agenttdd;

/** Safe protocol-boundary failure; it never carries business payload or downstream response data. */
public final class McpProtocolException extends RuntimeException {
    private final int code;

    public McpProtocolException(int code, String message) {
        super(message == null ? "Invalid MCP request" : message);
        this.code = code;
    }

    /** @return JSON-RPC error code */
    public int code() {
        return code;
    }
}
