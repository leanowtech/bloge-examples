package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Optional;
import java.util.function.Supplier;

/** Transactional command lock and exact response journal for Package compilation. */
public interface PackageCompilationReceiptRepository {
    <T> T withCommandLock(CapabilitySnapshot.Scope scope, String idempotencyKey, Supplier<T> operation);

    Optional<PackageCompilationReceipt> find(
            CapabilitySnapshot.Scope scope, String idempotencyKey);

    void save(CapabilitySnapshot.Scope scope,
              String idempotencyKey,
              PackageCompilationReceipt receipt);
}
