package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic composite over independently owned Package dependency authorities.
 *
 * <p>The composite rejects duplicate source-kind ownership, resolves every declared ref exactly
 * once, and re-runs the same resolution immediately before publication. Unsupported kinds remain
 * explicit {@code MISSING} observations; they never fall through to a second registry.</p>
 */
public final class CompositePackageCompilationAuthority implements PackageCompilationAuthority {
    /** Code-owned fail-closed policy generation for the first composite authority. */
    public static final String POLICY_ID = "business-mirror-package-compilation-policy";
    public static final long POLICY_REVISION = 1;
    private static final int MAXIMUM_GENERATION_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final Map<String, PackageDependencyAuthorityAdapter> adaptersByKind;
    private final List<String> adapterIds;
    private final MirrorArtifactRef policyRef;

    public CompositePackageCompilationAuthority(
            ObjectMapper mapper, List<PackageDependencyAuthorityAdapter> adapters) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Map<String, PackageDependencyAuthorityAdapter> indexed = new LinkedHashMap<>();
        List<PackageDependencyAuthorityAdapter> exactAdapters = adapters == null
                ? List.of() : adapters.stream()
                .map(value -> Objects.requireNonNull(value, "authority adapter"))
                .sorted(Comparator.comparing(PackageDependencyAuthorityAdapter::adapterId))
                .toList();
        Set<String> identities = new LinkedHashSet<>();
        for (PackageDependencyAuthorityAdapter adapter : exactAdapters) {
            String adapterId = required(adapter.adapterId(), "adapterId");
            if (!identities.add(adapterId)) {
                throw new IllegalArgumentException("duplicate Package authority adapter id");
            }
            Set<String> kinds = adapter.sourceKinds() == null
                    ? Set.of() : Set.copyOf(adapter.sourceKinds());
            if (kinds.isEmpty()) {
                throw new IllegalArgumentException("Package authority adapter owns no source kind");
            }
            for (String kind : kinds.stream().sorted().toList()) {
                String exactKind = required(kind, "sourceKind").toUpperCase(java.util.Locale.ROOT);
                if (indexed.putIfAbsent(exactKind, adapter) != null) {
                    throw new IllegalArgumentException(
                            "multiple Package authority adapters own source kind " + exactKind);
                }
            }
        }
        this.adaptersByKind = Map.copyOf(indexed);
        this.adapterIds = List.copyOf(identities);
        this.policyRef = policyRef(mapper, this.adapterIds, this.adaptersByKind.keySet());
    }

    @Override
    public boolean ready() {
        return !adaptersByKind.isEmpty();
    }

    /** Returns the exact source kinds for which this deployment has an authority adapter. */
    public Set<String> supportedSourceKinds() {
        return adaptersByKind.keySet().stream().sorted().collect(
                java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft source) {
        StoredDomainCapabilityPackageDraft exact = Objects.requireNonNull(source, "source");
        return freeze(exact.scope(), PackageDependencyRefs.from(exact.draft()), exact.updatedAt());
    }

    @Override
    public void assertUnchanged(FrozenPackageDependencies frozen) {
        FrozenPackageDependencies exact = Objects.requireNonNull(frozen, "frozen");
        List<MirrorArtifactRef> sourceRefs = exact.observations().stream()
                .map(PackageDependencyObservation::sourceRef)
                .toList();
        FrozenPackageDependencies current = freeze(exact.scope(), sourceRefs, exact.capturedAt());
        if (!exact.authorityGeneration().equals(current.authorityGeneration())
                || !exact.observations().equals(current.observations())
                || !Objects.equals(exact.capabilityClosureRef(), current.capabilityClosureRef())
                || !exact.mirrorPlanRefs().equals(current.mirrorPlanRefs())
                || !exact.businessAssetLinks().equals(current.businessAssetLinks())
                || !exact.evidenceRefs().equals(current.evidenceRefs())
                || !Objects.equals(exact.policyGenerationRef(), current.policyGenerationRef())) {
            throw new PackageDependencyDriftException(
                    "Package dependency authority changed inside the compilation window");
        }
    }

    private FrozenPackageDependencies freeze(
            CapabilitySnapshot.Scope scope,
            List<MirrorArtifactRef> sourceRefs,
            java.time.Instant capturedAt) {
        List<PackageDependencyResolution> resolutions = new ArrayList<>();
        for (MirrorArtifactRef sourceRef : sourceRefs) {
            PackageDependencyAuthorityAdapter adapter = adaptersByKind.get(sourceRef.kind());
            PackageDependencyResolution resolution = adapter == null
                    ? PackageDependencyResolution.missing(sourceRef)
                    : Objects.requireNonNull(adapter.resolve(scope, sourceRef),
                    "authority adapter resolution");
            if (!sourceRef.equals(resolution.observation().sourceRef())) {
                throw new IllegalArgumentException(
                        "authority adapter changed the requested source reference");
            }
            resolutions.add(resolution);
        }

        List<PackageDependencyObservation> observations = resolutions.stream()
                .map(PackageDependencyResolution::observation).toList();
        List<MirrorArtifactRef> closureRefs = resolutions.stream()
                .map(PackageDependencyResolution::capabilityClosureRef)
                .filter(Objects::nonNull).distinct().toList();
        MirrorArtifactRef capabilityClosureRef = closureRefs.size() == 1
                ? closureRefs.getFirst() : null;
        List<MirrorArtifactRef> mirrorPlans = resolutions.stream()
                .flatMap(value -> value.mirrorPlanRefs().stream()).distinct().toList();
        List<BusinessAssetLink> links = resolutions.stream()
                .flatMap(value -> value.businessAssetLinks().stream()).distinct().toList();
        List<MirrorArtifactRef> evidence = resolutions.stream()
                .flatMap(value -> value.evidenceRefs().stream()).distinct().toList();
        String generation = generation(scope, observations, closureRefs, mirrorPlans,
                links, evidence, policyRef);
        return new FrozenPackageDependencies(scope, generation, observations,
                capabilityClosureRef, mirrorPlans, links, evidence, policyRef, capturedAt);
    }

    private String generation(
            CapabilitySnapshot.Scope scope,
            List<PackageDependencyObservation> observations,
            List<MirrorArtifactRef> closureRefs,
            List<MirrorArtifactRef> mirrorPlanRefs,
            List<BusinessAssetLink> links,
            List<MirrorArtifactRef> evidenceRefs,
            MirrorArtifactRef generationPolicyRef) {
        GenerationMaterial material = new GenerationMaterial(
                adapterIds, scope, observations, closureRefs, mirrorPlanRefs,
                links, evidenceRefs, generationPolicyRef);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_GENERATION_BYTES);
        return "composite-authority-v1:" + fingerprint.substring("sha256:".length());
    }

    private static MirrorArtifactRef policyRef(
            ObjectMapper mapper, List<String> adapterIds, Set<String> kinds) {
        Map<String, Object> material = Map.of(
                "policyId", POLICY_ID,
                "revision", POLICY_REVISION,
                "compilerVersion", PackageCompiler.COMPILER_VERSION,
                "failClosedUnsupportedKinds", true,
                "duplicateKindOwnershipForbidden", true,
                "adapterIds", adapterIds,
                "sourceKinds", kinds.stream().sorted().toList());
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_GENERATION_BYTES);
        return new MirrorArtifactRef(
                "PACKAGE_COMPILATION_POLICY", POLICY_ID, POLICY_REVISION, fingerprint);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record GenerationMaterial(
            List<String> adapterIds,
            CapabilitySnapshot.Scope scope,
            List<PackageDependencyObservation> observations,
            List<MirrorArtifactRef> capabilityClosureRefs,
            List<MirrorArtifactRef> mirrorPlanRefs,
            List<BusinessAssetLink> businessAssetLinks,
            List<MirrorArtifactRef> evidenceRefs,
            MirrorArtifactRef policyGenerationRef
    ) {
    }
}
