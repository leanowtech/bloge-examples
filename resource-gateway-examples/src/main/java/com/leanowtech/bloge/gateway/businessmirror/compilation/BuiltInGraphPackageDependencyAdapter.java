package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Package dependency authority for shipped GraphDraft and graph Contract projections. */
public final class BuiltInGraphPackageDependencyAdapter
        implements PackageDependencyAuthorityAdapter {
    public static final String ADAPTER_ID = "built-in-graph-asset-authority-v1";
    private static final Set<String> SOURCE_KINDS = Set.of("GRAPH_DRAFT", "CONTRACT");

    private final BuiltInGraphAssetAuthority authority;

    public BuiltInGraphPackageDependencyAdapter(BuiltInGraphAssetAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public Set<String> sourceKinds() {
        return SOURCE_KINDS;
    }

    @Override
    public PackageDependencyResolution resolve(
            CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sourceRef, "sourceRef");
        return switch (sourceRef.kind()) {
            case "GRAPH_DRAFT" -> resolveGraph(scope, sourceRef);
            case "CONTRACT" -> resolveContract(scope, sourceRef);
            default -> PackageDependencyResolution.missing(sourceRef);
        };
    }

    private PackageDependencyResolution resolveGraph(
            CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef) {
        String graphName = BuiltInGraphAssetAuthority.graphNameFromGraphId(sourceRef.id());
        BuiltInGraphAssetAuthority.Snapshot snapshot = find(scope, graphName);
        if (snapshot == null) {
            return PackageDependencyResolution.missing(sourceRef);
        }
        if (!sourceRef.equals(snapshot.graphRef())) {
            return mismatch(sourceRef, snapshot.graphRef());
        }
        List<MirrorArtifactRef> evidence = new ArrayList<>();
        evidence.add(snapshot.contractRef());
        evidence.addAll(snapshot.testSuiteRefs());
        return new PackageDependencyResolution(
                resolved(sourceRef, snapshot.rootCapabilityRef(), snapshot.graphRef(), scope),
                snapshot.capabilityClosureRef(), List.of(), List.of(), evidence);
    }

    private PackageDependencyResolution resolveContract(
            CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef) {
        String graphName = BuiltInGraphAssetAuthority.graphNameFromContractId(sourceRef.id());
        BuiltInGraphAssetAuthority.Snapshot snapshot = find(scope, graphName);
        if (snapshot == null) {
            return PackageDependencyResolution.missing(sourceRef);
        }
        if (!sourceRef.equals(snapshot.contractRef())) {
            return mismatch(sourceRef, snapshot.contractRef());
        }
        return new PackageDependencyResolution(
                resolved(sourceRef, snapshot.contractRef(), snapshot.contractRef(), scope),
                null, List.of(), List.of(), snapshot.testSuiteRefs());
    }

    private BuiltInGraphAssetAuthority.Snapshot find(
            CapabilitySnapshot.Scope scope, String graphName) {
        if (graphName.isBlank() || !authority.graphNames().contains(graphName)) {
            return null;
        }
        return authority.resolve(scope, graphName);
    }

    private static PackageDependencyObservation resolved(
            MirrorArtifactRef sourceRef,
            MirrorArtifactRef materializedRef,
            MirrorArtifactRef observedHeadRef,
            CapabilitySnapshot.Scope scope) {
        return new PackageDependencyObservation(
                sourceRef, materializedRef, observedHeadRef, scope,
                PackageDependencyObservation.Status.RESOLVED,
                List.of(PackageDependencyObservation.Assurance.SCHEMA_VALID));
    }

    private static PackageDependencyResolution mismatch(
            MirrorArtifactRef sourceRef, MirrorArtifactRef observedHeadRef) {
        return new PackageDependencyResolution(new PackageDependencyObservation(
                sourceRef, null, observedHeadRef, null,
                PackageDependencyObservation.Status.FINGERPRINT_MISMATCH, List.of()),
                null, List.of(), List.of(), List.of());
    }
}
