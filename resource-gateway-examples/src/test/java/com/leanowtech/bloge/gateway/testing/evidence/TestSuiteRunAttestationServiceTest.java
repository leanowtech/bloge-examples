package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteRunAttestationServiceTest {

    private static final String REQUEST_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final Instant SIGNED_AT = Instant.parse("2026-07-16T10:15:30Z");

    private ObjectMapper objectMapper;
    private TestSuiteRunAttestationService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TestSuiteRunAttestationService(objectMapper,
                new InMemoryVisualEvidenceSigner(), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    }

    @Test
    void signsAndVerifiesCheckpointAndTerminalDomainsIndependently() {
        TestSuiteRunEvidence checkpoint = evidence(TestSuiteRunEvidence.Status.RUNNING, Map.of());
        var checkpointSeal = service.seal(checkpoint, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.CHECKPOINT);
        TestSuiteRunEvidence terminal = evidence(TestSuiteRunEvidence.Status.PASSED,
                Map.of("completed", true));
        var terminalSeal = service.seal(terminal, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.TERMINAL);

        assertThat(checkpointSeal.verified()).isTrue();
        assertThat(checkpointSeal.attestation().signedAt()).isEqualTo(SIGNED_AT);
        assertThat(service.verify(checkpoint, checkpointSeal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
        assertThat(terminalSeal.verified()).isTrue();
        assertThat(terminalSeal.attestation().terminallyVerifiable()).isTrue();
        assertThat(service.verify(terminal, terminalSeal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
        assertThat(terminalSeal.attestation().signature())
                .isNotEqualTo(checkpointSeal.attestation().signature());
    }

    @Test
    void aggregateMutationAndChildOrderMutationInvalidateSignature() {
        TestSuiteRunEvidence original = evidence(TestSuiteRunEvidence.Status.PASSED, Map.of());
        var seal = service.seal(original, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        TestSuiteRunEvidence mutated = evidence(TestSuiteRunEvidence.Status.PASSED,
                Map.of("tampered", true));
        List<TestSuiteRunAttestation.ChildEvidenceRef> reversed = List.of(
                children().get(1), children().getFirst());
        TestSuiteRunAttestation reordered = copy(seal.attestation(), REQUEST_FINGERPRINT, reversed);

        assertThat(service.verify(mutated, seal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.INVALID);
        assertThat(service.verify(original, reordered))
                .isEqualTo(TestSuiteRunAttestationService.Verification.INVALID);
    }

    @Test
    void requestIdentityMutationInvalidatesSignature() {
        TestSuiteRunEvidence evidence = evidence(TestSuiteRunEvidence.Status.PASSED, Map.of());
        var seal = service.seal(evidence, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        TestSuiteRunAttestation changed = copy(seal.attestation(),
                "sha256:" + "f".repeat(64), children());

        assertThat(service.verify(evidence, changed))
                .isEqualTo(TestSuiteRunAttestationService.Verification.INVALID);
    }

    @Test
    void declaredAlgorithmMustMatchResolvedVerificationKey() {
        TestSuiteRunEvidence evidence = evidence(TestSuiteRunEvidence.Status.PASSED, Map.of());
        var seal = service.seal(evidence, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        TestSuiteRunAttestation changed = new TestSuiteRunAttestation(
                seal.attestation().schemaVersion(), seal.attestation().signatureStatus(),
                seal.attestation().scope(), seal.attestation().suiteRunId(),
                seal.attestation().suiteRef(), seal.attestation().requestFingerprint(),
                seal.attestation().aggregateEvidenceFingerprint(),
                seal.attestation().childEvidenceRefs(), seal.attestation().signedAt(),
                seal.attestation().keyId(), "RSA", seal.attestation().signature(), true);

        assertThat(service.verify(evidence, changed))
                .isEqualTo(TestSuiteRunAttestationService.Verification.INVALID);
    }

    @Test
    void unavailableSignerProducesExplicitFailClosedManifest() {
        TestSuiteRunAttestationService unavailable = new TestSuiteRunAttestationService(
                objectMapper, VisualEvidenceSigner.unavailable(),
                Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

        var result = unavailable.seal(evidence(TestSuiteRunEvidence.Status.RUNNING, Map.of()),
                REQUEST_FINGERPRINT, children(), TestSuiteRunAttestation.Scope.CHECKPOINT);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode())
                .isEqualTo(TestSuiteRunAttestationService.SIGNER_UNAVAILABLE);
        assertThat(result.attestation().signatureStatus())
                .isEqualTo(TestSuiteRunAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE);
    }

    @Test
    void semanticEvidenceUsesIndependentV2SignatureDomain() {
        TestSuiteRunEvidence structural = evidence(TestSuiteRunEvidence.Status.PASSED, Map.of());
        var requirement = new SemanticCoveragePolicy.RetryRequirement("retry",
                SemanticCoveragePolicy.Kind.RETRY, "/root/remote#PRIMARY", 2);
        TestSuiteRunEvidenceV2 semantic = new TestSuiteRunEvidenceV2("", structural.suiteRunId(),
                structural.clientRequestId(), structural.status(), structural.executionPurpose(),
                structural.suiteRef(), structural.target(), structural.startedAt(), structural.completedAt(),
                structural.caseResults(), structural.coverage(), new SemanticCoverageVerdict(
                SemanticCoverageVerdict.Status.SATISFIED, List.of(requirement), List.of(
                new SemanticCoverageVerdict.Observation("retry", SemanticCoveragePolicy.Kind.RETRY,
                        List.of("golden"))), List.of(), List.of()), structural.promotion(),
                structural.diagnostics(), structural.metadata());

        var seal = service.seal(semantic, REQUEST_FINGERPRINT, children(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        TestSuiteRunAttestation downgradedDomain = new TestSuiteRunAttestation(
                TestSuiteRunAttestation.SCHEMA_VERSION, seal.attestation().signatureStatus(),
                seal.attestation().scope(), seal.attestation().suiteRunId(), seal.attestation().suiteRef(),
                seal.attestation().requestFingerprint(), seal.attestation().aggregateEvidenceFingerprint(),
                seal.attestation().childEvidenceRefs(), seal.attestation().signedAt(),
                seal.attestation().keyId(), seal.attestation().algorithm(),
                seal.attestation().signature(), true);

        assertThat(seal.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V2);
        assertThat(service.verify(semantic, seal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
        assertThat(service.verify(semantic, downgradedDomain))
                .isEqualTo(TestSuiteRunAttestationService.Verification.INVALID);
    }

    private static TestSuiteRunAttestation copy(
            TestSuiteRunAttestation source, String requestFingerprint,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        return new TestSuiteRunAttestation(source.schemaVersion(), source.signatureStatus(),
                source.scope(), source.suiteRunId(), source.suiteRef(), requestFingerprint,
                source.aggregateEvidenceFingerprint(), children, source.signedAt(), source.keyId(),
                source.algorithm(), source.signature(), source.independentlyVerifiable());
    }

    private static List<TestSuiteRunAttestation.ChildEvidenceRef> children() {
        return List.of(
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "golden", "run-1", "sha256:" + "1".repeat(64)),
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "negative", "run-2", "sha256:" + "2".repeat(64)));
    }

    private static TestSuiteRunEvidence evidence(TestSuiteRunEvidence.Status status,
                                                 Map<String, Object> metadata) {
        boolean terminal = status != TestSuiteRunEvidence.Status.RUNNING;
        return new TestSuiteRunEvidence("", "suite-run-1", "request-1", status,
                "TEST_SUITE_EXECUTION", new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "b".repeat(64)), new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "c".repeat(64)), SIGNED_AT.minusSeconds(30),
                terminal ? SIGNED_AT : null, List.of(),
                terminal ? new TestSuiteRunEvidence.CoverageVerdict(
                        TestSuiteRunEvidence.CoverageStatus.SATISFIED, 0, 0, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        0, List.of(), List.of(), true)
                        : TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                terminal ? new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true,
                        0, 0, true, true, true)
                        : TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), metadata);
    }
}
