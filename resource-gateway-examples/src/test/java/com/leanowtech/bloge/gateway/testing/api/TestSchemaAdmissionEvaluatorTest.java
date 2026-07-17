package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.TestBoundaryCasePlanner;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSchemaAdmissionEvaluatorTest {
    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final TestExecutionApiRequest.Target TARGET =
            new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET_FINGERPRINT);
    private static final TestSuite.FixtureBundleRef FIXTURE =
            new TestSuite.FixtureBundleRef("boundary-fixture", 1,
                    "sha256:" + "b".repeat(64));

    private ObjectMapper mapper;
    private TestBoundaryCasePlanner planner;
    private TestSchemaAdmissionEvaluator evaluator;
    private SchemaEnvelope schema;
    private TestBoundaryCasePlan plan;
    private TestSchemaAdmissionTarget current;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        planner = new TestBoundaryCasePlanner(mapper, new JsonSchemaSampleGenerator());
        evaluator = new TestSchemaAdmissionEvaluator(mapper);
        schema = SchemaEnvelope.object(Map.of(
                "name", Map.of("type", "string", "minLength", 1)), List.of("name"));
        plan = planner.plan(TARGET, schema, List.of());
        current = TestSchemaAdmissionTarget.verified(mapper, TARGET, schema, plan);
    }

    @Test
    void evaluatesAcceptedAndRejectedCasesWithNoBusinessChildEvidence() {
        TestSuiteV3 suite = suite(plan, selected(plan, 3), null);
        TestSchemaAdmissionEvaluator.PreparedAdmission prepared =
                evaluator.prepare(suite, current);

        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> results = suite.cases().stream()
                .map(testCase -> evaluator.evaluate(prepared, testCase,
                        suite.admissionExpectations().get(testCase.caseId())))
                .toList();
        TestSuiteRunEvidenceV3.AdmissionCoverageVerdict coverage = evaluator.coverage(results);

        assertThat(results).extracting(TestSuiteRunEvidenceV3.AdmissionCaseResult::status)
                .containsOnly(TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED);
        assertThat(results).anySatisfy(result -> assertThat(result.observedOutcome())
                .isEqualTo(TestSuiteV3.ExpectedOutcome.ACCEPTED));
        assertThat(results).anySatisfy(result -> {
            assertThat(result.observedOutcome())
                    .isEqualTo(TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED);
            assertThat(result.observedValidationCodes()).isNotEmpty();
        });
        assertThat(coverage.status())
                .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED);
        assertThat(coverage.matchedCases()).isEqualTo(suite.cases().size());
        assertThat(suite.cases()).zipSatisfy(results, (testCase, result) -> {
            TestSuiteRunEvidence.CaseResult common = evaluator.commonResult(testCase, result);
            assertThat(common.status()).isEqualTo(TestSuiteRunEvidence.CaseStatus.PASSED);
            assertThat(common.runId()).isBlank();
            assertThat(common.evidenceStatus()).isNull();
            assertThat(common.evidenceClass()).isNull();
            assertThat(common.assertionsEvaluated()).isZero();
        });
    }

    @Test
    void rejectsTargetSchemaAndPlanDriftBeforeCaseEvaluation() {
        TestSuiteV3 suite = suite(plan, selected(plan, 2), null);
        TestExecutionApiRequest.Target changedTarget = new TestExecutionApiRequest.Target(
                "GRAPH", "orders", "sha256:" + "c".repeat(64));
        TestBoundaryCasePlan changedTargetPlan = planner.plan(changedTarget, schema, List.of());
        SchemaEnvelope changedSchema = SchemaEnvelope.object(Map.of(
                "orderId", Map.of("type", "string")), List.of("orderId"));
        TestBoundaryCasePlan changedSchemaPlan = planner.plan(TARGET, changedSchema, List.of());
        TestBoundaryCasePlan changedPlan = planner.plan(TARGET, schema, List.of(
                new TestBoundaryCasePlan.CoverageGap(
                        TestBoundaryCasePlan.GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                        "/inputSchema/schema", "format")));

        assertConflict(() -> evaluator.prepare(suite,
                        TestSchemaAdmissionTarget.verified(
                                mapper, changedTarget, schema, changedTargetPlan)),
                TestSchemaAdmissionEvaluator.TARGET_CONFLICT);
        assertConflict(() -> evaluator.prepare(suite,
                        TestSchemaAdmissionTarget.verified(
                                mapper, TARGET, changedSchema, changedSchemaPlan)),
                TestSchemaAdmissionEvaluator.INPUT_SCHEMA_CONFLICT);
        assertConflict(() -> evaluator.prepare(suite,
                        TestSchemaAdmissionTarget.verified(mapper, TARGET, schema, changedPlan)),
                TestSchemaAdmissionEvaluator.BOUNDARY_PLAN_CONFLICT);
    }

    @Test
    void targetSnapshotCannotCombineSchemaAndPlanFromDifferentResolutions() {
        SchemaEnvelope changedSchema = SchemaEnvelope.object(Map.of(
                "orderId", Map.of("type", "string")), List.of("orderId"));

        assertThatThrownBy(() -> TestSchemaAdmissionTarget.verified(
                mapper, TARGET, changedSchema, plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema and boundary plan fingerprints must match");
    }

    @Test
    void requiresExplicitExactGapAcceptanceForPartialPlans() {
        TestBoundaryCasePlan partial = planner.plan(TARGET, schema, List.of(
                new TestBoundaryCasePlan.CoverageGap(
                        TestBoundaryCasePlan.GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                        "/inputSchema/schema/properties/name/format", "format")));
        Map<String, Object> wrongMetadata = metadata(partial, 2);
        wrongMetadata.put("coverageGapsAccepted", false);
        TestSuiteV3 suite = suite(partial, selected(partial, 2), wrongMetadata);

        assertThat(partial.status()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);
        assertConflict(() -> evaluator.prepare(suite,
                        TestSchemaAdmissionTarget.verified(mapper, TARGET, schema, partial)),
                TestSchemaAdmissionEvaluator.SUITE_PROVENANCE_INVALID);
    }

    @Test
    void caseInputTamperingProducesSignedEvidenceCandidateInsteadOfPreflightConflict() {
        List<TestBoundaryCasePlan.BoundaryCase> selected = selected(plan, 2);
        TestSuiteV3 original = suite(plan, selected, null);
        List<TestSuite.TestCase> changedCases = new ArrayList<>(original.cases());
        TestSuite.TestCase source = changedCases.getFirst();
        changedCases.set(0, new TestSuite.TestCase(source.caseId(), source.caseType(), Map.of(),
                source.fixtureBundleRef(), source.tags(), source.metadata()));
        TestSuiteV3 changed = copy(original, changedCases,
                original.admissionExpectations(), original.metadata());
        TestSchemaAdmissionEvaluator.PreparedAdmission prepared =
                evaluator.prepare(changed, current);

        TestSuiteRunEvidenceV3.AdmissionCaseResult result = evaluator.evaluate(prepared,
                changed.cases().getFirst(),
                changed.admissionExpectations().get(changed.cases().getFirst().caseId()));

        assertThat(result.status())
                .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCaseStatus.PROVENANCE_MISMATCH);
        assertThat(result.diagnosticCode())
                .isEqualTo(TestSchemaAdmissionEvaluator.CASE_PROVENANCE_MISMATCH);
        assertThat(result.observedOutcome())
                .isEqualTo(TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED);
    }

    @Test
    void validatorDisagreementTakesExpectationMismatchPathWhenPlanProvenanceMatches() {
        String schemaFingerprint = ProtocolFingerprint.of(mapper, schema);
        TestBoundaryCasePlan.BoundaryCase impossible = new TestBoundaryCasePlan.BoundaryCase(
                "impossible-accepted", TestBoundaryCasePlan.BoundaryKind.BASELINE,
                "", "/inputSchema/schema", TestBoundaryCasePlan.ExpectedOutcome.ACCEPTED,
                Map.of(), List.of());
        TestBoundaryCasePlan inconsistent = new TestBoundaryCasePlan("", TARGET,
                schemaFingerprint, "sha256:" + "d".repeat(64),
                TestBoundaryCasePlan.Status.GENERATED, plan.policy(), List.of(impossible), List.of());
        TestSuiteV3 suite = suite(inconsistent, List.of(impossible), null);
        TestSchemaAdmissionEvaluator.PreparedAdmission prepared = evaluator.prepare(suite,
                TestSchemaAdmissionTarget.verified(mapper, TARGET, schema, inconsistent));

        TestSuiteRunEvidenceV3.AdmissionCaseResult result = evaluator.evaluate(prepared,
                suite.cases().getFirst(), suite.admissionExpectations().get(impossible.caseId()));

        assertThat(result.status())
                .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCaseStatus.EXPECTATION_MISMATCH);
        assertThat(result.diagnosticCode())
                .isEqualTo(TestSchemaAdmissionEvaluator.EXPECTATION_MISMATCH);
        assertThat(result.observedValidationCodes()).isNotEmpty();
    }

    @Test
    void pendingAndPartialClosuresProduceHonestIncompleteCoverage() {
        TestSuiteV3 suite = suite(plan, selected(plan, 2), null);
        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> pending = evaluator.pending(suite);
        TestSchemaAdmissionEvaluator.PreparedAdmission prepared = evaluator.prepare(suite, current);
        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> partial = new ArrayList<>(pending);
        partial.set(0, evaluator.evaluate(prepared, suite.cases().getFirst(),
                suite.admissionExpectations().get(suite.cases().getFirst().caseId())));

        assertThat(evaluator.coverage(pending).status())
                .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.NOT_EVALUATED);
        TestSuiteRunEvidenceV3.AdmissionCoverageVerdict coverage = evaluator.coverage(partial);
        assertThat(coverage.status())
                .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE);
        assertThat(coverage.evaluatedCases()).isOne();
        assertThat(coverage.incompleteCaseIds()).containsExactly(suite.cases().get(1).caseId());
        assertThat(coverage.allCasesCompleted()).isFalse();
    }

    private TestSuiteV3 suite(
            TestBoundaryCasePlan sourcePlan,
            List<TestBoundaryCasePlan.BoundaryCase> sourceCases,
            Map<String, Object> explicitMetadata) {
        List<TestSuite.TestCase> cases = sourceCases.stream().map(source ->
                new TestSuite.TestCase(source.caseId(), TestSuite.CaseType.BOUNDARY,
                        source.input(), FIXTURE, List.of("schema-admission"), Map.of())).toList();
        Map<String, TestSuiteV3.AdmissionExpectation> expectations = new LinkedHashMap<>();
        sourceCases.forEach(source -> expectations.put(source.caseId(),
                new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.valueOf(source.expectedOutcome().name()),
                        source.validationCodes())));
        Map<String, Object> provenance = explicitMetadata == null
                ? metadata(sourcePlan, cases.size()) : explicitMetadata;
        return new TestSuiteV3("", "boundary-suite", 1,
                new TestSuite.Target(sourcePlan.target().kind(), sourcePlan.target().id(),
                        sourcePlan.target().fingerprint()), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(cases.size(), List.of(TestSuite.CaseType.BOUNDARY),
                        List.of(), List.of(), 0, false), SemanticCoveragePolicy.empty(),
                new TestSuite.PromotionPolicy(true, 0, false),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                sourcePlan.planFingerprint(), sourcePlan.inputSchemaFingerprint(),
                expectations, provenance);
    }

    private static TestSuiteV3 copy(
            TestSuiteV3 source,
            List<TestSuite.TestCase> cases,
            Map<String, TestSuiteV3.AdmissionExpectation> expectations,
            Map<String, Object> metadata) {
        return new TestSuiteV3(source.schemaVersion(), source.suiteId(), source.revision(),
                source.target(), source.classification(), cases, source.coveragePolicy(),
                source.semanticCoveragePolicy(), source.promotionPolicy(), source.evaluationMode(),
                source.boundaryPlanFingerprint(), source.inputSchemaFingerprint(),
                expectations, metadata);
    }

    private static Map<String, Object> metadata(TestBoundaryCasePlan sourcePlan, int caseCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "schema-boundary-plan");
        metadata.put("evaluationMode", TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION.name());
        metadata.put("boundaryPlanStatus", sourcePlan.status().name());
        metadata.put("coverageGapCount", sourcePlan.gaps().size());
        metadata.put("coverageGapsAccepted",
                sourcePlan.status() == TestBoundaryCasePlan.Status.PARTIAL);
        metadata.put("selectedCaseCount", caseCount);
        return metadata;
    }

    private static List<TestBoundaryCasePlan.BoundaryCase> selected(
            TestBoundaryCasePlan sourcePlan, int count) {
        return sourcePlan.cases().stream().limit(count).toList();
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TestSchemaAdmissionEvaluator.Conflict.class,
                        conflict -> assertThat(conflict.code()).isEqualTo(code));
    }
}
