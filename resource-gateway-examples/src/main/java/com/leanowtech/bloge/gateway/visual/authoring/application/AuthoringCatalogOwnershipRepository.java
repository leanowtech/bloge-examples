package com.leanowtech.bloge.gateway.visual.authoring.application;

import java.time.Instant;
import java.util.Optional;

/**
 * Atomic enterprise ownership authority for canonical libraries created by the Workbench.
 */
public interface AuthoringCatalogOwnershipRepository {

    Optional<Ownership> find(String libraryId);

    Ownership claim(AuthoringScope scope, String libraryId, String actor, Instant claimedAt);

    record Ownership(
            AuthoringScope scope,
            String libraryId,
            String claimedBy,
            Instant claimedAt
    ) {
        public Ownership {
            scope = java.util.Objects.requireNonNull(scope, "scope");
            libraryId = required(libraryId, "libraryId", 255);
            claimedBy = required(claimedBy, "claimedBy", 255);
            claimedAt = java.util.Objects.requireNonNull(claimedAt, "claimedAt");
        }

        private static String required(String value, String field, int maximumLength) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(field + " must be present and bounded");
            }
            return normalized;
        }
    }
}
