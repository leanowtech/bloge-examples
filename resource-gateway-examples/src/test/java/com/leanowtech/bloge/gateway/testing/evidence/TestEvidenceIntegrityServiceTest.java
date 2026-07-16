package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestEvidenceIntegrityServiceTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();
    private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final TestEvidenceIntegrityService service =
            new TestEvidenceIntegrityService(mapper, signer);

    @Test
    void sealsAndVerifiesCompleteSanitizedEvidence() {
        TestRunEvidence evidence = evidence("run-1", TestRunEvidence.Status.PASSED);

        TestEvidenceIntegrityService.SealResult result = service.seal(evidence);

        assertThat(result.verified()).isTrue();
        assertThat(result.integrity().signed()).isTrue();
        assertThat(result.integrity().independentlyVerifiable()).isTrue();
        assertThat(result.integrity().evidenceFingerprint()).startsWith("sha256:");
        assertThat(service.verify(evidence, result.integrity()))
                .isEqualTo(TestEvidenceIntegrityService.Verification.VERIFIED);
    }

    @Test
    void rejectsEvidenceChangedAfterSigning() {
        TestEvidenceIntegrity integrity = service.seal(
                evidence("run-1", TestRunEvidence.Status.PASSED)).integrity();

        assertThat(service.verify(evidence("run-1", TestRunEvidence.Status.EXECUTION_FAILED), integrity))
                .isEqualTo(TestEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void projectedEvidenceRetainsLineageWithoutClaimingIndependentVerification() {
        TestRunEvidence full = evidence("run-1", TestRunEvidence.Status.PASSED);
        TestEvidenceIntegrity integrity = service.seal(full).integrity();
        TestRunEvidence summary = new TestRunEvidence(full.schemaVersion(), full.runId(), full.status(),
                full.evidenceClass(), full.executionPurpose(), full.targetFingerprint(),
                full.fixtureBundleFingerprint(), full.planFingerprint(),
                full.semanticResultFingerprint(), full.startedAt(), full.completedAt(), List.of(),
                List.of(), full.fixtureConsumptions(), full.assertionResults(), full.diagnostics(),
                full.metadata());

        TestEvidenceIntegrity projected = service.project(integrity, summary,
                TestEvidenceIntegrity.Projection.SUMMARY);

        assertThat(projected.signatureStatus()).isEqualTo(TestEvidenceIntegrity.SignatureStatus.VERIFIED);
        assertThat(projected.evidenceFingerprint()).isEqualTo(integrity.evidenceFingerprint());
        assertThat(projected.projectionFingerprint()).isNotEqualTo(projected.evidenceFingerprint());
        assertThat(projected.independentlyVerifiable()).isFalse();
        assertThat(service.verify(summary, projected))
                .isEqualTo(TestEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void failsClosedWhenSigningAuthorityIsUnavailable() {
        TestEvidenceIntegrityService unavailable = new TestEvidenceIntegrityService(
                mapper, VisualEvidenceSigner.unavailable());

        TestEvidenceIntegrityService.SealResult result = unavailable.seal(
                evidence("run-1", TestRunEvidence.Status.PASSED));

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode()).isEqualTo(TestEvidenceIntegrityService.SIGNER_UNAVAILABLE);
        assertThat(result.integrity().signatureStatus())
                .isEqualTo(TestEvidenceIntegrity.SignatureStatus.VERIFICATION_UNAVAILABLE);
    }

    @Test
    void reportsMalformedDetachedSignatureAsInvalid() {
        TestRunEvidence evidence = evidence("run-1", TestRunEvidence.Status.PASSED);
        TestEvidenceIntegrity original = service.seal(evidence).integrity();
        TestEvidenceIntegrity malformed = new TestEvidenceIntegrity("", original.evidenceFingerprint(),
                TestEvidenceIntegrity.SignatureStatus.VERIFIED, original.keyId(), original.algorithm(),
                original.signedAt(), "not-base64", TestEvidenceIntegrity.Projection.FULL,
                original.evidenceFingerprint(), true);

        assertThat(service.verify(evidence, malformed))
                .isEqualTo(TestEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void rejectsSigningTimeChangedAfterSigning() {
        TestRunEvidence evidence = evidence("run-1", TestRunEvidence.Status.PASSED);
        TestEvidenceIntegrity original = service.seal(evidence).integrity();
        TestEvidenceIntegrity altered = new TestEvidenceIntegrity("", original.evidenceFingerprint(),
                TestEvidenceIntegrity.SignatureStatus.VERIFIED, original.keyId(), original.algorithm(),
                original.signedAt().plusSeconds(1), original.signature(),
                TestEvidenceIntegrity.Projection.FULL, original.evidenceFingerprint(), true);

        assertThat(service.verify(evidence, altered))
                .isEqualTo(TestEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void refusesToSealCurrentEvidenceWithAStaleSemanticFingerprint() {
        TestRunEvidence original = evidence("run-1", TestRunEvidence.Status.PASSED);
        TestRunEvidence stale = new TestRunEvidence(original.schemaVersion(), original.runId(),
                TestRunEvidence.Status.EXECUTION_FAILED, original.evidenceClass(),
                original.executionPurpose(), original.targetFingerprint(),
                original.fixtureBundleFingerprint(), original.planFingerprint(),
                original.semanticResultFingerprint(), original.startedAt(), original.completedAt(),
                original.nodeTrace(), original.edgeTrace(), original.fixtureConsumptions(),
                original.assertionResults(), original.diagnostics(), original.metadata());

        TestEvidenceIntegrityService.SealResult result = service.seal(stale);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode())
                .isEqualTo(TestEvidenceIntegrityService.SEMANTIC_FINGERPRINT_INVALID);
    }

    private static TestRunEvidence evidence(String runId, TestRunEvidence.Status status) {
        Instant started = Instant.parse("2026-07-16T00:00:00Z");
        TestRunEvidence evidence = new TestRunEvidence("", runId, status,
                TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST", "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64), "sha256:" + "3".repeat(64),
                started, started.plusSeconds(1),
                List.of(new TestRunEvidence.NodeTrace("node-a", "operator-a", "SUCCESS", "REAL",
                        Map.of("id", "42"), Map.of("ok", true), "", 4)),
                List.of(), List.of(), List.of(), List.of(), Map.of("caseId", "case-a"));
        return TestSemanticResultFingerprint.attach(JsonMapper.builder()
                .addModule(new JavaTimeModule()).build(), evidence);
    }
}
