package com.leanowtech.bloge.gateway.visual.authoring.resource;

/** Stable domain-protocol failure from the API Resource authoring module. */
public final class ApiResourceAuthoringException extends RuntimeException {

    /** Machine-readable authoring failure categories. */
    public enum Code {
        NOT_FOUND,
        ALREADY_EXISTS,
        CAS_MISMATCH,
        VALIDATION
    }

    private final Code code;

    /**
     * @param code stable failure code used by protocol adapters
     * @param message diagnostic safe for logs and clients (must not contain secrets)
     */
    public ApiResourceAuthoringException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** @return stable machine-readable category */
    public Code code() {
        return code;
    }
}
