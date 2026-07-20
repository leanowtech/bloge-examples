package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;

import java.time.Instant;

/**
 * Immutable tenant- and environment-scoped test-suite registry revision.
 *
 * <p>A record constructor alone cannot detach arbitrary values embedded in case inputs. Registry
 * adapters and consumers use {@link StoredTestSuiteIntegrity#verifiedSnapshot} before trusting an
 * instance, which binds this envelope to a canonical, independently owned suite value.</p>
 *
 * @param schemaVersion stored-suite response protocol version
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment scope
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
        String environmentId,
        String suiteId,
        long revision,
        String fingerprint,
        TestSuiteProtocol suite,
        Instant createdAt,
        String createdBy
) {
    /** Current stored-suite response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.storedTestSuite.v1";

    /** Applies the current protocol version to service-created responses. */
    public StoredTestSuite {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
    }
}
