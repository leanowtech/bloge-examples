package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Optional;

/** Append-only Package compilation facts with a per-Package revision allocator. */
public interface PackageCompilationFactRepository {
    long reserveRevision(CapabilitySnapshot.Scope scope, String packageId);

    void append(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt);

    Optional<PackageCompilationReceipt> find(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision);
}
