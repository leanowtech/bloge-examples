package com.leanowtech.bloge.gateway.testing.world.impact;

/** Sanitized, payload-free failure at the World impact-index boundary. */
public final class WorldImpactException extends IllegalArgumentException {
    public enum Code {
        INVALID_INPUT,
        TENANT_SCOPE,
        SOURCE_INTEGRITY,
        EVIDENCE_UNVERIFIED,
        MAPPING_MISSING,
        FINGERPRINT_MISMATCH,
        INDEX_CONFLICT,
        INDEX_STALE,
        IMPACT_UNKNOWN,
        LIMIT_EXCEEDED
    }

    private final Code code;

    public WorldImpactException(Code code) {
        super("RG.WORLD_IMPACT." + (code == null ? Code.INVALID_INPUT : code).name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }
}
