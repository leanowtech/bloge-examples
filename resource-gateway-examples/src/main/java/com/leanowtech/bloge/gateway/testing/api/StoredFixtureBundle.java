package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.time.Instant;

/**
 * Immutable enterprise-scoped fixture registry revision.
 *
 * <p>{@code fingerprint} is the canonical fingerprint of {@code bundle}, not an independent caller
 * assertion. Repository and service trust boundaries create a detached canonical snapshot, verify
 * that binding together with the envelope/bundle id and revision, and bind repository results to
 * the complete lookup key or submitted immutable create identity before use. Idempotent creates
 * preserve the first registry timestamp and author.</p>
 */
public record StoredFixtureBundle(
        String schemaVersion,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String fixtureBundleId,
        long revision,
        String fingerprint,
        FixtureBundle bundle,
        Instant createdAt,
        String createdBy
) {
    /** Current public stored-fixture response version. */
    public static final String SCHEMA_VERSION = "bloge.storedFixtureBundle.v2";
    /** Historical tenant/environment-only response retained for explicit migration. */
    public static final String LEGACY_SCHEMA_VERSION = "bloge.storedFixtureBundle.v1";

    /** Applies v2 only when the complete enterprise scope is present. */
    public StoredFixtureBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? (completeEnterpriseScope(organizationId, projectId, region)
                ? SCHEMA_VERSION : LEGACY_SCHEMA_VERSION)
                : schemaVersion.trim();
        tenantId = normalized(tenantId);
        organizationId = normalized(organizationId);
        projectId = normalized(projectId);
        environmentId = normalized(environmentId);
        region = normalized(region);
    }

    /**
     * Source-compatible constructor for historical v1 envelopes.
     *
     * <p>An unscoped value is migration input, not proof that any organization or project owns
     * the fixture.</p>
     */
    public StoredFixtureBundle(
            String schemaVersion,
            String tenantId,
            String environmentId,
            String fixtureBundleId,
            long revision,
            String fingerprint,
            FixtureBundle bundle,
            Instant createdAt,
            String createdBy) {
        this(schemaVersion, tenantId, "", "", environmentId, "", fixtureBundleId, revision,
                fingerprint, bundle, createdAt, createdBy);
    }

    /** @return complete immutable v2 ownership coordinate */
    public TestingArtifactScope scope() {
        return new TestingArtifactScope(
                tenantId, organizationId, projectId, environmentId, region);
    }

    /** @return whether this envelope carries the complete v2 ownership coordinate */
    public boolean enterpriseScoped() {
        return SCHEMA_VERSION.equals(schemaVersion)
                && completeEnterpriseScope(organizationId, projectId, region);
    }

    private static boolean completeEnterpriseScope(
            String organizationId, String projectId, String region) {
        return !normalized(organizationId).isBlank()
                && !normalized(projectId).isBlank()
                && !normalized(region).isBlank();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
