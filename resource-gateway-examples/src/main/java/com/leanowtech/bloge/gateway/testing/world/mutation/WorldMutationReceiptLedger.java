package com.leanowtech.bloge.gateway.testing.world.mutation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local replay protection for equivalence receipts. */
public final class WorldMutationReceiptLedger {
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();

    public void consume(WorldMutationEquivalenceAuthority.Verification verification,
                         WorldMutationEquivalenceReceipt receipt, String tenant,
                         WorldMutationPlan plan, WorldMutationPlan.PlannedMutant mutant,
                         String purpose) {
        if (verification == null || !verification.accepted()
                || verification.verifierId() == null || verification.verifierId().isBlank()
                || !receipt.authorityId().equals(verification.verifierId())
                || verification.proofFingerprint() == null
                || !verification.proofFingerprint().matches(WorldMutationEquivalenceAuthority.FINGERPRINT_PATTERN)) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
        receipt.verifyFor(tenant, plan, mutant, purpose);
        if (!consumed.add(receipt.receiptFingerprint())) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_REUSED);
        }
    }

    public int consumedCount() {
        return consumed.size();
    }
}
