package com.leanowtech.bloge.gateway.testing.verification;

/** A validator's payload-free signed/attested outcome; authority validation is external to this value. */
public record ValidatorRejectionReceipt(
        String tenantId,
        String caseCode,
        String caseFingerprint,
        String validatorId,
        String purpose,
        String sourceSchemaFingerprint,
        String algorithmVersion,
        String policyFingerprint,
        String corpusFingerprint,
        String goldenFingerprint,
        Decision decision,
        String diagnosticCode,
        String authorityId,
        String authorityReceiptFingerprint,
        String receiptFingerprint) {

    public enum Decision { REJECTED, ALLOWED }

    public ValidatorRejectionReceipt {
        tenantId = ValidatorVerificationSupport.token(tenantId);
        caseCode = ValidatorVerificationSupport.token(caseCode);
        caseFingerprint = ValidatorVerificationSupport.fingerprint(caseFingerprint);
        validatorId = ValidatorVerificationSupport.token(validatorId);
        purpose = ValidatorVerificationSupport.token(purpose);
        sourceSchemaFingerprint = ValidatorVerificationSupport.fingerprint(sourceSchemaFingerprint);
        algorithmVersion = ValidatorVerificationSupport.token(algorithmVersion);
        policyFingerprint = ValidatorVerificationSupport.fingerprint(policyFingerprint);
        corpusFingerprint = ValidatorVerificationSupport.fingerprint(corpusFingerprint);
        goldenFingerprint = ValidatorVerificationSupport.fingerprint(goldenFingerprint);
        if (decision == null) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        diagnosticCode = ValidatorVerificationSupport.token(diagnosticCode);
        authorityId = ValidatorVerificationSupport.token(authorityId);
        authorityReceiptFingerprint = ValidatorVerificationSupport.fingerprint(authorityReceiptFingerprint);
        receiptFingerprint = ValidatorVerificationSupport.fingerprint(receiptFingerprint);
    }

    public static ValidatorRejectionReceipt create(ValidatorAdversarialCorpus corpus,
                                                    ValidatorAdversarialCase expected,
                                                    ValidatorReceiptAuthority authority, Decision decision,
                                                    String diagnosticCode,
                                                    String authorityReceiptFingerprint) {
        if (corpus == null || expected == null || authority == null) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        String tenant = corpus.tenantId();
        String purpose = expected.purpose();
        String authorityId = authority.authorityId();
        String receiptFingerprint = computeFingerprint(tenant, expected.caseCode().code(), expected.fingerprint(),
                expected.validatorId(),
                purpose, expected.sourceSchemaFingerprint(), expected.algorithmVersion(),
                expected.policyFingerprint(), corpus.fingerprint(), expected.goldenFingerprint(), decision,
                diagnosticCode, authorityId, authorityReceiptFingerprint);
        return new ValidatorRejectionReceipt(tenant, expected.caseCode().code(), expected.fingerprint(),
                expected.validatorId(), purpose,
                expected.sourceSchemaFingerprint(), expected.algorithmVersion(), expected.policyFingerprint(),
                corpus.fingerprint(), expected.goldenFingerprint(), decision, diagnosticCode, authorityId,
                authorityReceiptFingerprint, receiptFingerprint);
    }

    public static String computeFingerprint(String tenantId, String caseCode, String caseFingerprint,
                                            String validatorId, String purpose,
                                            String sourceSchemaFingerprint, String algorithmVersion,
                                            String policyFingerprint, String corpusFingerprint,
                                            String goldenFingerprint, Decision decision, String diagnosticCode,
                                            String authorityId, String authorityReceiptFingerprint) {
        return ValidatorVerificationSupport.hash("tenant", tenantId, "case", caseCode, "caseFingerprint",
                caseFingerprint, "validator", validatorId,
                "purpose", purpose, "sourceSchema", sourceSchemaFingerprint, "algorithm", algorithmVersion,
                "policy", policyFingerprint, "corpus", corpusFingerprint, "golden", goldenFingerprint,
                "decision", decision, "diagnostic", diagnosticCode, "authority", authorityId,
                "authorityReceipt", authorityReceiptFingerprint);
    }
}
