package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Durable idempotency receipt store for Fixture approval commands. */
public interface FixtureApprovalReceiptRepository {

    Optional<FixtureApprovalReceipt> find(
            EnterpriseScope scope, String idempotencyKeyFingerprint);

    boolean saveIfAbsent(FixtureApprovalReceipt receipt);
}
