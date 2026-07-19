package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic independently signed external-archive fixtures for server tests. */
public final class TestSuiteStabilityObservationExternalArchiveProtocolFixtures {
    private TestSuiteStabilityObservationExternalArchiveProtocolFixtures() {
    }

    /**
     * Creates a test authority whose receipt signatures are reverified before commit.
     *
     * @param objectMapper canonical protocol mapper
     * @return process-local external-authority substitute for tests only
     */
    public static TestSuiteStabilityObservationExternalArchiveAuthority authority(
            ObjectMapper objectMapper) {
        return new FixtureAuthority(objectMapper);
    }

    /**
     * Creates one canonical signed receipt set for direct repository tests.
     *
     * @param objectMapper canonical protocol mapper
     * @param retirement exact signed retirement
     * @param retainUntil requested immutable deadline
     * @return canonical one-copy receipt set
     */
    public static TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil) {
        return create(objectMapper, new InMemoryVisualEvidenceSigner(), retirement, retainUntil);
    }

    private static TestSuiteStabilityObservationExternalArchiveReceiptSet create(
            ObjectMapper objectMapper,
            InMemoryVisualEvidenceSigner signer,
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil) {
        Instant requestedAt = retirement.evidence().retiredAt()
                .plusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = requestedAt.plusSeconds(30);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);
        TestSuiteStabilityObservationExternalArchiveRequest request =
                TestSuiteStabilityObservationExternalArchiveRequest.create(
                        objectMapper, "archive.example", "archive-set-a", retirement,
                        retainUntil, challenge, requestedAt, expiresAt);
        Instant issuedAt = requestedAt.plusSeconds(1);
        String objectId = TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, retirement);
        TestSuiteStabilityObservationExternalArchiveReceipt.Material material =
                new TestSuiteStabilityObservationExternalArchiveReceipt.Material(
                        TestSuiteStabilityObservationExternalArchiveReceipt.SCHEMA_VERSION,
                        request.requestFingerprint(), request.trustDomain(), request.archiveSetId(),
                        "archive-a", "region-a", signer.descriptor().activeKeyId(), objectId,
                        retirement.evidence().retirementId(),
                        retirement.retirementFingerprint(),
                        retirement.evidence().archiveSegment().segmentId(),
                        retirement.evidence().archiveSegment().segmentFingerprint(),
                        retirement.evidence().retentionPolicyFingerprint(), retainUntil,
                        requestedAt, issuedAt, expiresAt,
                        TestSuiteStabilityObservationExternalArchiveReceipt.RetentionMode
                                .COMPLIANCE,
                        true, true, true, "Ed25519");
        String receiptFingerprint = ProtocolFingerprint.of(objectMapper, material);
        VisualRunEvidenceSeal seal = signer.seal(receiptFingerprint);
        TestSuiteStabilityObservationExternalArchiveReceipt receipt =
                new TestSuiteStabilityObservationExternalArchiveReceipt(
                        material.schemaVersion(), receiptFingerprint,
                        material.requestFingerprint(), material.trustDomain(),
                        material.archiveSetId(), material.authorityId(),
                        material.failureDomain(), seal.keyId(), material.objectId(),
                        material.retirementId(), material.retirementFingerprint(),
                        material.segmentId(), material.segmentFingerprint(),
                        material.retentionPolicyFingerprint(), material.retainUntil(),
                        material.storedAt(), material.issuedAt(), material.expiresAt(),
                        material.retentionMode(), material.externallyDurable(),
                        material.writeOnce(), material.deleteBeforeRetentionDenied(),
                        seal.algorithm(), seal.signature());
        return TestSuiteStabilityObservationExternalArchiveIntegrity.sealSet(
                objectMapper, request, 1, List.of(receipt), issuedAt.plusSeconds(1));
    }

    private static final class FixtureAuthority
            implements TestSuiteStabilityObservationExternalArchiveAuthority {
        private final ObjectMapper objectMapper;
        private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();

        private FixtureAuthority(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        }

        @Override
        public TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
                TestSuiteStabilityObservationFloorRetirement retirement,
                Instant retainUntil) {
            return create(objectMapper, signer, retirement, retainUntil);
        }

        @Override
        public Verification verify(
                TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
            if (!TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                    objectMapper, receiptSet)) {
                return Verification.INVALID;
            }
            for (TestSuiteStabilityObservationExternalArchiveReceipt receipt
                    : receiptSet.receipts()) {
                VisualRunEvidenceSeal seal = new VisualRunEvidenceSeal(
                        "", receipt.receiptFingerprint(), receipt.algorithm(), receipt.keyId(),
                        receipt.issuedAt(), receipt.signature());
                if (!signer.verify(seal, receipt.receiptFingerprint()).valid()) {
                    return Verification.INVALID;
                }
            }
            return Verification.VERIFIED;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, true, true,
                    1, 1, 1, Duration.ofDays(1),
                    Map.of("sourceType", "TEST_FIXTURE",
                            "externalFirstCommit", true,
                            "writeOnce", true,
                            "complianceRetention", true));
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(Snapshot.SCHEMA_VERSION, true, "HEALTHY", Instant.now(),
                    1, 0, 0, 1, 1, 1);
        }
    }
}
