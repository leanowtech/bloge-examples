package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Optional;
import java.util.function.Supplier;

/** Transactional command lock and exact response journal for Capability Proposal saves. */
public interface CapabilityProposalSaveReceiptRepository {
    <T> T withCommandLock(
            CapabilitySnapshot.Scope scope, String idempotencyKey, Supplier<T> operation);

    Optional<CapabilityProposalSaveReceipt> find(
            CapabilitySnapshot.Scope scope, String idempotencyKey);

    void save(CapabilitySnapshot.Scope scope,
              String idempotencyKey,
              CapabilityProposalSaveReceipt receipt);
}
