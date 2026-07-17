package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteRegistryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmptyResourceRegistry resources = new EmptyResourceRegistry();
    private final InMemoryFixtures fixtures = new InMemoryFixtures();
    private final InMemorySuites suites = new InMemorySuites();
    private final InMemorySecurityEvents securityEvents = new InMemorySecurityEvents();
    private TestSuiteRegistryService service;
    private GatewayGraphService graphService;
    private String targetFingerprint;
    private StoredFixtureBundle internalFixture;

    @BeforeEach
    void setUp() {
        Operator<Object, Object> operator = (input, context) -> Map.of("result", input);
        Graph graph = new GraphBuilder("controlled-graph")
                .node("subject", operator).input((results, context) -> context.get("input"))
                .build().withDefinitionSource(new GraphDefinitionSource(
                        "1.0.0", "bloge-dsl-json", "{\"name\":\"controlled-graph\"}"));
        graphService = mock(GatewayGraphService.class);
        when(graphService.requireGraph("controlled-graph")).thenReturn(graph);
        targetFingerprint = GraphExecutionTargetSnapshot.capture(mapper, graph, resources).fingerprint();
        service = new TestSuiteRegistryService(graphService, new DefaultOperatorRegistry(), resources,
                mapper, fixtures, suites, securityEvents);
        internalFixture = storeFixture("fixture-internal", "INTERNAL", 1);
    }

    @Test
    void registersDependencyClosedRevisionIdempotentlyAndScopesLookup() {
        TestSuite suite = suite("suite-a", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture),
                        testCase("boundary", TestSuite.CaseType.BOUNDARY, internalFixture)),
                new TestSuite.CoveragePolicy(2,
                        List.of(TestSuite.CaseType.GOLDEN, TestSuite.CaseType.BOUNDARY),
                        List.of("subject"), List.of(), 1, true));
        TestSuiteRegistrationRequest request = new TestSuiteRegistrationRequest("", suite);

        StoredTestSuite first = service.register("suite-a", request, identity("tenant-a", "test", "CONFIDENTIAL"));
        StoredTestSuite repeated = service.register(
                "suite-a", request, identity("tenant-a", "test", "CONFIDENTIAL"));

        assertThat(first).isEqualTo(repeated);
        assertThat(first.fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(first.suite().cases()).extracting(TestSuite.TestCase::caseId)
                .containsExactly("golden", "boundary");
        assertThat(service.find("suite-a", 1, identity("tenant-a", "test", "CONFIDENTIAL")))
                .isEqualTo(first);
        assertProblem(() -> service.find("suite-a", 1,
                identity("tenant-b", "test", "CONFIDENTIAL")), "RG.TEST.SUITE_NOT_FOUND", 404);
    }

    @Test
    void sameRevisionCannotBeOverwrittenWithDifferentImmutableContent() {
        TestSuite original = suite("suite-a", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture)),
                TestSuite.CoveragePolicy.defaults());
        service.register("suite-a", new TestSuiteRegistrationRequest("", original),
                identity("tenant-a", "test", "CONFIDENTIAL"));
        TestSuite changed = new TestSuite("", original.suiteId(), original.revision(), original.target(),
                original.classification(), original.cases(), original.coveragePolicy(),
                original.promotionPolicy(), Map.of("owner", "different"));

        assertProblem(() -> service.register("suite-a", new TestSuiteRegistrationRequest("", changed),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_REVISION_CONFLICT", 409);
    }

    @Test
    void staleTargetAndFixtureSubstitutionFailBeforePersistence() {
        TestSuite staleTarget = suite("suite-stale", 1, "INTERNAL", "sha256:" + "0".repeat(64),
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture)),
                TestSuite.CoveragePolicy.defaults());

        assertProblem(() -> service.register("suite-stale",
                new TestSuiteRegistrationRequest("", staleTarget),
                identity("tenant-a", "test", "CONFIDENTIAL")), "RG.TEST.SUITE_TARGET_STALE", 409);

        TestSuite.TestCase substituted = new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                Map.of("input", "hello"), new TestSuite.FixtureBundleRef(
                internalFixture.fixtureBundleId(), internalFixture.revision(), "sha256:" + "f".repeat(64)),
                List.of(), Map.of());
        TestSuite fixtureChanged = suite("suite-substitution", 1, "INTERNAL", targetFingerprint,
                List.of(substituted), TestSuite.CoveragePolicy.defaults());

        assertProblem(() -> service.register("suite-substitution",
                new TestSuiteRegistrationRequest("", fixtureChanged),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_FIXTURE_FINGERPRINT_CONFLICT", 409);
        assertThat(suites.values).isEmpty();
    }

    @Test
    void suiteCannotDowngradeFixtureClassificationOrBypassReadClearance() {
        StoredFixtureBundle confidential = storeFixture("fixture-confidential", "CONFIDENTIAL", 1);
        TestSuite downgraded = suite("suite-public", 1, "PUBLIC", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, confidential)),
                TestSuite.CoveragePolicy.defaults());

        assertProblem(() -> service.register("suite-public",
                new TestSuiteRegistrationRequest("", downgraded),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_CLASSIFICATION_DOWNGRADE", 400);

        TestSuite governed = suite("suite-confidential", 1, "CONFIDENTIAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, confidential)),
                TestSuite.CoveragePolicy.defaults());
        service.register("suite-confidential", new TestSuiteRegistrationRequest("", governed),
                identity("tenant-a", "test", "CONFIDENTIAL"));

        assertProblem(() -> service.find("suite-confidential", 1,
                identity("tenant-a", "test", "PUBLIC")), "RG.TEST.SUITE_CLEARANCE_FORBIDDEN", 403);
        assertThat(securityEvents.events).singleElement()
                .extracting(TestSecurityEvent::eventType)
                .isEqualTo("TEST_SUITE_CLEARANCE_VIOLATION");
    }

    @Test
    void unsatisfiedCoverageAndNonObjectGraphInputAreRejected() {
        TestSuite missingType = suite("suite-missing-type", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture)),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.NEGATIVE),
                        List.of(), List.of(), 0, true));
        assertProblem(() -> service.register("suite-missing-type",
                new TestSuiteRegistrationRequest("", missingType),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_CASE_TYPE_COVERAGE_UNMET", 400);

        TestSuite.TestCase invalidInput = new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                "not-an-object", fixtureRef(internalFixture), List.of(), Map.of());
        TestSuite malformed = suite("suite-input", 1, "INTERNAL", targetFingerprint,
                List.of(invalidInput), TestSuite.CoveragePolicy.defaults());
        assertProblem(() -> service.register("suite-input", new TestSuiteRegistrationRequest("", malformed),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_GRAPH_INPUT_INVALID", 400);

        TestSuite disguisedProperty = suite("suite-property-v1", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("property-001", TestSuite.CaseType.PROPERTY, internalFixture)),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), 1, false));
        assertProblem(() -> service.register("suite-property-v1",
                new TestSuiteRegistrationRequest("", disguisedProperty),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_CASE_TYPE_GENERATION_INVALID", 400);
    }

    @Test
    void productionIdentityIsRejectedAndSecurityAuditFailureFailsClosed() {
        TestSuite suite = suite("suite-prod", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture)),
                TestSuite.CoveragePolicy.defaults());

        assertProblem(() -> service.register("suite-prod", new TestSuiteRegistrationRequest("", suite),
                identity("tenant-a", "prod", "CONFIDENTIAL")), "RG.TEST.ENVIRONMENT_FORBIDDEN", 403);
        assertThat(securityEvents.events).singleElement()
                .extracting(TestSecurityEvent::eventType)
                .isEqualTo("TEST_PURPOSE_PRODUCTION_TOUCH");

        securityEvents.failAppends = true;
        assertProblem(() -> service.find("suite-prod", 1,
                identity("tenant-a", "prod", "CONFIDENTIAL")),
                "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE", 503);
    }

    @Test
    void promotionPolicyCannotRequireCertificationFromAnIneligibleTargetRevision() {
        Operator<Object, Object> operator = (input, context) -> input;
        Graph ineligibleGraph = new GraphBuilder("ineligible-graph").node("subject", operator).build();
        when(graphService.requireGraph("ineligible-graph")).thenReturn(ineligibleGraph);
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                mapper, ineligibleGraph, resources);
        assertThat(snapshot.certificationEligible()).isFalse();
        StoredFixtureBundle fixture = storeFixture(
                "ineligible-fixture", "INTERNAL", 1, snapshot.fingerprint());
        TestSuite suite = new TestSuite("", "suite-ineligible", 1,
                new TestSuite.Target("GRAPH", "ineligible-graph", snapshot.fingerprint()), "INTERNAL",
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, fixture)),
                TestSuite.CoveragePolicy.defaults(),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of());

        assertProblem(() -> service.register("suite-ineligible",
                new TestSuiteRegistrationRequest("", suite),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_TARGET_NOT_CERTIFIABLE", 400);
        assertThat(suites.values).isEmpty();
    }

    @Test
    void registersTypedSemanticV2AndRejectsAnEmptySemanticPolicy() {
        TestSuite structural = suite("suite-v2", 1, "INTERNAL", targetFingerprint,
                List.of(testCase("golden", TestSuite.CaseType.GOLDEN, internalFixture)),
                TestSuite.CoveragePolicy.defaults());
        TestSuiteV2 semantic = new TestSuiteV2("", structural.suiteId(), structural.revision(),
                structural.target(), structural.classification(), structural.cases(),
                structural.coveragePolicy(), new SemanticCoveragePolicy(List.of(
                new SemanticCoveragePolicy.BranchRequirement("branch",
                        SemanticCoveragePolicy.Kind.BRANCH_TRANSFERRED,
                        "/root/input#PRIMARY", "/root/subject#PRIMARY"),
                new SemanticCoveragePolicy.SiteRequirement("compensation",
                        SemanticCoveragePolicy.Kind.COMPENSATION,
                        "/root/refund#COMPENSATION", ""))),
                structural.promotionPolicy(), structural.metadata());

        StoredTestSuite stored = service.register("suite-v2",
                new TestSuiteRegistrationRequest("", semantic),
                identity("tenant-a", "test", "CONFIDENTIAL"));

        assertThat(stored.suite()).isInstanceOf(TestSuiteV2.class);
        assertThat(stored.fingerprint()).isEqualTo(ProtocolFingerprint.of(mapper, semantic));

        TestSuiteV2 empty = new TestSuiteV2("", "suite-v2-empty", 1, structural.target(),
                structural.classification(), structural.cases(), structural.coveragePolicy(),
                SemanticCoveragePolicy.empty(), structural.promotionPolicy(), structural.metadata());
        assertProblem(() -> service.register("suite-v2-empty",
                new TestSuiteRegistrationRequest("", empty),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_SEMANTIC_POLICY_INVALID", 400);
    }

    @Test
    void registersSchemaAdmissionV3WithExactExpectationClosureAndNonObjectGraphInput() {
        StoredFixtureBundle inert = storeInertFixture("fixture-admission", "INTERNAL", 1);
        TestSuite.TestCase rejected = new TestSuite.TestCase("root-type",
                TestSuite.CaseType.NEGATIVE, List.of("not-an-object"), fixtureRef(inert),
                List.of("schema-admission"), Map.of());
        TestSuiteV3 suite = admissionSuite("suite-v3", rejected, inert,
                Map.of("root-type", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED,
                        List.of("visual.context.typeMismatch"))));

        StoredTestSuite stored = service.register("suite-v3",
                new TestSuiteRegistrationRequest("", suite),
                identity("tenant-a", "test", "CONFIDENTIAL"));

        assertThat(stored.suite()).isInstanceOf(TestSuiteV3.class);
        assertThat(stored.fingerprint()).isEqualTo(ProtocolFingerprint.of(mapper, suite));
    }

    @Test
    void schemaAdmissionV3RejectsMissingExpectationsNonInertFixturesAndPromotionClaims() {
        StoredFixtureBundle inert = storeInertFixture("fixture-admission", "INTERNAL", 1);
        TestSuite.TestCase testCase = new TestSuite.TestCase("accepted",
                TestSuite.CaseType.GOLDEN, Map.of("input", "hello"), fixtureRef(inert),
                List.of(), Map.of());
        TestSuiteV3 missing = admissionSuite("suite-v3-missing", testCase, inert, Map.of());
        assertProblem(() -> service.register("suite-v3-missing",
                new TestSuiteRegistrationRequest("", missing),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_ADMISSION_EXPECTATIONS_INVALID", 400);

        TestSuite.TestCase nonInertCase = new TestSuite.TestCase("accepted",
                TestSuite.CaseType.GOLDEN, Map.of("input", "hello"),
                fixtureRef(internalFixture), List.of(), Map.of());
        TestSuiteV3 nonInert = admissionSuite("suite-v3-non-inert", nonInertCase,
                internalFixture, Map.of("accepted", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of())));
        assertProblem(() -> service.register("suite-v3-non-inert",
                new TestSuiteRegistrationRequest("", nonInert),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_ADMISSION_FIXTURE_NOT_INERT", 400);

        TestSuiteV3 promotionClaim = new TestSuiteV3("", "suite-v3-promotion", 1,
                missing.target(), missing.classification(), List.of(testCase), missing.coveragePolicy(),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 1, true),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                missing.boundaryPlanFingerprint(), missing.inputSchemaFingerprint(),
                Map.of("accepted", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of())), Map.of());
        assertProblem(() -> service.register("suite-v3-promotion",
                new TestSuiteRegistrationRequest("", promotionClaim),
                identity("tenant-a", "test", "CONFIDENTIAL")),
                "RG.TEST.SUITE_ADMISSION_POLICY_INVALID", 400);
    }

    @Test
    void propertyV4RequiresTrustedMaterializationAndRegistersAnExactPlanClosure() {
        TestPropertyCasePlan plan = propertyPlan(Map.of("input", "generated"));
        TestSuiteV4 propertySuite = propertySuite("suite-v4", plan, internalFixture,
                Map.of("input", "generated"), 1);
        IntegrationRequestContext identity = identity("tenant-a", "test", "CONFIDENTIAL");

        assertProblem(() -> service.register("suite-v4",
                new TestSuiteRegistrationRequest("", propertySuite), identity),
                "RG.TEST.PROPERTY_SUITE_MATERIALIZATION_REQUIRED", 400);

        StoredTestSuite stored = service.registerPropertySuite("suite-v4",
                new TestSuiteRegistrationRequest("", propertySuite), plan, identity);

        assertThat(stored.suite()).isInstanceOf(TestSuiteV4.class);
        assertThat(stored.fingerprint()).isEqualTo(ProtocolFingerprint.of(mapper, propertySuite));
        assertThat(stored.suite().cases()).extracting(TestSuite.TestCase::caseType)
                .containsExactly(TestSuite.CaseType.PROPERTY);
    }

    @Test
    void propertyV4RejectsInputSubstitutionAndAssertionFreeFixtures() {
        TestPropertyCasePlan plan = propertyPlan(Map.of("input", "generated"));
        TestSuiteV4 substituted = propertySuite("suite-v4-substituted", plan, internalFixture,
                Map.of("input", "caller-substitution"), 1);
        IntegrationRequestContext identity = identity("tenant-a", "test", "CONFIDENTIAL");

        assertProblem(() -> service.registerPropertySuite("suite-v4-substituted",
                new TestSuiteRegistrationRequest("", substituted), plan, identity),
                "RG.TEST.PROPERTY_SUITE_INPUT_MISMATCH", 409);

        StoredFixtureBundle inert = storeInertFixture("property-inert", "INTERNAL", 1);
        TestSuiteV4 noAssertions = propertySuite(
                "suite-v4-no-assertions", plan, inert, Map.of("input", "generated"), 0);
        assertProblem(() -> service.registerPropertySuite("suite-v4-no-assertions",
                new TestSuiteRegistrationRequest("", noAssertions), plan, identity),
                "RG.TEST.PROPERTY_SUITE_ASSERTIONS_REQUIRED", 400);
    }

    private TestPropertyCasePlan propertyPlan(Object input) {
        String inputFingerprint = ProtocolFingerprint.of(mapper, input);
        TestPropertyCasePlan.GenerationPolicy policy = new TestPropertyCasePlan.GenerationPolicy(
                "property-cases-v1", 42, 1, 0, 1, 32, 8, 32,
                "DRAFT_2020_12_SHARED_VALIDATOR");
        return new TestPropertyCasePlan("",
                new TestExecutionApiRequest.Target(
                        "GRAPH", "controlled-graph", targetFingerprint),
                "sha256:" + "c".repeat(64), "sha256:" + "b".repeat(64),
                TestPropertyCasePlan.Status.GENERATED,
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED, false, policy,
                List.of(new TestPropertyCasePlan.PropertyTrial(
                        "property-001", input, inputFingerprint, 1, List.of())), List.of());
    }

    private TestSuiteV4 propertySuite(
            String suiteId,
            TestPropertyCasePlan plan,
            StoredFixtureBundle fixture,
            Object input,
            int minimumAssertions) {
        TestSuiteV4.PropertyGenerationPolicy policy = new TestSuiteV4.PropertyGenerationPolicy(
                plan.policy().generatorVersion(), plan.policy().seed(),
                plan.policy().requestedTrials(), plan.policy().maxShrinkSteps(),
                plan.policy().maxCases(), plan.policy().maxGenerationAttempts(),
                plan.policy().maxDepth(), plan.policy().maxCollectionItems(),
                plan.policy().verificationMode());
        TestPropertyCasePlan.PropertyTrial root = plan.trials().getFirst();
        return new TestSuiteV4("", suiteId, 1,
                new TestSuite.Target(plan.target().kind(), plan.target().id(),
                        plan.target().fingerprint()), "INTERNAL",
                List.of(new TestSuite.TestCase(root.trialId(), TestSuite.CaseType.PROPERTY,
                        input, fixtureRef(fixture), List.of("property-root"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), minimumAssertions, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 1, true),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false,
                plan.planFingerprint(), plan.inputSchemaFingerprint(), policy,
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of(),
                List.of(new TestSuiteV4.PropertyTrialRef(root.trialId(),
                        root.inputFingerprint(), root.complexity(), List.of())),
                Map.of("source", "property-plan"));
    }

    private TestSuiteV3 admissionSuite(String suiteId, TestSuite.TestCase testCase,
                                       StoredFixtureBundle fixture,
                                       Map<String, TestSuiteV3.AdmissionExpectation> expectations) {
        return new TestSuiteV3("", suiteId, 1,
                new TestSuite.Target("GRAPH", "controlled-graph", targetFingerprint), "INTERNAL",
                List.of(testCase), new TestSuite.CoveragePolicy(1,
                List.of(testCase.caseType()), List.of(), List.of(), 0, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 0, false),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64),
                expectations, Map.of("fixtureId", fixture.fixtureBundleId()));
    }

    private StoredFixtureBundle storeFixture(String id, String classification, long revision) {
        return storeFixture(id, classification, revision, targetFingerprint);
    }

    private StoredFixtureBundle storeFixture(String id, String classification, long revision,
                                              String fingerprint) {
        FixtureBundle bundle = new FixtureBundle("", id, revision, fingerprint,
                classification, null, null, List.of(), List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/result", "EQUALS", "hello", null)), Map.of());
        StoredFixtureBundle stored = new StoredFixtureBundle("", "tenant-a", "test", id, revision,
                ProtocolFingerprint.of(mapper, bundle), bundle, Instant.now(), "runner");
        return fixtures.create(stored);
    }

    private StoredFixtureBundle storeInertFixture(String id, String classification, long revision) {
        FixtureBundle bundle = new FixtureBundle("", id, revision, targetFingerprint,
                classification, null, null, List.of(), List.of(), Map.of("mode", "admission"));
        StoredFixtureBundle stored = new StoredFixtureBundle("", "tenant-a", "test", id, revision,
                ProtocolFingerprint.of(mapper, bundle), bundle, Instant.now(), "runner");
        return fixtures.create(stored);
    }

    private TestSuite suite(String id, long revision, String classification, String fingerprint,
                            List<TestSuite.TestCase> cases, TestSuite.CoveragePolicy coverage) {
        return new TestSuite("", id, revision,
                new TestSuite.Target("GRAPH", "controlled-graph", fingerprint), classification, cases,
                coverage, new TestSuite.PromotionPolicy(true, 1, true), Map.of("owner", "quality"));
    }

    private static TestSuite.TestCase testCase(String id, TestSuite.CaseType type,
                                               StoredFixtureBundle fixture) {
        return new TestSuite.TestCase(id, type, Map.of("input", "hello"), fixtureRef(fixture),
                List.of("ci"), Map.of());
    }

    private static TestSuite.FixtureBundleRef fixtureRef(StoredFixtureBundle fixture) {
        return new TestSuite.FixtureBundleRef(fixture.fixtureBundleId(), fixture.revision(),
                fixture.fingerprint());
    }

    private static IntegrationRequestContext identity(String tenant, String environment, String clearance) {
        return new IntegrationRequestContext(tenant, "org-a", "project-a", environment,
                "local", "WORKLOAD", "runner", "", "TEST_SUITE_WRITE", "correlation-1",
                java.util.Set.of("quality"), clearance, "");
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                                      String code, int status) {
        assertThatThrownBy(operation).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.status()).isEqualTo(status);
                });
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override public ResourceDescriptor resolve(String resourceId) { throw new IllegalArgumentException(); }
        @Override public boolean contains(String resourceId) { return false; }
        @Override public java.util.Collection<ResourceDescriptor> all() { return List.of(); }
    }

    private static final class InMemoryFixtures implements FixtureBundleRepository {
        private final Map<String, StoredFixtureBundle> values = new LinkedHashMap<>();
        @Override public StoredFixtureBundle create(StoredFixtureBundle value) {
            values.put(key(value.tenantId(), value.environmentId(), value.fixtureBundleId(), value.revision()), value);
            return value;
        }
        @Override public Optional<StoredFixtureBundle> find(String tenant, String environment,
                                                            String id, long revision) {
            return Optional.ofNullable(values.get(key(tenant, environment, id, revision)));
        }
        private static String key(String tenant, String environment, String id, long revision) {
            return tenant + "|" + environment + "|" + id + "|" + revision;
        }
    }

    private static final class InMemorySuites implements TestSuiteRepository {
        private final Map<String, StoredTestSuite> values = new LinkedHashMap<>();
        @Override public StoredTestSuite create(StoredTestSuite value) {
            String key = key(value.tenantId(), value.environmentId(), value.suiteId(), value.revision());
            StoredTestSuite existing = values.putIfAbsent(key, value);
            if (existing != null && !existing.fingerprint().equals(value.fingerprint())) {
                throw new TestSuiteConflictException("different immutable content");
            }
            return existing == null ? value : existing;
        }
        @Override public Optional<StoredTestSuite> find(String tenant, String environment,
                                                        String id, long revision) {
            return Optional.ofNullable(values.get(key(tenant, environment, id, revision)));
        }
        private static String key(String tenant, String environment, String id, long revision) {
            return tenant + "|" + environment + "|" + id + "|" + revision;
        }
    }

    private static final class InMemorySecurityEvents implements TestSecurityEventRepository {
        private final List<TestSecurityEvent> events = new ArrayList<>();
        private boolean failAppends;
        @Override public TestSecurityEvent append(TestSecurityEvent event) {
            if (failAppends) {
                throw new IllegalStateException("audit unavailable");
            }
            TestSecurityEvent stored = event.withSequence(events.size() + 1L);
            events.add(stored);
            return stored;
        }
        @Override public List<TestSecurityEvent> recent(int limit) { return List.copyOf(events); }
    }
}
