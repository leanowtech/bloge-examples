package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Comparator;
import java.util.List;

/** One authority observation that binds a Package source reference to immutable compile material. */
public record PackageDependencyObservation(
        MirrorArtifactRef sourceRef,
        MirrorArtifactRef materializedRef,
        MirrorArtifactRef observedHeadRef,
        CapabilitySnapshot.Scope scope,
        Status status,
        List<Assurance> assurances
) {
    /** Resolution outcome. Non-resolved states become fail-closed readiness findings. */
    public enum Status {
        RESOLVED,
        MISSING,
        FINGERPRINT_MISMATCH,
        SCOPE_VIOLATION,
        INVALID
    }

    /** Kind-specific facts proven by the authoritative dependency adapter. */
    public enum Assurance {
        SCHEMA_VALID,
        NON_EMPTY_DENOMINATOR,
        OUTCOME_PARSABLE,
        SIMULATION_BOUNDED,
        REAL_EXTERNAL_CALLS_FORBIDDEN,
        STATE_EFFECT_PROTECTED
    }

    /** Enforces bounded, deterministic and non-ambiguous dependency observations. */
    public PackageDependencyObservation {
        sourceRef = java.util.Objects.requireNonNull(sourceRef, "sourceRef");
        status = java.util.Objects.requireNonNull(status, "status");
        assurances = assurances == null ? List.of() : assurances.stream()
                .map(value -> java.util.Objects.requireNonNull(value, "assurance"))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (assurances.size() > Assurance.values().length) {
            throw new IllegalArgumentException("dependency assurances exceed their protocol bound");
        }
        if (status == Status.RESOLVED && (materializedRef == null || scope == null)) {
            throw new IllegalArgumentException("resolved dependency requires materialized ref and scope");
        }
        if (status != Status.RESOLVED && !assurances.isEmpty()) {
            throw new IllegalArgumentException("unresolved dependency must not advertise assurances");
        }
    }

    /** @return whether the adapter proved a named kind-specific assurance */
    public boolean assures(Assurance assurance) {
        return assurances.contains(assurance);
    }
}
