package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical integrity boundary for immutable stored test-suite revisions.
 *
 * <p>Indexed envelope columns and serialized suite JSON are independent corruption domains. Every
 * create and read therefore binds the envelope to the exact suite generation and recomputes its
 * canonical fingerprint. Consumers verify again at their repository trust transition so alternate
 * adapters cannot substitute a valid revision from another tenant, environment, or key.</p>
 */
public final class StoredTestSuiteIntegrity {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private StoredTestSuiteIntegrity() {
    }

    /**
     * Canonically detaches one repository-owned suite and verifies the exact detached value.
     *
     * <p>The exact-generation codec round trip is intentional. It removes arbitrary mutable Java
     * objects embedded in {@code Object} inputs and gives the returned envelope an independently
     * owned JSON value graph before its fingerprint is trusted.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned envelope
     * @return independently owned and integrity-verified envelope
     * @throws TestSuiteIntegrityException when canonicalization or verification fails
     */
    public static StoredTestSuite verifiedSnapshot(ObjectMapper objectMapper,
                                                   StoredTestSuite stored) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (stored == null || stored.suite() == null) {
            throw new TestSuiteIntegrityException();
        }
        try {
            TestSuiteProtocolCodec codec = new TestSuiteProtocolCodec(objectMapper);
            TestSuiteProtocol suite = codec.read(codec.write(stored.suite()));
            return verify(codec, new StoredTestSuite(stored.schemaVersion(), stored.tenantId(),
                    stored.organizationId(), stored.projectId(), stored.environmentId(),
                    stored.region(), stored.suiteId(), stored.revision(), stored.fingerprint(),
                    suite, stored.createdAt(), stored.createdBy()));
        } catch (TestSuiteIntegrityException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new TestSuiteIntegrityException(invalid);
        }
    }

    /**
     * Detaches and verifies a repository result against the complete authorized lookup key.
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned result
     * @param tenantId authorized tenant lookup key
     * @param environmentId authorized environment lookup key
     * @param suiteId requested suite id
     * @param revision requested revision
     * @return independently owned result bound to the exact lookup key
     * @throws TestSuiteIntegrityException when the result is corrupt or cross-boundary
     */
    public static StoredTestSuite verifiedSnapshot(ObjectMapper objectMapper,
                                                   StoredTestSuite stored,
                                                   String tenantId,
                                                   String environmentId,
                                                   String suiteId,
                                                   long revision) {
        StoredTestSuite snapshot = verifiedSnapshot(objectMapper, stored);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(suiteId, snapshot.suiteId())
                || revision != snapshot.revision()) {
            throw new TestSuiteIntegrityException();
        }
        return snapshot;
    }

    /**
     * Detaches and binds a repository result to the complete enterprise lookup key.
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned result
     * @param scope authorized enterprise scope
     * @param suiteId requested suite id
     * @param revision requested immutable revision
     * @return independently owned v2 result bound to every scope dimension
     */
    public static StoredTestSuite verifiedSnapshot(
            ObjectMapper objectMapper,
            StoredTestSuite stored,
            TestingArtifactScope scope,
            String suiteId,
            long revision) {
        Objects.requireNonNull(scope, "scope");
        StoredTestSuite snapshot = verifiedSnapshot(objectMapper, stored);
        if (!snapshot.enterpriseScoped()
                || !scope.equals(snapshot.scope())
                || !Objects.equals(suiteId, snapshot.suiteId())
                || revision != snapshot.revision()) {
            throw new TestSuiteIntegrityException();
        }
        return snapshot;
    }

    /**
     * Detaches and verifies a create result against the submitted immutable identity and content.
     *
     * <p>Creation provenance is intentionally not compared. An idempotent create returns the
     * original registry timestamp and author, which legitimately differ from a retry. The base
     * integrity check still requires both values to be complete.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned create result
     * @param expected canonical envelope submitted to the repository
     * @return independently owned result retaining authoritative first-write provenance
     * @throws TestSuiteIntegrityException when immutable identity or content changed
     */
    public static StoredTestSuite verifiedSnapshot(ObjectMapper objectMapper,
                                                   StoredTestSuite stored,
                                                   StoredTestSuite expected) {
        if (expected == null) {
            throw new TestSuiteIntegrityException();
        }
        StoredTestSuite expectedSnapshot = verifiedSnapshot(objectMapper, expected);
        StoredTestSuite snapshot = expectedSnapshot.enterpriseScoped()
                ? verifiedSnapshot(objectMapper, stored, expectedSnapshot.scope(),
                expectedSnapshot.suiteId(), expectedSnapshot.revision())
                : verifiedSnapshot(objectMapper, stored,
                expectedSnapshot.tenantId(), expectedSnapshot.environmentId(),
                expectedSnapshot.suiteId(), expectedSnapshot.revision());
        if (!Objects.equals(expectedSnapshot.schemaVersion(), snapshot.schemaVersion())
                || !same(expectedSnapshot.fingerprint(), snapshot.fingerprint())) {
            throw new TestSuiteIntegrityException();
        }
        return snapshot;
    }

    private static StoredTestSuite verify(TestSuiteProtocolCodec codec, StoredTestSuite stored) {
        boolean legacy = StoredTestSuite.LEGACY_SCHEMA_VERSION.equals(stored.schemaVersion())
                && blank(stored.organizationId()) && blank(stored.projectId())
                && blank(stored.region());
        boolean enterprise = StoredTestSuite.SCHEMA_VERSION.equals(stored.schemaVersion())
                && !blank(stored.organizationId()) && !blank(stored.projectId())
                && !blank(stored.region());
        if (!(legacy || enterprise)
                || blank(stored.tenantId()) || blank(stored.environmentId())
                || blank(stored.suiteId()) || stored.revision() <= 0
                || stored.createdAt() == null || blank(stored.createdBy())
                || stored.suite() == null || !validFingerprint(stored.fingerprint())) {
            throw new TestSuiteIntegrityException();
        }
        TestSuiteProtocol suite = stored.suite();
        if (!stored.suiteId().equals(suite.suiteId()) || stored.revision() != suite.revision()) {
            throw new TestSuiteIntegrityException();
        }
        String actual;
        try {
            actual = codec.fingerprint(suite);
        } catch (RuntimeException invalid) {
            throw new TestSuiteIntegrityException(invalid);
        }
        if (!same(stored.fingerprint(), actual)) {
            throw new TestSuiteIntegrityException();
        }
        return stored;
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean same(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
