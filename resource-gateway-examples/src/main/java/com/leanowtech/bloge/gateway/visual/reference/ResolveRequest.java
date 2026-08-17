package com.leanowtech.bloge.gateway.visual.reference;

import java.util.Objects;

/** Exact candidate coordinate sent to the authoritative provider before binding. */
public record ResolveRequest(
        String schemaVersion,
        String kind,
        String id,
        long revision,
        String fingerprint,
        ReferenceScope scope,
        String intendedUse
) {
    public static final String SCHEMA_VERSION = "bloge.referenceResolveRequest.v1";

    public ResolveRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ResolveRequest schemaVersion: " + schemaVersion);
        }
        kind = required(kind, "kind");
        id = required(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        fingerprint = required(fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        intendedUse = required(intendedUse, "intendedUse");
    }

    public ResolveRequest(String kind,
                          String id,
                          long revision,
                          String fingerprint,
                          ReferenceScope scope,
                          String intendedUse) {
        this(SCHEMA_VERSION, kind, id, revision, fingerprint, scope, intendedUse);
    }

    public static ResolveRequest from(ReferenceCandidate candidate, ReferenceScope scope, String intendedUse) {
        Objects.requireNonNull(candidate, "candidate");
        return new ResolveRequest(candidate.kind(), candidate.id(), candidate.revision(),
                candidate.fingerprint(), scope, intendedUse);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
