package com.leanowtech.bloge.gateway.testing.verification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies the closed proof set for validator negative controls. It never executes a validator and never reads
 * a payload; only an independently authenticated receipt can close a case.
 */
public final class ValidatorAdversarialCorpusGate {
    public ValidatorAdversarialGateReport evaluate(ValidatorAdversarialCorpus corpus,
                                                    ValidatorAdversarialGateContext context,
                                                    Collection<ValidatorRejectionReceipt> receipts,
                                                    ValidatorReceiptAuthority authority) {
        if (corpus == null || context == null) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        if (!corpus.tenantId().equals(context.tenantId())) {
            return report(corpus, ValidatorAdversarialGateStatus.BLOCKED,
                    corpus.cases().stream().map(value -> result(value, ValidatorAdversarialCaseStatus.BLOCKED,
                            ValidatorSecurityDiagnostic.TENANT_SCOPE_MISMATCH)).toList());
        }
        if (isStale(corpus, context)) {
            return report(corpus, ValidatorAdversarialGateStatus.STALE_GOLDEN,
                    corpus.cases().stream().map(value -> result(value, ValidatorAdversarialCaseStatus.STALE_GOLDEN,
                            ValidatorSecurityDiagnostic.STALE_GOLDEN)).toList());
        }

        List<ValidatorRejectionReceipt> supplied = receipts == null ? List.of() : new ArrayList<>(receipts);
        Map<String, List<ValidatorRejectionReceipt>> byCase = new HashMap<>();
        boolean malformed = false;
        for (ValidatorRejectionReceipt receipt : supplied) {
            if (receipt == null) {
                malformed = true;
                continue;
            }
            byCase.computeIfAbsent(receipt.caseCode(), ignored -> new ArrayList<>()).add(receipt);
        }
        boolean setMismatch = supplied.size() != corpus.cases().size()
                || byCase.keySet().stream().anyMatch(code -> corpus.cases().stream()
                .noneMatch(expected -> expected.caseCode().code().equals(code)))
                || byCase.values().stream().anyMatch(values -> values.size() != 1)
                || malformed;

        List<ValidatorAdversarialGateReport.CaseResult> results = new ArrayList<>();
        for (ValidatorAdversarialCase expected : corpus.cases()) {
            List<ValidatorRejectionReceipt> candidates = byCase.getOrDefault(expected.caseCode().code(), List.of());
            if (candidates.isEmpty()) {
                results.add(result(expected, ValidatorAdversarialCaseStatus.BLOCKED,
                        ValidatorSecurityDiagnostic.MISSING_RECEIPT));
                continue;
            }
            if (candidates.size() != 1) {
                results.add(result(expected, ValidatorAdversarialCaseStatus.BLOCKED,
                        ValidatorSecurityDiagnostic.RECEIPT_SET_MISMATCH));
                continue;
            }
            ValidatorRejectionReceipt receipt = candidates.getFirst();
            ValidatorSecurityDiagnostic diagnostic = validateReceipt(expected, corpus, context, receipt, authority);
            if (diagnostic != null) {
                results.add(result(expected, ValidatorAdversarialCaseStatus.BLOCKED, diagnostic));
            } else {
                results.add(result(expected, ValidatorAdversarialCaseStatus.REJECTION_VERIFIED,
                        ValidatorSecurityDiagnostic.REJECTION_VERIFIED));
            }
        }
        if (setMismatch) {
            results.replaceAll(value -> value.status() == ValidatorAdversarialCaseStatus.REJECTION_VERIFIED
                    ? new ValidatorAdversarialGateReport.CaseResult(value.caseCode(),
                    ValidatorAdversarialCaseStatus.BLOCKED, ValidatorSecurityDiagnostic.RECEIPT_SET_MISMATCH,
                    value.fingerprint()) : value);
        }
        ValidatorAdversarialGateStatus status = results.stream()
                .allMatch(value -> value.status() == ValidatorAdversarialCaseStatus.REJECTION_VERIFIED)
                ? ValidatorAdversarialGateStatus.PASSED : ValidatorAdversarialGateStatus.BLOCKED;
        return report(corpus, status, results);
    }

    private static ValidatorSecurityDiagnostic validateReceipt(ValidatorAdversarialCase expected,
                                                               ValidatorAdversarialCorpus corpus,
                                                               ValidatorAdversarialGateContext context,
                                                               ValidatorRejectionReceipt receipt,
                                                               ValidatorReceiptAuthority authority) {
        if (authority == null) {
            return ValidatorSecurityDiagnostic.RECEIPT_AUTHORITY_UNAVAILABLE;
        }
        final String authorityId;
        try {
            authorityId = ValidatorVerificationSupport.token(authority.authorityId());
        } catch (RuntimeException ignored) {
            return ValidatorSecurityDiagnostic.RECEIPT_AUTHORITY_UNAVAILABLE;
        }
        if (!receipt.tenantId().equals(context.tenantId()) || !receipt.tenantId().equals(corpus.tenantId())) {
            return ValidatorSecurityDiagnostic.TENANT_SCOPE_MISMATCH;
        }
        if (!receipt.purpose().equals(context.purpose()) || !receipt.purpose().equals(expected.purpose())) {
            return ValidatorSecurityDiagnostic.PURPOSE_MISMATCH;
        }
        if (!receipt.caseCode().equals(expected.caseCode().code())
                || !receipt.caseFingerprint().equals(expected.fingerprint())
                || !receipt.validatorId().equals(expected.validatorId())
                || !receipt.sourceSchemaFingerprint().equals(expected.sourceSchemaFingerprint())
                || !receipt.algorithmVersion().equals(expected.algorithmVersion())
                || !receipt.policyFingerprint().equals(expected.policyFingerprint())
                || !receipt.corpusFingerprint().equals(corpus.fingerprint())
                || !receipt.goldenFingerprint().equals(expected.goldenFingerprint())
                || !receipt.authorityId().equals(authorityId)
                || !receipt.diagnosticCode().equals(expected.expectedRejectionCode())) {
            return ValidatorSecurityDiagnostic.RECEIPT_BINDING_MISMATCH;
        }
        String computed = ValidatorRejectionReceipt.computeFingerprint(receipt.tenantId(), receipt.caseCode(),
                receipt.caseFingerprint(), receipt.validatorId(), receipt.purpose(), receipt.sourceSchemaFingerprint(),
                receipt.algorithmVersion(), receipt.policyFingerprint(), receipt.corpusFingerprint(),
                receipt.goldenFingerprint(), receipt.decision(), receipt.diagnosticCode(), receipt.authorityId(),
                receipt.authorityReceiptFingerprint());
        if (!computed.equals(receipt.receiptFingerprint())) {
            return ValidatorSecurityDiagnostic.RECEIPT_TAMPERED;
        }
        if (receipt.decision() != ValidatorRejectionReceipt.Decision.REJECTED) {
            return ValidatorSecurityDiagnostic.VALIDATOR_DID_NOT_REJECT;
        }
        try {
            ValidatorReceiptAuthority.Verification verification = authority.verify(expected, receipt);
            return verification != null && verification.status() == ValidatorReceiptAuthority.Status.VERIFIED
                    ? null : ValidatorSecurityDiagnostic.RECEIPT_NOT_AUTHORIZED;
        } catch (RuntimeException ignored) {
            return ValidatorSecurityDiagnostic.RECEIPT_NOT_AUTHORIZED;
        }
    }

    private static boolean isStale(ValidatorAdversarialCorpus corpus, ValidatorAdversarialGateContext context) {
        ValidatorAdversarialCase binding = corpus.cases().getFirst();
        return !binding.sourceSchemaFingerprint().equals(context.sourceSchemaFingerprint())
                || !binding.algorithmVersion().equals(context.algorithmVersion())
                || !binding.policyFingerprint().equals(context.policyFingerprint())
                || !binding.goldenFingerprint().equals(context.goldenFingerprint());
    }

    private static ValidatorAdversarialGateReport.CaseResult result(ValidatorAdversarialCase expected,
                                                                    ValidatorAdversarialCaseStatus status,
                                                                    ValidatorSecurityDiagnostic diagnostic) {
        return new ValidatorAdversarialGateReport.CaseResult(expected.caseCode().code(), status, diagnostic,
                expected.fingerprint());
    }

    private static ValidatorAdversarialGateReport report(ValidatorAdversarialCorpus corpus,
                                                         ValidatorAdversarialGateStatus status,
                                                         List<ValidatorAdversarialGateReport.CaseResult> cases) {
        List<ValidatorAdversarialGateReport.CaseResult> canonical = cases.stream()
                .sorted(Comparator.comparing(ValidatorAdversarialGateReport.CaseResult::caseCode)).toList();
        String fingerprint = ValidatorAdversarialGateReport.computeFingerprint(status, canonical);
        return new ValidatorAdversarialGateReport(status, canonical, fingerprint);
    }
}
