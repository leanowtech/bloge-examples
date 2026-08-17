package com.leanowtech.bloge.gateway.visual.reference;

/** Versioned metadata-only result for authoritative exact resolution. */
public record ResolveResult(
        String schemaVersion,
        Status status,
        ReferenceCandidate candidate,
        String errorCode
) {
    public static final String SCHEMA_VERSION = "bloge.referenceResolveResult.v1";

    public ResolveResult {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ResolveResult schemaVersion: " + schemaVersion);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if ((status == Status.RESOLVED || status == Status.DRIFTED) && candidate == null) {
            throw new IllegalArgumentException("resolved or drifted result must contain the authoritative candidate");
        }
        if (status != Status.RESOLVED && status != Status.DRIFTED && candidate != null) {
            throw new IllegalArgumentException("not-found or forbidden result must not contain a candidate");
        }
        errorCode = errorCode == null ? "" : errorCode;
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }

    public enum Status {
        RESOLVED,
        NOT_FOUND,
        DRIFTED,
        FORBIDDEN
    }
}
