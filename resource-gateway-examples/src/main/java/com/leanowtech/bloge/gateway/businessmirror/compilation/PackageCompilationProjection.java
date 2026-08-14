package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

/** Transactional outbox admission for projections derived from immutable Package facts. */
@FunctionalInterface
public interface PackageCompilationProjection {
    void enqueue(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt);

    static PackageCompilationProjection none() {
        return (scope, receipt) -> {
        };
    }
}
