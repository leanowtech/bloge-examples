package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestMutationSuiteMaterializationServiceTest {
    private static final String TARGET = fingerprint('a');
    private static final String SOURCE = fingerprint('b');
    private static final String GRAPH = fingerprint('c');
    private static final String PLAN = fingerprint('d');
    private static final String FIXTURE = fingerprint('e');
    private static final String ORACLE = fingerprint('f');
    private static final String MUTATION_SUITE = fingerprint('1');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TestExecutionApiService executions;
    private TestSuiteRegistryService suites;
    private TestMutationSuiteMaterializationService service;
    private IntegrationRequestContext identity;
    private StoredTestSuite oracle;

    @BeforeEach
    void setUp() {
        executions = mock(TestExecutionApiService.class);
        suites = mock(TestSuiteRegistryService.class);
        service = new TestMutationSuiteMaterializationService(executions, suites, mapper);
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "author", "", "TEST_SUITE_WRITE", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
        oracle = oracleSuite(2);
        when(suites.find("orders-oracle", 7, identity)).thenReturn(oracle);
        when(executions.findFixture("orders-fixture", 3, identity)).thenReturn(fixture(true));
        when(suites.registerMutationSuite(any(), any(), any(), any(), eq(identity)))
                .thenAnswer(invocation -> {
                    TestSuiteV5 suite = (TestSuiteV5) invocation
                            .<TestSuiteRegistrationRequest>getArgument(1).testSuite();
                    return new StoredTestSuite("", "tenant-a", "test", suite.suiteId(),
                            suite.revision(), MUTATION_SUITE, suite, Instant.EPOCH, "author");
                });
    }

    @Test
    void materializesTheCompletePlanAndOracleClosureDeterministically() {
        TestMutationCasePlan plan = generatedPlan(false);
        when(executions.planGraphMutationCases("orders", 2, identity)).thenReturn(plan);

        TestMutationSuiteMaterializationResponse first =
                service.materializeGraph("orders", request(false), identity);
        TestMutationSuiteMaterializationResponse replay =
                service.materializeGraph("orders", request(false), identity);

        assertThat(replay).isEqualTo(first);
        assertThat(first.mutantIds()).containsExactly("mutant-001", "mutant-002");
        assertThat(first.oracleCaseIds()).containsExactly("case-1", "case-2");
        assertThat(first.mutantCaseExecutions()).isEqualTo(4);
        assertThat(first.planningGapsAccepted()).isFalse();
        assertThat(first.oracleSuiteRef().fingerprint()).isEqualTo(ORACLE);
        assertThat(first.suiteRef().fingerprint()).isEqualTo(MUTATION_SUITE);
        assertThat(first.materializationFingerprint()).matches("sha256:[a-f0-9]{64}");

        ArgumentCaptor<TestSuiteRegistrationRequest> registrations =
                ArgumentCaptor.forClass(TestSuiteRegistrationRequest.class);
        verify(suites, org.mockito.Mockito.times(2)).registerMutationSuite(
                eq("orders-mutations"), registrations.capture(), eq(plan), eq(oracle), eq(identity));
        TestSuiteV5 suite = (TestSuiteV5) registrations.getAllValues().getFirst().testSuite();
        assertThat(suite.cases()).isEqualTo(oracle.suite().cases());
        assertThat(suite.mutants()).hasSize(2);
        assertThat(suite.oracleSuiteRef().fingerprint()).isEqualTo(ORACLE);
        assertThat(suite.scorePolicy().minimumScoreBasisPoints()).isEqualTo(8_000);
        assertThat(registrations.getAllValues()).extracting(value -> value.testSuite().revision())
                .containsOnly(suite.revision());
        verify(executions, never()).registerFixture(any(), any(), any());
    }

    @Test
    void rejectsPlanDriftAndAssertionFreeOracleBeforeSuiteWrite() {
        when(executions.planGraphMutationCases("orders", 2, identity))
                .thenReturn(generatedPlan(false));
        TestMutationSuiteMaterializationRequest stale = new TestMutationSuiteMaterializationRequest(
                "", "orders-mutations", "INTERNAL", fingerprint('9'), SOURCE, GRAPH, PLAN, 2,
                oracleRef(), false, scorePolicy());

        assertProblem(() -> service.materializeGraph("orders", stale, identity),
                "RG.TEST.MUTATION_PLAN_FINGERPRINT_CONFLICT", 409);

        when(executions.findFixture("orders-fixture", 3, identity)).thenReturn(fixture(false));
        assertProblem(() -> service.materializeGraph("orders", request(false), identity),
                "RG.TEST.MUTATION_ORACLE_ASSERTIONS_REQUIRED", 400);
        verify(suites, never()).registerMutationSuite(any(), any(), any(), any(), any());
    }

    @Test
    void partialPlansRequireAcceptanceAndWorkIsBoundedBeforeWrite() {
        TestMutationCasePlan partial = generatedPlan(true);
        when(executions.planGraphMutationCases("orders", 2, identity)).thenReturn(partial);

        assertProblem(() -> service.materializeGraph("orders", request(false), identity),
                "RG.TEST.MUTATION_PLAN_GAPS_NOT_ACCEPTED", 400);
        TestMutationSuiteMaterializationResponse accepted =
                service.materializeGraph("orders", request(true), identity);
        assertThat(accepted.sourcePlanStatus()).isEqualTo(TestMutationCasePlan.Status.PARTIAL);
        assertThat(accepted.planningGapsAccepted()).isTrue();

        when(suites.find("orders-oracle", 7, identity)).thenReturn(oracleSuite(17));
        assertProblem(() -> service.materializeGraph("orders", request(true), identity),
                "RG.TEST.MUTATION_SUITE_WORK_LIMIT_EXCEEDED", 400);
    }

    @Test
    void rejectsSubstitutedOrNonExecutableOracleSuites() {
        when(executions.planGraphMutationCases("orders", 2, identity))
                .thenReturn(generatedPlan(false));
        TestMutationSuiteMaterializationRequest substituted = new TestMutationSuiteMaterializationRequest(
                "", "orders-mutations", "INTERNAL", TARGET, SOURCE, GRAPH, PLAN, 2,
                new TestSuiteExecutionRequest.SuiteRef("orders-oracle", 7, fingerprint('8')),
                false, scorePolicy());
        assertProblem(() -> service.materializeGraph("orders", substituted, identity),
                "RG.TEST.MUTATION_ORACLE_FINGERPRINT_CONFLICT", 409);

        when(suites.find("orders-oracle", 7, identity)).thenReturn(new StoredTestSuite(
                "", "tenant-a", "test", "orders-oracle", 7, ORACLE,
                TestSchemaAdmissionFixtures.suite(TARGET), Instant.EPOCH, "author"));
        assertProblem(() -> service.materializeGraph("orders", request(false), identity),
                "RG.TEST.MUTATION_ORACLE_SUITE_UNSUPPORTED", 400);
        verifyNoInteractionsAfterPlan();
    }

    private void verifyNoInteractionsAfterPlan() {
        verify(suites, never()).registerMutationSuite(any(), any(), any(), any(), any());
    }

    private TestMutationCasePlan generatedPlan(boolean partial) {
        TestMutationCasePlan.MutationPolicy policy = new TestMutationCasePlan.MutationPolicy(
                TestMutationCasePlan.PLANNER_VERSION, 2, TestMutationCasePlan.SOURCE_FORMAT,
                TestMutationCasePlan.VERIFICATION_MODE, false, false);
        List<TestMutationCasePlan.PlannedMutant> mutants = List.of(
                mutant(1, TestMutationCasePlan.MutationKind.DECISION_CONDITION_NEGATED),
                mutant(2, TestMutationCasePlan.MutationKind.FALLBACK_REMOVED));
        List<TestMutationCasePlan.PlanningGap> gaps = partial ? List.of(
                new TestMutationCasePlan.PlanningGap(
                        TestMutationCasePlan.GapCode.NESTED_SCOPE_NOT_EXPANDED,
                        "/members/4", "FOREACH")) : List.of();
        return new TestMutationCasePlan("", target(), TestMutationCasePlan.SOURCE_FORMAT,
                SOURCE, GRAPH, PLAN, partial ? TestMutationCasePlan.Status.PARTIAL
                : TestMutationCasePlan.Status.GENERATED, policy, mutants, gaps);
    }

    private static TestMutationCasePlan.PlannedMutant mutant(
            int index, TestMutationCasePlan.MutationKind kind) {
        return new TestMutationCasePlan.PlannedMutant("mutant-%03d".formatted(index), kind,
                "/members/%d".formatted(index), index, 1, indexedFingerprint(index),
                indexedFingerprint(100 + index), indexedFingerprint(200 + index),
                TestMutationCasePlan.EquivalenceClassification.UNKNOWN);
    }

    private StoredTestSuite oracleSuite(int caseCount) {
        List<TestSuite.TestCase> cases = java.util.stream.IntStream.rangeClosed(1, caseCount)
                .mapToObj(index -> new TestSuite.TestCase("case-" + index,
                        TestSuite.CaseType.REGRESSION, Map.of("value", index), fixtureRef(),
                        List.of("mutation-oracle"), Map.of())).toList();
        TestSuite suite = new TestSuite("", "orders-oracle", 7,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(1, List.of(), List.of(), List.of(), 1, false),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of());
        return new StoredTestSuite("", "tenant-a", "test", "orders-oracle", 7,
                ORACLE, suite, Instant.EPOCH, "author");
    }

    private StoredFixtureBundle fixture(boolean assertions) {
        List<FixtureBundle.Assertion> values = assertions ? List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/result", "EQUALS", "approved", null)) : List.of();
        FixtureBundle bundle = new FixtureBundle("", "orders-fixture", 3, TARGET,
                "INTERNAL", null, 42L, List.of(), values, Map.of());
        return new StoredFixtureBundle("", "tenant-a", "test", "orders-fixture", 3,
                FIXTURE, bundle, Instant.EPOCH, "author");
    }

    private static TestMutationSuiteMaterializationRequest request(boolean acceptGaps) {
        return new TestMutationSuiteMaterializationRequest("", "orders-mutations", "INTERNAL",
                TARGET, SOURCE, GRAPH, PLAN, 2, oracleRef(), acceptGaps, scorePolicy());
    }

    private static TestSuiteV5.MutationScorePolicy scorePolicy() {
        return new TestSuiteV5.MutationScorePolicy(8_000, 0, false, false);
    }

    private static TestSuiteExecutionRequest.SuiteRef oracleRef() {
        return new TestSuiteExecutionRequest.SuiteRef("orders-oracle", 7, ORACLE);
    }

    private static TestSuite.FixtureBundleRef fixtureRef() {
        return new TestSuite.FixtureBundleRef("orders-fixture", 3, FIXTURE);
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }

    private static void assertProblem(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            String code,
            int status) {
        assertThatThrownBy(action).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.status()).isEqualTo(status);
                });
    }

    /** Minimal schema-admission fixture used only to prove that non-executable oracles are rejected. */
    private static final class TestSchemaAdmissionFixtures {
        private static com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3 suite(String target) {
            TestSuite.TestCase testCase = new TestSuite.TestCase("boundary", TestSuite.CaseType.BOUNDARY,
                    Map.of(), fixtureRef(), List.of(), Map.of());
            return new com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3("", "orders-oracle", 7,
                    new TestSuite.Target("GRAPH", "orders", target), "INTERNAL", List.of(testCase),
                    new TestSuite.CoveragePolicy(1, List.of(), List.of(), List.of(), 0, false),
                    com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy.empty(),
                    new TestSuite.PromotionPolicy(true, 0, false),
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                    PLAN, SOURCE,
                    Map.of("boundary", new com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3.AdmissionExpectation(
                            com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3.ExpectedOutcome.ACCEPTED,
                            List.of())), Map.of());
        }
    }
}
