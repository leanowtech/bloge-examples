package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Comparator;
import java.util.List;

/** Complete contribution produced while one Package dependency is authority-resolved. */
public record PackageDependencyResolution(
        PackageDependencyObservation observation,
        MirrorArtifactRef capabilityClosureRef,
        List<MirrorArtifactRef> mirrorPlanRefs,
        List<BusinessAssetLink> businessAssetLinks,
        List<MirrorArtifactRef> evidenceRefs
) {
    /** Normalizes adapter output before it is merged into one authority-frozen window. */
    public PackageDependencyResolution {
        observation = java.util.Objects.requireNonNull(observation, "observation");
        if (capabilityClosureRef != null
                && !"CAPABILITY_CLOSURE".equals(capabilityClosureRef.kind())) {
            throw new IllegalArgumentException(
                    "capabilityClosureRef must reference CAPABILITY_CLOSURE");
        }
        mirrorPlanRefs = exactRefs(mirrorPlanRefs, "MIRROR_PLAN", "mirrorPlanRefs");
        businessAssetLinks = businessAssetLinks == null
                ? List.of() : List.copyOf(businessAssetLinks);
        evidenceRefs = exactRefs(evidenceRefs, null, "evidenceRefs");
    }

    /** Creates a fail-closed resolution for an unsupported or absent source. */
    public static PackageDependencyResolution missing(MirrorArtifactRef sourceRef) {
        return new PackageDependencyResolution(new PackageDependencyObservation(
                sourceRef, null, null, null,
                PackageDependencyObservation.Status.MISSING, List.of()),
                null, List.of(), List.of(), List.of());
    }

    private static List<MirrorArtifactRef> exactRefs(
            List<MirrorArtifactRef> values, String kind, String field) {
        List<MirrorArtifactRef> normalized = values == null ? List.of() : values.stream()
                .map(value -> java.util.Objects.requireNonNull(value, field + " item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (normalized.size() > 4_096
                || normalized.stream().distinct().count() != normalized.size()
                || (kind != null && normalized.stream()
                .anyMatch(value -> !kind.equals(value.kind())))) {
            throw new IllegalArgumentException(field + " must contain unique exact references");
        }
        return normalized;
    }
}
