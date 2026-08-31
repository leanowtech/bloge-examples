package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** Closed payload-free failure taxonomy for Flow compilation and authoring. */
public final class ReusableFlowFailure extends RuntimeException {
    public enum Code {
        VALIDATION, DEPENDENCY_NOT_FOUND, DEPENDENCY_DRIFT, MAPPING_INVALID,
        SCHEMA_INCOMPATIBLE, CYCLE, LAYOUT_INVALID
    }

    private final Code code;

    public ReusableFlowFailure(Code code) {
        super("Reusable Flow operation failed: " + code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
