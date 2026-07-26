package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeDomainFidelitySourceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void mapsIndependentlyVerifiedMatchIntoAuthoritativeOutcomePass() {
        DomainFidelityInventory inventory = inventory();
        AuthoritativeOutcomeObservationIntegrity integrity =
                integrity();
        AuthoritativeOutcomeObservation signed =
                integrity.sign(
                        observation(
                                inventory,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.MATCH,
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .fact(
                                                        "settlement-ledger",
                                                        "settlement-001",
                                                        'a',
                                                        'c',
                                                        true)),
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "settlement-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT))));
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        DomainFidelityProfileProjector.Measurement measurement =
                source.measurements(
                        inventory,
                        List.of(signed),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support"))
                        .getFirst();

        assertThat(measurement.sourceMode())
                .isEqualTo(
                        DomainFidelityProfile.SourceMode
                                .AUTHORITATIVE);
        assertThat(measurement.certifiable()).isTrue();
        assertThat(measurement.evidenceComplete()).isTrue();
        assertThat(measurement.results())
                .containsExactly(
                        new DomainFidelityProfile.DimensionResult(
                                DomainFidelityProfile.Dimension
                                        .OUTCOME,
                                DomainFidelityProfile
                                        .MeasurementOutcome.PASS,
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .ASSERTIONS_PASSED));
    }

    @Test
    void preservesPendingCensoredAndConflictAsDistinctAbstentionDebt() {
        DomainFidelityInventory inventory = inventory();
        AuthoritativeOutcomeObservationIntegrity integrity =
                integrity();
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        assertReason(
                source,
                inventory,
                integrity.sign(
                        observation(
                                inventory,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.PENDING,
                                List.of(),
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "settlement-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT
                                                                .minusSeconds(1))))),
                DomainFidelityProfile.MeasurementReason
                        .OUTCOME_PENDING);
        assertReason(
                source,
                inventory,
                integrity.sign(
                        observation(
                                inventory,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "settlement-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT)))),
                DomainFidelityProfile.MeasurementReason
                        .OUTCOME_CENSORED);
        assertReason(
                source,
                inventory,
                integrity.sign(
                        observation(
                                inventory,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CONFLICT,
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .fact(
                                                        "complaint-ledger",
                                                        "complaint-001",
                                                        'b',
                                                        'c',
                                                        true),
                                        AuthoritativeOutcomeTestFixtures
                                                .fact(
                                                        "settlement-ledger",
                                                        "settlement-001",
                                                        'a',
                                                        'd',
                                                        true)),
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "complaint-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT),
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "settlement-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT)))),
                DomainFidelityProfile.MeasurementReason
                        .OUTCOME_CONFLICTING);
    }

    @Test
    void rejectsDuplicateUnitsAndCrossInventoryDrift() {
        DomainFidelityInventory inventory = inventory();
        AuthoritativeOutcomeObservationIntegrity integrity =
                integrity();
        AuthoritativeOutcomeObservation signed =
                integrity.sign(
                        observation(
                                inventory,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                List.of(
                                        AuthoritativeOutcomeTestFixtures
                                                .watermark(
                                                        "settlement-ledger",
                                                        AuthoritativeOutcomeTestFixtures
                                                                .WINDOW_CLOSES_AT))));
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(signed, signed),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")))
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(
                        failure -> ((IntegrationProblemException)
                                failure).problem().code())
                .isEqualTo(
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_INVALID");
    }

    @Test
    void rejectsCohortMembersSelectedAtDifferentCuts() {
        DomainFidelityInventory inventory =
                inventoryWithTwoOutcomeUnits();
        AuthoritativeOutcomeObservationIntegrity integrity =
                integrity();
        AuthoritativeOutcomeObservation.SelectionProof firstProof =
                AuthoritativeOutcomeTestFixtures.matched()
                        .selectionProof();
        AuthoritativeOutcomeObservation.SelectionProof laterProof =
                new AuthoritativeOutcomeObservation.SelectionProof(
                        firstProof.cohortRef(),
                        firstProof.samplingFrameRef(),
                        firstProof.stratumId(),
                        AuthoritativeOutcomeTestFixtures
                                .fingerprint('c'),
                        firstProof.selectedAt().plusSeconds(1),
                        firstProof.eligiblePopulationSize(),
                        firstProof.selectedPopulationSize(),
                        firstProof.sampleOrdinal() + 1,
                        firstProof.selectionMode());
        List<AuthoritativeOutcomeObservation
                .AuthorityWatermark> watermarks =
                List.of(
                        AuthoritativeOutcomeTestFixtures.watermark(
                                "settlement-ledger",
                                AuthoritativeOutcomeTestFixtures
                                        .WINDOW_CLOSES_AT));
        AuthoritativeOutcomeObservation first =
                integrity.sign(
                        observation(
                                inventory,
                                0,
                                "outcome-refund-boundary",
                                firstProof,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                watermarks));
        AuthoritativeOutcomeObservation later =
                integrity.sign(
                        observation(
                                inventory,
                                1,
                                "outcome-refund-escalation",
                                laterProof,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                watermarks));
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(first, later),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")))
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(
                        failure -> ((IntegrationProblemException)
                                failure).problem().code())
                .isEqualTo(
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_INVALID");
    }

    @Test
    void rejectsObservationIdentityEquivocationAcrossUnits() {
        DomainFidelityInventory inventory =
                inventoryWithTwoOutcomeUnits();
        AuthoritativeOutcomeObservationIntegrity integrity =
                integrity();
        AuthoritativeOutcomeObservation.SelectionProof firstProof =
                AuthoritativeOutcomeTestFixtures.matched()
                        .selectionProof();
        AuthoritativeOutcomeObservation.SelectionProof secondProof =
                new AuthoritativeOutcomeObservation.SelectionProof(
                        firstProof.cohortRef(),
                        firstProof.samplingFrameRef(),
                        firstProof.stratumId(),
                        AuthoritativeOutcomeTestFixtures
                                .fingerprint('c'),
                        firstProof.selectedAt(),
                        firstProof.eligiblePopulationSize(),
                        firstProof.selectedPopulationSize(),
                        firstProof.sampleOrdinal() + 1,
                        firstProof.selectionMode());
        List<AuthoritativeOutcomeObservation
                .AuthorityWatermark> watermarks =
                List.of(
                        AuthoritativeOutcomeTestFixtures.watermark(
                                "settlement-ledger",
                                AuthoritativeOutcomeTestFixtures
                                        .WINDOW_CLOSES_AT));
        AuthoritativeOutcomeObservation first =
                integrity.sign(
                        observation(
                                inventory,
                                0,
                                "outcome-reused",
                                firstProof,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                watermarks));
        AuthoritativeOutcomeObservation second =
                integrity.sign(
                        observation(
                                inventory,
                                1,
                                "outcome-reused",
                                secondProof,
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.CENSORED,
                                List.of(),
                                watermarks));
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(first, second),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")))
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(
                        failure -> ((IntegrationProblemException)
                                failure).problem().code())
                .isEqualTo(
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_INVALID");
    }

    @Test
    void reportsAuthorityOutageDuringVerificationAsRetryableUnavailability() {
        DomainFidelityInventory inventory = inventory();
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservation signed =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        authorityAlwaysAvailable(),
                        DomainFidelityTestFixtures.CLOCK)
                        .sign(
                                observation(
                                        inventory,
                                        AuthoritativeOutcomeObservation
                                                .Reconciliation.CENSORED,
                                        List.of(),
                                        List.of(
                                                AuthoritativeOutcomeTestFixtures
                                                        .watermark(
                                                                "settlement-ledger",
                                                                AuthoritativeOutcomeTestFixtures
                                                                        .WINDOW_CLOSES_AT))));
        AtomicInteger availabilityChecks =
                new AtomicInteger();
        AuthoritativeOutcomeObservationIntegrity unstable =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        new AuthoritativeOutcomeAuthorityVerifier() {
                            @Override
                            public boolean available() {
                                return availabilityChecks
                                        .getAndIncrement() == 0;
                            }

                            @Override
                            public void verify(
                                    AuthoritativeOutcomeObservation
                                            observation) {
                            }
                        },
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeDomainFidelitySource source =
                new AuthoritativeOutcomeDomainFidelitySource(
                        unstable,
                        DomainFidelityTestFixtures.policy());

        assertThatThrownBy(() ->
                source.measurements(
                        inventory,
                        List.of(signed),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")))
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(
                        failure -> ((IntegrationProblemException)
                                failure).problem().code())
                .isEqualTo(
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_UNAVAILABLE");
    }

    private void assertReason(
            AuthoritativeOutcomeDomainFidelitySource source,
            DomainFidelityInventory inventory,
            AuthoritativeOutcomeObservation observation,
            DomainFidelityProfile.MeasurementReason reason) {
        DomainFidelityProfileProjector.Measurement measurement =
                source.measurements(
                        inventory,
                        List.of(observation),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support"))
                        .getFirst();
        DomainFidelityProfile profile =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        List.of(measurement),
                        DomainFidelityTestFixtures
                                .policy().projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);

        DomainFidelityProfile.DimensionResult outcome =
                profile.unitAssessments().getFirst()
                        .results().stream()
                        .filter(result -> result.dimension()
                                == DomainFidelityProfile
                                .Dimension.OUTCOME)
                        .findFirst()
                        .orElseThrow();
        assertThat(outcome.outcome())
                .isEqualTo(
                        DomainFidelityProfile
                                .MeasurementOutcome.ABSTAINED);
        assertThat(outcome.reason()).isEqualTo(reason);
    }

    private DomainFidelityInventory inventory() {
        return DomainFidelityTestFixtures.inventory(
                mapper,
                DomainFidelityTestFixtures.scope("support"),
                1,
                List.of(coverageUnit(
                        "refund-boundary",
                        "refund",
                        '2',
                        '3')));
    }

    private DomainFidelityInventory
    inventoryWithTwoOutcomeUnits() {
        return DomainFidelityTestFixtures.inventory(
                mapper,
                DomainFidelityTestFixtures.scope("support"),
                1,
                List.of(
                        coverageUnit(
                                "refund-boundary",
                                "refund",
                                '2',
                                '3'),
                        coverageUnit(
                                "refund-escalation",
                                "escalate-refund",
                                '4',
                                '5')));
    }

    private DomainFidelityInventory.CoverageUnit coverageUnit(
            String unitId,
            String capabilityId,
            char scenarioMaterial,
            char capabilityMaterial) {
        return new DomainFidelityInventory.CoverageUnit(
                unitId,
                AuthoritativeOutcomeTestFixtures.ref(
                        "SCENARIO_CASE",
                        unitId,
                        scenarioMaterial),
                AuthoritativeOutcomeTestFixtures.ref(
                        "CAPABILITY",
                        capabilityId,
                        capabilityMaterial),
                ScenarioCase.CaseType.BOUNDARY,
                List.of(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT,
                        DomainFidelityProfile.Dimension.OUTCOME));
    }

    private AuthoritativeOutcomeObservation observation(
            DomainFidelityInventory inventory,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks) {
        AuthoritativeOutcomeObservation base =
                AuthoritativeOutcomeTestFixtures.observation(
                        reconciliation, facts, watermarks);
        return observation(
                inventory,
                0,
                base.observationId(),
                base.selectionProof(),
                reconciliation,
                facts,
                watermarks);
    }

    private AuthoritativeOutcomeObservation observation(
            DomainFidelityInventory inventory,
            int unitIndex,
            String observationId,
            AuthoritativeOutcomeObservation.SelectionProof
                    selectionProof,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks) {
        AuthoritativeOutcomeObservation base =
                AuthoritativeOutcomeTestFixtures.observation(
                        reconciliation, facts, watermarks);
        DomainFidelityInventory.CoverageUnit unit =
                inventory.units().get(unitIndex);
        return new AuthoritativeOutcomeObservation(
                base.schemaVersion(),
                observationId,
                base.revision(),
                "",
                inventory.scope(),
                inventory.artifactRef(),
                unit.unitId(),
                unit.scenarioCaseRef(),
                unit.targetCapabilityRef(),
                base.outcomeDefinitionRef(),
                base.attributionPolicyRef(),
                base.authoritySetRef(),
                selectionProof,
                base.subjectFingerprint(),
                base.attributionKeyFingerprint(),
                base.modelOutcomeFingerprint(),
                base.attributionWindow(),
                base.reconciledAt(),
                base.attestedAt(),
                base.authorityWatermarks(),
                base.authorityFacts(),
                base.reconciliation(),
                base.evidenceComplete(),
                base.observationSeal());
    }

    private AuthoritativeOutcomeObservationIntegrity integrity() {
        return new AuthoritativeOutcomeObservationIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK),
                authorityAlwaysAvailable(),
                DomainFidelityTestFixtures.CLOCK);
    }

    private static AuthoritativeOutcomeAuthorityVerifier
    authorityAlwaysAvailable() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
            }
        };
    }
}
