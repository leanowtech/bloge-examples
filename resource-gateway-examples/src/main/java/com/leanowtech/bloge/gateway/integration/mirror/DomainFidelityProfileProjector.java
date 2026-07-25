package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, fail-closed projection kernel for {@link DomainFidelityProfile}.
 *
 * <p>The kernel accepts only payload-free measurements that already carry an exact source
 * artifact reference. Adapters for Scenario workbooks, read-only shadow comparisons, and
 * authoritative outcomes remain separate trust boundaries. This class owns denominator
 * preservation, latest-evidence selection, freshness, abstention, Wilson intervals, source debt,
 * and conservative profile state. It never infers a missing obligation from a successful run and
 * never emits a composite score.</p>
 */
public final class DomainFidelityProfileProjector {
    /** Largest supplied measurement set admitted by one projection call. */
    public static final int MAXIMUM_MEASUREMENTS =
            DomainFidelityInventory.MAXIMUM_UNITS * 4;
    private static final double TOLERANCE = 1.0e-12d;
    private static final Set<String> SOURCE_ARTIFACT_KINDS =
            Set.of(
                    "AUTHORITATIVE_OUTCOME_OBSERVATION",
                    "FIDELITY_SHADOW_COMPARISON",
                    "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED",
                    "SCENARIO_REHEARSAL_WORKBOOK_SEED");

    private DomainFidelityProfileProjector() {
    }

    /**
     * One independently verified, payload-free source projection offered to the kernel.
     *
     * @param unitId exact inventory unit identity
     * @param scenarioCaseRef exact ScenarioCase measured by the source
     * @param sourceRef exact Scenario workbook, shadow comparison, or outcome artifact
     * @param observedAt authoritative source observation time
     * @param sourceMode source provenance class
     * @param certifiable whether the source passed its independent trust boundary
     * @param evidenceComplete whether the source exposed all facts it claimed to expose
     * @param results sorted measured dimension results; missing required dimensions abstain
     */
    public record Measurement(
            String unitId,
            MirrorArtifactRef scenarioCaseRef,
            MirrorArtifactRef sourceRef,
            Instant observedAt,
            DomainFidelityProfile.SourceMode sourceMode,
            boolean certifiable,
            boolean evidenceComplete,
            List<DomainFidelityProfile.DimensionResult> results
    ) {
        /** Validates exact source identity and pre-freshness measured outcomes. */
        public Measurement {
            unitId = required(unitId, "unitId");
            if (scenarioCaseRef == null
                    || !"SCENARIO_CASE".equals(
                    scenarioCaseRef.kind())) {
                throw new IllegalArgumentException(
                        "measurement scenarioCaseRef must be exact");
            }
            sourceRef = Objects.requireNonNull(
                    sourceRef, "sourceRef");
            if (!SOURCE_ARTIFACT_KINDS.contains(
                    sourceRef.kind())) {
                throw new IllegalArgumentException(
                        "measurement sourceRef kind is not independently verifiable in v1");
            }
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            sourceMode = Objects.requireNonNull(
                    sourceMode, "sourceMode");
            results = results == null
                    ? List.of() : List.copyOf(results);
            List<DomainFidelityProfile.Dimension> ordered =
                    results.stream()
                            .map(DomainFidelityProfile
                                    .DimensionResult::dimension)
                            .toList();
            List<DomainFidelityProfile.Dimension> canonical =
                    ordered.stream()
                            .distinct()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList();
            if (!ordered.equals(canonical)
                    || results.stream().anyMatch(
                    result -> result.outcome()
                            == DomainFidelityProfile
                            .MeasurementOutcome.STALE
                            || result.outcome()
                            == DomainFidelityProfile
                            .MeasurementOutcome.MISSING)) {
                throw new IllegalArgumentException(
                        "measurement results must be unique, ordered, and pre-freshness");
            }
        }
    }

    /**
     * Projects one exact inventory at a fixed evidence cut.
     *
     * @param mapper canonical protocol mapper
     * @param inventory verified owner-approved denominator
     * @param measurements independently verified payload-free source projections
     * @param policy deterministic projection policy
     * @param measuredAt exact evidence cut
     * @return content-addressed unsigned fidelity profile
     */
    public static DomainFidelityProfile project(
            ObjectMapper mapper,
            DomainFidelityInventory inventory,
            List<Measurement> measurements,
            DomainFidelityProfile.ProjectionPolicy policy,
            Instant measuredAt) {
        Objects.requireNonNull(mapper, "mapper");
        DomainFidelityInventory exactInventory =
                Objects.requireNonNull(inventory, "inventory");
        exactInventory.verify(mapper);
        DomainFidelityProfile.ProjectionPolicy exactPolicy =
                Objects.requireNonNull(policy, "policy");
        Instant cut = Objects.requireNonNull(
                measuredAt, "measuredAt");
        if (cut.isBefore(exactInventory.effectiveAt())
                || !cut.isBefore(exactInventory.expiresAt())) {
            throw new IllegalArgumentException(
                    "profile cut is outside the approved inventory window");
        }
        List<Measurement> supplied = measurements == null
                ? List.of() : List.copyOf(measurements);
        if (supplied.size() > MAXIMUM_MEASUREMENTS) {
            throw new IllegalArgumentException(
                    "measurement set exceeds the v1 bound");
        }
        Map<String, DomainFidelityInventory.CoverageUnit>
                unitsById = new LinkedHashMap<>();
        for (DomainFidelityInventory.CoverageUnit unit
                : exactInventory.units()) {
            unitsById.put(unit.unitId(), unit);
        }
        Map<String, Measurement> latest = latestMeasurements(
                supplied, unitsById, cut);
        List<DomainFidelityProfile.UnitAssessment> assessments =
                exactInventory.units().stream()
                        .map(unit -> assessment(
                                unit,
                                latest.get(unit.unitId()),
                                exactPolicy,
                                cut))
                        .toList();
        DomainFidelityProfile.CoverageDenominator denominator =
                denominator(assessments);
        List<DomainFidelityProfile.DimensionMetric> metrics =
                metrics(assessments, exactPolicy);
        DomainFidelityProfile.AbstentionDebt debt =
                abstentionDebt(denominator, assessments);
        DomainFidelityProfile.SourceComposition composition =
                sourceComposition(assessments);
        DomainFidelityProfile.Assessment assessment =
                assessment(metrics, assessments);
        List<DomainFidelityProfile.Limitation> limitations =
                limitations(metrics, assessments, composition);
        Instant validUntil = validUntil(assessments, cut);
        DomainFidelityProfile material =
                new DomainFidelityProfile(
                        DomainFidelityProfile.SCHEMA_VERSION,
                        "",
                        exactInventory.scope(),
                        exactInventory.domainId(),
                        exactInventory.artifactRef(),
                        exactInventory.taxonomyRef(),
                        exactPolicy,
                        cut,
                        validUntil,
                        denominator,
                        assessments,
                        metrics,
                        debt,
                        composition,
                        assessment,
                        limitations,
                        VisualRunEvidenceSeal.unsigned());
        DomainFidelityProfile sealed =
                material.withFingerprint(
                        ProtocolFingerprint.ofBounded(
                                mapper,
                                material,
                                DomainFidelityProfile
                                        .MAXIMUM_CANONICAL_BYTES));
        sealed.verify(mapper);
        return sealed;
    }

    /**
     * Reconstructs every derived profile field from its complete unit-assessment closure.
     *
     * <p>This method intentionally does not resolve the external inventory or source artifacts.
     * The server and offline Test Kit verify those trust boundaries separately. It proves that the
     * profile did not repair its own denominator or forge its metric, debt, freshness, source, or
     * completeness arithmetic.</p>
     *
     * @param profile decoded profile
     */
    static void verify(DomainFidelityProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<DomainFidelityProfile.UnitAssessment> assessments =
                profile.unitAssessments();
        if (assessments.size()
                != profile.denominator().totalUnits()
                || assessments.isEmpty()) {
            throw new IllegalArgumentException(
                    "profile unit closure differs from denominator");
        }
        String previousUnit = "";
        Set<String> unitIds = new HashSet<>();
        Set<MirrorArtifactRef> scenarioRefs =
                new HashSet<>();
        for (DomainFidelityProfile.UnitAssessment unit
                : assessments) {
            if (!unitIds.add(unit.unitId())
                    || !scenarioRefs.add(
                    unit.scenarioCaseRef())
                    || unit.unitId()
                    .compareTo(previousUnit) <= 0) {
                throw new IllegalArgumentException(
                        "profile unit assessments must be unique and ordered");
            }
            previousUnit = unit.unitId();
            if (unit.sourceRef() != null
                    && !SOURCE_ARTIFACT_KINDS.contains(
                    unit.sourceRef().kind())) {
                throw new IllegalArgumentException(
                        "profile sourceRef kind is not independently verifiable in v1");
            }
            requireTemporalShape(
                    unit, profile.policy(), profile.measuredAt());
        }
        DomainFidelityProfile.CoverageDenominator denominator =
                denominator(assessments);
        List<DomainFidelityProfile.DimensionMetric> metrics =
                metrics(assessments, profile.policy());
        DomainFidelityProfile.AbstentionDebt debt =
                abstentionDebt(denominator, assessments);
        DomainFidelityProfile.SourceComposition composition =
                sourceComposition(assessments);
        DomainFidelityProfile.Assessment assessment =
                assessment(metrics, assessments);
        List<DomainFidelityProfile.Limitation> limitations =
                limitations(metrics, assessments, composition);
        if (!denominator.equals(profile.denominator())
                || !sameMetrics(metrics, profile.dimensions())
                || !sameDebt(debt, profile.abstentionDebt())
                || !sameComposition(
                composition, profile.sourceComposition())
                || assessment != profile.assessment()
                || !limitations.equals(profile.limitations())
                || !validUntil(assessments, profile.measuredAt())
                .equals(profile.validUntil())) {
            throw new IllegalArgumentException(
                    "profile derived fidelity arithmetic is invalid");
        }
    }

    private static Map<String, Measurement> latestMeasurements(
            List<Measurement> values,
            Map<String, DomainFidelityInventory.CoverageUnit>
                    units,
            Instant measuredAt) {
        Map<String, Measurement> latest = new HashMap<>();
        Set<String> unitSourceRefs = new HashSet<>();
        for (Measurement measurement : values) {
            Measurement exact = Objects.requireNonNull(
                    measurement, "measurement");
            DomainFidelityInventory.CoverageUnit unit =
                    units.get(exact.unitId());
            if (unit == null
                    || !unit.scenarioCaseRef()
                    .equals(exact.scenarioCaseRef())
                    || exact.observedAt().isAfter(measuredAt)
                    || !unitSourceRefs.add(
                    exact.unitId() + "\u0000"
                            + exact.sourceRef().kind()
                            + "\u0000"
                            + exact.sourceRef().id()
                            + "\u0000"
                            + exact.sourceRef().revision()
                            + "\u0000"
                            + exact.sourceRef().fingerprint())) {
                throw new IllegalArgumentException(
                        "measurement does not belong to the exact inventory cut");
            }
            Measurement current = latest.get(
                    exact.unitId());
            if (current == null
                    || compareMeasurement(
                    exact, current) > 0) {
                latest.put(exact.unitId(), exact);
            }
        }
        return Map.copyOf(latest);
    }

    private static int compareMeasurement(
            Measurement left, Measurement right) {
        int time = left.observedAt()
                .compareTo(right.observedAt());
        if (time != 0) {
            return time;
        }
        return left.sourceRef().fingerprint()
                .compareTo(right.sourceRef().fingerprint());
    }

    private static DomainFidelityProfile.UnitAssessment
    assessment(
            DomainFidelityInventory.CoverageUnit unit,
            Measurement measurement,
            DomainFidelityProfile.ProjectionPolicy policy,
            Instant measuredAt) {
        if (measurement == null) {
            return new DomainFidelityProfile.UnitAssessment(
                    unit.unitId(),
                    unit.scenarioCaseRef(),
                    null,
                    null,
                    null,
                    DomainFidelityProfile.SourceMode.UNKNOWN,
                    unit.requiredDimensions().stream()
                            .map(dimension ->
                                    new DomainFidelityProfile
                                            .DimensionResult(
                                            dimension,
                                            DomainFidelityProfile
                                                    .MeasurementOutcome
                                                    .MISSING,
                                            DomainFidelityProfile
                                                    .MeasurementReason
                                                    .NO_ELIGIBLE_EVIDENCE))
                            .toList());
        }
        Instant expiresAt = measurement.observedAt()
                .plus(policy.freshnessWindow());
        boolean stale = !expiresAt.isAfter(measuredAt);
        Map<DomainFidelityProfile.Dimension,
                DomainFidelityProfile.DimensionResult> supplied =
                new EnumMap<>(
                        DomainFidelityProfile.Dimension.class);
        for (DomainFidelityProfile.DimensionResult result
                : measurement.results()) {
            if (!unit.requiredDimensions()
                    .contains(result.dimension())) {
                throw new IllegalArgumentException(
                        "measurement contains a non-denominator dimension");
            }
            supplied.put(result.dimension(), result);
        }
        List<DomainFidelityProfile.DimensionResult> results =
                new ArrayList<>();
        for (DomainFidelityProfile.Dimension dimension
                : unit.requiredDimensions()) {
            results.add(stale
                    ? result(
                    dimension,
                    DomainFidelityProfile
                            .MeasurementOutcome.STALE,
                    DomainFidelityProfile
                            .MeasurementReason.EVIDENCE_STALE)
                    : !measurement.certifiable()
                    && policy.certifiableEvidenceRequired()
                    ? result(
                    dimension,
                    DomainFidelityProfile
                            .MeasurementOutcome.ABSTAINED,
                    DomainFidelityProfile
                            .MeasurementReason
                            .EVIDENCE_NOT_CERTIFIABLE)
                    : !measurement.evidenceComplete()
                    ? result(
                    dimension,
                    DomainFidelityProfile
                            .MeasurementOutcome.ABSTAINED,
                    DomainFidelityProfile
                            .MeasurementReason
                            .SOURCE_EVIDENCE_INCOMPLETE)
                    : supplied.getOrDefault(
                    dimension,
                    unavailable(dimension)));
        }
        return new DomainFidelityProfile.UnitAssessment(
                unit.unitId(),
                unit.scenarioCaseRef(),
                measurement.sourceRef(),
                measurement.observedAt(),
                expiresAt,
                measurement.sourceMode(),
                results);
    }

    private static DomainFidelityProfile.DimensionResult
    unavailable(DomainFidelityProfile.Dimension dimension) {
        DomainFidelityProfile.MeasurementReason reason =
                switch (dimension) {
                    case OUTCOME -> DomainFidelityProfile
                            .MeasurementReason
                            .OUTCOME_AUTHORITY_UNAVAILABLE;
                    case REQUEST_SPACE -> DomainFidelityProfile
                            .MeasurementReason
                            .REQUEST_SPACE_EVIDENCE_UNAVAILABLE;
                    default -> DomainFidelityProfile
                            .MeasurementReason
                            .DIMENSION_ASSERTION_ABSENT;
                };
        return result(
                dimension,
                DomainFidelityProfile.MeasurementOutcome
                        .ABSTAINED,
                reason);
    }

    private static DomainFidelityProfile.DimensionResult
    result(
            DomainFidelityProfile.Dimension dimension,
            DomainFidelityProfile.MeasurementOutcome outcome,
            DomainFidelityProfile.MeasurementReason reason) {
        return new DomainFidelityProfile.DimensionResult(
                dimension, outcome, reason);
    }

    private static DomainFidelityProfile.CoverageDenominator
    denominator(
            List<DomainFidelityProfile.UnitAssessment>
                    assessments) {
        EnumMap<DomainFidelityProfile.Dimension, Integer>
                counts = new EnumMap<>(
                DomainFidelityProfile.Dimension.class);
        int obligations = 0;
        for (DomainFidelityProfile.UnitAssessment unit
                : assessments) {
            for (DomainFidelityProfile.DimensionResult result
                    : unit.results()) {
                counts.merge(
                        result.dimension(), 1, Integer::sum);
                obligations++;
            }
        }
        List<DomainFidelityProfile.DimensionDenominator>
                dimensions = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .map(entry ->
                        new DomainFidelityProfile
                                .DimensionDenominator(
                                entry.getKey(),
                                entry.getValue()))
                .toList();
        return new DomainFidelityProfile.CoverageDenominator(
                assessments.size(), obligations, dimensions);
    }

    private static List<DomainFidelityProfile.DimensionMetric>
    metrics(
            List<DomainFidelityProfile.UnitAssessment>
                    assessments,
            DomainFidelityProfile.ProjectionPolicy policy) {
        Map<DomainFidelityProfile.Dimension,
                EnumMap<DomainFidelityProfile.MeasurementOutcome,
                        Integer>> counts =
                new TreeMap<>(
                        Comparator.comparing(Enum::name));
        for (DomainFidelityProfile.UnitAssessment unit
                : assessments) {
            for (DomainFidelityProfile.DimensionResult result
                    : unit.results()) {
                counts.computeIfAbsent(
                                result.dimension(),
                                ignored -> new EnumMap<>(
                                        DomainFidelityProfile
                                                .MeasurementOutcome
                                                .class))
                        .merge(
                                result.outcome(),
                                1,
                                Integer::sum);
            }
        }
        List<DomainFidelityProfile.DimensionMetric> metrics =
                new ArrayList<>();
        for (Map.Entry<DomainFidelityProfile.Dimension,
                EnumMap<DomainFidelityProfile.MeasurementOutcome,
                        Integer>> entry : counts.entrySet()) {
            EnumMap<DomainFidelityProfile.MeasurementOutcome,
                    Integer> values = entry.getValue();
            int passed = count(
                    values,
                    DomainFidelityProfile
                            .MeasurementOutcome.PASS);
            int failed = count(
                    values,
                    DomainFidelityProfile
                            .MeasurementOutcome.FAIL);
            int abstained = count(
                    values,
                    DomainFidelityProfile
                            .MeasurementOutcome.ABSTAINED);
            int stale = count(
                    values,
                    DomainFidelityProfile
                            .MeasurementOutcome.STALE);
            int missing = count(
                    values,
                    DomainFidelityProfile
                            .MeasurementOutcome.MISSING);
            int assessed = passed + failed;
            int fresh = assessed + abstained;
            int required = fresh + stale + missing;
            DomainFidelityProfile.Sufficiency sufficiency =
                    assessed == 0
                            ? DomainFidelityProfile.Sufficiency
                            .NO_ASSESSED_EVIDENCE
                            : assessed
                            < policy.minimumAssessedUnits()
                            ? DomainFidelityProfile.Sufficiency
                            .BELOW_MINIMUM_SAMPLE
                            : abstained + stale + missing > 0
                            ? DomainFidelityProfile.Sufficiency
                            .PARTIAL_COVERAGE
                            : DomainFidelityProfile.Sufficiency
                            .MEASURED;
            metrics.add(
                    new DomainFidelityProfile.DimensionMetric(
                            entry.getKey(),
                            required,
                            fresh,
                            assessed,
                            passed,
                            failed,
                            abstained,
                            stale,
                            missing,
                            ratio(fresh, required),
                            ratio(abstained, required),
                            assessed == 0
                                    ? null
                                    : wilson(
                                    passed, assessed),
                            sufficiency));
        }
        return List.copyOf(metrics);
    }

    private static DomainFidelityProfile.AbstentionDebt
    abstentionDebt(
            DomainFidelityProfile.CoverageDenominator
                    denominator,
            List<DomainFidelityProfile.UnitAssessment>
                    assessments) {
        Map<DomainFidelityProfile.MeasurementReason, Integer>
                reasons = new TreeMap<>(
                Comparator.comparing(Enum::name));
        for (DomainFidelityProfile.UnitAssessment unit
                : assessments) {
            for (DomainFidelityProfile.DimensionResult result
                    : unit.results()) {
                if (result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.ABSTAINED) {
                    reasons.merge(
                            result.reason(), 1, Integer::sum);
                }
            }
        }
        int abstained = reasons.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        return new DomainFidelityProfile.AbstentionDebt(
                denominator.totalObligations(),
                abstained,
                ratio(
                        abstained,
                        denominator.totalObligations()),
                reasons.entrySet().stream()
                        .map(entry ->
                                new DomainFidelityProfile
                                        .ReasonCount(
                                        entry.getKey(),
                                        entry.getValue()))
                        .toList());
    }

    private static DomainFidelityProfile.SourceComposition
    sourceComposition(
            List<DomainFidelityProfile.UnitAssessment>
                    assessments) {
        EnumMap<DomainFidelityProfile.SourceMode, Integer>
                counts = new EnumMap<>(
                DomainFidelityProfile.SourceMode.class);
        for (DomainFidelityProfile.UnitAssessment unit
                : assessments) {
            counts.merge(
                    unit.sourceMode(), 1, Integer::sum);
        }
        int total = assessments.size();
        int synthesized = count(
                counts,
                DomainFidelityProfile.SourceMode.SYNTHESIZED);
        int unknown = count(
                counts,
                DomainFidelityProfile.SourceMode.UNKNOWN);
        return new DomainFidelityProfile.SourceComposition(
                total,
                count(
                        counts,
                        DomainFidelityProfile.SourceMode.RECORDED),
                synthesized,
                count(
                        counts,
                        DomainFidelityProfile.SourceMode.OWNER_DECLARED),
                count(
                        counts,
                        DomainFidelityProfile.SourceMode.AUTHORITATIVE),
                unknown,
                ratio(synthesized, total),
                ratio(unknown, total));
    }

    private static DomainFidelityProfile.Assessment assessment(
            List<DomainFidelityProfile.DimensionMetric> metrics,
            List<DomainFidelityProfile.UnitAssessment>
                    assessments) {
        if (assessments.stream()
                .flatMap(unit -> unit.results().stream())
                .anyMatch(result -> result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.STALE)) {
            return DomainFidelityProfile.Assessment.STALE;
        }
        if (metrics.stream().anyMatch(metric ->
                metric.sufficiency()
                        == DomainFidelityProfile.Sufficiency
                        .NO_ASSESSED_EVIDENCE
                        || metric.sufficiency()
                        == DomainFidelityProfile.Sufficiency
                        .BELOW_MINIMUM_SAMPLE)) {
            return DomainFidelityProfile.Assessment
                    .INSUFFICIENT_EVIDENCE;
        }
        if (metrics.stream().anyMatch(metric ->
                metric.sufficiency()
                        == DomainFidelityProfile.Sufficiency
                        .PARTIAL_COVERAGE)) {
            return DomainFidelityProfile.Assessment.PARTIAL;
        }
        return DomainFidelityProfile.Assessment.COMPLETE;
    }

    private static List<DomainFidelityProfile.Limitation>
    limitations(
            List<DomainFidelityProfile.DimensionMetric> metrics,
            List<DomainFidelityProfile.UnitAssessment>
                    assessments,
            DomainFidelityProfile.SourceComposition composition) {
        Set<DomainFidelityProfile.Limitation> values =
                java.util.EnumSet.noneOf(
                        DomainFidelityProfile.Limitation.class);
        if (metrics.stream().anyMatch(metric ->
                metric.abstainedUnits() > 0)) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .ABSTENTION_PRESENT);
        }
        if (metrics.stream().anyMatch(metric ->
                metric.abstainedUnits()
                        + metric.staleUnits()
                        + metric.missingUnits() > 0)) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .COVERAGE_INCOMPLETE);
        }
        if (metrics.stream().anyMatch(metric ->
                metric.staleUnits() > 0)) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .EVIDENCE_STALE);
        }
        if (metrics.stream().anyMatch(metric ->
                metric.sufficiency()
                        == DomainFidelityProfile.Sufficiency
                        .BELOW_MINIMUM_SAMPLE
                        || metric.sufficiency()
                        == DomainFidelityProfile.Sufficiency
                        .NO_ASSESSED_EVIDENCE)) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .LOW_SAMPLE);
        }
        metrics.stream()
                .filter(metric -> metric.dimension()
                        == DomainFidelityProfile.Dimension.OUTCOME
                        && metric.sufficiency()
                        != DomainFidelityProfile.Sufficiency.MEASURED)
                .findAny()
                .ifPresent(ignored -> values.add(
                        DomainFidelityProfile.Limitation
                                .OUTCOME_UNCALIBRATED));
        metrics.stream()
                .filter(metric -> metric.dimension()
                        == DomainFidelityProfile.Dimension.REQUEST_SPACE
                        && metric.sufficiency()
                        != DomainFidelityProfile.Sufficiency.MEASURED)
                .findAny()
                .ifPresent(ignored -> values.add(
                        DomainFidelityProfile.Limitation
                                .REQUEST_SPACE_UNMEASURED));
        if (composition.synthesizedUnits() > 0) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .SYNTHESIZED_SOURCE_PRESENT);
        }
        if (composition.unknownUnits() > 0) {
            values.add(
                    DomainFidelityProfile.Limitation
                            .SOURCE_MODE_UNKNOWN);
        }
        return values.stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private static Instant validUntil(
            List<DomainFidelityProfile.UnitAssessment>
                    assessments,
            Instant measuredAt) {
        return assessments.stream()
                .filter(unit -> unit.expiresAt() != null
                        && unit.expiresAt().isAfter(measuredAt))
                .map(DomainFidelityProfile.UnitAssessment::expiresAt)
                .min(Comparator.naturalOrder())
                .orElse(measuredAt);
    }

    private static void requireTemporalShape(
            DomainFidelityProfile.UnitAssessment unit,
            DomainFidelityProfile.ProjectionPolicy policy,
            Instant measuredAt) {
        if (unit.sourceRef() == null) {
            return;
        }
        if (unit.observedAt().isAfter(measuredAt)
                || !unit.observedAt()
                .plus(policy.freshnessWindow())
                .equals(unit.expiresAt())) {
            throw new IllegalArgumentException(
                    "profile evidence time or policy expiry is invalid");
        }
        boolean stale = !unit.expiresAt()
                .isAfter(measuredAt);
        boolean allStale = unit.results().stream()
                .allMatch(result -> result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.STALE);
        boolean anyStaleOrMissing = unit.results().stream()
                .anyMatch(result -> result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.STALE
                        || result.outcome()
                        == DomainFidelityProfile
                        .MeasurementOutcome.MISSING);
        if (stale ? !allStale : anyStaleOrMissing) {
            throw new IllegalArgumentException(
                    "profile freshness and unit outcomes disagree");
        }
    }

    private static ArtifactProvenance.Confidence wilson(
            int passed, int assessed) {
        double point = (double) passed / assessed;
        double z = 1.959963984540054d;
        double denominator = 1.0d
                + z * z / assessed;
        double center = point
                + z * z / (2.0d * assessed);
        double spread = z * Math.sqrt(
                point * (1.0d - point) / assessed
                        + z * z
                        / (4.0d * assessed * assessed));
        return new ArtifactProvenance.Confidence(
                point,
                Math.max(
                        0.0d,
                        (center - spread) / denominator),
                Math.min(
                        1.0d,
                        (center + spread) / denominator),
                DomainFidelityProfile.CONFIDENCE_METHOD);
    }

    private static boolean sameMetrics(
            List<DomainFidelityProfile.DimensionMetric> expected,
            List<DomainFidelityProfile.DimensionMetric> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            DomainFidelityProfile.DimensionMetric left =
                    expected.get(index);
            DomainFidelityProfile.DimensionMetric right =
                    actual.get(index);
            if (left.dimension() != right.dimension()
                    || left.requiredUnits() != right.requiredUnits()
                    || left.freshEvidenceUnits()
                    != right.freshEvidenceUnits()
                    || left.assessedUnits() != right.assessedUnits()
                    || left.passedUnits() != right.passedUnits()
                    || left.failedUnits() != right.failedUnits()
                    || left.abstainedUnits() != right.abstainedUnits()
                    || left.staleUnits() != right.staleUnits()
                    || left.missingUnits() != right.missingUnits()
                    || !near(
                    left.coverageRatio(),
                    right.coverageRatio())
                    || !near(
                    left.abstentionRatio(),
                    right.abstentionRatio())
                    || !sameConfidence(
                    left.confidence(),
                    right.confidence())
                    || left.sufficiency() != right.sufficiency()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameConfidence(
            ArtifactProvenance.Confidence left,
            ArtifactProvenance.Confidence right) {
        return left == null && right == null
                || left != null && right != null
                && left.method().equals(right.method())
                && near(left.point(), right.point())
                && near(
                left.lowerBound(), right.lowerBound())
                && near(
                left.upperBound(), right.upperBound());
    }

    private static boolean sameDebt(
            DomainFidelityProfile.AbstentionDebt left,
            DomainFidelityProfile.AbstentionDebt right) {
        return left.totalObligations()
                == right.totalObligations()
                && left.abstainedObligations()
                == right.abstainedObligations()
                && near(left.ratio(), right.ratio())
                && left.reasons().equals(right.reasons());
    }

    private static boolean sameComposition(
            DomainFidelityProfile.SourceComposition left,
            DomainFidelityProfile.SourceComposition right) {
        return left.totalUnits() == right.totalUnits()
                && left.recordedUnits()
                == right.recordedUnits()
                && left.synthesizedUnits()
                == right.synthesizedUnits()
                && left.ownerDeclaredUnits()
                == right.ownerDeclaredUnits()
                && left.authoritativeUnits()
                == right.authoritativeUnits()
                && left.unknownUnits()
                == right.unknownUnits()
                && near(
                left.synthesizedRatio(),
                right.synthesizedRatio())
                && near(
                left.unknownRatio(),
                right.unknownRatio());
    }

    private static boolean near(
            double left, double right) {
        return Math.abs(left - right) <= TOLERANCE;
    }

    private static double ratio(
            int numerator, int denominator) {
        return (double) numerator / denominator;
    }

    private static <K extends Enum<K>> int count(
            Map<K, Integer> counts, K key) {
        return counts.getOrDefault(key, 0);
    }

    private static String required(
            String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
