package com.leanowtech.bloge.gateway.testing.world;

/** Payload-free, fail-closed error from the pure Scenario-to-FixtureBundle compiler. */
public final class WorldScenarioCompilationException extends IllegalArgumentException {
    public enum Code {
        INVALID_INPUT,
        TARGET_KIND_UNSUPPORTED,
        ADMISSION_REJECTED,
        TARGET_DRIFT,
        WORLD_DRIFT,
        INVALID_SELECTION,
        SELECTION_MISSING,
        SELECTION_EXTRA,
        SELECTION_NOT_UNIQUE,
        INVALID_BINDING,
        INVALID_COMPILATION,
        COMPILATION_FINGERPRINT_MISMATCH,
        SOURCE_MAP_INVALID,
        CONTRACT_NOT_DECLARED,
        CONTRACT_DRIFT,
        TAG_INVALID,
        MULTIPLE_CONTRACT_TAGS,
        ZERO_MATCH,
        INVOCATION_INVENTORY,
        SELECTOR_RESOLUTION,
        WORLD_STATE_ACCESS_ORDER_AMBIGUOUS
    }

    private final Code code;

    public WorldScenarioCompilationException(Code code) {
        super("RG.WORLD.COMPILER." + (code == null ? Code.INVALID_INPUT : code).name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }

    public String wireCode() {
        return getMessage();
    }
}
