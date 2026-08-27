package com.leanowtech.bloge.gateway.testing.verification;

/** Current authoritative binding against which a corpus golden is freshness-checked. */
public record ValidatorAdversarialGateContext(
        String tenantId,
        String purpose,
        String sourceSchemaFingerprint,
        String algorithmVersion,
        String policyFingerprint,
        String goldenFingerprint) {

    public ValidatorAdversarialGateContext {
        tenantId = ValidatorVerificationSupport.token(tenantId);
        purpose = ValidatorVerificationSupport.token(purpose);
        sourceSchemaFingerprint = ValidatorVerificationSupport.fingerprint(sourceSchemaFingerprint);
        algorithmVersion = ValidatorVerificationSupport.token(algorithmVersion);
        policyFingerprint = ValidatorVerificationSupport.fingerprint(policyFingerprint);
        goldenFingerprint = ValidatorVerificationSupport.fingerprint(goldenFingerprint);
    }

    public static ValidatorAdversarialGateContext of(String tenantId, String purpose,
                                                     String sourceSchemaFingerprint, String algorithmVersion,
                                                     String policyFingerprint, String goldenFingerprint) {
        return new ValidatorAdversarialGateContext(tenantId, purpose, sourceSchemaFingerprint,
                algorithmVersion, policyFingerprint, goldenFingerprint);
    }
}
