package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;

/** Complete deterministic output of one fenced Package compile attempt. */
public record PackageCompilationResult(
        PackageReadinessReport readiness,
        BusinessAssetLinkClosure businessAssetLinkClosure,
        DomainCapabilityPackageSnapshot snapshot,
        FrozenPackageDependencies frozenDependencies
) {
    public PackageCompilationResult {
        readiness = java.util.Objects.requireNonNull(readiness, "readiness");
        businessAssetLinkClosure = java.util.Objects.requireNonNull(
                businessAssetLinkClosure, "businessAssetLinkClosure");
        frozenDependencies = java.util.Objects.requireNonNull(frozenDependencies, "frozenDependencies");
        if ((readiness.status() == PackageReadinessReport.Status.BLOCKED) != (snapshot == null)) {
            throw new IllegalArgumentException("blocked compilation must not publish a Package snapshot");
        }
    }

    /** @return whether this attempt produced an immutable Package snapshot */
    public boolean compiled() {
        return snapshot != null;
    }
}
