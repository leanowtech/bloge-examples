package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.TestPropertyCasePlanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Materializes one exact seeded property plan into an assertion-bearing immutable V4 suite.
 *
 * <p>The full root and shrink closure is regenerated and committed; callers cannot select only
 * favorable cases. The service never creates an inert fixture. Instead it resolves one exact
 * existing fixture revision and requires at least one business assertion before asking the suite
 * registry to verify the regenerated plan proof and persist the content-addressed revision.</p>
 */
public final class TestPropertySuiteMaterializationService {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final TestExecutionApiService executions;
    private final TestSuiteRegistryService suites;
    private final ObjectMapper objectMapper;

    /**
     * @param executions exact plan regeneration and fixture lookup service
     * @param suites immutable suite registry
     * @param objectMapper canonical fingerprint mapper
     */
    public TestPropertySuiteMaterializationService(
            TestExecutionApiService executions,
            TestSuiteRegistryService suites,
            ObjectMapper objectMapper) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.suites = Objects.requireNonNull(suites, "suites");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Materializes the current exact graph property plan. */
    public TestPropertySuiteMaterializationResponse materializeGraph(
            String graphName,
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireRequest(request, identity);
        TestPropertyCasePlan plan = executions.planGraphPropertyCases(graphName, request.seed(),
                request.trials(), request.maxShrinkSteps(), identity);
        return materialize(plan, request, identity);
    }

    /** Materializes the current exact operator property plan. */
    public TestPropertySuiteMaterializationResponse materializeOperator(
            String operatorRef,
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireRequest(request, identity);
        TestPropertyCasePlan plan = executions.planOperatorPropertyCases(operatorRef, request.seed(),
                request.trials(), request.maxShrinkSteps(), identity);
        return materialize(plan, request, identity);
    }

    private TestPropertySuiteMaterializationResponse materialize(
            TestPropertyCasePlan plan,
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        requireExpectedPlan(plan, request, identity);
        StoredFixtureBundle fixture = requireFixture(plan, request, identity);
        TestSuite.FixtureBundleRef fixtureRef = new TestSuite.FixtureBundleRef(
                fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint());

        List<TestSuite.TestCase> cases = new ArrayList<>();
        for (TestPropertyCasePlan.PropertyTrial root : plan.trials()) {
            cases.add(testCase(plan, root, fixtureRef, "ROOT", ""));
            for (TestPropertyCasePlan.ShrinkCandidate shrink : root.shrinkPath()) {
                cases.add(testCase(plan, shrink, fixtureRef, "SHRINK", shrink.parentCaseId()));
            }
        }
        TestSuiteV4.PropertyGenerationPolicy generationPolicy = generationPolicy(plan.policy());
        List<TestSuiteV4.PropertyGenerationGap> gaps = plan.gaps().stream()
                .map(TestPropertySuiteMaterializationService::generationGap).toList();
        List<TestSuiteV4.PropertyTrialRef> trials = plan.trials().stream()
                .map(TestPropertySuiteMaterializationService::trialRef).toList();
        TestSuite.CoveragePolicy coverage = new TestSuite.CoveragePolicy(
                cases.size(), List.of(TestSuite.CaseType.PROPERTY), List.of(), List.of(),
                fixture.bundle().assertions().size(), false);
        TestSuite.PromotionPolicy promotion = new TestSuite.PromotionPolicy(
                true, cases.size(), true);
        boolean gapsAccepted = plan.status() == TestPropertyCasePlan.Status.PARTIAL;
        Map<String, Object> metadata = Map.of(
                "source", "seeded-property-plan",
                "evaluationMode", TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION.name(),
                "propertyPlanStatus", plan.status().name(),
                "generationGapCount", plan.gaps().size(),
                "generationGapsAccepted", gapsAccepted,
                "rootTrialCount", plan.trials().size(),
                "caseCount", cases.size());
        TestSuiteV4 draft = new TestSuiteV4("", request.suiteId(), 1,
                new TestSuite.Target(plan.target().kind(), plan.target().id(),
                        plan.target().fingerprint()), request.classification(), cases, coverage,
                SemanticCoveragePolicy.empty(), promotion,
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false,
                plan.planFingerprint(), plan.inputSchemaFingerprint(), generationPolicy,
                TestSuiteV4.SourcePlanStatus.valueOf(plan.status().name()), gapsAccepted, gaps,
                trials, metadata);
        long revision = contentRevision("PROPERTY_SUITE", draft);
        TestSuiteV4 suite = new TestSuiteV4("", draft.suiteId(), revision, draft.target(),
                draft.classification(), draft.cases(), draft.coveragePolicy(),
                draft.semanticCoveragePolicy(), draft.promotionPolicy(), draft.evaluationMode(),
                draft.quantification(), draft.exhaustive(), draft.propertyPlanFingerprint(),
                draft.inputSchemaFingerprint(), draft.generationPolicy(), draft.sourcePlanStatus(),
                draft.generationGapsAccepted(), draft.generationGaps(), draft.propertyTrials(),
                draft.metadata());
        StoredTestSuite stored = suites.registerPropertySuite(request.suiteId(),
                new TestSuiteRegistrationRequest("", suite), plan, identity);
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                stored.suiteId(), stored.revision(), stored.fingerprint());
        List<String> rootIds = plan.trials().stream()
                .map(TestPropertyCasePlan.PropertyTrial::trialId).toList();
        List<String> caseIds = plan.allCases().stream()
                .map(TestPropertyCasePlan.PlannedCase::caseId).toList();
        Map<String, Object> responseMaterial = new LinkedHashMap<>();
        responseMaterial.put("schemaVersion", TestPropertySuiteMaterializationResponse.SCHEMA_VERSION);
        responseMaterial.put("target", plan.target());
        responseMaterial.put("inputSchemaFingerprint", plan.inputSchemaFingerprint());
        responseMaterial.put("propertyPlanFingerprint", plan.planFingerprint());
        responseMaterial.put("sourcePlanStatus", plan.status().name());
        responseMaterial.put("generationGapsAccepted", gapsAccepted);
        responseMaterial.put("generationPolicy", generationPolicy);
        responseMaterial.put("rootTrialIds", rootIds);
        responseMaterial.put("caseIds", caseIds);
        responseMaterial.put("fixtureRef", fixtureRef);
        responseMaterial.put("suiteRef", suiteRef);
        return new TestPropertySuiteMaterializationResponse("",
                ProtocolFingerprint.of(objectMapper, responseMaterial), plan.target(),
                plan.inputSchemaFingerprint(), plan.planFingerprint(), plan.status(), gapsAccepted,
                generationPolicy, rootIds, caseIds, fixtureRef, suiteRef);
    }

    private StoredFixtureBundle requireFixture(
            TestPropertyCasePlan plan,
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        TestSuite.FixtureBundleRef reference = request.fixtureRef();
        StoredFixtureBundle fixture = executions.findFixture(
                reference.fixtureBundleId(), reference.revision(), identity);
        if (!reference.fingerprint().equals(fixture.fingerprint())) {
            throw conflict(identity, "RG.TEST.PROPERTY_FIXTURE_FINGERPRINT_CONFLICT",
                    "The governed fixture differs from the exact caller-reviewed reference.", Map.of());
        }
        if (!plan.target().fingerprint().equals(fixture.bundle().targetFingerprint())) {
            throw conflict(identity, "RG.TEST.PROPERTY_FIXTURE_TARGET_STALE",
                    "The governed fixture targets a different artifact snapshot.", Map.of());
        }
        if (fixture.bundle().assertions().isEmpty()) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_ASSERTIONS_REQUIRED",
                    "Property-suite materialization requires an existing fixture with business assertions.",
                    Map.of("fixtureBundleId", fixture.fixtureBundleId()));
        }
        return fixture;
    }

    private static TestSuite.TestCase testCase(
            TestPropertyCasePlan plan,
            TestPropertyCasePlan.PlannedCase source,
            TestSuite.FixtureBundleRef fixtureRef,
            String role,
            String parentCaseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "seeded-property-plan");
        metadata.put("propertyPlanFingerprint", plan.planFingerprint());
        metadata.put("role", role);
        metadata.put("inputFingerprint", source.inputFingerprint());
        metadata.put("complexity", source.complexity());
        if (!parentCaseId.isBlank()) {
            metadata.put("parentCaseId", parentCaseId);
            metadata.put("shrinkStep", ((TestPropertyCasePlan.ShrinkCandidate) source).step());
        }
        return new TestSuite.TestCase(source.caseId(), TestSuite.CaseType.PROPERTY,
                source.input(), fixtureRef,
                List.of("property-generated", "property-" + role.toLowerCase(Locale.ROOT)),
                Map.copyOf(metadata));
    }

    private static void requireRequest(
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        TestSuite.FixtureBundleRef fixture = request == null ? null : request.fixtureRef();
        if (request == null
                || !TestPropertySuiteMaterializationRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !SAFE_ID.matcher(request.suiteId()).matches()
                || !CLASSIFICATIONS.contains(request.classification())
                || !FINGERPRINT.matcher(request.expectedTargetFingerprint()).matches()
                || !FINGERPRINT.matcher(request.expectedInputSchemaFingerprint()).matches()
                || !FINGERPRINT.matcher(request.expectedPlanFingerprint()).matches()
                || request.trials() < 1 || request.trials() > TestPropertyCasePlanner.MAX_TRIALS
                || request.maxShrinkSteps() < 0
                || request.maxShrinkSteps() > TestPropertyCasePlanner.MAX_SHRINK_STEPS
                || fixture == null || !SAFE_ID.matcher(fixture.fixtureBundleId()).matches()
                || fixture.revision() <= 0 || !FINGERPRINT.matcher(fixture.fingerprint()).matches()) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_REQUEST_INVALID",
                    "A versioned request with safe ids, exact fingerprints, bounded generation policy, and fixture reference is required.",
                    Map.of());
        }
    }

    private static void requireExpectedPlan(
            TestPropertyCasePlan plan,
            TestPropertySuiteMaterializationRequest request,
            IntegrationRequestContext identity) {
        if (!plan.target().fingerprint().equals(request.expectedTargetFingerprint())
                || !plan.inputSchemaFingerprint().equals(request.expectedInputSchemaFingerprint())
                || !plan.planFingerprint().equals(request.expectedPlanFingerprint())) {
            throw conflict(identity, "RG.TEST.PROPERTY_PLAN_FINGERPRINT_CONFLICT",
                    "The current exact property plan differs from the caller-reviewed fingerprints.",
                    Map.of("currentTargetFingerprint", plan.target().fingerprint(),
                            "currentInputSchemaFingerprint", plan.inputSchemaFingerprint(),
                            "currentPlanFingerprint", plan.planFingerprint()));
        }
        if (plan.status() == TestPropertyCasePlan.Status.UNAVAILABLE) {
            throw badRequest(identity, "RG.TEST.PROPERTY_PLAN_UNAVAILABLE",
                    "An unavailable property plan has no validator-proven cases to materialize.",
                    Map.of());
        }
        if (plan.status() == TestPropertyCasePlan.Status.PARTIAL
                && !request.acceptGenerationGaps()) {
            throw badRequest(identity, "RG.TEST.PROPERTY_PLAN_GAPS_NOT_ACCEPTED",
                    "A partial property plan requires explicit generation-gap acceptance.",
                    Map.of("gapCount", plan.gaps().size()));
        }
    }

    private long contentRevision(String assetKind, Object content) {
        String fingerprint = ProtocolFingerprint.of(objectMapper,
                Map.of("assetKind", assetKind, "content", content));
        return Math.max(1, Long.parseLong(fingerprint.substring(
                "sha256:".length(), "sha256:".length() + 15), 16));
    }

    private static TestSuiteV4.PropertyGenerationPolicy generationPolicy(
            TestPropertyCasePlan.GenerationPolicy source) {
        return new TestSuiteV4.PropertyGenerationPolicy(source.generatorVersion(), source.seed(),
                source.requestedTrials(), source.maxShrinkSteps(), source.maxCases(),
                source.maxGenerationAttempts(), source.maxDepth(), source.maxCollectionItems(),
                source.verificationMode());
    }

    private static TestSuiteV4.PropertyGenerationGap generationGap(
            TestPropertyCasePlan.CoverageGap source) {
        return new TestSuiteV4.PropertyGenerationGap(
                TestSuiteV4.GenerationGapCode.valueOf(source.code().name()),
                source.schemaPath(), source.keyword());
    }

    private static TestSuiteV4.PropertyTrialRef trialRef(
            TestPropertyCasePlan.PropertyTrial source) {
        return new TestSuiteV4.PropertyTrialRef(source.trialId(), source.inputFingerprint(),
                source.complexity(), source.shrinkPath().stream()
                .map(shrink -> new TestSuiteV4.PropertyShrinkRef(shrink.caseId(),
                        shrink.parentCaseId(), shrink.step(), shrink.inputFingerprint(),
                        shrink.complexity())).toList());
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
