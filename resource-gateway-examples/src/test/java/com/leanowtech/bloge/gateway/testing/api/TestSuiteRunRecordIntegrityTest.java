package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteRunRecordIntegrityTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-20T08:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestSuiteRunAttestationService attestations =
            new TestSuiteRunAttestationService(mapper, new InMemoryVisualEvidenceSigner());

    @Test
    void canonicalWriteSnapshotBindsSignedEvidenceToTheStorageEnvelope() {
        TestSuiteRunRecord submitted = record();

        TestSuiteRunRecord snapshot = TestSuiteRunRecordIntegrity.verifiedWriteSnapshot(
                mapper, attestations, submitted);

        assertThat(snapshot).isNotSameAs(submitted).isEqualTo(submitted);
        assertThat(attestations.verify(snapshot.evidence(), snapshot.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
    }

    @Test
    void exactWriteReceiptRejectsOtherwiseValidEnvelopeSubstitution() {
        TestSuiteRunRecord expected = record();
        TestSuiteRunRecord substituted = new TestSuiteRunRecord(
                expected.suiteRunId(), expected.clientRequestId(), expected.requestFingerprint(),
                expected.tenantId(), expected.organizationId(), expected.projectId(),
                expected.environmentId(), expected.actorId(), expected.classification(),
                expected.evidenceFingerprint(), expected.evidence(), expected.attestation(),
                expected.createdAt(), expected.expiresAt().plusSeconds(1));

        assertPayloadFreeFailure(() -> TestSuiteRunRecordIntegrity.verifiedWriteReceipt(
                mapper, attestations, substituted, expected));
    }

    @Test
    void completeLookupKeyRejectsCrossScopeRepositorySubstitution() {
        TestSuiteRunRecord stored = record();

        assertPayloadFreeFailure(() -> TestSuiteRunRecordIntegrity.verifiedRunLookup(
                mapper, attestations, stored, "tenant-b", "test", stored.suiteRunId()));
        assertPayloadFreeFailure(() -> TestSuiteRunRecordIntegrity.verifiedClientLookup(
                mapper, attestations, stored, "tenant-a", "staging", stored.clientRequestId()));
        assertPayloadFreeFailure(() -> TestSuiteRunRecordIntegrity.verifiedSuiteLookup(
                mapper, attestations, stored, "tenant-a", "test", "another-suite", 3));
    }

    @Test
    void abandonedCandidateMustBeRunningRetainedAndActuallyLeaseExpired() {
        TestSuiteRunRecord stored = record();
        Instant observedAt = STARTED_AT.plusSeconds(30);

        AbandonedTestSuiteRun verified = TestSuiteRunRecordIntegrity.verifiedAbandoned(
                mapper, attestations, new AbandonedTestSuiteRun(
                        stored, 7, "instance-a", observedAt.minusSeconds(1)), observedAt);

        assertThat(verified.record()).isEqualTo(stored);
        assertPayloadFreeFailure(() -> TestSuiteRunRecordIntegrity.verifiedAbandoned(
                mapper, attestations, new AbandonedTestSuiteRun(
                        stored, 7, "instance-a", observedAt.plusSeconds(1)), observedAt));
    }

    private TestSuiteRunRecord record() {
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "b".repeat(64));
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence(
                "", "suite-run-a", "request-a", TestSuiteRunEvidence.Status.RUNNING,
                "TEST_SUITE_EXECUTION", suiteRef, target, STARTED_AT, null, List.of(),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of(
                "tenantId", "tenant-a",
                "organizationId", "org-a",
                "projectId", "project-a",
                "environmentId", "test",
                "actorId", "runner",
                "classification", "INTERNAL"));
        String requestFingerprint = "sha256:" + "c".repeat(64);
        var seal = attestations.seal(evidence, requestFingerprint, List.of(),
                TestSuiteRunAttestation.Scope.CHECKPOINT);
        return new TestSuiteRunRecord(
                evidence.suiteRunId(), evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                seal.evidence(), seal.attestation(), STARTED_AT, STARTED_AT.plusSeconds(3600));
    }

    private static void assertPayloadFreeFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(TestSuiteRunIntegrityException.class)
                .hasMessage("Suite-run persistence requires a structurally valid signed attestation")
                .hasMessageNotContaining("tenant-a")
                .hasMessageNotContaining("tenant-b")
                .hasMessageNotContaining("suite-run-a");
    }
}
