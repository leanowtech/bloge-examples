package com.leanowtech.bloge.gateway.visual.authoring.fixture;

/** Closed, payload-free failure raised while compiling parent-Flow APPLY_CASE controls. */
public final class ParentFlowApplyCaseFailure extends RuntimeException {
    public enum Code { VALIDATION, NOT_FOUND, INTEGRITY, UNSUPPORTED }

    private final Code code;

    public ParentFlowApplyCaseFailure(Code code) {
        super("Parent Flow Fixture cannot be compiled: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
