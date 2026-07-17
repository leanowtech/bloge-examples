package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPropertySuiteMaterializationServiceTest {
    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String INPUT_SCHEMA = "sha256:" + "b".repeat(64);
    private static final String PLAN = "sha256:" + "c".repeat(64);
    private static final String FIXTURE = "sha256:" + "d".repeat(64);
    private static final String SUITE = "sha256:" + "e".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TestExecutionApiService executions;
    private TestSuiteRegistryService suites;
    private TestPropertySuiteMaterializationService service;
    private IntegrationRequestContext identity;
    private StoredFixtureBundle fixture;

    @BeforeEach
    void setUp() {
        executions = mock(TestExecutionApiService.class);
        suites = mock(TestSuiteRegistryService.class);
        service = new TestPropertySuiteMaterializationService(executions, suites, mapper);
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "author", "", "TEST_SUITE_WRITE", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
        fixture = fixture(true);
        when(executions.findFixture("property-fixture", 7, identity)).thenReturn(fixture);
        when(suites.registerPropertySuite(any(), any(), any(), eq(identity)))
                .thenAnswer(invocation -> {
                    TestSuiteV4 suite = (TestSuiteV4) invocation
                            .<TestSuiteRegistrationRequest>getArgument(1).testSuite();
                    return new StoredTestSuite("", "tenant-a", "test", suite.suiteId(),
                            suite.revision(), SUITE, suite, Instant.EPOCH, "author");
                });
    }

    @Test
    void materializesTheCompleteRootAndShrinkClosureDeterministically() {
        TestPropertyCasePlan plan = generatedPlan();
        when(executions.planGraphPropertyCases("orders", 42, 1, 1, identity)).thenReturn(plan);

        TestPropertySuiteMaterializationResponse first =
                service.materializeGraph("orders", request(false), identity);
        TestPropertySuiteMaterializationResponse retry =
                service.materializeGraph("orders", request(false), identity);

        assertThat(retry).isEqualTo(first);
        assertThat(first.rootTrialIds()).containsExactly("property-001");
        assertThat(first.caseIds()).containsExactly(
                "property-001", "property-001-shrink-001");
        assertThat(first.generationGapsAccepted()).isFalse();
        assertThat(first.fixtureRef().fingerprint()).isEqualTo(FIXTURE);
        assertThat(first.suiteRef().fingerprint()).isEqualTo(SUITE);
        assertThat(first.materializationFingerprint()).matches("sha256:[a-f0-9]{64}");

        ArgumentCaptor<TestSuiteRegistrationRequest> registrations =
                ArgumentCaptor.forClass(TestSuiteRegistrationRequest.class);
        verify(suites, times(2)).registerPropertySuite(eq("orders-properties"),
                registrations.capture(), eq(plan), eq(identity));
        TestSuiteV4 suite = (TestSuiteV4) registrations.getAllValues().getFirst().testSuite();
        assertThat(suite.cases()).extracting(TestSuite.TestCase::caseId)
                .containsExactly("property-001", "property-001-shrink-001");
        assertThat(suite.cases()).extracting(TestSuite.TestCase::caseType)
                .containsOnly(TestSuite.CaseType.PROPERTY);
        assertThat(suite.coveragePolicy().minimumAssertionsPerCase()).isEqualTo(1);
        assertThat(suite.promotionPolicy().minimumCertifiableCases()).isEqualTo(2);
        assertThat(suite.quantification()).isEqualTo(TestSuiteV4.Quantification.BOUNDED_SAMPLED);
        assertThat(suite.exhaustive()).isFalse();
        assertThat(registrations.getAllValues()).extracting(value ->
                value.testSuite().revision()).containsOnly(suite.revision());
        verify(executions, never()).registerFixture(any(), any(), any());
    }

    @Test
    void rejectsStaleReviewAndAssertionFreeOrSubstitutedFixturesBeforeSuiteWrite() {
        when(executions.planGraphPropertyCases("orders", 42, 1, 1, identity))
                .thenReturn(generatedPlan());
        TestPropertySuiteMaterializationRequest stale = new TestPropertySuiteMaterializationRequest(
                "", "orders-properties", "INTERNAL", "sha256:" + "9".repeat(64),
                INPUT_SCHEMA, PLAN, 42, 1, 1, fixtureRef(), false);

        assertProblem(() -> service.materializeGraph("orders", stale, identity),
                "RG.TEST.PROPERTY_PLAN_FINGERPRINT_CONFLICT", 409);

        when(executions.findFixture("property-fixture", 7, identity)).thenReturn(fixture(false));
        assertProblem(() -> service.materializeGraph("orders", request(false), identity),
                "RG.TEST.PROPERTY_SUITE_ASSERTIONS_REQUIRED", 400);

        when(executions.findFixture("property-fixture", 7, identity)).thenReturn(fixture);
        TestPropertySuiteMaterializationRequest substituted = new TestPropertySuiteMaterializationRequest(
                "", "orders-properties", "INTERNAL", TARGET, INPUT_SCHEMA, PLAN,
                42, 1, 1, new TestSuite.FixtureBundleRef(
                "property-fixture", 7, "sha256:" + "8".repeat(64)), false);
        assertProblem(() -> service.materializeGraph("orders", substituted, identity),
                "RG.TEST.PROPERTY_FIXTURE_FINGERPRINT_CONFLICT", 409);
        verifyNoInteractions(suites);
    }

    @Test
    void partialPlansRequireExplicitAcceptanceAndUnavailablePlansCannotWrite() {
        TestPropertyCasePlan partial = new TestPropertyCasePlan("", target(), INPUT_SCHEMA, PLAN,
                TestPropertyCasePlan.Status.PARTIAL,
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED, false,
                generatedPlan().policy(), generatedPlan().trials(),
                List.of(new TestPropertyCasePlan.CoverageGap(
                        TestPropertyCasePlan.GapCode.CONSTRAINT_NOT_GENERATED,
                        "/properties/code", "pattern")));
        when(executions.planGraphPropertyCases("orders", 42, 1, 1, identity)).thenReturn(partial);

        assertProblem(() -> service.materializeGraph("orders", request(false), identity),
                "RG.TEST.PROPERTY_PLAN_GAPS_NOT_ACCEPTED", 400);
        TestPropertySuiteMaterializationResponse accepted =
                service.materializeGraph("orders", request(true), identity);
        assertThat(accepted.sourcePlanStatus()).isEqualTo(TestPropertyCasePlan.Status.PARTIAL);
        assertThat(accepted.generationGapsAccepted()).isTrue();

        TestPropertyCasePlan unavailable = new TestPropertyCasePlan("", target(), INPUT_SCHEMA, PLAN,
                TestPropertyCasePlan.Status.UNAVAILABLE,
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED, false,
                generatedPlan().policy(), List.of(),
                List.of(new TestPropertyCasePlan.CoverageGap(
                        TestPropertyCasePlan.GapCode.OPAQUE_INPUT_SCHEMA, "", "")));
        when(executions.planGraphPropertyCases("orders", 42, 1, 1, identity))
                .thenReturn(unavailable);
        assertProblem(() -> service.materializeGraph("orders", request(true), identity),
                "RG.TEST.PROPERTY_PLAN_UNAVAILABLE", 400);
    }

    @Test
    void operatorRouteRegeneratesTheExactOperatorPlan() {
        TestPropertyCasePlan plan = generatedPlan();
        when(executions.planOperatorPropertyCases(
                "customer.normalize", 42, 1, 1, identity)).thenReturn(plan);

        service.materializeOperator("customer.normalize", request(false), identity);

        verify(executions).planOperatorPropertyCases(
                "customer.normalize", 42, 1, 1, identity);
        verify(suites).registerPropertySuite(
                eq("orders-properties"), any(), eq(plan), eq(identity));
    }

    private TestPropertyCasePlan generatedPlan() {
        Map<String, Object> root = Map.of("input", "generated", "size", 2);
        Map<String, Object> shrink = Map.of("input", "generated", "size", 1);
        TestPropertyCasePlan.GenerationPolicy policy = new TestPropertyCasePlan.GenerationPolicy(
                "property-cases-v1", 42, 1, 1, 2, 32, 8, 32,
                "VISUAL_SCHEMA_VALIDATOR_PROOF");
        return new TestPropertyCasePlan("", target(), INPUT_SCHEMA, PLAN,
                TestPropertyCasePlan.Status.GENERATED,
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED, false, policy,
                List.of(new TestPropertyCasePlan.PropertyTrial(
                        "property-001", root, ProtocolFingerprint.of(mapper, root), 2,
                        List.of(new TestPropertyCasePlan.ShrinkCandidate(
                                "property-001-shrink-001", "property-001", 1, shrink,
                                ProtocolFingerprint.of(mapper, shrink), 1)))), List.of());
    }

    private StoredFixtureBundle fixture(boolean assertions) {
        List<FixtureBundle.Assertion> values = assertions
                ? List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/result", "EQUALS", "approved", null))
                : List.of();
        FixtureBundle bundle = new FixtureBundle("", "property-fixture", 7, TARGET,
                "INTERNAL", null, 42L, List.of(), values, Map.of("owner", "quality"));
        return new StoredFixtureBundle("", "tenant-a", "test", "property-fixture", 7,
                FIXTURE, bundle, Instant.EPOCH, "author");
    }

    private static TestPropertySuiteMaterializationRequest request(boolean acceptGaps) {
        return new TestPropertySuiteMaterializationRequest("", "orders-properties", "INTERNAL",
                TARGET, INPUT_SCHEMA, PLAN, 42, 1, 1, fixtureRef(), acceptGaps);
    }

    private static TestSuite.FixtureBundleRef fixtureRef() {
        return new TestSuite.FixtureBundleRef("property-fixture", 7, FIXTURE);
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET);
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
}
