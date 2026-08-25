package com.leanowtech.bloge.gateway.testing.world;

/** Payload-free, fail-closed error for the Stage 1 Scenario boundary. */
public final class ScenarioException extends IllegalArgumentException {
    public enum Code {
        INVALID_SCENARIO,
        INVALID_TARGET,
        TARGET_KIND_UNSUPPORTED,
        TARGET_ID_INVALID,
        TARGET_FINGERPRINT_INVALID,
        INVALID_WORLD_REF,
        WORLD_MODEL_REQUIRED,
        WORLD_MODEL_MISMATCH,
        TENANT_DRIFT,
        STATE_NOT_SUPPORTED,
        EXPECTATION_INVALID,
        EXPECTATION_SCOPE_UNSUPPORTED,
        EXPECTATION_OPERATOR_UNSUPPORTED,
        EXPECTATION_PATH_INVALID,
        EXPECTATION_NODE_REQUIRED,
        EXPECTATION_TOLERANCE_INVALID,
        EXPECTATION_NUMERIC_VALUE_REQUIRED,
        EXPECTATION_SCHEMA_REQUIRED,
        CONTRACT_DEPENDENCY_INVALID,
        CONTRACT_NOT_DECLARED,
        CONTRACT_COMPATIBILITY_REVIEW_REQUIRED,
        CONTRACT_INCOMPATIBLE
    }

    private final Code code;

    public ScenarioException(Code code) {
        super("RG.WORLD.SCENARIO." + (code == null ? Code.INVALID_SCENARIO : code).name());
        this.code = code == null ? Code.INVALID_SCENARIO : code;
    }

    public Code code() {
        return code;
    }

    public String wireCode() {
        return getMessage();
    }
}
