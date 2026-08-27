package com.leanowtech.bloge.gateway.testing.world.mutation;

/** External trust boundary for equivalence; receipt metadata alone is never proof. */
@FunctionalInterface
public interface WorldMutationEquivalenceAuthority {
    String FINGERPRINT_PATTERN = "sha256:[a-f0-9]{64}";

    Verification verify(WorldMutationEquivalenceReceipt receipt, String tenantId,
                         WorldMutationPlan plan, WorldMutationPlan.PlannedMutant mutant,
                         String purpose);

    record Verification(boolean accepted, String verifierId, String proofFingerprint) {
        public Verification {
            if (accepted && (verifierId == null || verifierId.isBlank()
                    || proofFingerprint == null || !proofFingerprint.matches(FINGERPRINT_PATTERN))) {
                throw new IllegalArgumentException("accepted equivalence requires external proof");
            }
        }

        public static Verification rejected() {
            return new Verification(false, "", "");
        }
    }

    static WorldMutationEquivalenceAuthority rejecting() {
        return (receipt, tenantId, plan, mutant, purpose) -> Verification.rejected();
    }
}
