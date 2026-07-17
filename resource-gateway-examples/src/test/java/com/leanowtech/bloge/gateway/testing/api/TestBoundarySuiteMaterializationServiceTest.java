package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
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

class TestBoundarySuiteMaterializationServiceTest {
    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String INPUT_SCHEMA = "sha256:" + "b".repeat(64);
    private static final String PLAN = "sha256:" + "c".repeat(64);
    private static final String FIXTURE = "sha256:" + "d".repeat(64);
    private static final String SUITE = "sha256:" + "e".repeat(64);

    private TestExecutionApiService executions;
    private TestSuiteRegistryService suites;
    private TestBoundarySuiteMaterializationService service;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        executions = mock(TestExecutionApiService.class);
        suites = mock(TestSuiteRegistryService.class);
        service = new TestBoundarySuiteMaterializationService(executions, suites,
                new ObjectMapper().findAndRegisterModules());
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "author", "", "TEST_SUITE_WRITE", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
        when(executions.registerFixture(any(), any(), eq(identity))).thenAnswer(invocation -> {
            FixtureBundle bundle = invocation.<FixtureBundleRegistrationRequest>getArgument(1)
                    .fixtureBundle();
            return new StoredFixtureBundle("", "tenant-a", "test", bundle.fixtureBundleId(),
                    bundle.revision(), FIXTURE, bundle, Instant.EPOCH, "author");
        });
        when(suites.register(any(), any(), eq(identity))).thenAnswer(invocation -> {
            TestSuiteV3 suite = (TestSuiteV3) invocation.<TestSuiteRegistrationRequest>getArgument(1)
                    .testSuite();
            return new StoredTestSuite("", "tenant-a", "test", suite.suiteId(),
                    suite.revision(), SUITE, suite, Instant.EPOCH, "author");
        });
    }

    @Test
    void materializesSelectedCasesInPlanOrderAsDeterministicInertV3Assets() {
        TestBoundaryCasePlan plan = generatedPlan();
        when(executions.planGraphBoundaryCases("orders", identity)).thenReturn(plan);
        TestBoundarySuiteMaterializationRequest request = request(
                List.of("required-missing", "baseline"), true);

        TestBoundarySuiteMaterializationResponse first =
                service.materializeGraph("orders", request, identity);
        TestBoundarySuiteMaterializationResponse retry =
                service.materializeGraph("orders", request, identity);

        assertThat(retry).isEqualTo(first);
        assertThat(first.selectedCaseIds()).containsExactly("baseline", "required-missing");
        assertThat(first.coverageGapsAccepted()).isFalse();
        assertThat(first.fixtureRef().fingerprint()).isEqualTo(FIXTURE);
        assertThat(first.suiteRef().fingerprint()).isEqualTo(SUITE);
        assertThat(first.materializationFingerprint()).matches("sha256:[a-f0-9]{64}");

        ArgumentCaptor<FixtureBundleRegistrationRequest> fixtureRequests =
                ArgumentCaptor.forClass(FixtureBundleRegistrationRequest.class);
        verify(executions, times(2)).registerFixture(any(), fixtureRequests.capture(), eq(identity));
        assertThat(fixtureRequests.getAllValues()).extracting(requestValue ->
                requestValue.fixtureBundle().revision()).containsOnly(
                fixtureRequests.getAllValues().getFirst().fixtureBundle().revision());
        assertThat(fixtureRequests.getAllValues().getFirst().fixtureBundle()).satisfies(fixture -> {
            assertThat(fixture.rules()).isEmpty();
            assertThat(fixture.assertions()).isEmpty();
            assertThat(fixture.logicalClock()).isNull();
            assertThat(fixture.randomSeed()).isNull();
        });

        ArgumentCaptor<TestSuiteRegistrationRequest> suiteRequests =
                ArgumentCaptor.forClass(TestSuiteRegistrationRequest.class);
        verify(suites, times(2)).register(eq("orders-schema-boundaries"),
                suiteRequests.capture(), eq(identity));
        TestSuiteV3 suite = (TestSuiteV3) suiteRequests.getAllValues().getFirst().testSuite();
        assertThat(suite.evaluationMode()).isEqualTo(TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION);
        assertThat(suite.cases()).extracting(testCase -> testCase.caseId())
                .containsExactly("baseline", "required-missing");
        assertThat(suite.admissionExpectations()).containsOnlyKeys("baseline", "required-missing");
        assertThat(suite.admissionExpectations().get("required-missing").expectedOutcome())
                .isEqualTo(TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED);
        assertThat(suite.promotionPolicy().minimumCertifiableCases()).isZero();
        assertThat(suite.promotionPolicy().requireTargetCertificationEligible()).isFalse();
        assertThat(suiteRequests.getAllValues()).extracting(requestValue ->
                requestValue.testSuite().revision()).containsOnly(suite.revision());
    }

    @Test
    void rejectsStaleFingerprintsAndUnknownOrOversizedSelectionsBeforeWriting() {
        when(executions.planGraphBoundaryCases("orders", identity)).thenReturn(generatedPlan());
        TestBoundarySuiteMaterializationRequest stale = new TestBoundarySuiteMaterializationRequest(
                "", "orders-schema-boundaries", "INTERNAL", "sha256:" + "9".repeat(64),
                INPUT_SCHEMA, PLAN, List.of("baseline"), false);

        assertProblem(() -> service.materializeGraph("orders", stale, identity),
                "RG.TEST.BOUNDARY_PLAN_FINGERPRINT_CONFLICT", 409);
        assertProblem(() -> service.materializeGraph("orders", request(List.of("unknown"), false),
                        identity),
                "RG.TEST.BOUNDARY_CASE_SELECTION_INVALID", 400);
        assertProblem(() -> service.materializeGraph("orders",
                        request(List.of("x".repeat(129)), false), identity),
                "RG.TEST.BOUNDARY_SUITE_REQUEST_INVALID", 400);
        verify(executions, times(2)).planGraphBoundaryCases("orders", identity);
        verify(executions, never()).registerFixture(any(), any(), any());
        verifyNoInteractions(suites);
    }

    @Test
    void partialPlansRequireExplicitGapAcceptanceAndUnavailablePlansAreNeverMaterialized() {
        TestBoundaryCasePlan partial = new TestBoundaryCasePlan("", target(), INPUT_SCHEMA, PLAN,
                TestBoundaryCasePlan.Status.PARTIAL, policy(), generatedPlan().cases(),
                List.of(new TestBoundaryCasePlan.CoverageGap(
                        TestBoundaryCasePlan.GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                        "/properties/code", "pattern")));
        when(executions.planGraphBoundaryCases("orders", identity)).thenReturn(partial);

        assertProblem(() -> service.materializeGraph("orders",
                        request(List.of("baseline"), false), identity),
                "RG.TEST.BOUNDARY_PLAN_GAPS_NOT_ACCEPTED", 400);
        TestBoundarySuiteMaterializationResponse accepted = service.materializeGraph("orders",
                request(List.of("baseline"), true), identity);
        assertThat(accepted.coverageGapsAccepted()).isTrue();
        assertThat(accepted.sourcePlanStatus()).isEqualTo(TestBoundaryCasePlan.Status.PARTIAL);

        TestBoundaryCasePlan unavailable = new TestBoundaryCasePlan("", target(), INPUT_SCHEMA,
                PLAN, TestBoundaryCasePlan.Status.UNAVAILABLE, policy(), List.of(),
                List.of(new TestBoundaryCasePlan.CoverageGap(
                        TestBoundaryCasePlan.GapCode.OPAQUE_INPUT_SCHEMA, "", "")));
        when(executions.planGraphBoundaryCases("orders", identity)).thenReturn(unavailable);
        assertProblem(() -> service.materializeGraph("orders",
                        request(List.of("baseline"), true), identity),
                "RG.TEST.BOUNDARY_PLAN_UNAVAILABLE", 400);
    }

    @Test
    void operatorRouteRegeneratesTheExactOperatorPlan() {
        TestBoundaryCasePlan plan = generatedPlan();
        when(executions.planOperatorBoundaryCases("customer.normalize", identity)).thenReturn(plan);

        service.materializeOperator("customer.normalize",
                request(List.of("baseline"), false), identity);

        verify(executions).planOperatorBoundaryCases("customer.normalize", identity);
        verify(suites).register(eq("orders-schema-boundaries"), any(), eq(identity));
    }

    private static TestBoundarySuiteMaterializationRequest request(List<String> selected,
                                                                    boolean acceptGaps) {
        return new TestBoundarySuiteMaterializationRequest("", "orders-schema-boundaries",
                "INTERNAL", TARGET, INPUT_SCHEMA, PLAN, selected, acceptGaps);
    }

    private static TestBoundaryCasePlan generatedPlan() {
        return new TestBoundaryCasePlan("", target(), INPUT_SCHEMA, PLAN,
                TestBoundaryCasePlan.Status.GENERATED, policy(), List.of(
                new TestBoundaryCasePlan.BoundaryCase("baseline",
                        TestBoundaryCasePlan.BoundaryKind.BASELINE, "", "",
                        TestBoundaryCasePlan.ExpectedOutcome.ACCEPTED,
                        Map.of("customerId", "C-1"), List.of()),
                new TestBoundaryCasePlan.BoundaryCase("required-missing",
                        TestBoundaryCasePlan.BoundaryKind.REQUIRED_PROPERTY_MISSING,
                        "/customerId", "/required",
                        TestBoundaryCasePlan.ExpectedOutcome.SCHEMA_REJECTED,
                        Map.of(), List.of("visual.context.requiredMissing"))), List.of());
    }

    private static TestExecutionApiRequest.Target target() {
        return new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET);
    }

    private static TestBoundaryCasePlan.GenerationPolicy policy() {
        return new TestBoundaryCasePlan.GenerationPolicy(
                "bloge.testBoundaryCaseGenerator.v1", 64, 8, 32,
                "VISUAL_SCHEMA_VALIDATOR_PROOF");
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                      String code, int status) {
        assertThatThrownBy(action).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.status()).isEqualTo(status);
                });
    }
}
