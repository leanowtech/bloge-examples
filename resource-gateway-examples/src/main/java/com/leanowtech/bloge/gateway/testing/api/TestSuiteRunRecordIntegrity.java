package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunEvidenceProtocolCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Canonical trust boundary for durable v1-v5 suite-run checkpoint and terminal aggregates. */
public final class TestSuiteRunRecordIntegrity {

    private static final String[] IDENTITY_KEYS = {
            "tenantId", "organizationId", "projectId", "environmentId", "actorId",
            "classification"
    };

    private TestSuiteRunRecordIntegrity() {
    }

    /**
     * Detaches and verifies one repository-owned aggregate for read use.
     *
     * <p>Historical unsigned v1 rows remain decodable for explicit migration policy. For signed
     * rows, a temporarily unavailable verification key is kept distinct from corrupted material so
     * the service can return an authority-outage result. Canonical fingerprints and envelope facts
     * are still checked while that authority is unavailable.</p>
     */
    public static TestSuiteRunRecord verifiedSnapshot(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(attestations, "attestations");
        if (record == null) {
            throw new TestSuiteRunIntegrityException();
        }
        try {
            TestSuiteRunRecord snapshot = objectMapper.readValue(
                    objectMapper.writeValueAsBytes(record), TestSuiteRunRecord.class);
            return verify(objectMapper, attestations, snapshot);
        } catch (TestSuiteRunIntegrityException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new TestSuiteRunIntegrityException(invalid);
        }
    }

    /** Requires a canonical aggregate that is eligible for a current create or update. */
    public static TestSuiteRunRecord verifiedWriteSnapshot(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record) {
        TestSuiteRunRecord snapshot = verifiedSnapshot(objectMapper, attestations, record);
        TestSuiteRunAttestation attestation = snapshot.attestation();
        TestSuiteRunAttestationService.Verification verification = attestations.verify(
                snapshot.evidence(), attestation);
        if (attestation.signatureStatus() == TestSuiteRunAttestation.SignatureStatus.VERIFIED) {
            if (verification != TestSuiteRunAttestationService.Verification.VERIFIED) {
                throw new TestSuiteRunIntegrityException();
            }
            return snapshot;
        }
        boolean unavailableTerminal = snapshot.evidence().status()
                == TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE
                && snapshot.evidence().promotion().status()
                == TestSuiteRunEvidence.PromotionStatus.BLOCKED
                && attestation.scope() == TestSuiteRunAttestation.Scope.TERMINAL;
        if (attestation.signatureStatus()
                == TestSuiteRunAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE
                && verification == TestSuiteRunAttestationService.Verification.UNAVAILABLE
                && unavailableTerminal) {
            return snapshot;
        }
        throw new TestSuiteRunIntegrityException();
    }

    /**
     * Requires an alternate repository to return the exact aggregate submitted for a write.
     *
     * <p>Both create and checkpoint replacement are whole-aggregate operations. A repository may
     * canonicalize representation, but it must not substitute any signed or envelope fact.</p>
     */
    public static TestSuiteRunRecord verifiedWriteReceipt(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord returned,
            TestSuiteRunRecord expected) {
        TestSuiteRunRecord expectedSnapshot = verifiedWriteSnapshot(
                objectMapper, attestations, expected);
        TestSuiteRunRecord returnedSnapshot = verifiedWriteSnapshot(
                objectMapper, attestations, returned);
        if (!expectedSnapshot.equals(returnedSnapshot)) {
            throw new TestSuiteRunIntegrityException();
        }
        return returnedSnapshot;
    }

    /** Binds one read result to an exact tenant/environment/suite-run lookup key. */
    public static TestSuiteRunRecord verifiedRunLookup(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record,
            String tenantId,
            String environmentId,
            String suiteRunId) {
        TestSuiteRunRecord snapshot = verifiedSnapshot(objectMapper, attestations, record);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(suiteRunId, snapshot.suiteRunId())) {
            throw new TestSuiteRunIntegrityException();
        }
        return snapshot;
    }

    /** Binds one idempotency read result to its complete authorized lookup key. */
    public static TestSuiteRunRecord verifiedClientLookup(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record,
            String tenantId,
            String environmentId,
            String clientRequestId) {
        TestSuiteRunRecord snapshot = verifiedSnapshot(objectMapper, attestations, record);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(clientRequestId, snapshot.clientRequestId())) {
            throw new TestSuiteRunIntegrityException();
        }
        return snapshot;
    }

    /** Binds one history result to an exact suite revision and authorized scope. */
    public static TestSuiteRunRecord verifiedSuiteLookup(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record,
            String tenantId,
            String environmentId,
            String suiteId,
            long revision) {
        TestSuiteRunRecord snapshot = verifiedSnapshot(objectMapper, attestations, record);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(suiteId, snapshot.evidence().suiteRef().suiteId())
                || revision != snapshot.evidence().suiteRef().revision()) {
            throw new TestSuiteRunIntegrityException();
        }
        return snapshot;
    }

    /**
     * Verifies an adapter-provided abandoned-run candidate against the authoritative sweep time.
     *
     * @return canonical candidate whose checkpoint is signed, running, unexpired, and lease-expired
     */
    public static AbandonedTestSuiteRun verifiedAbandoned(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            AbandonedTestSuiteRun candidate,
            Instant observedAt) {
        if (candidate == null || observedAt == null || candidate.leaseOwner().isBlank()
                || candidate.leaseExpiresAt().isAfter(observedAt)) {
            throw new TestSuiteRunIntegrityException();
        }
        TestSuiteRunRecord snapshot = verifiedSnapshot(
                objectMapper, attestations, candidate.record());
        if (snapshot.evidence().status() != TestSuiteRunEvidence.Status.RUNNING
                || !snapshot.expiresAt().isAfter(observedAt)) {
            throw new TestSuiteRunIntegrityException();
        }
        return new AbandonedTestSuiteRun(snapshot, candidate.checkpointVersion(),
                candidate.leaseOwner(), candidate.leaseExpiresAt());
    }

    private static TestSuiteRunRecord verify(
            ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations,
            TestSuiteRunRecord record) {
        TestSuiteRunEvidenceProtocol evidence = record.evidence();
        TestSuiteRunAttestation attestation = record.attestation();
        if (blank(record.suiteRunId()) || blank(record.clientRequestId())
                || blank(record.requestFingerprint()) || blank(record.tenantId())
                || blank(record.organizationId()) || blank(record.projectId())
                || blank(record.environmentId()) || blank(record.actorId())
                || blank(record.classification()) || evidence == null || attestation == null
                || record.createdAt() == null || record.expiresAt() == null
                || !record.expiresAt().isAfter(record.createdAt()) || evidence.suiteRef() == null
                || evidence.target() == null) {
            throw new TestSuiteRunIntegrityException();
        }
        boolean running = evidence.status() == TestSuiteRunEvidence.Status.RUNNING;
        if (!record.suiteRunId().equals(evidence.suiteRunId())
                || !record.clientRequestId().equals(evidence.clientRequestId())
                || !record.createdAt().equals(evidence.startedAt())
                || running != (evidence.completedAt() == null)) {
            throw new TestSuiteRunIntegrityException();
        }
        if (attestation.signatureStatus() == TestSuiteRunAttestation.SignatureStatus.UNSIGNED) {
            if (!(evidence instanceof TestSuiteRunEvidence v1)
                    || !TestSuiteRunEvidence.SCHEMA_VERSION.equals(v1.schemaVersion())) {
                throw new TestSuiteRunIntegrityException();
            }
            return record;
        }
        String aggregateFingerprint = new TestSuiteRunEvidenceProtocolCodec(objectMapper)
                .fingerprint(evidence);
        boolean fingerprintMatches = same(aggregateFingerprint,
                attestation.aggregateEvidenceFingerprint())
                && (running ? record.evidenceFingerprint().isBlank()
                : same(record.evidenceFingerprint(), aggregateFingerprint));
        boolean identityMatches = record.suiteRunId().equals(attestation.suiteRunId())
                && record.requestFingerprint().equals(attestation.requestFingerprint())
                && Objects.equals(evidence.suiteRef(), attestation.suiteRef());
        boolean scopeMatches = attestation.scope() == (running
                ? TestSuiteRunAttestation.Scope.CHECKPOINT
                : TestSuiteRunAttestation.Scope.TERMINAL);
        if (!fingerprintMatches || !identityMatches || !scopeMatches
                || !generationMatches(evidence, attestation)) {
            throw new TestSuiteRunIntegrityException();
        }
        verifyIdentityBinding(record, evidence.metadata());
        TestSuiteRunAttestationService.Verification verification = attestations.verify(
                evidence, attestation);
        if (verification == TestSuiteRunAttestationService.Verification.INVALID
                || verification == TestSuiteRunAttestationService.Verification.UNSIGNED) {
            throw new TestSuiteRunIntegrityException();
        }
        return record;
    }

    private static void verifyIdentityBinding(TestSuiteRunRecord record,
                                              Map<String, Object> metadata) {
        Object[] expected = {record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification()};
        for (int index = 0; index < IDENTITY_KEYS.length; index++) {
            if (!Objects.equals(expected[index], metadata.get(IDENTITY_KEYS[index]))) {
                throw new TestSuiteRunIntegrityException();
            }
        }
    }

    private static boolean generationMatches(TestSuiteRunEvidenceProtocol evidence,
                                             TestSuiteRunAttestation attestation) {
        if (evidence instanceof TestSuiteRunEvidenceV5 v5) {
            return TestSuiteRunEvidenceV5.SCHEMA_VERSION.equals(v5.schemaVersion())
                    && TestSuiteRunAttestation.SCHEMA_VERSION_V5.equals(attestation.schemaVersion());
        }
        if (evidence instanceof TestSuiteRunEvidenceV4 v4) {
            return TestSuiteRunEvidenceV4.SCHEMA_VERSION.equals(v4.schemaVersion())
                    && TestSuiteRunAttestation.SCHEMA_VERSION_V4.equals(attestation.schemaVersion());
        }
        if (evidence instanceof TestSuiteRunEvidenceV3 v3) {
            return TestSuiteRunEvidenceV3.SCHEMA_VERSION.equals(v3.schemaVersion())
                    && TestSuiteRunAttestation.SCHEMA_VERSION_V3.equals(attestation.schemaVersion())
                    && attestation.childEvidenceRefs().isEmpty();
        }
        if (evidence instanceof TestSuiteRunEvidenceV2 v2) {
            return TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(v2.schemaVersion())
                    && TestSuiteRunAttestation.SCHEMA_VERSION_V2.equals(attestation.schemaVersion());
        }
        return evidence instanceof TestSuiteRunEvidence v1
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(v1.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION.equals(attestation.schemaVersion());
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
