package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Durable idempotency receipt boundary for Oracle approval. */
public interface OracleApprovalReceiptRepository {

    Optional<OracleApprovalReceipt> find(
            EnterpriseScope scope,
            String idempotencyKeyFingerprint);

    boolean saveIfAbsent(OracleApprovalReceipt receipt);
}
