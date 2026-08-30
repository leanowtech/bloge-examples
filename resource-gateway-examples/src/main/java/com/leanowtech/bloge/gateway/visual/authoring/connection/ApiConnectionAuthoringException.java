package com.leanowtech.bloge.gateway.visual.authoring.connection;

/** Safe domain failure for pure Connection authoring decisions. */
public final class ApiConnectionAuthoringException extends RuntimeException {
    /** Stable protocol categories. Scope-protected references use NOT_FOUND. */
    public enum Code { NOT_FOUND, ALREADY_EXISTS, CAS_MISMATCH, VALIDATION }

    private final Code code;

    /** Creates a failure with a fixed, code-derived safe message. */
    public ApiConnectionAuthoringException(Code code) {
        super(safeMessage(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
    }

    /** @return stable machine-readable failure category */
    public Code code() { return code; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[code=" + code + "]";
    }

    private static String safeMessage(Code code) {
        return switch (code == null ? Code.VALIDATION : code) {
            case NOT_FOUND -> "connection or secret was not found";
            case ALREADY_EXISTS -> "connection already exists";
            case CAS_MISMATCH -> "connection revision does not match";
            case VALIDATION -> "connection command is invalid";
        };
    }
}
