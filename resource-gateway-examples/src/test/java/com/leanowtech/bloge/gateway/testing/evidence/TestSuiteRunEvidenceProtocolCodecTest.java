package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
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
                TestSuiteRunEvidence.Status.PASSED, "SCHEMA_ADMISSION_SUITE_EXECUTION",
                new TestSuiteExecutionRequest.SuiteRef("suite", 3, FINGERPRINT),
                new TestSuite.Target("GRAPH", "graph", FINGERPRINT),
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:00:01Z"), List.of(commonResult),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of("BUSINESS_EXECUTION_NOT_PERFORMED", "SCHEMA_ADMISSION_ONLY"),
                        true, 0, 0, false, false, true),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                FINGERPRINT, "sha256:" + "b".repeat(64), "boundary-cases-v1",
                "EXACT_SHARED_VALIDATOR", TestBoundaryCasePlan.Status.GENERATED,
                0, false, List.of(admissionResult),
                new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(
                        TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED,
                        1, 1, 1, List.of(), List.of(), List.of(), true),
                List.of(), Map.of("scope", "tenant/environment"));
    }
}
