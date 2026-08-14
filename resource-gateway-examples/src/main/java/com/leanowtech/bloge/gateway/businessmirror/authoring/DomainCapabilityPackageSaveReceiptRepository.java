package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Optional;
import java.util.function.Supplier;

/** Transactional command lock and exact response journal for Package saves. */
public interface DomainCapabilityPackageSaveReceiptRepository {
    <T> T withCommandLock(CapabilitySnapshot.Scope scope, String idempotencyKey, Supplier<T> operation);

    Optional<DomainCapabilityPackageSaveReceipt> find(
            CapabilitySnapshot.Scope scope, String idempotencyKey);

    void save(CapabilitySnapshot.Scope scope,
              String idempotencyKey,
              DomainCapabilityPackageSaveReceipt receipt);
}
