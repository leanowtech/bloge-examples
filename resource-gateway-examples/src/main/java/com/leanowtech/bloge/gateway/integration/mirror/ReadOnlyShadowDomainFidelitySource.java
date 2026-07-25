package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Independently verified read-only shadow source for Domain Fidelity projection.
 *
 * <p>The adapter accepts only root-signed comparisons that bind an exact inventory unit to a
 * paired request and a zero-write access proof. It re-verifies every comparison, rejects duplicate
 * units and cross-scope or cross-revision drift, maps only the four dimensions a per-request
 * differential can prove, and leaves outcome, request-space, and error-distribution obligations
 * absent for conservative projector abstention.</p>
 */
public final class ReadOnlyShadowDomainFidelitySource
        implements DomainFidelityMeasurementSource {
    private final ReadOnlyShadowComparisonIntegrity integrity;
    private final DomainFidelityPolicy policy;

    /**
     * Creates the source adapter.
     *
     * @param integrity independent comparison verifier
     * @param policy server-owned Fidelity projector authorization policy
     */
    public ReadOnlyShadowDomainFidelitySource(
            ReadOnlyShadowComparisonIntegrity integrity,
            DomainFidelityPolicy policy) {
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
    }

    @Override
    public Type type() {
        return Type.READ_ONLY_SHADOW;
    }

    @Override
    public boolean ready() {
        return integrity.available();
    }

    /**
     * Converts a bounded partial or complete signed comparison set into measurements.
     *
     * @param inventory exact verified owner denominator
     * @param comparisons signed comparison artifacts; omitted units remain missing
     * @param identity authenticated Fidelity projector service
     * @return ordered measurements for supplied unique inventory units
     */
    public List<DomainFidelityProfileProjector.Measurement>
    measurements(
            DomainFidelityInventory inventory,
            List<ReadOnlyShadowComparison> comparisons,
            IntegrationRequestContext identity) {
        IntegrationRequestContext projector =
                requireProjector(identity);
        DomainFidelityInventory denominator =
                Objects.requireNonNull(inventory, "inventory");
        if (!ready()) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.FIDELITY.SHADOW_SOURCE_UNAVAILABLE",
                            "The read-only Shadow comparison verification authority is unavailable.",
                            projector.correlationId(),
                            Map.of()));
        }
        List<ReadOnlyShadowComparison> values =
                comparisons == null
                        ? List.of()
                        : List.copyOf(comparisons);
        if (values.size()
                > DomainFidelityInventory.MAXIMUM_UNITS) {
            throw invalidSource(
                    projector,
                    "The Shadow comparison set exceeds the inventory bound.");
        }
        Map<String, DomainFidelityInventory.CoverageUnit>
                units = new LinkedHashMap<>();
        for (DomainFidelityInventory.CoverageUnit unit
                : denominator.units()) {
            units.put(unit.unitId(), unit);
        }
        Map<String, DomainFidelityProfileProjector.Measurement>
                measured = new LinkedHashMap<>();
        for (ReadOnlyShadowComparison untrusted : values) {
            ReadOnlyShadowComparison comparison;
            try {
                comparison = integrity.verify(untrusted);
            } catch (RuntimeException invalid) {
                throw invalidSource(
                        projector,
                        "A Shadow comparison failed independent integrity verification.");
            }
            DomainFidelityInventory.CoverageUnit unit =
                    units.get(comparison.unitId());
            if (!comparison.scope().equals(
                    denominator.scope())
                    || !comparison.inventoryRef().equals(
                    denominator.artifactRef())
                    || unit == null
                    || !unit.scenarioCaseRef().equals(
                    comparison.scenarioCaseRef())
                    || !unit.targetCapabilityRef().equals(
                    comparison.targetCapabilityRef())
                    || measured.containsKey(
                    comparison.unitId())) {
                throw invalidSource(
                        projector,
                        "Shadow comparisons do not belong to unique exact inventory units.");
            }
            measured.put(
                    unit.unitId(),
                    measurement(unit, comparison));
        }
        List<DomainFidelityProfileProjector.Measurement>
                ordered = new ArrayList<>();
        for (DomainFidelityInventory.CoverageUnit unit
                : denominator.units()) {
            DomainFidelityProfileProjector.Measurement value =
                    measured.get(unit.unitId());
            if (value != null) {
                ordered.add(value);
            }
        }
        return List.copyOf(ordered);
    }

    private static DomainFidelityProfileProjector.Measurement
    measurement(
            DomainFidelityInventory.CoverageUnit unit,
            ReadOnlyShadowComparison comparison) {
        List<DomainFidelityProfile.DimensionResult> results =
                comparison.results().stream()
                        .filter(result ->
                                unit.requiredDimensions()
                                        .contains(
                                                result.dimension()))
                        .map(ReadOnlyShadowDomainFidelitySource
                                ::dimensionResult)
                        .toList();
        return new DomainFidelityProfileProjector.Measurement(
                unit.unitId(),
                unit.scenarioCaseRef(),
                comparison.artifactRef(),
                comparison.observedAt(),
                DomainFidelityProfile.SourceMode.RECORDED,
                comparison.certifiable(),
                comparison.evidenceComplete(),
                results);
    }

    private static DomainFidelityProfile.DimensionResult
    dimensionResult(
            ReadOnlyShadowComparison.DimensionComparison
                    comparison) {
        return switch (comparison.outcome()) {
            case MATCH ->
                    new DomainFidelityProfile.DimensionResult(
                            comparison.dimension(),
                            DomainFidelityProfile
                                    .MeasurementOutcome.PASS,
                            DomainFidelityProfile
                                    .MeasurementReason
                                    .ASSERTIONS_PASSED);
            case MISMATCH ->
                    new DomainFidelityProfile.DimensionResult(
                            comparison.dimension(),
                            DomainFidelityProfile
                                    .MeasurementOutcome.FAIL,
                            DomainFidelityProfile
                                    .MeasurementReason
                                    .ASSERTION_FAILED);
            case INDETERMINATE ->
                    new DomainFidelityProfile.DimensionResult(
                            comparison.dimension(),
                            DomainFidelityProfile
                                    .MeasurementOutcome
                                    .ABSTAINED,
                            DomainFidelityProfile
                                    .MeasurementReason
                                    .ASSERTION_EVIDENCE_INDETERMINATE);
        };
    }

    private IntegrationRequestContext requireProjector(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!DomainFidelityPolicy.PROJECTION_PURPOSE.equals(
                exact.purpose())
                || !policy.mayProject(exact)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.FIDELITY.PROJECTOR_FORBIDDEN",
                            "The authenticated service is not an authorized Fidelity projector.",
                            exact.correlationId(),
                            Map.of()));
        }
        return exact;
    }

    private static IntegrationProblemException invalidSource(
            IntegrationRequestContext identity,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        "RG.MIRROR.FIDELITY.SHADOW_SOURCE_INVALID",
                        title,
                        identity.correlationId(),
                        Map.of()));
    }
}
