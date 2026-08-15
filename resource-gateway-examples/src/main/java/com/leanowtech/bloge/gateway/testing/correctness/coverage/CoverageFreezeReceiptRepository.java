package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Scope-exact idempotency authority for irreversible freeze commands. */
public interface CoverageFreezeReceiptRepository {

    Optional<CoverageFreezeReceipt> find(
            EnterpriseScope scope,
            String idempotencyKeyFingerprint);

    boolean saveIfAbsent(CoverageFreezeReceipt receipt);
}
