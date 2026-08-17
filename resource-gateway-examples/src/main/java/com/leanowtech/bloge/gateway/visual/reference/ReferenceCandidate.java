package com.leanowtech.bloge.gateway.visual.reference;

import java.util.List;
import java.util.Objects;

/** Metadata-only discovery projection. It deliberately contains no schema, fixture, or evidence data. */
public record ReferenceCandidate(
        String schemaVersion,
        String kind,
        String id,
        String displayName,
        String description,
        long revision,
        String fingerprint,
        String authority,
        ReferenceScope scope,
        Lifecycle lifecycle,
        Owner owner,
        List<String> labels,
        Compatibility compatibility,
        String disabledReasonCode
) {
    public static final String SCHEMA_VERSION = "bloge.referenceCandidate.v1";

    public ReferenceCandidate {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ReferenceCandidate schemaVersion: " + schemaVersion);
        }
        kind = required(kind, "kind");
        id = required(id, "id");
        displayName = required(displayName, "displayName");
        description = description == null ? "" : description;
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        fingerprint = required(fingerprint, "fingerprint");
        authority = required(authority, "authority");
        scope = Objects.requireNonNull(scope, "scope");
        if (!scope.isFullySpecified()) {
            throw new IllegalArgumentException("candidate scope must be fully specified");
        }
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        labels = labels == null ? List.of() : List.copyOf(labels);
        compatibility = Objects.requireNonNull(compatibility, "compatibility");
        disabledReasonCode = disabledReasonCode == null ? "" : disabledReasonCode;
    }

    public boolean exactCoordinateEquals(String candidateKind,
                                         String candidateId,
                                         long candidateRevision,
                                         String candidateFingerprint) {
        return kind.equals(candidateKind)
                && id.equals(candidateId)
                && revision == candidateRevision
                && fingerprint.equals(candidateFingerprint);
    }

    public record Owner(String stableId, String displayName) {
        public Owner {
            stableId = required(stableId, "owner.stableId");
            displayName = required(displayName, "owner.displayName");
        }
    }

    public enum Lifecycle {
        DRAFT,
        ACTIVE,
        DEPRECATED,
        SUPERSEDED
    }

    public enum Compatibility {
        COMPATIBLE,
        REVIEW,
        INCOMPATIBLE,
        UNKNOWN
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
