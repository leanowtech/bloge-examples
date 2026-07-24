package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;

import java.time.Instant;

/**
 * Immutable enterprise-scoped test-suite registry revision.
 *
 * <p>A record constructor alone cannot detach arbitrary values embedded in case inputs. Registry
 * adapters and consumers use {@link StoredTestSuiteIntegrity#verifiedSnapshot} before trusting an
 * instance, which binds this envelope to a canonical, independently owned suite value.</p>
 *
 * @param schemaVersion stored-suite response protocol version
 * @param tenantId verified tenant scope
 * @param organizationId verified organization scope
 * @param projectId verified project scope
 * @param environmentId verified non-production environment scope
 * @param region verified deployment and data-residency region
 * @param suiteId stable suite identifier
 * @param revision immutable suite revision
 * @param fingerprint full suite content fingerprint
 * @param suite immutable suite content
 * @param createdAt authoritative registry creation time
 * @param createdBy verified actor that registered the revision
 */
public record StoredTestSuite(
        String schemaVersion,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String suiteId,
        long revision,
        String fingerprint,
        TestSuiteProtocol suite,
        Instant createdAt,
        String createdBy
) {
    /** Current stored-suite response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.storedTestSuite.v2";
    /** Historical tenant/environment-only response version retained for explicit migration. */
    public static final String LEGACY_SCHEMA_VERSION = "bloge.storedTestSuite.v1";

    /** Applies the appropriate protocol version without upgrading an unscoped legacy envelope. */
    public StoredTestSuite {
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
     * <p>Legacy values remain deliberately unscoped and cannot be consumed through a v2
     * full-enterprise repository lookup until explicitly re-registered by an authorized owner.</p>
     */
    public StoredTestSuite(
            String schemaVersion,
            String tenantId,
            String environmentId,
            String suiteId,
            long revision,
            String fingerprint,
            TestSuiteProtocol suite,
            Instant createdAt,
            String createdBy) {
        this(schemaVersion, tenantId, "", "", environmentId, "", suiteId, revision,
                fingerprint, suite, createdAt, createdBy);
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
