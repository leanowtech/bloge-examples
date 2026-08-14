package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic extraction of every exact dependency declared by a Package draft. */
public final class PackageDependencyRefs {
    private PackageDependencyRefs() {
    }

    /** @return sorted, unique exact source references that an authority must observe */
    public static List<MirrorArtifactRef> from(DomainCapabilityPackageDraft draft) {
        DomainCapabilityPackageDraft exact = java.util.Objects.requireNonNull(draft, "draft");
        Set<MirrorArtifactRef> refs = new LinkedHashSet<>();
        add(refs, exact.businessDefinition().problemTaxonomyRef());
        add(refs, exact.packageContractRef());
        refs.addAll(exact.capabilityRefs());
        refs.addAll(exact.graphRefs());
        refs.addAll(exact.proposalRefs());
        refs.addAll(exact.stateModelRefs());
        refs.addAll(exact.effectModelRefs());
        add(refs, exact.scenarioInventoryRef());
        refs.addAll(exact.scenarioPackRefs());
        refs.addAll(exact.solutionRefs().stream().map(PackageDependencyRefs::artifactRef).toList());
        refs.addAll(exact.carrierRefs().stream().map(PackageDependencyRefs::artifactRef).toList());
        refs.addAll(exact.channelRefs().stream().map(PackageDependencyRefs::artifactRef).toList());
        add(refs, exact.fidelityInventoryRef());
        refs.addAll(exact.outcomeDefinitionRefs());
        return refs.stream().sorted(Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint)).toList();
    }

    private static MirrorArtifactRef artifactRef(BusinessAssetRef ref) {
        return new MirrorArtifactRef(ref.kind().name(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static void add(Set<MirrorArtifactRef> refs, MirrorArtifactRef ref) {
        if (ref != null) {
            refs.add(ref);
        }
    }
}
