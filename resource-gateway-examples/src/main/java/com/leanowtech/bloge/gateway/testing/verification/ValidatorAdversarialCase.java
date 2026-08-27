package com.leanowtech.bloge.gateway.testing.verification;

/** Payload-free, content-addressed definition of one must-reject control. */
public record ValidatorAdversarialCase(
        ValidatorAdversarialCaseCode caseCode,
        String validatorId,
        String purpose,
        String sourceSchemaFingerprint,
        String algorithmVersion,
        String policyFingerprint,
        String goldenFingerprint,
        String expectedRejectionCode,
        String fingerprint) {

    public ValidatorAdversarialCase {
        if (caseCode == null) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        validatorId = ValidatorVerificationSupport.token(validatorId);
        purpose = ValidatorVerificationSupport.token(purpose);
        sourceSchemaFingerprint = ValidatorVerificationSupport.fingerprint(sourceSchemaFingerprint);
        algorithmVersion = ValidatorVerificationSupport.token(algorithmVersion);
        policyFingerprint = ValidatorVerificationSupport.fingerprint(policyFingerprint);
        goldenFingerprint = ValidatorVerificationSupport.fingerprint(goldenFingerprint);
        expectedRejectionCode = ValidatorVerificationSupport.token(expectedRejectionCode);
        fingerprint = ValidatorVerificationSupport.fingerprint(fingerprint);
        if (!caseCode.validatorId().equals(validatorId)
                || !caseCode.expectedRejectionCode().equals(expectedRejectionCode)) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.CORPUS_SHAPE_INVALID);
        }
        if (!fingerprint.equals(computeFingerprint(caseCode, validatorId, purpose, sourceSchemaFingerprint,
                algorithmVersion, policyFingerprint, goldenFingerprint, expectedRejectionCode))) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.FINGERPRINT_MISMATCH);
        }
    }

    public static ValidatorAdversarialCase create(ValidatorAdversarialCaseCode caseCode, String purpose,
                                                   String sourceSchemaFingerprint, String algorithmVersion,
                                                   String policyFingerprint, String goldenFingerprint) {
        if (caseCode == null) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        String validatorId = caseCode.validatorId();
        String rejection = caseCode.expectedRejectionCode();
        return new ValidatorAdversarialCase(caseCode, validatorId, purpose, sourceSchemaFingerprint,
                algorithmVersion, policyFingerprint, goldenFingerprint, rejection,
                computeFingerprint(caseCode, validatorId, purpose, sourceSchemaFingerprint, algorithmVersion,
                        policyFingerprint, goldenFingerprint, rejection));
    }

    private static String computeFingerprint(ValidatorAdversarialCaseCode caseCode, String validatorId,
                                             String purpose, String sourceSchemaFingerprint,
                                             String algorithmVersion, String policyFingerprint,
                                             String goldenFingerprint, String expectedRejectionCode) {
        return ValidatorVerificationSupport.hash("case", caseCode.code(), "validator", validatorId,
                "purpose", purpose, "sourceSchema", sourceSchemaFingerprint, "algorithm", algorithmVersion,
                "policy", policyFingerprint, "golden", goldenFingerprint, "rejection", expectedRejectionCode);
    }
}
