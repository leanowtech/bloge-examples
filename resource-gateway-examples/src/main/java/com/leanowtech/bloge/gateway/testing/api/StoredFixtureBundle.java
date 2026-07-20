package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.time.Instant;

/**
 * Immutable tenant- and environment-scoped fixture registry revision.
 *
 * <p>{@code fingerprint} is the canonical fingerprint of {@code bundle}, not an independent caller
 * assertion. Repository and service trust boundaries verify that binding together with the
 * envelope/bundle id and revision before use.</p>
 */
public record StoredFixtureBundle(
        String schemaVersion,
        String tenantId,
        String environmentId,
        String fixtureBundleId,
        long revision,
        String fingerprint,
        FixtureBundle bundle,
        Instant createdAt,
        String createdBy
) {
    /** Current public stored-fixture response version. */
    public static final String SCHEMA_VERSION = "bloge.storedFixtureBundle.v1";

    /** Applies the public protocol version when constructed inside the service. */
    public StoredFixtureBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
    }
}
