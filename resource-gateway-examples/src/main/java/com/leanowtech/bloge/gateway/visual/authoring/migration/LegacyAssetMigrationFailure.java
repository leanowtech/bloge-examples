package com.leanowtech.bloge.gateway.visual.authoring.migration;

/** Closed failure taxonomy for payload-free legacy preview reads. */
public final class LegacyAssetMigrationFailure extends RuntimeException {
    public enum Code { NOT_FOUND, NEEDS_REPAIR }

    private final Code code;

    public LegacyAssetMigrationFailure(Code code) {
        super(code == Code.NOT_FOUND
                ? "The legacy Resource source was not found."
                : "The legacy Resource cannot be re-authored without repair.");
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
