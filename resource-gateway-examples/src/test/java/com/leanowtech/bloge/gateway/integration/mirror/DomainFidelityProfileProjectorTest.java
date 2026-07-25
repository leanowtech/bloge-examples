package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainFidelityProfileProjectorTest {
    private static final Instant APPROVED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant MEASURED_AT =
            Instant.parse("2026-07-25T00:00:00Z");
    private static final Duration FRESHNESS =
            Duration.ofDays(30);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsOwnerApprovedInventoryAndRejectsChangedDenominator() {
        DomainFidelityInventory inventory = inventory(
                List.of(unit(
                        "refund-golden",
                        'a',
                        ScenarioCase.CaseType.GOLDEN,
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT)));

        assertThat(inventory.fingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(inventory.artifactRef().kind())
                .isEqualTo("DOMAIN_FIDELITY_INVENTORY");
        inventory.verify(mapper);

        DomainFidelityInventory changed =
                new DomainFidelityInventory(
                        inventory.schemaVersion(),
                        inventory.inventoryId(),
                        inventory.revision(),
                        inventory.fingerprint(),
                        inventory.scope(),
                        inventory.domainId(),
                        inventory.taxonomyRef(),
                        List.of(unit(
                                "refund-golden",
                                'b',
                                ScenarioCase.CaseType.GOLDEN,
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                DomainFidelityProfile.Dimension.CONTRACT)),
                        inventory.provenance(),
                        inventory.lifecycle(),
                        inventory.effectiveAt(),
                        inventory.expiresAt());

        assertThatThrownBy(() -> changed.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void neverRepairsMissingUnitOrLowSampleFromPerfectPasses() {
        DomainFidelityInventory inventory = inventory(
                List.of(
                        unit(
                                "refund-golden",
                                'a',
                                ScenarioCase.CaseType.GOLDEN,
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                DomainFidelityProfile.Dimension.CONTRACT),
                        unit(
                                "refund-state",
                                'b',
                                ScenarioCase.CaseType.STATE_TRANSITION,
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                DomainFidelityProfile.Dimension.CONTRACT,
                                DomainFidelityProfile.Dimension.STATE_TRANSITION)));
        DomainFidelityProfile profile = project(
                inventory,
                List.of(pass(
                        inventory.units().getFirst(),
                        "workbook-001",
                        MEASURED_AT.minus(Duration.ofDays(1)),
                        DomainFidelityProfile.SourceMode.RECORDED)),
                2);

        assertThat(profile.denominator().totalUnits())
                .isEqualTo(2);
        assertThat(profile.denominator().totalObligations())
                .isEqualTo(5);
        assertThat(profile.unitAssessments().get(1).results())
                .allSatisfy(result -> {
                    assertThat(result.outcome())
                            .isEqualTo(
                                    DomainFidelityProfile
                                            .MeasurementOutcome.MISSING);
                    assertThat(result.reason())
                            .isEqualTo(
                                    DomainFidelityProfile
                                            .MeasurementReason
                                            .NO_ELIGIBLE_EVIDENCE);
                });
        assertThat(profile.dimensions())
                .allSatisfy(metric ->
                        assertThat(metric.sufficiency())
                                .isNotEqualTo(
                                        DomainFidelityProfile
                                                .Sufficiency.MEASURED));
        assertThat(profile.assessment())
                .isEqualTo(
                        DomainFidelityProfile.Assessment
                                .INSUFFICIENT_EVIDENCE);
        assertThat(profile.limitations())
                .contains(
                        DomainFidelityProfile.Limitation
                                .COVERAGE_INCOMPLETE,
                        DomainFidelityProfile.Limitation
                                .LOW_SAMPLE,
                        DomainFidelityProfile.Limitation
                                .SOURCE_MODE_UNKNOWN);
        profile.verify(mapper);
    }

    @Test
    void completeProfileHasIndependentWilsonDimensionsAndNoTotalScore() {
        DomainFidelityInventory inventory = twoUnitInventory();
        DomainFidelityProfile profile = project(
                inventory,
                List.of(
                        pass(
                                inventory.units().get(0),
                                "workbook-shared",
                                MEASURED_AT.minusSeconds(120),
                                DomainFidelityProfile.SourceMode.RECORDED),
                        pass(
                                inventory.units().get(1),
                                "workbook-shared",
                                MEASURED_AT.minusSeconds(120),
                                DomainFidelityProfile.SourceMode.RECORDED)),
                2);

        assertThat(profile.assessment())
                .isEqualTo(
                        DomainFidelityProfile.Assessment.COMPLETE);
        assertThat(profile.limitations()).isEmpty();
        assertThat(profile.dimensions())
                .extracting(
                        DomainFidelityProfile
                                .DimensionMetric::dimension)
                .containsExactly(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT);
        assertThat(profile.dimensions())
                .allSatisfy(metric -> {
                    assertThat(metric.sufficiency())
                            .isEqualTo(
                                    DomainFidelityProfile
                                            .Sufficiency.MEASURED);
                    assertThat(metric.confidence())
                            .isNotNull();
                    assertThat(metric.confidence().point())
                            .isEqualTo(1.0d);
                    assertThat(metric.confidence().lowerBound())
                            .isLessThan(1.0d);
                });
        assertThat(mapper.valueToTree(profile).has("score"))
                .isFalse();
        assertThat(profile.sourceComposition().recordedUnits())
                .isEqualTo(2);
        profile.verify(mapper);
    }

    @Test
    void staleCertifiablePassesDowngradeEveryObligation() {
        DomainFidelityInventory inventory = twoUnitInventory();
        List<DomainFidelityProfileProjector.Measurement>
                measurements = inventory.units().stream()
                .map(unit -> pass(
                        unit,
                        "stale-" + unit.unitId(),
                        MEASURED_AT.minus(Duration.ofDays(31)),
                        DomainFidelityProfile.SourceMode.RECORDED))
                .toList();

        DomainFidelityProfile profile = project(
                inventory, measurements, 1);

        assertThat(profile.assessment())
                .isEqualTo(
                        DomainFidelityProfile.Assessment.STALE);
        assertThat(profile.validUntil())
                .isEqualTo(MEASURED_AT);
        assertThat(profile.dimensions())
                .allSatisfy(metric -> {
                    assertThat(metric.staleUnits())
                            .isEqualTo(2);
                    assertThat(metric.assessedUnits())
                            .isZero();
                    assertThat(metric.confidence()).isNull();
                });
        assertThat(profile.limitations())
                .contains(
                        DomainFidelityProfile.Limitation
                                .EVIDENCE_STALE);
    }

    @Test
    void exploratoryAndIncompleteEvidenceBecomeExplicitAbstentionDebt() {
        DomainFidelityInventory inventory = twoUnitInventory();
        DomainFidelityProfileProjector.Measurement exploratory =
                measurement(
                        inventory.units().get(0),
                        "exploratory",
                        MEASURED_AT.minusSeconds(10),
                        DomainFidelityProfile.SourceMode.RECORDED,
                        false,
                        true,
                        passResults(
                                inventory.units().get(0)));
        DomainFidelityProfileProjector.Measurement incomplete =
                measurement(
                        inventory.units().get(1),
                        "incomplete",
                        MEASURED_AT.minusSeconds(5),
                        DomainFidelityProfile.SourceMode.SYNTHESIZED,
                        true,
                        false,
                        passResults(
                                inventory.units().get(1)));

        DomainFidelityProfile profile = project(
                inventory,
                List.of(exploratory, incomplete),
                1);

        assertThat(profile.abstentionDebt()
                .abstainedObligations()).isEqualTo(4);
        assertThat(profile.abstentionDebt().ratio())
                .isEqualTo(1.0d);
        assertThat(profile.abstentionDebt().reasons())
                .extracting(
                        DomainFidelityProfile.ReasonCount::reason,
                        DomainFidelityProfile.ReasonCount::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .EVIDENCE_NOT_CERTIFIABLE,
                                2),
                        org.assertj.core.groups.Tuple.tuple(
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .SOURCE_EVIDENCE_INCOMPLETE,
                                2));
        assertThat(profile.sourceComposition()
                .synthesizedRatio()).isEqualTo(0.5d);
        assertThat(profile.limitations())
                .contains(
                        DomainFidelityProfile.Limitation
                                .ABSTENTION_PRESENT,
                        DomainFidelityProfile.Limitation
                                .SYNTHESIZED_SOURCE_PRESENT);
    }

    @Test
    void missingOutcomeAndRequestSpaceAdaptersCannotBorrowBehaviorPasses() {
        DomainFidelityInventory.CoverageUnit unit = unit(
                "refund-calibration",
                'a',
                ScenarioCase.CaseType.GOLDEN,
                DomainFidelityProfile.Dimension.BEHAVIOR,
                DomainFidelityProfile.Dimension.CONTRACT,
                DomainFidelityProfile.Dimension.OUTCOME,
                DomainFidelityProfile.Dimension.REQUEST_SPACE);
        DomainFidelityInventory inventory =
                inventory(List.of(unit));
        DomainFidelityProfile profile = project(
                inventory,
                List.of(pass(
                        unit,
                        "workbook-calibration",
                        MEASURED_AT.minusSeconds(1),
                        DomainFidelityProfile.SourceMode.RECORDED)),
                1);

        assertThat(profile.unitAssessments()
                .getFirst().results())
                .filteredOn(result ->
                        result.dimension()
                                == DomainFidelityProfile.Dimension.OUTCOME)
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.outcome())
                            .isEqualTo(
                                    DomainFidelityProfile
                                            .MeasurementOutcome.ABSTAINED);
                    assertThat(result.reason())
                            .isEqualTo(
                                    DomainFidelityProfile
                                            .MeasurementReason
                                            .OUTCOME_AUTHORITY_UNAVAILABLE);
                });
        assertThat(profile.limitations())
                .contains(
                        DomainFidelityProfile.Limitation
                                .OUTCOME_UNCALIBRATED,
                        DomainFidelityProfile.Limitation
                                .REQUEST_SPACE_UNMEASURED);
    }

    @Test
    void latestMeasurementWinsDeterministicallyWithoutShrinkingSources() {
        DomainFidelityInventory inventory =
                inventory(List.of(unit(
                        "refund-golden",
                        'a',
                        ScenarioCase.CaseType.GOLDEN,
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT)));
        DomainFidelityProfileProjector.Measurement older =
                measurement(
                        inventory.units().getFirst(),
                        "old",
                        MEASURED_AT.minusSeconds(10),
                        DomainFidelityProfile.SourceMode.RECORDED,
                        true,
                        true,
                        inventory.units().getFirst()
                                .requiredDimensions().stream()
                                .map(dimension ->
                                        new DomainFidelityProfile
                                                .DimensionResult(
                                                dimension,
                                                DomainFidelityProfile
                                                        .MeasurementOutcome.FAIL,
                                                DomainFidelityProfile
                                                        .MeasurementReason
                                                        .ASSERTION_FAILED))
                                .toList());
        DomainFidelityProfileProjector.Measurement newer =
                pass(
                        inventory.units().getFirst(),
                        "new",
                        MEASURED_AT.minusSeconds(5),
                        DomainFidelityProfile.SourceMode.RECORDED);

        DomainFidelityProfile profile = project(
                inventory, List.of(newer, older), 1);

        assertThat(profile.dimensions())
                .allSatisfy(metric -> {
                    assertThat(metric.passedUnits())
                            .isEqualTo(1);
                    assertThat(metric.failedUnits()).isZero();
                });
        assertThat(profile.unitAssessments()
                .getFirst().sourceRef().id())
                .isEqualTo("new");
    }

    @Test
    void rejectsForgedWilsonIntervalEvenWithRecomputedProfileAddress() {
        DomainFidelityInventory inventory = twoUnitInventory();
        DomainFidelityProfile profile = project(
                inventory,
                twoPassMeasurements(inventory),
                2);
        List<DomainFidelityProfile.DimensionMetric> forged =
                new ArrayList<>(profile.dimensions());
        DomainFidelityProfile.DimensionMetric first =
                forged.getFirst();
        forged.set(0,
                new DomainFidelityProfile.DimensionMetric(
                        first.dimension(),
                        first.requiredUnits(),
                        first.freshEvidenceUnits(),
                        first.assessedUnits(),
                        first.passedUnits(),
                        first.failedUnits(),
                        first.abstainedUnits(),
                        first.staleUnits(),
                        first.missingUnits(),
                        first.coverageRatio(),
                        first.abstentionRatio(),
                        new ArtifactProvenance.Confidence(
                                1.0d,
                                1.0d,
                                1.0d,
                                DomainFidelityProfile
                                        .CONFIDENCE_METHOD),
                        first.sufficiency()));
        DomainFidelityProfile tampered =
                new DomainFidelityProfile(
                        profile.schemaVersion(),
                        profile.profileFingerprint(),
                        profile.scope(),
                        profile.domainId(),
                        profile.inventoryRef(),
                        profile.taxonomyRef(),
                        profile.policy(),
                        profile.measuredAt(),
                        profile.validUntil(),
                        profile.denominator(),
                        profile.unitAssessments(),
                        forged,
                        profile.abstentionDebt(),
                        profile.sourceComposition(),
                        profile.assessment(),
                        profile.limitations(),
                        profile.profileSeal());

        assertThatThrownBy(() -> tampered.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arithmetic");
    }

    @Test
    void rejectsForgedFreshnessExpiryEvenWithConsistentStaleLabel() {
        DomainFidelityInventory inventory = twoUnitInventory();
        DomainFidelityProfile profile = project(
                inventory,
                twoPassMeasurements(inventory),
                2);
        DomainFidelityProfile.UnitAssessment first =
                profile.unitAssessments().getFirst();
        List<DomainFidelityProfile.UnitAssessment> forged =
                new ArrayList<>(profile.unitAssessments());
        forged.set(
                0,
                new DomainFidelityProfile.UnitAssessment(
                        first.unitId(),
                        first.scenarioCaseRef(),
                        first.sourceRef(),
                        first.observedAt(),
                        first.expiresAt().plus(Duration.ofDays(1)),
                        first.sourceMode(),
                        first.results()));
        DomainFidelityProfile tampered =
                new DomainFidelityProfile(
                        profile.schemaVersion(),
                        profile.profileFingerprint(),
                        profile.scope(),
                        profile.domainId(),
                        profile.inventoryRef(),
                        profile.taxonomyRef(),
                        profile.policy(),
                        profile.measuredAt(),
                        profile.validUntil(),
                        profile.denominator(),
                        forged,
                        profile.dimensions(),
                        profile.abstentionDebt(),
                        profile.sourceComposition(),
                        profile.assessment(),
                        profile.limitations(),
                        profile.profileSeal());

        assertThatThrownBy(() -> tampered.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy expiry");
    }

    @Test
    void rejectsMeasurementFromUnknownSourceArtifactKind() {
        DomainFidelityInventory.CoverageUnit unit =
                twoUnitInventory().units().getFirst();

        assertThatThrownBy(() ->
                new DomainFidelityProfileProjector.Measurement(
                        unit.unitId(),
                        unit.scenarioCaseRef(),
                        ref(
                                "UNVERIFIED_REPORT",
                                "report",
                                'e'),
                        MEASURED_AT.minusSeconds(1),
                        DomainFidelityProfile.SourceMode.RECORDED,
                        true,
                        true,
                        passResults(unit)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "not independently verifiable");
    }

    @Test
    void rejectsFutureEvidenceAndMeasurementsOutsideInventory() {
        DomainFidelityInventory inventory = twoUnitInventory();
        DomainFidelityInventory.CoverageUnit unit =
                inventory.units().getFirst();
        DomainFidelityProfileProjector.Measurement future =
                pass(
                        unit,
                        "future",
                        MEASURED_AT.plusSeconds(1),
                        DomainFidelityProfile.SourceMode.RECORDED);
        DomainFidelityProfileProjector.Measurement outside =
                new DomainFidelityProfileProjector.Measurement(
                        "not-in-inventory",
                        unit.scenarioCaseRef(),
                        ref(
                                "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                                "outside",
                                'e'),
                        MEASURED_AT.minusSeconds(1),
                        DomainFidelityProfile.SourceMode.RECORDED,
                        true,
                        true,
                        passResults(unit));

        assertThatThrownBy(() -> project(
                inventory, List.of(future), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory cut");
        assertThatThrownBy(() -> project(
                inventory, List.of(outside), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory cut");
    }

    private DomainFidelityInventory twoUnitInventory() {
        return inventory(List.of(
                unit(
                        "refund-boundary",
                        'a',
                        ScenarioCase.CaseType.BOUNDARY,
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT),
                unit(
                        "refund-golden",
                        'b',
                        ScenarioCase.CaseType.GOLDEN,
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT)));
    }

    private List<DomainFidelityProfileProjector.Measurement>
    twoPassMeasurements(DomainFidelityInventory inventory) {
        return List.of(
                pass(
                        inventory.units().get(0),
                        "workbook-a",
                        MEASURED_AT.minusSeconds(2),
                        DomainFidelityProfile.SourceMode.RECORDED),
                pass(
                        inventory.units().get(1),
                        "workbook-b",
                        MEASURED_AT.minusSeconds(1),
                        DomainFidelityProfile.SourceMode.RECORDED));
    }

    private DomainFidelityProfile project(
            DomainFidelityInventory inventory,
            List<DomainFidelityProfileProjector.Measurement>
                    measurements,
            int minimum) {
        return DomainFidelityProfileProjector.project(
                mapper,
                inventory,
                measurements,
                new DomainFidelityProfile.ProjectionPolicy(
                        minimum,
                        FRESHNESS,
                        true,
                        DomainFidelityProfile.CONFIDENCE_METHOD),
                MEASURED_AT);
    }

    private DomainFidelityInventory inventory(
            List<DomainFidelityInventory.CoverageUnit> units) {
        Instant expiresAt =
                Instant.parse("2026-08-01T00:00:00Z");
        ArtifactProvenance provenance =
                new ArtifactProvenance(
                        "",
                        ArtifactProvenance.SourceType.OWNER,
                        List.of(),
                        "tenant-a",
                        "FIDELITY_GOVERNANCE",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        "owner-a",
                        APPROVED_AT,
                        expiresAt,
                        "");
        return new DomainFidelityInventory(
                "",
                "support-refund",
                1,
                "",
                scope(),
                "refund-support",
                ref(
                        "DOMAIN_FIDELITY_TAXONOMY",
                        "support-taxonomy",
                        'f'),
                units,
                provenance,
                CapabilitySnapshot.Lifecycle.ACTIVE,
                APPROVED_AT.plusSeconds(1),
                expiresAt).seal(mapper);
    }

    private static DomainFidelityInventory.CoverageUnit unit(
            String unitId,
            char fingerprint,
            ScenarioCase.CaseType caseType,
            DomainFidelityProfile.Dimension... dimensions) {
        return new DomainFidelityInventory.CoverageUnit(
                unitId,
                ref(
                        "SCENARIO_CASE",
                        unitId,
                        fingerprint),
                ref(
                        "CAPABILITY",
                        "refund",
                        'c'),
                caseType,
                List.of(dimensions));
    }

    private static DomainFidelityProfileProjector.Measurement
    pass(
            DomainFidelityInventory.CoverageUnit unit,
            String sourceId,
            Instant observedAt,
            DomainFidelityProfile.SourceMode sourceMode) {
        return measurement(
                unit,
                sourceId,
                observedAt,
                sourceMode,
                true,
                true,
                passResults(unit));
    }

    private static DomainFidelityProfileProjector.Measurement
    measurement(
            DomainFidelityInventory.CoverageUnit unit,
            String sourceId,
            Instant observedAt,
            DomainFidelityProfile.SourceMode sourceMode,
            boolean certifiable,
            boolean complete,
            List<DomainFidelityProfile.DimensionResult> results) {
        return new DomainFidelityProfileProjector.Measurement(
                unit.unitId(),
                unit.scenarioCaseRef(),
                ref(
                        "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                        sourceId,
                        sourceId.charAt(0)),
                observedAt,
                sourceMode,
                certifiable,
                complete,
                results);
    }

    private static List<DomainFidelityProfile.DimensionResult>
    passResults(
            DomainFidelityInventory.CoverageUnit unit) {
        return unit.requiredDimensions().stream()
                .filter(dimension ->
                        dimension
                                != DomainFidelityProfile.Dimension.OUTCOME
                                && dimension
                                != DomainFidelityProfile.Dimension
                                .REQUEST_SPACE)
                .map(dimension ->
                        new DomainFidelityProfile.DimensionResult(
                                dimension,
                                DomainFidelityProfile
                                        .MeasurementOutcome.PASS,
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .ASSERTIONS_PASSED))
                .toList();
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "support",
                "refunds",
                "staging",
                "sg");
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                "sha256:" + String.valueOf(safe)
                        .repeat(64));
    }
}
