package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.TestBoundaryCasePlanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Materializes a human-selected boundary-plan subset into content-addressed immutable assets.
 *
 * <p>The service always regenerates the current plan and compares all caller-observed
 * fingerprints before writing. It stores one inert fixture first and then one v3 admission suite;
 * a failed second write can leave only an unreferenced immutable fixture and is safe to retry.
 * Partial plans require explicit gap acceptance and unavailable plans can never be materialized.</p>
 */
public final class TestBoundarySuiteMaterializationService {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final TestExecutionApiService executions;
    private final TestSuiteRegistryService suites;
    private final ObjectMapper objectMapper;

    /**
     * @param executions exact plan regeneration and fixture registry service
     * @param suites immutable suite registry
     * @param objectMapper canonical fingerprint mapper
     */
    public TestBoundarySuiteMaterializationService(
            TestExecutionApiService executions,
            TestSuiteRegistryService suites,
            ObjectMapper objectMapper) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.suites = Objects.requireNonNull(suites, "suites");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Materializes selected cases from the current exact graph boundary plan. */
    public TestBoundarySuiteMaterializationResponse materializeGraph(
            String graphName,
            TestBoundarySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireRequest(request, identity);
        return materialize(executions.planGraphBoundaryCases(graphName, identity), request, identity);
    }

    /** Materializes selected cases from the current exact operator boundary plan. */
    public TestBoundarySuiteMaterializationResponse materializeOperator(
            String operatorRef,
            TestBoundarySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireRequest(request, identity);
        return materialize(executions.planOperatorBoundaryCases(operatorRef, identity), request, identity);
    }

    private TestBoundarySuiteMaterializationResponse materialize(
            TestBoundaryCasePlan plan,
            TestBoundarySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireExpectedPlan(plan, request, identity);
        List<TestBoundaryCasePlan.BoundaryCase> selected = selectedCases(plan, request, identity);
        boolean coverageGapsAccepted = plan.status() == TestBoundaryCasePlan.Status.PARTIAL
                && request.acceptCoverageGaps();

        String fixtureId = fixtureId(request.suiteId(), request.expectedPlanFingerprint());
        Map<String, Object> fixtureMetadata = Map.of(
                "source", "schema-boundary-plan",
                "evaluationMode", TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION.name(),
                "boundaryPlanFingerprint", plan.planFingerprint(),
                "inputSchemaFingerprint", plan.inputSchemaFingerprint());
        FixtureBundle fixtureDraft = new FixtureBundle("", fixtureId, 1,
                plan.target().fingerprint(), request.classification(), null, null,
                List.of(), List.of(), fixtureMetadata);
        long fixtureRevision = contentRevision("BOUNDARY_ADMISSION_FIXTURE", fixtureDraft);
        FixtureBundle fixture = new FixtureBundle("", fixtureId, fixtureRevision,
                fixtureDraft.targetFingerprint(), fixtureDraft.classification(), null, null,
                List.of(), List.of(), fixtureDraft.metadata());
        StoredFixtureBundle storedFixture = executions.registerFixture(fixtureId,
                new FixtureBundleRegistrationRequest("", plan.target(), fixture), identity);
        TestSuite.FixtureBundleRef fixtureRef = new TestSuite.FixtureBundleRef(
                storedFixture.fixtureBundleId(), storedFixture.revision(), storedFixture.fingerprint());

        List<TestSuite.TestCase> cases = new ArrayList<>();
        Map<String, TestSuiteV3.AdmissionExpectation> expectations = new LinkedHashMap<>();
        for (TestBoundaryCasePlan.BoundaryCase source : selected) {
            Map<String, Object> metadata = caseMetadata(plan, source);
            cases.add(new TestSuite.TestCase(source.caseId(), caseType(source), source.input(),
                    fixtureRef, tags(source), metadata));
            expectations.put(source.caseId(), new TestSuiteV3.AdmissionExpectation(
                    TestSuiteV3.ExpectedOutcome.valueOf(source.expectedOutcome().name()),
                    source.validationCodes()));
        }

        List<TestSuite.CaseType> requiredTypes = cases.stream()
                .map(TestSuite.TestCase::caseType).distinct().toList();
        TestSuite.CoveragePolicy coverage = new TestSuite.CoveragePolicy(
                cases.size(), requiredTypes, List.of(), List.of(), 0, false);
        TestSuite.PromotionPolicy promotion = new TestSuite.PromotionPolicy(true, 0, false);
        Map<String, Object> suiteMetadata = suiteMetadata(
                plan, coverageGapsAccepted, selected.size());
        TestSuiteV3 draft = new TestSuiteV3("", request.suiteId(), 1,
                new TestSuite.Target(plan.target().kind(), plan.target().id(),
                        plan.target().fingerprint()),
                request.classification(), cases, coverage, SemanticCoveragePolicy.empty(), promotion,
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION, plan.planFingerprint(),
                plan.inputSchemaFingerprint(), expectations, suiteMetadata);
        long suiteRevision = contentRevision("SCHEMA_ADMISSION_SUITE", draft);
        TestSuiteV3 suite = new TestSuiteV3("", draft.suiteId(), suiteRevision, draft.target(),
                draft.classification(), draft.cases(), draft.coveragePolicy(),
                draft.semanticCoveragePolicy(), draft.promotionPolicy(), draft.evaluationMode(),
                draft.boundaryPlanFingerprint(), draft.inputSchemaFingerprint(),
                draft.admissionExpectations(), draft.metadata());
        StoredTestSuite storedSuite = suites.register(request.suiteId(),
                new TestSuiteRegistrationRequest("", suite), identity);
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint());
        List<String> selectedIds = selected.stream()
                .map(TestBoundaryCasePlan.BoundaryCase::caseId).toList();
        Map<String, Object> responseMaterial = new LinkedHashMap<>();
        responseMaterial.put("schemaVersion", TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION);
        responseMaterial.put("target", plan.target());
        responseMaterial.put("inputSchemaFingerprint", plan.inputSchemaFingerprint());
        responseMaterial.put("boundaryPlanFingerprint", plan.planFingerprint());
        responseMaterial.put("sourcePlanStatus", plan.status().name());
        responseMaterial.put("coverageGapsAccepted", coverageGapsAccepted);
        responseMaterial.put("selectedCaseIds", selectedIds);
        responseMaterial.put("fixtureRef", fixtureRef);
        responseMaterial.put("suiteRef", suiteRef);
        return new TestBoundarySuiteMaterializationResponse("",
                ProtocolFingerprint.of(objectMapper, responseMaterial), plan.target(),
                plan.inputSchemaFingerprint(), plan.planFingerprint(), plan.status(),
                coverageGapsAccepted, selectedIds, fixtureRef, suiteRef);
    }

    private static void requireRequest(TestBoundarySuiteMaterializationRequest request,
                                       IntegrationRequestContext identity) {
        if (request == null
                || !TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || !SAFE_ID.matcher(request.suiteId()).matches()
                || !CLASSIFICATIONS.contains(request.classification())
                || !FINGERPRINT.matcher(request.expectedTargetFingerprint()).matches()
                || !FINGERPRINT.matcher(request.expectedInputSchemaFingerprint()).matches()
                || !FINGERPRINT.matcher(request.expectedPlanFingerprint()).matches()
                || request.selectedCaseIds().isEmpty()
                || request.selectedCaseIds().size() > TestBoundaryCasePlanner.MAX_CASES
                || request.selectedCaseIds().stream()
                .anyMatch(caseId -> caseId.isBlank() || caseId.length() > 128)
                || new LinkedHashSet<>(request.selectedCaseIds()).size()
                != request.selectedCaseIds().size()) {
            throw badRequest(identity, "RG.TEST.BOUNDARY_SUITE_REQUEST_INVALID",
                    "A versioned request with safe suite id, exact fingerprints, classification, and unique selected case ids is required.",
                    Map.of());
        }
    }

    private static void requireExpectedPlan(TestBoundaryCasePlan plan,
                                            TestBoundarySuiteMaterializationRequest request,
                                            IntegrationRequestContext identity) {
        if (!plan.target().fingerprint().equals(request.expectedTargetFingerprint())
                || !plan.inputSchemaFingerprint().equals(request.expectedInputSchemaFingerprint())
                || !plan.planFingerprint().equals(request.expectedPlanFingerprint())) {
            throw conflict(identity, "RG.TEST.BOUNDARY_PLAN_FINGERPRINT_CONFLICT",
                    "The current exact boundary plan differs from the caller-reviewed fingerprints.",
                    Map.of("currentTargetFingerprint", plan.target().fingerprint(),
                            "currentInputSchemaFingerprint", plan.inputSchemaFingerprint(),
                            "currentPlanFingerprint", plan.planFingerprint()));
        }
        if (plan.status() == TestBoundaryCasePlan.Status.UNAVAILABLE) {
            throw badRequest(identity, "RG.TEST.BOUNDARY_PLAN_UNAVAILABLE",
                    "An unavailable boundary plan has no proven cases to materialize.", Map.of());
        }
        if (plan.status() == TestBoundaryCasePlan.Status.PARTIAL
                && !request.acceptCoverageGaps()) {
            throw badRequest(identity, "RG.TEST.BOUNDARY_PLAN_GAPS_NOT_ACCEPTED",
                    "A partial boundary plan requires explicit coverage-gap acceptance.",
                    Map.of("gapCount", plan.gaps().size()));
        }
    }

    private static List<TestBoundaryCasePlan.BoundaryCase> selectedCases(
            TestBoundaryCasePlan plan,
            TestBoundarySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        Set<String> requested = new LinkedHashSet<>(request.selectedCaseIds());
        List<TestBoundaryCasePlan.BoundaryCase> selected = plan.cases().stream()
                .filter(testCase -> requested.contains(testCase.caseId())).toList();
        Set<String> resolved = selected.stream().map(TestBoundaryCasePlan.BoundaryCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        if (selected.size() != requested.size()) {
            Set<String> missing = new LinkedHashSet<>(requested);
            missing.removeAll(resolved);
            throw badRequest(identity, "RG.TEST.BOUNDARY_CASE_SELECTION_INVALID",
                    "Every selected case id must belong to the exact reviewed plan.",
                    Map.of("unknownCaseIds", missing.stream().sorted().toList()));
        }
        return selected;
    }

    private long contentRevision(String assetKind, Object content) {
        String fingerprint = ProtocolFingerprint.of(objectMapper,
                Map.of("assetKind", assetKind, "content", content));
        return Math.max(1, Long.parseLong(fingerprint.substring(
                "sha256:".length(), "sha256:".length() + 15), 16));
    }

    private static String fixtureId(String suiteId, String planFingerprint) {
        String suffix = "-schema-admission-" + planFingerprint.substring(
                "sha256:".length(), "sha256:".length() + 12);
        int prefixLength = Math.min(suiteId.length(), 255 - suffix.length());
        return suiteId.substring(0, prefixLength) + suffix;
    }

    private static TestSuite.CaseType caseType(TestBoundaryCasePlan.BoundaryCase testCase) {
        if (testCase.kind() == TestBoundaryCasePlan.BoundaryKind.BASELINE) {
            return TestSuite.CaseType.GOLDEN;
        }
        return switch (testCase.kind()) {
            case REQUIRED_PROPERTY_MISSING, UNKNOWN_PROPERTY, TYPE_MISMATCH,
                    OUTSIDE_ENUM, OUTSIDE_CONST -> TestSuite.CaseType.NEGATIVE;
            default -> TestSuite.CaseType.BOUNDARY;
        };
    }

    private static List<String> tags(TestBoundaryCasePlan.BoundaryCase testCase) {
        return List.of("schema-admission", "boundary-generated",
                testCase.kind().name().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> caseMetadata(
            TestBoundaryCasePlan plan,
            TestBoundaryCasePlan.BoundaryCase testCase) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "schema-boundary-plan");
        metadata.put("boundaryPlanFingerprint", plan.planFingerprint());
        metadata.put("boundaryKind", testCase.kind().name());
        metadata.put("instancePath", testCase.instancePath());
        metadata.put("schemaPath", testCase.schemaPath());
        metadata.put("expectedOutcome", testCase.expectedOutcome().name());
        metadata.put("validationCodes", testCase.validationCodes());
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> suiteMetadata(
            TestBoundaryCasePlan plan,
            boolean coverageGapsAccepted,
            int caseCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "schema-boundary-plan");
        metadata.put("evaluationMode", TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION.name());
        metadata.put("boundaryPlanStatus", plan.status().name());
        metadata.put("coverageGapCount", plan.gaps().size());
        metadata.put("coverageGapsAccepted", coverageGapsAccepted);
        metadata.put("selectedCaseCount", caseCount);
        return Map.copyOf(metadata);
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String detail,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, detail, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String detail,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, detail, identity.correlationId(), details));
    }
}
