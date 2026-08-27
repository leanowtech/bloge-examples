package com.leanowtech.bloge.gateway.testing.world.migration;

/** Sanitized fail-closed error raised by the legacy world migrator. */
public final class WorldMigrationException extends IllegalArgumentException {
    public enum Code {
        INVALID_INPUT,
        SOURCE_INTEGRITY,
        SOURCE_TAMPERED,
        SOURCE_TENANT_MISMATCH,
        SOURCE_LIMIT_EXCEEDED,
        MAPPING_MISSING,
        MATERIALIZATION_PREREQUISITE_MISSING,
        MATERIALIZATION_INVALID,
        UNSUPPORTED_RULE,
        UNSAFE_RULE,
        UNMAPPED_RULE,
        SINK_CONFLICT,
        SINK_FAILURE
    }

    private final Code code;

    public WorldMigrationException(Code code) {
        super("RG.WORLD_MIGRATION." + (code == null ? Code.INVALID_INPUT : code).name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }
}
