package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalIntegrationServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void resolvesEveryOwningRegistryBeforePersistingCompilerOutput() {
        ScenarioArtifactRegistryService artifacts =
                mock(ScenarioArtifactRegistryService.class);
        TestSuiteRegistryService suites =
                mock(TestSuiteRegistryService.class);
        FixtureBundleRepository fixtures =
                mock(FixtureBundleRepository.class);
        MirrorPlanIntegrationService plans =
                mock(MirrorPlanIntegrationService.class);
        ScenarioRehearsalCompiler compiler =
                mock(ScenarioRehearsalCompiler.class);
        CompiledScenarioRehearsalPlanRepository compiledPlans =
                mock(CompiledScenarioRehearsalPlanRepository.class);
        CaseHandlingAssertion assertion = assertion();
        ScenarioCase scenarioCase = scenarioCase(assertion);
        ScenarioPack pack = pack(assertion, scenarioCase);
        StoredTestSuite suite = mock(StoredTestSuite.class);
        StoredFixtureBundle fixture = mock(StoredFixtureBundle.class);
        when(fixture.fingerprint()).thenReturn(
                scenarioCase.fixtureBundleRef().fingerprint());
        MirrorPlan mirrorPlan = mock(MirrorPlan.class);
        CompiledScenarioRehearsalPlan compiled =
                compiled(pack, scenarioCase);
        IntegrationRequestContext identity = identity();
        when(artifacts.findPack(
                pack.packId(), pack.revision(), pack.fingerprint(), identity))
                .thenReturn(pack);
        when(artifacts.requireAssertion(
                SCOPE, ScenarioPackIntegrity.reference(assertion), identity))
                .thenReturn(assertion);
        when(artifacts.requireCase(
                SCOPE, ScenarioPackIntegrity.reference(scenarioCase), identity))
                .thenReturn(scenarioCase);
        when(suites.find(
                scenarioCase.testSuiteRef().id(),
                scenarioCase.testSuiteRef().revision(),
                identity)).thenReturn(suite);
        when(fixtures.find(
                new TestingArtifactScope(
                        SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                        SCOPE.environmentId(), SCOPE.region()),
                scenarioCase.fixtureBundleRef().id(),
                scenarioCase.fixtureBundleRef().revision()))
                .thenReturn(Optional.of(fixture));
        when(plans.findForExecution(
                scenarioCase.mirrorPlanRef().id(), identity))
                .thenReturn(mirrorPlan);
        when(compiler.compile(
                org.mockito.ArgumentMatchers.any(
                        ScenarioRehearsalCompilationRequest.class)))
                .thenReturn(compiled);
        when(compiledPlans.create(compiled)).thenReturn(compiled);
        ScenarioRehearsalIntegrationService service =
                new ScenarioRehearsalIntegrationService(
                        artifacts, suites, fixtures, plans,
                        compiler, compiledPlans,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.compile(
                pack.packId(), pack.revision(),
                pack.fingerprint(), identity))
                .isEqualTo(compiled);
        ArgumentCaptor<ScenarioRehearsalCompilationRequest> request =
                ArgumentCaptor.forClass(
                        ScenarioRehearsalCompilationRequest.class);
        verify(compiler).compile(request.capture());
        assertThat(request.getValue().pack()).isEqualTo(pack);
        assertThat(request.getValue().assertions())
                .containsExactly(assertion);
        assertThat(request.getValue().cases())
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.scenarioCase())
                            .isEqualTo(scenarioCase);
                    assertThat(resolved.testSuite()).isSameAs(suite);
                    assertThat(resolved.fixtureBundle())
                            .isSameAs(fixture);
                    assertThat(resolved.mirrorPlan())
                            .isSameAs(mirrorPlan);
                    assertThat(resolved.sessionCheckpoint()).isNull();
                });
        verify(compiledPlans).create(compiled);
    }

    @Test
    void preservesCompilerRejectionCodeForGovernanceAutomation() {
        ScenarioArtifactRegistryService artifacts =
                mock(ScenarioArtifactRegistryService.class);
        TestSuiteRegistryService suites =
                mock(TestSuiteRegistryService.class);
        FixtureBundleRepository fixtures =
                mock(FixtureBundleRepository.class);
        MirrorPlanIntegrationService plans =
                mock(MirrorPlanIntegrationService.class);
        ScenarioRehearsalCompiler compiler =
                mock(ScenarioRehearsalCompiler.class);
        CompiledScenarioRehearsalPlanRepository compiledPlans =
                mock(CompiledScenarioRehearsalPlanRepository.class);
        CaseHandlingAssertion assertion = assertion();
        ScenarioCase scenarioCase = scenarioCase(assertion);
        ScenarioPack pack = pack(assertion, scenarioCase);
        IntegrationRequestContext identity = identity();
        when(artifacts.findPack(
                pack.packId(), pack.revision(), pack.fingerprint(), identity))
                .thenReturn(pack);
        when(artifacts.requireAssertion(
                SCOPE, ScenarioPackIntegrity.reference(assertion), identity))
                .thenReturn(assertion);
        when(artifacts.requireCase(
                SCOPE, ScenarioPackIntegrity.reference(scenarioCase), identity))
                .thenReturn(scenarioCase);
        when(suites.find(
                scenarioCase.testSuiteRef().id(),
                scenarioCase.testSuiteRef().revision(),
                identity)).thenReturn(mock(StoredTestSuite.class));
        when(fixtures.find(
                new TestingArtifactScope(
                        SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                        SCOPE.environmentId(), SCOPE.region()),
                scenarioCase.fixtureBundleRef().id(),
                scenarioCase.fixtureBundleRef().revision()))
                .thenAnswer(ignored -> {
                    StoredFixtureBundle fixture = mock(StoredFixtureBundle.class);
                    when(fixture.fingerprint()).thenReturn(
                            scenarioCase.fixtureBundleRef().fingerprint());
                    return Optional.of(fixture);
                });
        when(plans.findForExecution(
                scenarioCase.mirrorPlanRef().id(), identity))
                .thenReturn(mock(MirrorPlan.class));
        when(compiler.compile(
                org.mockito.ArgumentMatchers.any(
                        ScenarioRehearsalCompilationRequest.class)))
                .thenThrow(new ScenarioRehearsalRejectedException(
                        "RG.MIRROR.REHEARSAL.PLAN_POLICY_DRIFT",
                        Map.of("caseId", scenarioCase.caseId())));
        ScenarioRehearsalIntegrationService service =
                new ScenarioRehearsalIntegrationService(
                        artifacts, suites, fixtures, plans,
                        compiler, compiledPlans,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.compile(
                pack.packId(), pack.revision(),
                pack.fingerprint(), identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        problem -> {
                            assertThat(problem.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REHEARSAL.PLAN_POLICY_DRIFT");
                            assertThat(problem.problem().details())
                                    .containsEntry(
                                            "caseId",
                                            scenarioCase.caseId());
                        });
    }

    private CaseHandlingAssertion assertion() {
        return ScenarioPackIntegrity.sealAssertion(
                mapper,
                new CaseHandlingAssertion(
                        "", "customer-node-status", 1, "", SCOPE,
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        new CaseHandlingAssertion.Selector(
                                "loadCustomer", "", "", null, ""),
                        new CaseHandlingAssertion.Expectation(
                                List.of("SUCCESS"), "", "", "",
                                null, null, null, null),
                        CaseHandlingAssertion.Severity.BLOCKER,
                        "RG.MIRROR.SCENARIO.NODE_FAILED",
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private ScenarioCase scenarioCase(CaseHandlingAssertion assertion) {
        return ScenarioPackIntegrity.sealCase(
                mapper,
                new ScenarioCase(
                        "", "customer-found", 1, "", SCOPE,
                        ScenarioCase.CaseType.GOLDEN,
                        ref("CAPABILITY", "customer-view", SHA_A),
                        ref("TEST_SUITE", "customer-suite", SHA_B),
                        "customer-found",
                        ref("MIRROR_PLAN", "customer-plan", SHA_C),
                        ref("FIXTURE_BUNDLE", "customer-fixture", SHA_A),
                        null,
                        new MirrorPlan.ExecutionServices(
                                NOW, 42L, null, null),
                        List.of(),
                        List.of(ScenarioPackIntegrity.reference(assertion)),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private ScenarioPack pack(
            CaseHandlingAssertion assertion, ScenarioCase scenarioCase) {
        return ScenarioPackIntegrity.seal(
                mapper,
                new ScenarioPack(
                        "", "customer-rehearsal", 1, "", SCOPE,
                        scenarioCase.targetCapabilityRef(),
                        List.of(ScenarioPackIntegrity.reference(scenarioCase)),
                        List.of(ScenarioPackIntegrity.reference(assertion)),
                        List.of(),
                        null,
                        List.of(),
                        policy(),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private CompiledScenarioRehearsalPlan compiled(
            ScenarioPack pack, ScenarioCase scenarioCase) {
        return CompiledScenarioRehearsalPlanIntegrity.seal(
                mapper,
                new CompiledScenarioRehearsalPlan(
                        "",
                        pack.packId()
                                + ScenarioRehearsalCompiler.PLAN_ID_SUFFIX,
                        pack.revision(),
                        "",
                        SCOPE,
                        ScenarioPackIntegrity.reference(pack),
                        pack.targetCapabilityRef(),
                        List.of(
                                new CompiledScenarioRehearsalPlan.CaseBinding(
                                        ScenarioPackIntegrity.reference(
                                                scenarioCase),
                                        scenarioCase.caseType(),
                                        scenarioCase.testSuiteRef(),
                                        scenarioCase.testCaseId(),
                                        scenarioCase.mirrorPlanRef(),
                                        scenarioCase.fixtureBundleRef(),
                                        null,
                                        scenarioCase.executionServices(),
                                        scenarioCase.assertionRefs())),
                        pack.assertionRefs(),
                        pack.policy()));
    }

    private static ScenarioPack.RehearsalPolicy policy() {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true, false, false, false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                10, 100,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                true,
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"));
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(),
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                null, null, null, null, List.of(),
                "support-owner", NOW,
                NOW.plus(Duration.ofDays(1)), "");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(),
                "SERVICE", "scenario-client", "",
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                "corr-scenario", Set.of(), "CONFIDENTIAL", "");
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }
}
