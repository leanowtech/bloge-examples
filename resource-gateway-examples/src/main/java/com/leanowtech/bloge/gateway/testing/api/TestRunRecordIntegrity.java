package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestRunEvidenceProtocolCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical trust boundary for a durable sanitized child test-run aggregate.
 *
 * <p>The detached signature covers complete {@link TestRunEvidence}. Current evidence includes the
 * verified tenant, organization, project, environment, and actor in signed metadata. This boundary
 * binds those facts back to the storage envelope, binds target/fixture/plan dependencies, and checks
 * the signature before a new record can be written. A canonical JSON round trip also prevents a
 * retained mutable Java object from changing between signature verification and persistence.</p>
 */
public final class TestRunRecordIntegrity {

    private static final String[] IDENTITY_KEYS = {
            "tenantId", "organizationId", "projectId", "environmentId", "actorId"
    };

    private TestRunRecordIntegrity() {
    }

    /**
     * Detaches and verifies a repository-owned record for safe read use.
     *
     * <p>Historical unsigned v1 evidence may be decoded so callers can apply their explicit legacy
     * policy. Current evidence must carry an exact fingerprint. A temporarily unavailable key is not
     * treated as corruption on read; the service layer still fails closed with an availability error.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param integrityService evidence signature verifier
     * @param record repository-owned aggregate
     * @return independently owned, structurally and cryptographically consistent snapshot
     * @throws TestRunIntegrityException when material is malformed, altered, or cross-boundary
     */
    public static TestRunRecord verifiedSnapshot(ObjectMapper objectMapper,
                                                 TestEvidenceIntegrityService integrityService,
                                                 TestRunRecord record) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(integrityService, "integrityService");
        if (record == null) {
            throw new TestRunIntegrityException();
        }
        try {
            TestRunRecord snapshot = objectMapper.readValue(objectMapper.writeValueAsBytes(record),
                    TestRunRecord.class);
            return verify(objectMapper, integrityService, snapshot);
        } catch (TestRunIntegrityException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new TestRunIntegrityException(invalid);
        }
    }

    /**
     * Detaches a newly submitted record and requires a current write-eligible integrity state.
     *
     * @param objectMapper canonical protocol mapper
     * @param integrityService evidence signature verifier
     * @param record record submitted to the persistence boundary
     * @return canonical record safe to serialize exactly once
     * @throws TestRunIntegrityException when the record is unsigned, unverifiable, or inconsistent
     */
    public static TestRunRecord verifiedCreateSnapshot(ObjectMapper objectMapper,
                                                       TestEvidenceIntegrityService integrityService,
                                                       TestRunRecord record) {
        TestRunRecord snapshot = verifiedSnapshot(objectMapper, integrityService, record);
        TestEvidenceIntegrity integrity = snapshot.integrity();
        TestEvidenceIntegrityService.Verification verification = integrityService.verify(
                snapshot.evidence(), integrity);
        if (integrity.signatureStatus() == TestEvidenceIntegrity.SignatureStatus.VERIFIED) {
            if (verification != TestEvidenceIntegrityService.Verification.VERIFIED) {
                throw new TestRunIntegrityException();
            }
            return snapshot;
        }
        if (integrity.signatureStatus()
                == TestEvidenceIntegrity.SignatureStatus.VERIFICATION_UNAVAILABLE
                && verification == TestEvidenceIntegrityService.Verification.UNAVAILABLE
                && snapshot.evidence().status() == TestRunEvidence.Status.EVIDENCE_INCOMPLETE
                && snapshot.evidence().evidenceClass() == TestRunEvidence.EvidenceClass.EXPLORATORY) {
            return snapshot;
        }
        throw new TestRunIntegrityException();
    }

    /**
     * Verifies that an alternate repository returned the exact record submitted for creation.
     *
     * <p>Child run ids are unique and do not have first-writer provenance semantics, so every field
     * in the canonical aggregate must match. Both values are independently canonicalized and verified
     * before equality is trusted.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param integrityService evidence signature verifier
     * @param returned repository create result
     * @param expected record submitted to the repository
     * @return canonical verified create receipt
     * @throws TestRunIntegrityException when the repository substituted any aggregate fact
     */
    public static TestRunRecord verifiedCreateReceipt(ObjectMapper objectMapper,
                                                      TestEvidenceIntegrityService integrityService,
                                                      TestRunRecord returned,
                                                      TestRunRecord expected) {
        TestRunRecord expectedSnapshot = verifiedCreateSnapshot(
                objectMapper, integrityService, expected);
        TestRunRecord returnedSnapshot = verifiedCreateSnapshot(
                objectMapper, integrityService, returned);
        if (!expectedSnapshot.equals(returnedSnapshot)) {
            throw new TestRunIntegrityException();
        }
        return returnedSnapshot;
    }

    /**
     * Verifies a repository result against the complete authorized lookup key.
     *
     * @param objectMapper canonical protocol mapper
     * @param integrityService evidence signature verifier
     * @param record repository-owned result
     * @param tenantId authorized tenant lookup key
     * @param environmentId authorized environment lookup key
     * @param runId requested run id
     * @return canonical snapshot bound to the exact lookup
     * @throws TestRunIntegrityException when the repository substitutes another record
     */
    public static TestRunRecord verifiedSnapshot(ObjectMapper objectMapper,
                                                 TestEvidenceIntegrityService integrityService,
                                                 TestRunRecord record,
                                                 String tenantId,
                                                 String environmentId,
                                                 String runId) {
        TestRunRecord snapshot = verifiedSnapshot(objectMapper, integrityService, record);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(runId, snapshot.runId())) {
            throw new TestRunIntegrityException();
        }
        return snapshot;
    }

    private static TestRunRecord verify(ObjectMapper objectMapper,
                                        TestEvidenceIntegrityService integrityService,
                                        TestRunRecord record) {
        TestRunEvidence evidence = record.evidence();
        TestEvidenceIntegrity integrity = record.integrity();
        if (blank(record.runId()) || blank(record.tenantId()) || blank(record.organizationId())
                || blank(record.projectId()) || blank(record.environmentId()) || blank(record.actorId())
                || record.target() == null || blank(record.target().kind()) || blank(record.target().id())
                || blank(record.target().fingerprint()) || record.fixtureBundleRef() == null
                || blank(record.fixtureBundleRef().fingerprint()) || record.requestedVerbosity() == null
                || evidence == null || integrity == null || record.createdAt() == null
                || record.expiresAt() == null || !record.expiresAt().isAfter(record.createdAt())) {
            throw new TestRunIntegrityException();
        }
        if (!record.runId().equals(evidence.runId())
                || !record.target().fingerprint().equals(evidence.targetFingerprint())
                || !record.fixtureBundleRef().fingerprint().equals(evidence.fixtureBundleFingerprint())
                || evidence.completedAt() == null || !record.createdAt().equals(evidence.completedAt())) {
            throw new TestRunIntegrityException();
        }
        verifyPlan(record.plan(), evidence);
        verifyIdentityBinding(record, evidence.metadata(), integrity);
        verifyEvidenceIntegrity(objectMapper, integrityService, evidence, integrity);
        return record;
    }

    private static void verifyPlan(EffectiveExecutionPlan plan, TestRunEvidence evidence) {
        if (plan == null) {
            if (!evidence.planFingerprint().isBlank()) {
                throw new TestRunIntegrityException();
            }
            return;
        }
        if (!Objects.equals(plan.planFingerprint(), evidence.planFingerprint())
                || !Objects.equals(plan.targetFingerprint(), evidence.targetFingerprint())
                || !Objects.equals(plan.fixtureBundleFingerprint(), evidence.fixtureBundleFingerprint())
                || !Objects.equals(plan.authorizedPurpose(), evidence.executionPurpose())) {
            throw new TestRunIntegrityException();
        }
    }

    private static void verifyIdentityBinding(TestRunRecord record, Map<String, Object> metadata,
                                              TestEvidenceIntegrity integrity) {
        if (TestRunEvidence.SCHEMA_VERSION_V1.equals(record.evidence().schemaVersion())
                && integrity.signatureStatus() == TestEvidenceIntegrity.SignatureStatus.UNSIGNED) {
            return;
        }
        Object[] expected = {record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId()};
        for (int index = 0; index < IDENTITY_KEYS.length; index++) {
            if (!Objects.equals(expected[index], metadata.get(IDENTITY_KEYS[index]))) {
                throw new TestRunIntegrityException();
            }
        }
        if (!Boolean.TRUE.equals(metadata.get("payloadSanitized"))) {
            throw new TestRunIntegrityException();
        }
    }

    private static void verifyEvidenceIntegrity(ObjectMapper objectMapper,
                                                TestEvidenceIntegrityService integrityService,
                                                TestRunEvidence evidence,
                                                TestEvidenceIntegrity integrity) {
        new TestRunEvidenceProtocolCodec(objectMapper).controlProjection(evidence);
        TestEvidenceIntegrityService.Verification verification = integrityService.verify(
                evidence, integrity);
        if (verification == TestEvidenceIntegrityService.Verification.INVALID) {
            throw new TestRunIntegrityException();
        }
        if (verification == TestEvidenceIntegrityService.Verification.UNSIGNED) {
            if (!TestRunEvidence.SCHEMA_VERSION_V1.equals(evidence.schemaVersion())) {
                throw new TestRunIntegrityException();
            }
            return;
        }
        String actual = ProtocolFingerprint.of(objectMapper, evidence);
        if (!same(actual, integrity.evidenceFingerprint())
                || integrity.projection() != TestEvidenceIntegrity.Projection.FULL
                || !same(actual, integrity.projectionFingerprint())) {
            throw new TestRunIntegrityException();
        }
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
