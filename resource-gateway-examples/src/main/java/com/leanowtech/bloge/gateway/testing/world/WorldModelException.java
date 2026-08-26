package com.leanowtech.bloge.gateway.testing.world;

/**
 * Sanitized, fail-closed error for the S1-B world-model boundary.
 *
 * <p>Messages intentionally contain only stable error codes. They never include DSL, payload,
 * credentials, or parser internals.</p>
 */
public final class WorldModelException extends IllegalArgumentException {
    public enum Code {
        INVALID_MODEL,
        INVALID_SLICE,
        DUPLICATE_SLICE,
        TENANT_DRIFT,
        CONTRACT_MISMATCH,
        CONTRACT_DRIFT,
        BINDING_MISMATCH,
        BINDING_DRIFT,
        BINDING_UNAVAILABLE,
        STATE_NOT_SUPPORTED,
        FRAGMENT_INVALID,
        FRAGMENT_NOT_PURE,
        FRAGMENT_RESOURCE_FORBIDDEN,
        FRAGMENT_NETWORK_FORBIDDEN,
        FRAGMENT_FILESYSTEM_FORBIDDEN,
        FRAGMENT_WORLD_DELEGATION_FORBIDDEN,
        FRAGMENT_UNRESOLVED_CAPABILITY,
        FRAGMENT_NONDETERMINISTIC,
        FRAGMENT_AMBIGUOUS,
        FRAGMENT_EXECUTION_FAILED,
        STATE_INPUT_INVALID,
        STATE_WRITESET_INVALID,
        STATE_UNKNOWN_WRITE,
        STATE_READ_ONLY_WRITE,
        STATE_SCHEMA_MISMATCH,
        STATE_OUTPUT_MISSING,
        STATE_OUTPUT_EXTRA_FIELDS,
        STATE_OUTPUT_INVALID,
        NON_DETERMINISTIC_REPLAY,
        LIMIT_EXCEEDED,
        LIMIT_NODE_EXCEEDED,
        LIMIT_DEPTH_EXCEEDED,
        LIMIT_TIMEOUT,
        LIMIT_OUTPUT_EXCEEDED
    }

    private final Code code;

    public WorldModelException(Code code) {
        super(code == null ? "RG.WORLD.INVALID" : "RG.WORLD." + code.name());
        this.code = code == null ? Code.INVALID_MODEL : code;
    }

    public Code code() {
        return code;
    }

    public String wireCode() {
        return "RG.WORLD." + code.name();
    }
}
