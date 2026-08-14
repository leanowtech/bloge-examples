package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;

import java.util.List;

/** Fail-closed fallback used until a deployment installs authoritative dependency adapters. */
public final class UnavailablePackageCompilationAuthority implements PackageCompilationAuthority {
    /** Stable generation exposed by the fallback for diagnostics and capability readiness. */
    public static final String GENERATION = "authority-unavailable-v1";

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft source) {
        StoredDomainCapabilityPackageDraft exact = java.util.Objects.requireNonNull(source, "source");
        List<PackageDependencyObservation> missing = PackageDependencyRefs.from(exact.draft()).stream()
                .map(ref -> new PackageDependencyObservation(ref, null, null, null,
                        PackageDependencyObservation.Status.MISSING, List.of()))
                .toList();
        return new FrozenPackageDependencies(exact.scope(), GENERATION, missing, null,
                List.of(), List.of(), List.of(), null, exact.updatedAt());
    }

    @Override
    public void assertUnchanged(FrozenPackageDependencies frozen) {
        if (frozen == null || !GENERATION.equals(frozen.authorityGeneration())) {
            throw new PackageDependencyDriftException("fallback authority generation changed");
        }
    }
}
