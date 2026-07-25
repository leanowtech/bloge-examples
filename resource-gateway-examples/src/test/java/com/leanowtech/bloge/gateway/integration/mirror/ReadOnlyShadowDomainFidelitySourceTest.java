package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowDomainFidelitySourceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void signsReverifiesAndMapsOnlyTypedPerRequestDimensions() {
        DomainFidelityInventory inventory =
                inventory();
        ReadOnlyShadowComparisonIntegrity integrity =
                integrity();
        ReadOnlyShadowComparison signed =
                integrity.sign(
                        comparison(
                                inventory,
                                inventory.units().getFirst(),
                                List.of(
                                        match(
                                                DomainFidelityProfile
                                                        .Dimension
                                                        .BEHAVIOR,
                                                '1'),
                                        mismatch(
                                                DomainFidelityProfile
                                                        .Dimension
                                                        .CONTRACT,
                                                '2',
                                                '3',
                                                ReadOnlyShadowComparison
                                                        .DiffType
                                                        .OUTPUT_SCHEMA)),
                                true,
                                true));
        ReadOnlyShadowDomainFidelitySource source =
                new ReadOnlyShadowDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        List<DomainFidelityProfileProjector.Measurement>
                measurements = source.measurements(
                inventory,
                List.of(signed),
                DomainFidelityTestFixtures
                        .projectorIdentity("support"));

        assertThat(measurements).hasSize(1);
        assertThat(measurements.getFirst().sourceMode())
                .isEqualTo(
                        DomainFidelityProfile.SourceMode.RECORDED);
        assertThat(measurements.getFirst().certifiable())
                .isTrue();
        assertThat(measurements.getFirst().evidenceComplete())
                .isTrue();
        assertThat(measurements.getFirst().results())
                .extracting(
                        DomainFidelityProfile
                                .DimensionResult::outcome)
                .containsExactly(
                        DomainFidelityProfile
                                .MeasurementOutcome.PASS,
                        DomainFidelityProfile
                                .MeasurementOutcome.FAIL);

        DomainFidelityProfile profile =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        measurements,
                        DomainFidelityTestFixtures
                                .policy().projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);
        assertThat(profile.denominator().totalUnits())
                .isEqualTo(2);
        assertThat(profile.unitAssessments().getFirst()
                .sourceRef().kind())
                .isEqualTo(
                        ReadOnlyShadowComparison.ARTIFACT_KIND);
        assertThat(profile.unitAssessments().get(1)
                .results())
                .allMatch(result ->
                        result.outcome()
                                == DomainFidelityProfile
                                .MeasurementOutcome.MISSING);
    }

    @Test
    void incompleteOrExploratorySourcesCannotBecomeAssessedPasses() {
        DomainFidelityInventory inventory =
                inventory();
        ReadOnlyShadowComparisonIntegrity integrity =
                integrity();
        ReadOnlyShadowComparison signed =
                integrity.sign(
                        comparison(
                                inventory,
                                inventory.units().getFirst(),
                                List.of(
                                        indeterminate(
                                                DomainFidelityProfile
                                                        .Dimension
                                                        .BEHAVIOR),
                                        match(
                                                DomainFidelityProfile
                                                        .Dimension
                                                        .CONTRACT,
                                                '2')),
                                false,
                                false));
        ReadOnlyShadowDomainFidelitySource source =
                new ReadOnlyShadowDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        DomainFidelityProfileProjector.Measurement measurement =
                source.measurements(
                        inventory,
                        List.of(signed),
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

        assertThat(measurement.certifiable()).isFalse();
        assertThat(measurement.evidenceComplete()).isFalse();
        assertThat(profile.unitAssessments().getFirst()
                .results())
                .allMatch(result ->
                        result.outcome()
                                == DomainFidelityProfile
                                .MeasurementOutcome.ABSTAINED);
    }

    @Test
    void rejectsWriteCapabilityPairingDriftAndForgedDerivation() {
        DomainFidelityInventory inventory =
                inventory();
        DomainFidelityInventory.CoverageUnit unit =
                inventory.units().getFirst();
        ReadOnlyShadowComparison valid =
                comparison(
                        inventory,
                        unit,
                        List.of(
                                match(
                                        DomainFidelityProfile
                                                .Dimension
                                                .BEHAVIOR,
                                        '1')),
                        true,
                        true);

        assertThatThrownBy(() ->
                new ReadOnlyShadowComparison.AccessProof(
                        ReadOnlyShadowComparison
                                .AccessMode.READ_ONLY,
                        ref(
                                "SHADOW_SAMPLING_GRANT",
                                "grant",
                                '1'),
                        ref(
                                "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION",
                                "egress",
                                '2'),
                        ref(
                                "SHADOW_KILL_SWITCH_STATE",
                                "kill-switch",
                                '3'),
                        1,
                        100,
                        true,
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-write");

        ReadOnlyShadowComparison.SourceObservation drifted =
                observation(
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        unit.targetCapabilityRef(),
                        '9',
                        true,
                        true);
        assertThatThrownBy(() ->
                copy(valid, valid.accessProof(),
                        valid.baseline(), drifted,
                        valid.results()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request closure");

        assertThatThrownBy(() ->
                new ReadOnlyShadowComparison
                        .DimensionComparison(
                        DomainFidelityProfile
                                .Dimension.BEHAVIOR,
                        fingerprint('4'),
                        fingerprint('5'),
                        ReadOnlyShadowComparison
                                .DiffOutcome.MATCH,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not derived");
    }

    @Test
    void rejectsForgedSealDuplicateUnitsAndCrossInventoryDrift() {
        DomainFidelityInventory inventory =
                inventory();
        ReadOnlyShadowComparisonIntegrity integrity =
                integrity();
        ReadOnlyShadowComparison signed =
                integrity.sign(
                        comparison(
                                inventory,
                                inventory.units().getFirst(),
                                List.of(
                                        match(
                                                DomainFidelityProfile
                                                        .Dimension
                                                        .BEHAVIOR,
                                                '1')),
                                true,
                                true));
        ReadOnlyShadowDomainFidelitySource source =
                new ReadOnlyShadowDomainFidelitySource(
                        integrity,
                        DomainFidelityTestFixtures.policy());

        assertProblem(
                () -> source.measurements(
                        inventory,
                        List.of(signed, signed),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")),
                "RG.MIRROR.FIDELITY.SHADOW_SOURCE_INVALID");

        ReadOnlyShadowComparison drifted =
                new ReadOnlyShadowComparison(
                        signed.schemaVersion(),
                        signed.comparisonId(),
                        signed.revision(),
                        signed.comparisonFingerprint(),
                        signed.scope(),
                        ref(
                                DomainFidelityInventory
                                        .ARTIFACT_KIND,
                                "other-inventory",
                                'e'),
                        signed.unitId(),
                        signed.scenarioCaseRef(),
                        signed.targetCapabilityRef(),
                        signed.accessProof(),
                        signed.baseline(),
                        signed.candidate(),
                        signed.observedAt(),
                        signed.results(),
                        signed.comparisonSeal());
        assertProblem(
                () -> source.measurements(
                        inventory,
                        List.of(drifted),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")),
                "RG.MIRROR.FIDELITY.SHADOW_SOURCE_INVALID");
    }

    @Test
    void unavailableVerifierFailsClosedBeforeProjection() {
        VisualEvidenceSigner unavailable =
                VisualEvidenceSigner.unavailable();
        ReadOnlyShadowDomainFidelitySource source =
                new ReadOnlyShadowDomainFidelitySource(
                        new ReadOnlyShadowComparisonIntegrity(
                                mapper,
                                unavailable,
                                DomainFidelityTestFixtures.CLOCK),
                        DomainFidelityTestFixtures.policy());

        assertThat(source.ready()).isFalse();
        assertProblem(
                () -> source.measurements(
                        inventory(),
                        List.of(),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")),
                "RG.MIRROR.FIDELITY.SHADOW_SOURCE_UNAVAILABLE");
    }

    private DomainFidelityInventory inventory() {
        return DomainFidelityTestFixtures.inventory(
                mapper,
                DomainFidelityTestFixtures.scope("support"),
                1,
                DomainFidelityTestFixtures.units());
    }

    private ReadOnlyShadowComparisonIntegrity integrity() {
        return new ReadOnlyShadowComparisonIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK),
                DomainFidelityTestFixtures.CLOCK);
    }

    private static ReadOnlyShadowComparison comparison(
            DomainFidelityInventory inventory,
            DomainFidelityInventory.CoverageUnit unit,
            List<ReadOnlyShadowComparison.DimensionComparison>
                    results,
            boolean certifiable,
            boolean complete) {
        return new ReadOnlyShadowComparison(
                ReadOnlyShadowComparison.SCHEMA_VERSION,
                "shadow-" + unit.unitId(),
                1,
                "",
                inventory.scope(),
                inventory.artifactRef(),
                unit.unitId(),
                unit.scenarioCaseRef(),
                unit.targetCapabilityRef(),
                new ReadOnlyShadowComparison.AccessProof(
                        ReadOnlyShadowComparison
                                .AccessMode.READ_ONLY,
                        ref(
                                "SHADOW_SAMPLING_GRANT",
                                "grant",
                                '1'),
                        ref(
                                "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION",
                                "egress",
                                '2'),
                        ref(
                                "SHADOW_KILL_SWITCH_STATE",
                                "kill-switch",
                                '3'),
                        1,
                        100,
                        false,
                        0),
                observation(
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        unit.targetCapabilityRef(),
                        '4',
                        certifiable,
                        complete),
                observation(
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        unit.targetCapabilityRef(),
                        '4',
                        certifiable,
                        complete),
                DomainFidelityTestFixtures.NOW.minus(
                        Duration.ofMinutes(1)),
                results,
                null);
    }

    private static ReadOnlyShadowComparison.SourceObservation
    observation(
            ReadOnlyShadowComparison.SourceRole role,
            MirrorArtifactRef capability,
            char requestMaterial,
            boolean certifiable,
            boolean complete) {
        return new ReadOnlyShadowComparison.SourceObservation(
                role,
                ref(
                        role
                                == ReadOnlyShadowComparison
                                .SourceRole.BASELINE
                                ? "SHADOW_BASELINE_OBSERVATION"
                                : "MIRROR_EVIDENCE_BUNDLE",
                        role.name().toLowerCase(),
                        role
                                == ReadOnlyShadowComparison
                                .SourceRole.BASELINE
                                ? '5' : '6'),
                DomainFidelityTestFixtures.scope("support"),
                capability,
                fingerprint(requestMaterial),
                fingerprint('7'),
                DomainFidelityTestFixtures.NOW.minus(
                        Duration.ofMinutes(2)),
                certifiable
                        ? MirrorRunEvidence.EvidenceClass
                        .CERTIFIABLE
                        : MirrorRunEvidence.EvidenceClass
                        .EXPLORATORY,
                complete);
    }

    private static ReadOnlyShadowComparison.DimensionComparison
    match(
            DomainFidelityProfile.Dimension dimension,
            char material) {
        return new ReadOnlyShadowComparison.DimensionComparison(
                dimension,
                fingerprint(material),
                fingerprint(material),
                ReadOnlyShadowComparison.DiffOutcome.MATCH,
                List.of());
    }

    private static ReadOnlyShadowComparison.DimensionComparison
    mismatch(
            DomainFidelityProfile.Dimension dimension,
            char baseline,
            char candidate,
            ReadOnlyShadowComparison.DiffType type) {
        return new ReadOnlyShadowComparison.DimensionComparison(
                dimension,
                fingerprint(baseline),
                fingerprint(candidate),
                ReadOnlyShadowComparison.DiffOutcome.MISMATCH,
                List.of(type));
    }

    private static ReadOnlyShadowComparison.DimensionComparison
    indeterminate(
            DomainFidelityProfile.Dimension dimension) {
        return new ReadOnlyShadowComparison.DimensionComparison(
                dimension,
                "",
                fingerprint('8'),
                ReadOnlyShadowComparison
                        .DiffOutcome.INDETERMINATE,
                List.of(
                        ReadOnlyShadowComparison
                                .DiffType.EVIDENCE_GAP));
    }

    private static ReadOnlyShadowComparison copy(
            ReadOnlyShadowComparison source,
            ReadOnlyShadowComparison.AccessProof access,
            ReadOnlyShadowComparison.SourceObservation baseline,
            ReadOnlyShadowComparison.SourceObservation candidate,
            List<ReadOnlyShadowComparison.DimensionComparison>
                    results) {
        return new ReadOnlyShadowComparison(
                source.schemaVersion(),
                source.comparisonId(),
                source.revision(),
                source.comparisonFingerprint(),
                source.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                source.targetCapabilityRef(),
                access,
                baseline,
                candidate,
                source.observedAt(),
                results,
                source.comparisonSeal());
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char material) {
        return DomainFidelityTestFixtures.ref(
                kind, id, material);
    }

    private static String fingerprint(char material) {
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }

    private static void assertProblem(
            Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(failure ->
                        ((IntegrationProblemException) failure)
                                .problem().code())
                .isEqualTo(code);
    }
}
