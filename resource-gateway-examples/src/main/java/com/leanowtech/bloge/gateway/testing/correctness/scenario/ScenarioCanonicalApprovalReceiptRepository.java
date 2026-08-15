package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Durable idempotency receipt boundary for canonical Scenario Case approval. */
public interface ScenarioCanonicalApprovalReceiptRepository {

    Optional<ScenarioCanonicalApprovalReceipt> find(
            EnterpriseScope scope,
            String idempotencyKeyFingerprint);

    boolean saveIfAbsent(ScenarioCanonicalApprovalReceipt receipt);
}
