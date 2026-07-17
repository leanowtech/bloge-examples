package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteRunEvidenceProtocolCodecTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestSuiteRunEvidenceProtocolCodec codec =
            new TestSuiteRunEvidenceProtocolCodec(mapper);

    @Test
    void v3RoundTripRetainsPayloadFreeAdmissionEvidence() {
        TestSuiteRunEvidenceV3 evidence = evidence();

        String json = codec.write(evidence);

        assertThat(codec.read(json)).isInstanceOf(TestSuiteRunEvidenceV3.class)
                .isEqualTo(evidence);
        assertThat(codec.write(codec.read(json))).isEqualTo(json);
        assertThat(codec.fingerprint(evidence)).isEqualTo(ProtocolFingerprint.of(mapper, evidence));
        assertThat(json).contains(TestSuiteRunEvidenceV3.SCHEMA_VERSION,
                        "SCHEMA_ADMISSION", "MATCHED", "SATISFIED")
                .doesNotContain("hello", "requestPayload", "responsePayload");
    }

    @Test
    void unsupportedEvidenceGenerationFailsClosed() {
        assertThatThrownBy(() -> codec.read(
                "{\"schemaVersion\":\"bloge.testSuiteRunEvidence.v99\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported schemaVersion");
    }

    @Test
    void responseAndPortableBundleRetainAdmissionGenerations() {
        TestSuiteRunEvidenceV3 evidence = evidence();
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner()).seal(evidence, FINGERPRINT,
                List.of(), TestSuiteRunAttestation.Scope.TERMINAL).attestation();

        TestSuiteExecutionResponse response = new TestSuiteExecutionResponse(
                "", evidence.suiteRunId(), codec.fingerprint(evidence), evidence, attestation);
        TestSuiteEvidenceBundle bundle = new TestSuiteEvidenceBundle(
                "", evidence.suiteRunId(), FINGERPRINT,
                TestSuiteEvidenceBundle.PayloadPolicy.OMITTED, attestation, evidence);

        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V4);
        assertThat(bundle.schemaVersion()).isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V3);
        assertThat(bundle.attestation().childEvidenceRefs()).isEmpty();
        assertThatThrownBy(() -> new TestSuiteExecutionResponse(
                TestSuiteExecutionResponse.SCHEMA_VERSION_V3, evidence.suiteRunId(),
                codec.fingerprint(evidence), evidence, attestation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generations must match");
    }

    @Test
    void admissionEvidenceCannotClaimBusinessPromotionOrChildExecution() {
        TestSuiteRunEvidenceV3 source = evidence();
        TestSuiteRunEvidence.PromotionVerdict eligible =
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(),
                        true, 1, 1, true, true, true);
        TestSuiteRunEvidence.CaseResult childBacked = new TestSuiteRunEvidence.CaseResult(
                source.caseResults().getFirst().caseId(), TestSuite.CaseType.BOUNDARY,
                source.caseResults().getFirst().fixtureBundleRef(),
                TestSuiteRunEvidence.CaseStatus.PASSED, "child-run", null, null,
                0, 0, "", "");

        assertThatThrownBy(() -> copy(source, source.caseResults(), eligible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked from business promotion");
        assertThatThrownBy(() -> copy(source, List.of(childBacked), source.promotion()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot reference business child execution");
    }

    private static TestSuiteRunEvidenceV3 evidence() {
        TestSuite.FixtureBundleRef fixture =
                new TestSuite.FixtureBundleRef("fixture", 1, FINGERPRINT);
        TestSuiteRunEvidence.CaseResult commonResult = new TestSuiteRunEvidence.CaseResult(
                "required-name", TestSuite.CaseType.BOUNDARY, fixture,
                TestSuiteRunEvidence.CaseStatus.PASSED, "", null, null,
                0, 0, "", "");
        TestSuiteRunEvidenceV3.AdmissionCaseResult admissionResult =
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        "required-name", TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED,
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED,
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED,
                        List.of("visual.context.required"),
                        List.of("visual.context.required"), "");
        return new TestSuiteRunEvidenceV3("", "suite-run", "request",
                TestSuiteRunEvidence.Status.PASSED, TestSuiteRunEvidenceV3.EXECUTION_PURPOSE,
                new TestSuiteExecutionRequest.SuiteRef("suite", 3, FINGERPRINT),
                new TestSuite.Target("GRAPH", "graph", FINGERPRINT),
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:00:01Z"), List.of(commonResult),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of(TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED,
                                TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY),
                        true, 0, 0, false, false, true),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                FINGERPRINT, "sha256:" + "b".repeat(64), "boundary-cases-v1",
                TestSuiteRunEvidenceV3.VERIFICATION_MODE, TestBoundaryCasePlan.Status.GENERATED,
                0, false, List.of(admissionResult),
                new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(
                        TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED,
                        1, 1, 1, List.of(), List.of(), List.of(), true),
                List.of(), Map.of("scope", "tenant/environment"));
    }

    private static TestSuiteRunEvidenceV3 copy(
            TestSuiteRunEvidenceV3 source,
            List<TestSuiteRunEvidence.CaseResult> caseResults,
            TestSuiteRunEvidence.PromotionVerdict promotion) {
        return new TestSuiteRunEvidenceV3(source.schemaVersion(), source.suiteRunId(),
                source.clientRequestId(), source.status(), source.executionPurpose(),
                source.suiteRef(), source.target(), source.startedAt(), source.completedAt(),
                caseResults, source.coverage(), promotion, source.evaluationMode(),
                source.boundaryPlanFingerprint(), source.inputSchemaFingerprint(),
                source.generatorVersion(), source.verificationMode(), source.sourcePlanStatus(),
                source.sourceCoverageGapCount(), source.coverageGapsAccepted(),
                source.admissionResults(), source.admissionCoverage(), source.diagnostics(),
                source.metadata());
    }
}
