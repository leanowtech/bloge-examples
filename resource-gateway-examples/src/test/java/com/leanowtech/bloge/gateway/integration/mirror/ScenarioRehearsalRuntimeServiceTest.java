package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalRuntimeServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CapabilitySnapshot.Scope scope =
            MirrorPersistenceTestFixtures.scope("org-a");
    private final IntegrationRequestContext identity =
            new IntegrationRequestContext(
                    scope.tenantId(), scope.organizationId(),
                    scope.projectId(), scope.environmentId(), scope.region(),
                    "SERVICE", "scenario-runner", "",
                    "MIRROR_REHEARSAL", "correlation-1",
                    Set.of(), "RESTRICTED", "");

    @Test
    void executesTestSuiteContextThroughExistingMirrorRuntimeAndEvaluatesEvidence() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        ScenarioRehearsalRuntimeService service = fixture.service();

        ScenarioRehearsalEvidenceBundle aggregate = service.execute(
                fixture.request(), identity);
        ScenarioRehearsalResult result = aggregate.result();

        ScenarioRehearsalResultIntegrity.verify(mapper, result);
        assertThat(fixture.rehearsalIntegrity().verify(aggregate))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.VERIFIED);
        assertThat(result.outcome())
                .isEqualTo(ScenarioCaseRehearsalResult.Outcome.PASS);
        assertThat(result.summary())
                .isEqualTo(new ScenarioRehearsalResult.Summary(
                        1, 1, 0, 0, 1, 0, 0, 0, 0));
        ScenarioCaseRehearsalResult caseResult =
                result.caseResults().getFirst();
        assertThat(caseResult.runId())
                .isEqualTo(fixture.bundle().evidence().runId());
        assertThat(caseResult.assertionResults()).singleElement()
                .satisfies(assertion ->
                        assertThat(assertion.outcome()).isEqualTo(
                                ScenarioHandlingAssertionResult.Outcome.PASS));

        ArgumentCaptor<MirrorExecutionRequest> request =
                ArgumentCaptor.forClass(MirrorExecutionRequest.class);
        verify(fixture.mirrorRuns()).execute(
                request.capture(), any());
        assertThat(request.getValue().requestId())
                .isEqualTo("scenario-request-1:case:000");
        assertThat(request.getValue().context())
                .isEqualTo(Map.of("customerId", "C-1"));
        assertThat(request.getValue().sessionBinding()).isNull();
    }

    @Test
    void propagatesRetryableChildFailureSoTheSameRequestCanResume() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        when(fixture.mirrorRuns().execute(any(), any()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.retryableConflict(
                                "RG.MIRROR.RUN_REQUEST_IN_PROGRESS",
                                "in progress", identity.correlationId(),
                                Map.of("retryAfterSeconds", 1))));

        assertThatThrownBy(() ->
                fixture.service().execute(fixture.request(), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException) failure)
                                .problem().retryable()).isTrue());
    }

    @Test
    void recordsNonRetryableChildRejectionWithoutInventingEvidence() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        when(fixture.mirrorRuns().execute(any(), any()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.PLAN_FINGERPRINT_CONFLICT",
                                "stale", identity.correlationId(), Map.of())));

        ScenarioRehearsalResult result =
                fixture.service().execute(
                        fixture.request(), identity).result();

        assertThat(result.outcome())
                .isEqualTo(ScenarioCaseRehearsalResult.Outcome.FAIL);
        assertThat(result.caseResults()).singleElement()
                .satisfies(value -> {
                    assertThat(value.runId()).isBlank();
                    assertThat(value.assertionResults()).isEmpty();
                    assertThat(value.diagnosticCode())
                            .isEqualTo(
                                    "RG.MIRROR.PLAN_FINGERPRINT_CONFLICT");
                });
    }

    @Test
    void reprojectsAnIdempotentChildWhoseEvidencePredatesThisAttempt() {
        Fixture fixture = fixture(
                Map.of("customerId", "C-1"), true);

        ScenarioRehearsalResult result =
                fixture.service().execute(
                        fixture.request(), identity).result();

        assertThat(result.startedAt())
                .isEqualTo(fixture.bundle().evidence().startedAt());
        assertThat(result.completedAt())
                .isEqualTo(fixture.bundle().evidence().completedAt());
        assertThat(result.outcome())
                .isEqualTo(ScenarioCaseRehearsalResult.Outcome.PASS);
    }

    @Test
    void preservesJsonNullsInTheGovernedTestContext() {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("optionalCustomerId", null);
        Fixture fixture = fixture(input);

        fixture.service().execute(fixture.request(), identity);

        ArgumentCaptor<MirrorExecutionRequest> request =
                ArgumentCaptor.forClass(MirrorExecutionRequest.class);
        verify(fixture.mirrorRuns()).execute(
                request.capture(), any());
        assertThat(request.getValue().context())
                .containsEntry("optionalCustomerId", null);
    }

    @Test
    void rejectsAStoredSuiteInputThatCannotBecomeGraphContext() {
        Fixture fixture = fixture("scalar-operator-input");

        assertThatThrownBy(() ->
                fixture.service().execute(fixture.request(), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException) failure)
                                .problem().code()).isEqualTo(
                                "RG.MIRROR.REHEARSAL.CASE_INPUT_NOT_CONTEXT"));
        verify(fixture.mirrorRuns(), never()).execute(any(), any());
    }

    @Test
    void rejectsVerifiedEvidenceWhoseChildRequestIdentityDrifted() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        MirrorRunSummary summary =
                MirrorRunSummary.from(fixture.bundle());
        MirrorRunSummary drifted = new MirrorRunSummary(
                "", summary.runId(), "another-child", summary.planId(),
                summary.planFingerprint(),
                summary.requestContextFingerprint(), summary.scope(),
                summary.status(), summary.evidenceClass(),
                summary.startedAt(), summary.completedAt(),
                summary.durationMs(), summary.nodeTraceCount(),
                summary.edgeTraceCount(), summary.resolutionCount(),
                summary.evidenceBundleFingerprint());
        when(fixture.mirrorRuns().execute(any(), any()))
                .thenReturn(drifted);

        assertThatThrownBy(() ->
                fixture.service().execute(fixture.request(), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException) failure)
                                .problem().code()).isEqualTo(
                                "RG.MIRROR.REHEARSAL.CHILD_EVIDENCE_IDENTITY_INVALID"));
    }

    @Test
    void letsTheChildCoordinatorResolveStatefulRetriesBeforeReadingTheSessionHead() {
        Fixture fixture = fixture(
                Map.of("customerId", "C-1"), false, true);

        fixture.service().execute(fixture.request(), identity);

        ArgumentCaptor<MirrorExecutionRequest> request =
                ArgumentCaptor.forClass(MirrorExecutionRequest.class);
        verify(fixture.mirrorRuns()).execute(request.capture(), any());
        assertThat(request.getValue().sessionBinding())
                .isEqualTo(new MirrorSessionRunBinding(
                        "scenario-session-1", fingerprint('3')));
        verify(fixture.sessions(), never())
                .recover(any(), any(), any());
    }

    @Test
    void returnsVerifiedExistingAggregateWithoutExecutingAChildAgain() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        ScenarioRehearsalEvidenceBundle first =
                fixture.service().execute(fixture.request(), identity);
        when(fixture.rehearsalEvidence().find(
                scope, first.attestation().runId()))
                .thenReturn(Optional.of(first));

        ScenarioRehearsalEvidenceBundle retried =
                fixture.service().execute(fixture.request(), identity);

        assertThat(retried).isEqualTo(first);
        verify(fixture.mirrorRuns()).execute(any(), any());
    }

    @Test
    void rereadsEvidenceOnlyAfterIndependentVerification() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        ScenarioRehearsalEvidenceBundle first =
                fixture.service().execute(fixture.request(), identity);
        when(fixture.rehearsalEvidence().find(
                scope, first.attestation().runId()))
                .thenReturn(Optional.of(first));

        ScenarioRehearsalEvidenceBundle read =
                fixture.service().evidence(
                        first.attestation().runId(), identity);

        assertThat(read).isEqualTo(first);
        assertThat(fixture.rehearsalIntegrity().verify(read))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.VERIFIED);
    }

    @Test
    void distinguishesCorruptStoredEvidenceFromGenericStoreOutage() {
        Fixture fixture = fixture(Map.of("customerId", "C-1"));
        when(fixture.rehearsalEvidence().find(any(), any()))
                .thenThrow(new ScenarioRehearsalEvidenceStoreException(
                        ScenarioRehearsalEvidenceStoreException.Reason
                                .INTEGRITY_INVALID,
                        "corrupt", null));

        assertThatThrownBy(() ->
                fixture.service().execute(fixture.request(), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException) failure)
                                .problem().code()).isEqualTo(
                                "RG.MIRROR.REHEARSAL.EVIDENCE_INCONSISTENT"));
        verify(fixture.mirrorRuns(), never()).execute(any(), any());
    }

    private Fixture fixture(Object input) {
        return fixture(input, false, false);
    }

    private Fixture fixture(Object input, boolean delayedAttempt) {
        return fixture(input, delayedAttempt, false);
    }

    private Fixture fixture(
            Object input,
            boolean delayedAttempt,
            boolean stateful) {
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        ScenarioArtifactRegistryService scenarioArtifacts =
                mock(ScenarioArtifactRegistryService.class);
        TestSuiteRegistryService testSuites =
                mock(TestSuiteRegistryService.class);
        MirrorRunIntegrationService mirrorRuns =
                mock(MirrorRunIntegrationService.class);
        VisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        MirrorPlan mirrorPlan = MirrorPersistenceTestFixtures.plan(
                mapper, scope, "scenario-plan", '8');
        MirrorArtifactRef mirrorPlanRef = new MirrorArtifactRef(
                "MIRROR_PLAN", mirrorPlan.planId(), 1,
                mirrorPlan.planFingerprint());
        MirrorArtifactRef fixtureRef = mirrorPlan.fixtureBundleRef();
        String suiteFingerprint = fingerprint('5');
        MirrorArtifactRef suiteRef = new MirrorArtifactRef(
                "TEST_SUITE", "support-suite", 1, suiteFingerprint);
        MirrorArtifactRef checkpointRef = stateful
                ? new MirrorArtifactRef(
                "MIRROR_SESSION_CHECKPOINT",
                "scenario-checkpoint-1", 1, fingerprint('2'))
                : null;
        TestSuite.TestCase testCase = new TestSuite.TestCase(
                "golden", TestSuite.CaseType.GOLDEN, input,
                new TestSuite.FixtureBundleRef(
                        fixtureRef.id(), fixtureRef.revision(),
                        fixtureRef.fingerprint()),
                List.of("scenario"), Map.of());
        TestSuite suite = new TestSuite(
                "", suiteRef.id(), suiteRef.revision(),
                new TestSuite.Target(
                        "GRAPH", "support-graph", fingerprint('6')),
                "INTERNAL", List.of(testCase),
                TestSuite.CoveragePolicy.defaults(),
                TestSuite.PromotionPolicy.defaults(), Map.of());
        StoredTestSuite storedSuite = new StoredTestSuite(
                "", scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(),
                suiteRef.id(), suiteRef.revision(), suiteFingerprint,
                suite, Instant.parse("2026-03-01T00:00:00Z"),
                "support-owner");
        ArtifactProvenance provenance = provenance();
        CaseHandlingAssertion assertion =
                ScenarioPackIntegrity.sealAssertion(
                        mapper,
                        new CaseHandlingAssertion(
                                "", "certifiable", 1, "", scope,
                                CaseHandlingAssertion.Observation
                                        .GOVERNANCE_EXPECTATION,
                                CaseHandlingAssertion.Selector.empty(),
                                new CaseHandlingAssertion.Expectation(
                                        List.of("CERTIFIABLE"), "", "", "",
                                        null, null, null, true),
                                CaseHandlingAssertion.Severity.BLOCKER,
                                "RG.MIRROR.SCENARIO.NOT_CERTIFIABLE",
                                provenance,
                                CapabilitySnapshot.Lifecycle.ACTIVE,
                                Instant.parse("2026-03-01T00:00:00Z")));
        MirrorArtifactRef assertionRef =
                ScenarioPackIntegrity.reference(assertion);
        ScenarioCase scenarioCase = ScenarioPackIntegrity.sealCase(
                mapper,
                new ScenarioCase(
                        "", "support-golden", 1, "", scope,
                        stateful
                                ? ScenarioCase.CaseType.STATE_TRANSITION
                                : ScenarioCase.CaseType.GOLDEN,
                        mirrorPlan.rootCapability(), suiteRef, "golden",
                        mirrorPlanRef, fixtureRef, checkpointRef,
                        mirrorPlan.executionServices(), List.of(),
                        List.of(assertionRef), provenance,
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        Instant.parse("2026-03-01T00:00:00Z")));
        CompiledScenarioRehearsalPlan.CaseBinding binding =
                new CompiledScenarioRehearsalPlan.CaseBinding(
                        ScenarioPackIntegrity.reference(scenarioCase),
                        scenarioCase.caseType(), suiteRef,
                        scenarioCase.testCaseId(), mirrorPlanRef, fixtureRef,
                        checkpointRef, mirrorPlan.executionServices(),
                        List.of(assertionRef));
        CompiledScenarioRehearsalPlan compiled =
                CompiledScenarioRehearsalPlanIntegrity.seal(
                        mapper,
                        new CompiledScenarioRehearsalPlan(
                                "", "support-rehearsal-compiled", 1, "",
                                scope,
                                new MirrorArtifactRef(
                                        "SCENARIO_PACK",
                                        "support-rehearsal", 1,
                                        fingerprint('7')),
                                mirrorPlan.rootCapability(),
                                List.of(binding), List.of(assertionRef),
                                policy()));
        ScenarioRehearsalExecutionRequest request =
                new ScenarioRehearsalExecutionRequest(
                        "", "scenario-request-1",
                        CompiledScenarioRehearsalPlanIntegrity.reference(
                                compiled));
        String childRequestId = "scenario-request-1:case:000";
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.certifiableEvidence(
                        mapper, signer, mirrorPlan, "run-scenario-1", '9',
                        childRequestId, fingerprint('0'),
                        MirrorPersistenceTestFixtures.trustBinding(scope));
        Clock runtimeClock = Clock.fixed(
                delayedAttempt
                        ? bundle.evidence().completedAt().plusSeconds(10)
                        : bundle.evidence().startedAt(),
                ZoneOffset.UTC);
        MirrorEvidenceIntegrityService evidenceIntegrity =
                new MirrorEvidenceIntegrityService(
                        mapper, signer,
                        Clock.fixed(
                                bundle.evidence().completedAt().plusSeconds(2),
                                ZoneOffset.UTC));
        ScenarioRehearsalEvidenceIntegrityService rehearsalIntegrity =
                new ScenarioRehearsalEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                bundle.evidence().completedAt()
                                        .plusSeconds(20),
                                ZoneOffset.UTC));
        ScenarioRehearsalEvidenceRepository rehearsalEvidence =
                mock(ScenarioRehearsalEvidenceRepository.class);
        when(rehearsalEvidence.find(any(), any()))
                .thenReturn(Optional.empty());
        when(rehearsalEvidence.create(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(rehearsals.find(
                compiled.planId(), compiled.revision(),
                compiled.fingerprint(), identity))
                .thenReturn(compiled);
        when(scenarioArtifacts.requireCase(
                scope, binding.scenarioCaseRef(), identity))
                .thenReturn(scenarioCase);
        when(scenarioArtifacts.requireAssertion(
                scope, assertionRef, identity))
                .thenReturn(assertion);
        MirrorSessionIntegrationService sessions =
                stateful ? mock(MirrorSessionIntegrationService.class) : null;
        if (stateful) {
            MirrorSessionCheckpoint checkpoint =
                    mock(MirrorSessionCheckpoint.class);
            when(checkpoint.sessionId())
                    .thenReturn("scenario-session-1");
            when(checkpoint.stateFingerprint())
                    .thenReturn(fingerprint('3'));
            when(checkpoint.sessionExpiresAt())
                    .thenReturn(Instant.parse("2027-03-01T00:00:00Z"));
            MirrorSessionCheckpointBundle checkpointBundle =
                    mock(MirrorSessionCheckpointBundle.class);
            when(checkpointBundle.checkpoint()).thenReturn(checkpoint);
            when(scenarioArtifacts.requireCheckpoint(
                    scope, checkpointRef, identity))
                    .thenReturn(checkpointBundle);
        }
        when(testSuites.find(
                suiteRef.id(), suiteRef.revision(), identity))
                .thenReturn(storedSuite);
        when(mirrorRuns.execute(any(), any()))
                .thenReturn(MirrorRunSummary.from(bundle));
        when(mirrorRuns.evidence(bundle.evidence().runId(), identity))
                .thenReturn(bundle);

        ScenarioRehearsalRuntimeService service =
                new ScenarioRehearsalRuntimeService(
                        rehearsals, scenarioArtifacts, testSuites,
                        mirrorRuns, evidenceIntegrity,
                        new ScenarioHandlingAssertionEvaluator(mapper),
                        rehearsalIntegrity, rehearsalEvidence,
                        mapper, sessions, runtimeClock);
        return new Fixture(
                service, request, mirrorRuns, bundle, sessions,
                rehearsalIntegrity, rehearsalEvidence);
    }

    private ScenarioPack.RehearsalPolicy policy() {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true, false, false, false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                1, 100,
                Duration.ofSeconds(10), Duration.ofSeconds(30),
                false,
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of(scope.region()));
    }

    private ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                scope.tenantId(), "scenario-rehearsal",
                null, null, null, null, List.of(),
                "support-owner",
                Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2027-03-01T00:00:00Z"), "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            ScenarioRehearsalRuntimeService service,
            ScenarioRehearsalExecutionRequest request,
            MirrorRunIntegrationService mirrorRuns,
            MirrorEvidenceBundle bundle,
            MirrorSessionIntegrationService sessions,
            ScenarioRehearsalEvidenceIntegrityService rehearsalIntegrity,
            ScenarioRehearsalEvidenceRepository rehearsalEvidence) {
    }
}
