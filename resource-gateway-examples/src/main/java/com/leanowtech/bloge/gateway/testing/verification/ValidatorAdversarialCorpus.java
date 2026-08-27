package com.leanowtech.bloge.gateway.testing.verification;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** Immutable tenant-scoped and content-addressed corpus of mandatory negative controls. */
public record ValidatorAdversarialCorpus(
        String schemaVersion,
        int corpusVersion,
        String tenantId,
        List<ValidatorAdversarialCase> cases,
        String fingerprint) {

    public ValidatorAdversarialCorpus {
        if (!ValidatorVerificationSupport.SCHEMA_VERSION.equals(schemaVersion) || corpusVersion < 1) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        tenantId = ValidatorVerificationSupport.token(tenantId);
        cases = canonicalCases(cases);
        fingerprint = ValidatorVerificationSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(schemaVersion, corpusVersion, tenantId, cases))) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.FINGERPRINT_MISMATCH);
        }
    }

    public static ValidatorAdversarialCorpus create(String tenantId, int corpusVersion, List<ValidatorAdversarialCase> cases) {
        List<ValidatorAdversarialCase> canonical = canonicalCases(cases);
        return new ValidatorAdversarialCorpus(ValidatorVerificationSupport.SCHEMA_VERSION, corpusVersion,
                tenantId, canonical, computeFingerprint(ValidatorVerificationSupport.SCHEMA_VERSION,
                        corpusVersion, tenantId, canonical));
    }

    /** Builds the complete six-case baseline for one exact validator environment. */
    public static ValidatorAdversarialCorpus standard(String tenantId, String purpose,
                                                      String sourceSchemaFingerprint, String algorithmVersion,
                                                      String policyFingerprint, String goldenFingerprint) {
        List<ValidatorAdversarialCase> cases = Arrays.stream(ValidatorAdversarialCaseCode.values())
                .map(code -> ValidatorAdversarialCase.create(code, purpose, sourceSchemaFingerprint,
                        algorithmVersion, policyFingerprint, goldenFingerprint))
                .toList();
        return create(tenantId, 1, cases);
    }

    public String purpose() {
        return cases.getFirst().purpose();
    }

    public String sourceSchemaFingerprint() {
        return cases.getFirst().sourceSchemaFingerprint();
    }

    public String algorithmVersion() {
        return cases.getFirst().algorithmVersion();
    }

    public String policyFingerprint() {
        return cases.getFirst().policyFingerprint();
    }

    public String goldenFingerprint() {
        return cases.getFirst().goldenFingerprint();
    }

    private static List<ValidatorAdversarialCase> canonicalCases(List<ValidatorAdversarialCase> values) {
        if (values == null || values.size() != ValidatorAdversarialCaseCode.values().length) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.CORPUS_SHAPE_INVALID);
        }
        List<ValidatorAdversarialCase> copy = values.stream()
                .peek(value -> {
                    if (value == null) {
                        throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.CORPUS_SHAPE_INVALID);
                    }
                })
                .sorted(Comparator.comparing(value -> value.caseCode().code()))
                .toList();
        EnumSet<ValidatorAdversarialCaseCode> codes = EnumSet.noneOf(ValidatorAdversarialCaseCode.class);
        ValidatorAdversarialCase first = copy.getFirst();
        for (ValidatorAdversarialCase value : copy) {
            if (!codes.add(value.caseCode()) || !sameBinding(first, value)) {
                throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.CORPUS_SHAPE_INVALID);
            }
        }
        if (codes.size() != ValidatorAdversarialCaseCode.values().length) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.CORPUS_SHAPE_INVALID);
        }
        return List.copyOf(copy);
    }

    private static boolean sameBinding(ValidatorAdversarialCase first, ValidatorAdversarialCase value) {
        return first.purpose().equals(value.purpose())
                && first.sourceSchemaFingerprint().equals(value.sourceSchemaFingerprint())
                && first.algorithmVersion().equals(value.algorithmVersion())
                && first.policyFingerprint().equals(value.policyFingerprint())
                && first.goldenFingerprint().equals(value.goldenFingerprint());
    }

    private static String computeFingerprint(String schemaVersion, int corpusVersion, String tenantId,
                                             List<ValidatorAdversarialCase> cases) {
        return ValidatorVerificationSupport.hash("schema", schemaVersion, "version", corpusVersion,
                "tenant", tenantId, "cases", cases.stream().map(ValidatorAdversarialCase::fingerprint).toList());
    }
}
