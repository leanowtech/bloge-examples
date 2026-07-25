package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalDomainFidelitySourceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);
    private final VisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    DomainFidelityTestFixtures.CLOCK);
    private final ScenarioRehearsalEvidenceIntegrityService
            evidenceIntegrity =
            new ScenarioRehearsalEvidenceIntegrityService(
                    mapper,
                    signer,
                    DomainFidelityTestFixtures.CLOCK);

    @Test
    void independentlyVerifiesAndMapsOnlyProvableDimensions() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioRehearsalRuntimeService runtime =
                runtime(fixture);
        DomainFidelityInventory inventory =
                inventory(List.of(fixture.unit()));
        ScenarioRehearsalDomainFidelitySource source =
                source(runtime, signer);

        List<DomainFidelityProfileProjector.Measurement>
                measurements = source.measurements(
                inventory,
                List.of(fixture.workbook().runId()),
                DomainFidelityTestFixtures
                        .projectorIdentity("org-a"));

        assertThat(measurements).singleElement()
                .satisfies(measurement -> {
                    assertThat(measurement.unitId())
                            .isEqualTo(fixture.unit().unitId());
                    assertThat(measurement.sourceMode())
                            .isEqualTo(
                                    DomainFidelityProfile.SourceMode
                                            .SYNTHESIZED);
                    assertThat(measurement.certifiable()).isTrue();
                    assertThat(measurement.evidenceComplete())
                            .isTrue();
                    assertThat(measurement.results())
                            .extracting(
                                    DomainFidelityProfile
                                            .DimensionResult
                                            ::dimension)
                            .containsExactly(
                                    DomainFidelityProfile.Dimension
                                            .BEHAVIOR,
                                    DomainFidelityProfile.Dimension
                                            .CONTRACT,
                                    DomainFidelityProfile.Dimension
                                            .EFFECT,
                                    DomainFidelityProfile.Dimension
                                            .STATE_TRANSITION);
                });

        DomainFidelityProfile profile =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        measurements,
                        DomainFidelityTestFixtures.policy()
                                .projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);
        DomainFidelityProfile.UnitAssessment assessment =
                profile.unitAssessments().getFirst();
        assertThat(assessment.results())
                .filteredOn(result ->
                        result.dimension()
                        == DomainFidelityProfile.Dimension.OUTCOME)
                .singleElement()
                .extracting(
                        DomainFidelityProfile.DimensionResult::reason)
                .isEqualTo(
                        DomainFidelityProfile.MeasurementReason
                                .OUTCOME_AUTHORITY_UNAVAILABLE);
        assertThat(assessment.results())
                .filteredOn(result ->
                        result.dimension()
                        == DomainFidelityProfile.Dimension
                        .REQUEST_SPACE)
                .singleElement()
                .extracting(
                        DomainFidelityProfile.DimensionResult::reason)
                .isEqualTo(
                        DomainFidelityProfile.MeasurementReason
                                .REQUEST_SPACE_EVIDENCE_UNAVAILABLE);
        assertThat(assessment.results())
                .filteredOn(result ->
                        result.dimension()
                        == DomainFidelityProfile.Dimension
                        .ERROR_DISTRIBUTION)
                .singleElement()
                .extracting(
                        DomainFidelityProfile.DimensionResult::reason)
                .isEqualTo(
                        DomainFidelityProfile.MeasurementReason
                                .DIMENSION_ASSERTION_ABSENT);

        ArgumentCaptor<IntegrationRequestContext> reader =
                ArgumentCaptor.forClass(
                        IntegrationRequestContext.class);
        verify(runtime).evidence(
                eq(fixture.workbook().runId()),
                reader.capture());
        assertThat(reader.getValue().purpose())
                .isEqualTo(
                        "GOVERNANCE_EVIDENCE_INGESTION");
    }

    @Test
    void exploratoryAndIncompleteEvidenceCannotBecomeAssessedPasses() {
        Fixture exploratory = fixture(
                MirrorRunEvidence.EvidenceClass.EXPLORATORY,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioRehearsalDomainFidelitySource exploratorySource =
                source(runtime(exploratory), signer);
        DomainFidelityInventory inventory =
                inventory(List.of(exploratory.unit()));

        DomainFidelityProfileProjector.Measurement measurement =
                exploratorySource.measurements(
                        inventory,
                        List.of(exploratory.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a"))
                        .getFirst();

        assertThat(measurement.certifiable()).isFalse();
        DomainFidelityProfile exploratoryProfile =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        List.of(measurement),
                        DomainFidelityTestFixtures.policy()
                                .projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);
        assertThat(exploratoryProfile.unitAssessments()
                .getFirst().results())
                .allMatch(result ->
                        result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.ABSTAINED
                                && result.reason()
                                == DomainFidelityProfile
                                .MeasurementReason
                                .EVIDENCE_NOT_CERTIFIABLE);

        Fixture incomplete = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.EVIDENCE_INCOMPLETE,
                ScenarioHandlingAssertionResult.Outcome
                        .INDETERMINATE);
        DomainFidelityProfileProjector.Measurement incompleteMeasurement =
                source(runtime(incomplete), signer)
                        .measurements(
                                inventory(List.of(incomplete.unit())),
                                List.of(incomplete.workbook().runId()),
                                DomainFidelityTestFixtures
                                        .projectorIdentity("org-a"))
                        .getFirst();

        assertThat(incompleteMeasurement.certifiable()).isTrue();
        assertThat(incompleteMeasurement.evidenceComplete())
                .isFalse();
    }

    @Test
    void rejectsMissingDuplicateAndForeignInventoryCaseClosure() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioRehearsalDomainFidelitySource source =
                source(runtime(fixture), signer);
        DomainFidelityInventory inventory =
                inventory(DomainFidelityTestFixtures.units());

        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error ->
                        assertThat(((IntegrationProblemException) error)
                                .problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.SCENARIO_SOURCE_INVALID"));
        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(
                                fixture.workbook().runId(),
                                fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() ->
                source.measurements(
                        inventory(List.of(fixture.unit())),
                        List.of(fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-b")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error ->
                        assertThat(((IntegrationProblemException) error)
                                .problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.INVENTORY_NOT_FOUND"));
    }

    @Test
    void rejectsRetentionSignatureAndSignedAggregateDrift() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioRehearsalRetentionEvent proof =
                fixture.workbook().retentionProof();
        ScenarioRehearsalRetentionEvent forgedProof =
                proof.withEvidenceSeal(
                        new VisualRunEvidenceSeal(
                                "",
                                proof.eventFingerprint(),
                                "Ed25519",
                                proof.evidenceSeal().keyId(),
                                proof.evidenceSeal().signedAt(),
                                "Zm9yZ2Vk"));
        ScenarioRehearsalWorkbookSeed forged =
                replaceRetention(
                        fixture.workbook(),
                        forgedProof);
        ScenarioRehearsalRuntimeService forgedRuntime =
                mock(ScenarioRehearsalRuntimeService.class);
        when(forgedRuntime.evidence(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(fixture.bundle());
        when(forgedRuntime.workbookSeed(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(forged);

        assertThatThrownBy(() ->
                source(forgedRuntime, signer).measurements(
                        inventory(List.of(fixture.unit())),
                        List.of(fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention signature");

        Fixture other = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS,
                "different-request");
        ScenarioRehearsalRuntimeService drifted =
                mock(ScenarioRehearsalRuntimeService.class);
        when(drifted.evidence(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(fixture.bundle());
        when(drifted.workbookSeed(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(other.workbook());

        assertThatThrownBy(() ->
                source(drifted, signer).measurements(
                        inventory(List.of(fixture.unit())),
                        List.of(fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed aggregate");
    }

    @Test
    void authorizationAndVerifierOutageFailBeforeSourceReads() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                MirrorRunEvidence.Status.PASSED,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioRehearsalRuntimeService runtime =
                runtime(fixture);
        ScenarioRehearsalDomainFidelitySource unavailable =
                source(runtime, VisualEvidenceSigner.unavailable());
        DomainFidelityInventory inventory =
                inventory(List.of(fixture.unit()));

        assertThatThrownBy(() ->
                unavailable.measurements(
                        inventory,
                        List.of(fixture.workbook().runId()),
                        DomainFidelityTestFixtures
                                .projectorIdentity("org-a")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error ->
                        assertThat(((IntegrationProblemException) error)
                                .problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.SCENARIO_SOURCE_UNAVAILABLE"));
        verify(runtime, never()).evidence(any(), any());

        IntegrationRequestContext unauthorized =
                new IntegrationRequestContext(
                        "tenant-a",
                        "org-a",
                        "refunds",
                        "staging",
                        "sg",
                        "SERVICE",
                        "unknown-adapter",
                        "",
                        DomainFidelityPolicy.PROJECTION_PURPOSE,
                        "correlation",
                        Set.of(),
                        "CONFIDENTIAL",
                        "");
        assertThatThrownBy(() ->
                source(runtime, signer).measurements(
                        inventory,
                        List.of(fixture.workbook().runId()),
                        unauthorized))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error ->
                        assertThat(((IntegrationProblemException) error)
                                .problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.PROJECTOR_FORBIDDEN"));
    }

    private ScenarioRehearsalDomainFidelitySource source(
            ScenarioRehearsalRuntimeService runtime,
            VisualEvidenceSigner exactSigner) {
        return new ScenarioRehearsalDomainFidelitySource(
                runtime,
                new ScenarioRehearsalEvidenceIntegrityService(
                        mapper,
                        exactSigner,
                        DomainFidelityTestFixtures.CLOCK),
                exactSigner,
                DomainFidelityTestFixtures.policy(),
                mapper);
    }

    private ScenarioRehearsalRuntimeService runtime(
            Fixture fixture) {
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        when(runtime.evidence(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(fixture.bundle());
        when(runtime.workbookSeed(
                eq(fixture.workbook().runId()), any()))
                .thenReturn(fixture.workbook());
        return runtime;
    }

    private DomainFidelityInventory inventory(
            List<DomainFidelityInventory.CoverageUnit> units) {
        return DomainFidelityTestFixtures.inventory(
                mapper,
                DomainFidelityTestFixtures.scope("org-a"),
                1,
                units);
    }

    private Fixture fixture(
            MirrorRunEvidence.EvidenceClass evidenceClass,
            MirrorRunEvidence.Status status,
            ScenarioHandlingAssertionResult.Outcome assertionOutcome) {
        return fixture(
                evidenceClass,
                status,
                assertionOutcome,
                "scenario-request");
    }

    private Fixture fixture(
            MirrorRunEvidence.EvidenceClass evidenceClass,
            MirrorRunEvidence.Status status,
            ScenarioHandlingAssertionResult.Outcome assertionOutcome,
            String requestId) {
        Instant started =
                DomainFidelityTestFixtures.NOW
                        .minus(Duration.ofMinutes(10));
        DomainFidelityInventory.CoverageUnit unit =
                new DomainFidelityInventory.CoverageUnit(
                        "refund-golden",
                        DomainFidelityTestFixtures.ref(
                                "SCENARIO_CASE",
                                "refund-golden",
                                'b'),
                        DomainFidelityTestFixtures.ref(
                                "CAPABILITY",
                                "refund",
                                'c'),
                        ScenarioCase.CaseType.GOLDEN,
                        List.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                DomainFidelityProfile.Dimension.CONTRACT,
                                DomainFidelityProfile.Dimension.EFFECT,
                                DomainFidelityProfile.Dimension
                                        .ERROR_DISTRIBUTION,
                                DomainFidelityProfile.Dimension.OUTCOME,
                                DomainFidelityProfile.Dimension
                                        .REQUEST_SPACE,
                                DomainFidelityProfile.Dimension
                                        .STATE_TRANSITION));
        List<CaseHandlingAssertion.Observation> observations =
                List.of(
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        CaseHandlingAssertion.Observation
                                .GRAPH_OUTPUT_SCHEMA,
                        CaseHandlingAssertion.Observation
                                .SIDE_EFFECT_RECEIPT,
                        CaseHandlingAssertion.Observation
                                .STATE_TRANSITION);
        List<MirrorArtifactRef> assertionRefs =
                new ArrayList<>();
        for (int index = 0;
             index < observations.size();
             index++) {
            assertionRefs.add(
                    DomainFidelityTestFixtures.ref(
                            "CASE_HANDLING_ASSERTION",
                            "assertion-" + index,
                            (char) ('a' + index)));
        }
        CompiledScenarioRehearsalPlan plan =
                CompiledScenarioRehearsalPlanIntegrity.seal(
                        mapper,
                        new CompiledScenarioRehearsalPlan(
                                "",
                                "refund-scenario@compiled",
                                1,
                                "",
                                DomainFidelityTestFixtures.scope(
                                        "org-a"),
                                DomainFidelityTestFixtures.ref(
                                        "SCENARIO_PACK",
                                        "refund-scenarios",
                                        'd'),
                                unit.targetCapabilityRef(),
                                List.of(
                                        new CompiledScenarioRehearsalPlan
                                                .CaseBinding(
                                                unit.scenarioCaseRef(),
                                                unit.caseType(),
                                                DomainFidelityTestFixtures
                                                        .ref(
                                                        "TEST_SUITE",
                                                        "refund-suite",
                                                        'e'),
                                                "refund-golden",
                                                DomainFidelityTestFixtures
                                                        .ref(
                                                        "MIRROR_PLAN",
                                                        "refund-plan",
                                                        'f'),
                                                DomainFidelityTestFixtures
                                                        .ref(
                                                        "FIXTURE_BUNDLE",
                                                        "refund-fixture",
                                                        'a'),
                                                null,
                                                new MirrorPlan
                                                        .ExecutionServices(
                                                        started,
                                                        42,
                                                        null,
                                                        null),
                                                assertionRefs)),
                                assertionRefs,
                                rehearsalPolicy()));
        String childRunId = requestId + ":child";
        String childEvidence =
                "sha256:" + "8".repeat(64);
        List<ScenarioHandlingAssertionResult> assertions =
                new ArrayList<>();
        for (int index = 0;
             index < observations.size();
             index++) {
            ScenarioHandlingAssertionResult.ReasonCode reason =
                    switch (assertionOutcome) {
                        case PASS -> ScenarioHandlingAssertionResult
                                .ReasonCode.ASSERTION_MATCHED;
                        case FAIL -> ScenarioHandlingAssertionResult
                                .ReasonCode.ASSERTION_MISMATCH;
                        case INDETERMINATE ->
                                ScenarioHandlingAssertionResult
                                .ReasonCode
                                .ASSERTION_EVIDENCE_INCOMPLETE;
                    };
            assertions.add(
                    ScenarioHandlingAssertionResultIntegrity.seal(
                            mapper,
                            new ScenarioHandlingAssertionResult(
                                    "",
                                    "",
                                    childRunId,
                                    childEvidence,
                                    plan.cases().getFirst()
                                            .mirrorPlanRef()
                                            .fingerprint(),
                                    assertionRefs.get(index),
                                    observations.get(index),
                                    assertionOutcome,
                                    CaseHandlingAssertion.Severity
                                            .BLOCKER,
                                    "RG.MIRROR.FIDELITY.ASSERTION_"
                                            + index,
                                    reason,
                                    ScenarioHandlingAssertionResult
                                            .ObservedFacts.empty())));
        }
        ScenarioCaseRehearsalResult.Outcome caseOutcome =
                ScenarioCaseRehearsalResult.deriveOutcome(
                        status, assertions);
        ScenarioCaseRehearsalResult caseResult =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        new ScenarioCaseRehearsalResult(
                                "",
                                "",
                                0,
                                unit.scenarioCaseRef(),
                                unit.caseType(),
                                plan.cases().getFirst()
                                        .testSuiteRef(),
                                "refund-golden",
                                plan.cases().getFirst()
                                        .mirrorPlanRef(),
                                plan.cases().getFirst()
                                        .fixtureBundleRef(),
                                null,
                                requestId + ":case:000",
                                caseOutcome,
                                childRunId,
                                childEvidence,
                                status,
                                evidenceClass,
                                assertions,
                                caseOutcome
                                        == ScenarioCaseRehearsalResult
                                        .Outcome.PASS
                                        ? ""
                                        : "FIDELITY_ASSERTION_NOT_PROVEN",
                                started,
                                started.plusSeconds(1)));
        List<ScenarioCaseRehearsalResult> cases =
                List.of(caseResult);
        ScenarioRehearsalResult result =
                ScenarioRehearsalResultIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalResult(
                                "",
                                "",
                                requestId,
                                CompiledScenarioRehearsalPlanIntegrity
                                        .reference(plan),
                                DomainFidelityTestFixtures.scope(
                                        "org-a"),
                                unit.targetCapabilityRef(),
                                ScenarioRehearsalResult
                                        .deriveOutcome(cases),
                                cases,
                                ScenarioRehearsalResult.Summary.from(
                                        cases),
                                started,
                                started.plusSeconds(2)));
        String runId = ScenarioRehearsalRunIdentity.derive(
                mapper,
                result.scope(),
                result.requestId());
        ScenarioRehearsalEvidenceBundle bundle =
                evidenceIntegrity.seal(runId, result)
                        .bundle();
        ScenarioRehearsalRetentionEvent registration =
                registration(bundle);
        ScenarioRehearsalRetentionState retention =
                new ScenarioRehearsalRetentionState(
                        "",
                        result.scope(),
                        runId,
                        requestId,
                        bundle.bundleFingerprint(),
                        ScenarioRehearsalRetentionState.Status.RETAINED,
                        1,
                        registration.retainUntil(),
                        List.of(),
                        registration.occurredAt(),
                        registration);
        ScenarioRehearsalWorkbookSeed workbook =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        plan,
                        bundle,
                        retention,
                        List.of(registration));
        return new Fixture(
                unit, bundle, workbook);
    }

    private ScenarioRehearsalRetentionEvent registration(
            ScenarioRehearsalEvidenceBundle bundle) {
        ScenarioRehearsalRetentionEvent material =
                new ScenarioRehearsalRetentionEvent(
                        "",
                        "retention-" + bundle.attestation().runId(),
                        "register-" + bundle.result().requestId(),
                        bundle.result().scope(),
                        bundle.result().requestId(),
                        bundle.attestation().runId(),
                        1,
                        ScenarioRehearsalRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        DomainFidelityTestFixtures.NOW
                                .plus(Duration.ofDays(30)),
                        DomainFidelityTestFixtures.NOW,
                        "scenario-runtime",
                        "RG.MIRROR.RETENTION_REGISTERED",
                        "",
                        bundle.bundleFingerprint(),
                        "",
                        0,
                        ScenarioRehearsalRetentionEvent
                                .ChildEvidenceDisposition
                                .NOT_APPLICABLE,
                        VisualRunEvidenceSeal.unsigned());
        return material.withEvidenceSeal(
                signer.seal(material.eventFingerprint()));
    }

    private ScenarioRehearsalWorkbookSeed replaceRetention(
            ScenarioRehearsalWorkbookSeed workbook,
            ScenarioRehearsalRetentionEvent retention) {
        ScenarioRehearsalWorkbookSeed material =
                new ScenarioRehearsalWorkbookSeed(
                        workbook.schemaVersion(),
                        "",
                        workbook.scope(),
                        workbook.runId(),
                        workbook.requestId(),
                        workbook.scenarioPackRef(),
                        workbook.compiledPlanRef(),
                        workbook.targetCapabilityRef(),
                        workbook.evidenceBundleFingerprint(),
                        workbook.resultFingerprint(),
                        workbook.evidenceKeyId(),
                        retention,
                        workbook.outcome(),
                        workbook.summary(),
                        workbook.cases(),
                        workbook.gateReady(),
                        workbook.blockers());
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper,
                        material,
                        ScenarioRehearsalWorkbookSeed
                                .MAXIMUM_CANONICAL_BYTES));
    }

    private static ScenarioPack.RehearsalPolicy
    rehearsalPolicy() {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true,
                false,
                false,
                false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                10,
                100,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                true,
                CapabilityContract.DataClassification
                        .CONFIDENTIAL,
                List.of("sg"));
    }

    private record Fixture(
            DomainFidelityInventory.CoverageUnit unit,
            ScenarioRehearsalEvidenceBundle bundle,
            ScenarioRehearsalWorkbookSeed workbook
    ) {
    }
}
