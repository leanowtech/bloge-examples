package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Authority-frozen dependency window consumed by {@link PackageCompiler}.
 *
 * <p>The authority generation is an opaque fencing token. The compiler calls the authority again
 * before returning a successful result, so a mutable head change cannot be hidden by a valid exact
 * revision lookup.</p>
 */
public record FrozenPackageDependencies(
        CapabilitySnapshot.Scope scope,
        String authorityGeneration,
        List<PackageDependencyObservation> observations,
        MirrorArtifactRef capabilityClosureRef,
        List<MirrorArtifactRef> mirrorPlanRefs,
        List<BusinessAssetLink> businessAssetLinks,
        List<MirrorArtifactRef> evidenceRefs,
        MirrorArtifactRef policyGenerationRef,
        Instant capturedAt
) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Normalizes authority output before any compiler decision is made. */
    public FrozenPackageDependencies {
        scope = java.util.Objects.requireNonNull(scope, "scope");
        authorityGeneration = normalized(authorityGeneration);
        if (!IDENTIFIER.matcher(authorityGeneration).matches()) {
            throw new IllegalArgumentException("authorityGeneration is invalid");
        }
        observations = observations == null ? List.of() : observations.stream()
                .map(value -> java.util.Objects.requireNonNull(value, "observation"))
                .sorted(Comparator.comparing((PackageDependencyObservation value) -> value.sourceRef().kind())
                        .thenComparing(value -> value.sourceRef().id())
                        .thenComparingLong(value -> value.sourceRef().revision())
                        .thenComparing(value -> value.sourceRef().fingerprint()))
                .toList();
        if (observations.size() > 4_096
                || observations.stream().map(PackageDependencyObservation::sourceRef).distinct().count()
                != observations.size()) {
            throw new IllegalArgumentException("dependency observations must be unique and bounded");
        }
        capabilityClosureRef = optionalKind(capabilityClosureRef, "CAPABILITY_CLOSURE");
        mirrorPlanRefs = refs(mirrorPlanRefs, "MIRROR_PLAN", "mirrorPlanRefs");
        businessAssetLinks = businessAssetLinks == null ? List.of() : List.copyOf(businessAssetLinks);
        evidenceRefs = immutableRefs(evidenceRefs, "evidenceRefs");
        policyGenerationRef = optionalKind(policyGenerationRef, "PACKAGE_COMPILATION_POLICY");
        capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt");
    }

    private static List<MirrorArtifactRef> refs(
            List<MirrorArtifactRef> values, String kind, String field) {
        List<MirrorArtifactRef> exact = immutableRefs(values, field);
        if (exact.stream().anyMatch(value -> !kind.equals(value.kind()))) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static List<MirrorArtifactRef> immutableRefs(List<MirrorArtifactRef> values, String field) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> java.util.Objects.requireNonNull(value, field + " item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.size() > 4_096 || exact.stream().distinct().count() != exact.size()
                || exact.stream().anyMatch(value -> "GRAPH_DRAFT".equals(value.kind())
                || "CAPABILITY_PROPOSAL".equals(value.kind()))) {
            throw new IllegalArgumentException(field + " must contain unique immutable refs");
        }
        return exact;
    }

    private static MirrorArtifactRef optionalKind(MirrorArtifactRef value, String kind) {
        if (value != null && !kind.equals(value.kind())) {
            throw new IllegalArgumentException("reference must identify " + kind);
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
