package com.leanowtech.bloge.gateway.testing.world.mutation;

/** Payload-free fail-closed error for World mutation planning and verification. */
public final class WorldMutationException extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        WORLD_DRIFT,
        SLICE_DRIFT,
        SOURCE_DRIFT,
        PLAN_DRIFT,
        MUTANT_DRIFT,
        COMPILATION_FAILED,
        EQUIVALENCE_RECEIPT_INVALID,
        EQUIVALENCE_RECEIPT_REUSED,
        MATRIX_INCOMPLETE,
        MATRIX_DUPLICATE,
        CROSS_TENANT,
        PAYLOAD_FORBIDDEN,
        GATE_INCOMPLETE
    }

    private final Code code;

    public WorldMutationException(Code code) {
        super("RG.WORLD.MUTATION." + (code == null ? Code.INVALID_INPUT : code).name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }
}
