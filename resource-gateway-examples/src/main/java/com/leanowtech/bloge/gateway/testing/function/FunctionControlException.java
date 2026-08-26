package com.leanowtech.bloge.gateway.testing.function;

/** Sanitized rejection raised by function-control compilation or runtime execution. */
public final class FunctionControlException extends IllegalArgumentException {

    public enum Code {
        INVALID_INPUT,
        SITE_INVALID,
        SITE_COLLISION,
        INVENTORY_INVALID,
        INVENTORY_LIMIT,
        VALUE_INVALID,
        SCHEMA_INVALID,
        LIMIT_EXCEEDED,
        DECLARATION_INVALID,
        DECLARATION_DRIFT,
        RUNTIME_MISSING,
        RUNTIME_INVALID,
        RULE_INVALID,
        RULE_ZERO_MATCH,
        RULE_OVERLAP,
        RULE_AMBIGUOUS,
        UNKNOWN_NOT_CERTIFIABLE,
        PURE_NOT_OVERRIDDEN,
        PLAN_INVALID,
        RUNTIME_CONTEXT_INVALID,
        RUNTIME_SITE_UNPLANNED,
        RUNTIME_BINDING_DRIFT,
        CONTROL_ARGUMENT_MISMATCH,
        CONTROL_EXHAUSTED,
        CONTROL_THROW,
        CONTROL_TIMEOUT,
        CONTROL_DELAY_FAILED,
        MINIMUM_UNCONSUMED,
        RUNTIME_CLOSED
    }

    private final Code code;

    public FunctionControlException(Code code) {
        super("RG.FUNCTION." + (code == null ? Code.INVALID_INPUT : code).name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public FunctionControlException(Code code, Throwable cause) {
        super("RG.FUNCTION." + (code == null ? Code.INVALID_INPUT : code).name(),
                cause == null ? null : new SanitizedCause());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }

    private static final class SanitizedCause extends RuntimeException {
        private SanitizedCause() {
            super("function control failure");
        }
    }
}
