package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently verified authoritative outcome and calibration-cohort source for Domain Fidelity.
 *
 * <p>The adapter re-verifies every signed observation, enforces exact inventory coordinates,
 * rejects duplicate units and observation identities, and prevents the supplied members from being
 * mixed across cohorts by requiring one sampling frame and selection cut with consistent stratum
 * arithmetic and unique sample positions. Omitted inventory units remain explicit missing debt, so
 * a partial submission cannot masquerade as full inventory coverage. Match and mismatch are the
 * only assessed outcomes. Pending, censored, and conflicting authority closure remain explicit
 * abstention debt.</p>
 */
public final class AuthoritativeOutcomeDomainFidelitySource
        implements DomainFidelityMeasurementSource {
    private final AuthoritativeOutcomeObservationIntegrity
            integrity;
    private final DomainFidelityPolicy policy;

    /**
     * Creates the source adapter.
     *
     * @param integrity independent outcome and authority-chain verifier
     * @param policy server-owned Fidelity projector authorization policy
     */
    public AuthoritativeOutcomeDomainFidelitySource(
            AuthoritativeOutcomeObservationIntegrity integrity,
            DomainFidelityPolicy policy) {
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
    }

    @Override
    public Type type() {
        return Type.AUTHORITATIVE_OUTCOME;
    }

    @Override
    public boolean ready() {
        return integrity.available();
    }

    /**
     * Converts a bounded partial or complete calibration cohort into Fidelity measurements.
     *
     * @param inventory exact verified owner denominator
     * @param observations signed payload-free authoritative outcome observations
     * @param identity authenticated Fidelity projector service
     * @return inventory-ordered measurements for supplied unique units
     */
    public List<DomainFidelityProfileProjector.Measurement>
    measurements(
            DomainFidelityInventory inventory,
            List<AuthoritativeOutcomeObservation> observations,
            IntegrationRequestContext identity) {
        IntegrationRequestContext projector =
                requireProjector(identity);
        DomainFidelityInventory denominator =
                Objects.requireNonNull(inventory, "inventory");
        if (!ready()) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_UNAVAILABLE",
                            "The authoritative outcome verification authority is unavailable.",
                            projector.correlationId(),
                            Map.of()));
        }
        List<AuthoritativeOutcomeObservation> values =
                observations == null
                        ? List.of()
                        : List.copyOf(observations);
        if (values.size()
                > DomainFidelityInventory.MAXIMUM_UNITS) {
            throw invalidSource(
                    projector,
                    "The authoritative outcome set exceeds the inventory bound.");
        }
        Map<String, DomainFidelityInventory.CoverageUnit>
                units = new LinkedHashMap<>();
        for (DomainFidelityInventory.CoverageUnit unit
                : denominator.units()) {
            units.put(unit.unitId(), unit);
        }
        CohortClosure cohort = new CohortClosure();
        Set<String> observationIds = new HashSet<>();
        Map<String, DomainFidelityProfileProjector.Measurement>
                measured = new LinkedHashMap<>();
        for (AuthoritativeOutcomeObservation untrusted
                : values) {
            AuthoritativeOutcomeObservation observation;
            try {
                observation = integrity.verify(untrusted);
            } catch (AuthoritativeOutcomeObservationIntegrity
                     .Violation violation) {
                if (violation.reason()
                        == AuthoritativeOutcomeObservationIntegrity
                        .Reason.AUTHORITY_UNAVAILABLE
                        || violation.reason()
                        == AuthoritativeOutcomeObservationIntegrity
                        .Reason.KEY_UNAVAILABLE) {
                    throw unavailableSource(projector);
                }
                throw invalidSource(
                        projector,
                        "An authoritative outcome observation failed independent integrity verification.");
            } catch (RuntimeException invalid) {
                throw invalidSource(
                        projector,
                        "An authoritative outcome observation failed independent integrity verification.");
            }
            DomainFidelityInventory.CoverageUnit unit =
                    units.get(observation.unitId());
            if (!observation.scope().equals(
                    denominator.scope())
                    || !observation.inventoryRef().equals(
                    denominator.artifactRef())
                    || unit == null
                    || !unit.scenarioCaseRef().equals(
                    observation.scenarioCaseRef())
                    || !unit.targetCapabilityRef().equals(
                    observation.targetCapabilityRef())
                    || !observationIds.add(
                    observation.observationId())
                    || measured.containsKey(
                    observation.unitId())) {
                throw invalidSource(
                        projector,
                        "Outcome observations do not have unique identities on exact inventory units.");
            }
            try {
                cohort.admit(observation.selectionProof());
            } catch (IllegalArgumentException invalid) {
                throw invalidSource(
                        projector,
                        "Outcome observations do not form one consistent pre-treatment cohort.");
            }
            measured.put(
                    unit.unitId(),
                    measurement(unit, observation));
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
            AuthoritativeOutcomeObservation observation) {
        List<DomainFidelityProfile.DimensionResult> results =
                unit.requiredDimensions().contains(
                        DomainFidelityProfile.Dimension.OUTCOME)
                        ? List.of(outcomeResult(observation))
                        : List.of();
        return new DomainFidelityProfileProjector.Measurement(
                unit.unitId(),
                unit.scenarioCaseRef(),
                observation.artifactRef(),
                observation.reconciledAt(),
                DomainFidelityProfile.SourceMode.AUTHORITATIVE,
                true,
                observation.evidenceComplete(),
                results);
    }

    private static DomainFidelityProfile.DimensionResult
    outcomeResult(
            AuthoritativeOutcomeObservation observation) {
        return switch (observation.reconciliation()) {
            case MATCH -> result(
                    DomainFidelityProfile.MeasurementOutcome.PASS,
                    DomainFidelityProfile.MeasurementReason
                            .ASSERTIONS_PASSED);
            case MISMATCH -> result(
                    DomainFidelityProfile.MeasurementOutcome.FAIL,
                    DomainFidelityProfile.MeasurementReason
                            .ASSERTION_FAILED);
            case PENDING -> result(
                    DomainFidelityProfile.MeasurementOutcome
                            .ABSTAINED,
                    DomainFidelityProfile.MeasurementReason
                            .OUTCOME_PENDING);
            case CENSORED -> result(
                    DomainFidelityProfile.MeasurementOutcome
                            .ABSTAINED,
                    DomainFidelityProfile.MeasurementReason
                            .OUTCOME_CENSORED);
            case CONFLICT -> result(
                    DomainFidelityProfile.MeasurementOutcome
                            .ABSTAINED,
                    DomainFidelityProfile.MeasurementReason
                            .OUTCOME_CONFLICTING);
        };
    }

    private static DomainFidelityProfile.DimensionResult
    result(
            DomainFidelityProfile.MeasurementOutcome outcome,
            DomainFidelityProfile.MeasurementReason reason) {
        return new DomainFidelityProfile.DimensionResult(
                DomainFidelityProfile.Dimension.OUTCOME,
                outcome,
                reason);
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
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_INVALID",
                        title,
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException unavailableSource(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.FIDELITY.OUTCOME_SOURCE_UNAVAILABLE",
                        "The authoritative outcome verification authority became unavailable.",
                        identity.correlationId(),
                        Map.of()));
    }

    /** Detects mixed supplied cohort members without retaining subject or outcome identities. */
    private static final class CohortClosure {
        private MirrorArtifactRef cohortRef;
        private MirrorArtifactRef samplingFrameRef;
        private Instant selectedAt;
        private final Map<String, StratumShape> strata =
                new HashMap<>();
        private final Set<String> positions = new HashSet<>();
        private final Set<String> inclusionFingerprints =
                new HashSet<>();

        private void admit(
                AuthoritativeOutcomeObservation.SelectionProof
                        proof) {
            if (cohortRef == null) {
                cohortRef = proof.cohortRef();
                samplingFrameRef = proof.samplingFrameRef();
                selectedAt = proof.selectedAt();
            } else if (!cohortRef.equals(proof.cohortRef())
                    || !samplingFrameRef.equals(
                    proof.samplingFrameRef())
                    || !selectedAt.equals(proof.selectedAt())) {
                throw new IllegalArgumentException(
                        "cohort coordinates or selection cut differ");
            }
            StratumShape shape = new StratumShape(
                    proof.eligiblePopulationSize(),
                    proof.selectedPopulationSize(),
                    proof.selectionMode());
            StratumShape existing = strata.putIfAbsent(
                    proof.stratumId(), shape);
            String position = proof.stratumId()
                    + "\u0000" + proof.sampleOrdinal();
            if (existing != null && !existing.equals(shape)
                    || !positions.add(position)
                    || !inclusionFingerprints.add(
                    proof.inclusionFingerprint())) {
                throw new IllegalArgumentException(
                        "cohort stratum or sample identity conflicts");
            }
        }
    }

    private record StratumShape(
            long eligiblePopulationSize,
            long selectedPopulationSize,
            AuthoritativeOutcomeObservation.SelectionMode
                    selectionMode
    ) {
    }
}
