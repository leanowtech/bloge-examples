package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/** Exact non-secret Connection authority used to compile one Resource revision. */
public record ApiResourceConnectionSnapshot(String connectionId, long revision,
                                            String metadataFingerprint) {
    /** Validates the immutable Connection coordinate and metadata digest. */
    public ApiResourceConnectionSnapshot {
        if (connectionId == null || connectionId.isBlank() || revision < 1
                || metadataFingerprint == null
                || !metadataFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("connection snapshot is invalid");
        }
    }
}
