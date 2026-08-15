package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionPreflightResponse;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessTestingRegistryGateway;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessPreflightFacadeTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final String TARGET_FP = fp('a');
    private static final String FIXTURE_ONE_FP = fp('b');
    private static final String FIXTURE_TWO_FP = fp('c');
    private static final EnterpriseScope SCOPE = new EnterpriseScope(
            "tenant-a", "org-a", "project-a", "test", "sg");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CorrectnessPublicationRepository publications =
            mock(CorrectnessPublicationRepository.class);
    private final CorrectnessTestingRegistryGateway registry =
            mock(CorrectnessTestingRegistryGateway.class);
    private final TestExecutionApiService executions = mock(TestExecutionApiService.class);
    private CorrectnessPreflightFacade facade;
    private StoredCorrectnessPublication publication;
    private StoredTestSuite suite;

    @BeforeEach
    void setUp() {
        facade = new CorrectnessPreflightFacade(publications, registry, executions, mapper);
        TestSuite protocol = suite();
        String suiteFingerprint = ProtocolFingerprint.of(mapper, protocol);
        suite = new StoredTestSuite("", SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environment(), SCOPE.region(), protocol.suiteId(),
                protocol.revision(), suiteFingerprint, protocol, NOW, "author-a");
        CorrectnessPublication manifest = publication(protocol, suiteFingerprint);
        publication = StoredCorrectnessPublication.verified(mapper, manifest);
        when(publications.findPublication(SCOPE, manifest.publicationId()))
                .thenReturn(Optional.of(publication));
        when(registry.findSuite(protocol.suiteId(), protocol.revision(), identity("test")))
                .thenReturn(suite);
        when(executions.preflight(any(), eq(identity("test"))))
                .thenReturn(executionPreflight(FIXTURE_ONE_FP));
    }

    @Test
    void selectedPreflightBindsPublicationSuiteFixtureAndTrustedExecutionPlan() throws Exception {
        CorrectnessRunRequest.Selection selection = selection(
                CorrectnessRunRequest.Selection.Mode.SELECTED, List.of("approved"));
        CorrectnessPreflightRequest request = new CorrectnessPreflightRequest("",
                publicationRef(), selection);

        CorrectnessPreflightReport report = facade.preflight(request, identity("test"));

        assertThat(report.preflightFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(report.publicationRef()).isEqualTo(publicationRef());
        assertThat(report.compiledTestSuiteRef())
                .isEqualTo(publication.publication().compiledTestSuiteRef());
        assertThat(report.proofLevel())
                .isEqualTo(CorrectnessPreflightReport.ProofLevel.SIMULATED_BUSINESS);
        assertThat(report.cases()).singleElement().satisfies(testCase -> {
            assertThat(testCase.caseId()).isEqualTo("approved");
            assertThat(testCase.fixtureBundleRef().fingerprint()).isEqualTo(FIXTURE_ONE_FP);
            assertThat(testCase.invocationSites()).singleElement().satisfies(site -> {
                assertThat(site.nodeId()).isEqualTo("lookup");
                assertThat(site.resolution())
                        .isEqualTo(EffectiveExecutionPlan.Resolution.TEST_DOUBLE);
            });
        });
        assertThat(report.riskSummary().mockedCount()).isEqualTo(1);
        assertThat(report.riskSummary().realCount()).isZero();
        assertThat(report.riskSummary().secretRequirementCount()).isEqualTo(1);
        assertThat(report.riskSummary().logicalClockConfigured()).isTrue();
        assertThat(report.blockers()).isEmpty();
        assertThat(mapper.writeValueAsString(report))
                .doesNotContain("customer-secret", "fixture-value", "requestPayload");
        verify(executions).preflight(any(), eq(identity("test")));
    }

    @Test
    void unreviewedSelectionIntentIsResolvedAndFingerprintByTheServer() {
        CorrectnessPreflightRequest.SelectionIntent intent =
                new CorrectnessPreflightRequest.SelectionIntent(
                        CorrectnessRunRequest.Selection.Mode.SELECTED,
                        List.of("approved"), "");

        CorrectnessPreflightReport report = facade.preflight(
                new CorrectnessPreflightRequest("", publicationRef(), intent),
                identity("test"));

        assertThat(report.selection().caseIds()).containsExactly("approved");
        assertThat(report.selection().selectionFingerprint()).isEqualTo(
                facade.selectionFingerprint(
                        CorrectnessRunRequest.Selection.Mode.SELECTED,
                        List.of("approved")));
    }

    @Test
    void allSelectionFingerprintBindsTheResolvedImmutableCaseClosure() {
        CorrectnessRunRequest.Selection selection = selection(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of("approved", "declined"));
        when(executions.preflight(any(), eq(identity("test"))))
                .thenAnswer(invocation -> {
                    TestExecutionApiRequest request = invocation.getArgument(0);
                    String fixtureFingerprint = "fixture-approved".equals(
                            request.fixtureBundleRef().fixtureBundleId())
                            ? FIXTURE_ONE_FP : FIXTURE_TWO_FP;
                    return executionPreflight(fixtureFingerprint);
                });

        CorrectnessPreflightReport report = facade.preflight(
                new CorrectnessPreflightRequest("", publicationRef(), selection),
                identity("test"));

        assertThat(report.cases()).extracting(CorrectnessPreflightReport.CasePlan::caseId)
                .containsExactly("approved", "declined");
        assertThat(report.riskSummary().mockedCount()).isEqualTo(2);
    }

    @Test
    void preflightFingerprintIgnoresEphemeralPlannerIds() {
        CorrectnessRunRequest.Selection selection = selection(
                CorrectnessRunRequest.Selection.Mode.SELECTED, List.of("approved"));
        when(executions.preflight(any(), eq(identity("test"))))
                .thenReturn(executionPreflight(FIXTURE_ONE_FP, "plan-attempt-a"))
                .thenReturn(executionPreflight(FIXTURE_ONE_FP, "plan-attempt-b"));
        CorrectnessPreflightRequest request = new CorrectnessPreflightRequest(
                "", publicationRef(), selection);

        CorrectnessPreflightReport first = facade.preflight(request, identity("test"));
        CorrectnessPreflightReport second = facade.preflight(request, identity("test"));

        assertThat(second.preflightFingerprint()).isEqualTo(first.preflightFingerprint());
        assertThat(second.cases().getFirst().executionPlanFingerprint())
                .isEqualTo(first.cases().getFirst().executionPlanFingerprint());
    }

    @Test
    void staleSelectionFingerprintIsRejectedBeforeAnyFixtureResolution() {
        CorrectnessRunRequest.Selection stale = new CorrectnessRunRequest.Selection(
                CorrectnessRunRequest.Selection.Mode.SELECTED,
                List.of("approved"), fp('f'));

        assertThatThrownBy(() -> facade.preflight(
                new CorrectnessPreflightRequest("", publicationRef(), stale), identity("test")))
                .isInstanceOfSatisfying(CorrectnessRunException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.SELECTION_FINGERPRINT_CONFLICT");
                });
        verify(executions, never()).preflight(any(), any());
    }

    @Test
    void productionEnvironmentIsHardDisabledBeforePublicationLookup() {
        CorrectnessRunRequest.Selection selection = selection(
                CorrectnessRunRequest.Selection.Mode.SELECTED, List.of("approved"));

        assertThatThrownBy(() -> facade.preflight(
                new CorrectnessPreflightRequest("", publicationRef(), selection),
                identity("production")))
                .isInstanceOfSatisfying(CorrectnessRunException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.code()).isEqualTo(
                            "RG.CORRECTNESS.PRODUCTION_FIXTURE_INJECTION_FORBIDDEN");
                });
        verify(publications, never()).findPublication(any(), any());
    }

    @Test
    void commandProtocolRejectsPayloadOverrideFields() {
        String json = """
                {
                  "schemaVersion":"bloge.correctnessRunRequest.v1",
                  "publicationRef":{"publicationId":"publication-1","revision":1,
                    "fingerprint":"%s"},
                  "selection":{"mode":"SELECTED","caseIds":["approved"],
                    "selectionFingerprint":"%s"},
                  "preflightFingerprint":"%s",
                  "clientRequestId":"request-1",
                  "strategy":"COLLECT_ALL",
                  "fixtureBundle":{"requestPayload":"forbidden"}
                }
                """.formatted(publication.publicationFingerprint(), fp('d'), fp('e'));

        assertThatThrownBy(() -> mapper.readValue(json, CorrectnessRunRequest.class))
                .hasMessageContaining("fixtureBundle");
    }

    private CorrectnessRunRequest.Selection selection(
            CorrectnessRunRequest.Selection.Mode mode,
            List<String> resolvedCaseIds
    ) {
        List<String> supplied = mode == CorrectnessRunRequest.Selection.Mode.ALL
                ? List.of() : resolvedCaseIds;
        return new CorrectnessRunRequest.Selection(
                mode, supplied, facade.selectionFingerprint(mode, resolvedCaseIds));
    }

    private CorrectnessRunRequest.PublicationRef publicationRef() {
        return new CorrectnessRunRequest.PublicationRef(
                publication.publication().publicationId(), 1,
                publication.publicationFingerprint());
    }

    private static TestSuite suite() {
        TestSuite.Target target = new TestSuite.Target("GRAPH", "customer-resolution", TARGET_FP);
        List<TestSuite.TestCase> cases = List.of(
                testCase("approved", "fixture-approved", FIXTURE_ONE_FP,
                        Map.of("customerId", "customer-secret")),
                testCase("declined", "fixture-declined", FIXTURE_TWO_FP,
                        Map.of("customerId", "other")));
        return new TestSuite("", "correctness-suite", 1, target, "INTERNAL", cases,
                new TestSuite.CoveragePolicy(2,
                        List.of(TestSuite.CaseType.GOLDEN), List.of(), List.of(), 1, true),
                new TestSuite.PromotionPolicy(true, 2, true), Map.of());
    }

    private static TestSuite.TestCase testCase(
            String id, String fixtureId, String fixtureFingerprint, Object input
    ) {
        return new TestSuite.TestCase(id, TestSuite.CaseType.GOLDEN, input,
                new TestSuite.FixtureBundleRef(fixtureId, 1, fixtureFingerprint),
                List.of(), Map.of());
    }

    private static CorrectnessPublication publication(
            TestSuite suite,
            String suiteFingerprint
    ) {
        PrincipalRef actor = new PrincipalRef("author-a", PrincipalKind.USER, "Author");
        AuditMetadata metadata = new AuditMetadata(NOW, NOW, actor, actor);
        return new CorrectnessPublication("", "publication-1", SCOPE,
                new ExactTargetRef(TargetKind.GRAPH, suite.target().id(), 1, TARGET_FP),
                ref("CORRECTNESS_DEFINITION", "definition", fp('1')),
                ref("COVERAGE_INVENTORY", "inventory", fp('2')),
                ref("SCENARIO_DRAFT_SET", "scenarios", fp('3')),
                List.of(ref("BUSINESS_ORACLE", "oracle", fp('4'))),
                List.of(ref("ASSERTION_SET", "assertions", fp('5'))),
                List.of(ref("FIXTURE_ASSET", "fixture-asset", fp('6'))),
                List.of(
                        ref("FIXTURE_BUNDLE", "fixture-approved", FIXTURE_ONE_FP),
                        ref("FIXTURE_BUNDLE", "fixture-declined", FIXTURE_TWO_FP)),
                new ExactAssetRef("TEST_SUITE", suite.suiteId(), suite.revision(), suiteFingerprint),
                "correctness-compiler-v1", fp('7'), metadata);
    }

    private static TestExecutionPreflightResponse executionPreflight(String fixtureFingerprint) {
        return executionPreflight(fixtureFingerprint, "random-plan-id");
    }

    private static TestExecutionPreflightResponse executionPreflight(
            String fixtureFingerprint,
            String planId
    ) {
        InvocationSite invocation = new InvocationSite("", TARGET_FP, "/root", "lookup",
                "customerLookup", "", "", fp('8'), InvocationSite.InvocationKind.PRIMARY,
                null, "", null);
        EffectiveExecutionPlan.ResolvedSite resolved = new EffectiveExecutionPlan.ResolvedSite(
                invocation.invocationSiteId(), EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.NODE,
                List.of("lookup-fixture"), "FIXED");
        List<EffectiveExecutionPlan.ExecutionServiceBinding> services = List.of(
                new EffectiveExecutionPlan.ExecutionServiceBinding(
                        "TIME", "LOGICAL_ADVANCING", true, true, fp('9'),
                        List.of("/root/lookup#PRIMARY"), List.of()),
                new EffectiveExecutionPlan.ExecutionServiceBinding(
                        "SECRET", "EXTERNAL_TEST_AUTHORITY", true, true, fp('0'),
                        List.of("/root/lookup#PRIMARY"), List.of()));
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan("", planId, fp('d'),
                TestExecutionApiService.AUTHORIZED_PURPOSE, TARGET_FP, fixtureFingerprint,
                List.of(resolved), List.of(), services, Map.of("externalEffects", "DENY"), List.of());
        return new TestExecutionPreflightResponse("",
                new TestExecutionApiRequest.Target("GRAPH", "customer-resolution", TARGET_FP),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", "fixture-" + (FIXTURE_ONE_FP.equals(fixtureFingerprint)
                        ? "approved" : "declined"), 1, fixtureFingerprint),
                plan,
                List.of(new TestExecutionPreflightResponse.InvocationSiteDescriptor(
                        invocation, "READ_ONLY")),
                List.of(new TestExecutionPreflightResponse.RulePolicyDescriptor(
                        "lookup-fixture", FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.NODE, true, 1, 1,
                        FixtureRule.UnmatchedAction.FAIL, FixtureRule.ExhaustedAction.FAIL,
                        FixtureRule.SchemaCheckMode.STRICT)));
    }

    private static ExactAssetRef ref(String kind, String id, String fingerprint) {
        return new ExactAssetRef(kind, id, 1, fingerprint);
    }

    private static IntegrationRequestContext identity(String environment) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment,
                "sg", "WORKLOAD", "author-a", "", "TEST_EXECUTION", "correlation-a",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
