package com.leanowtech.bloge.gateway.testing.verification;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;

/** The only gate projection: case code, status, security diagnostic and fingerprints. */
public record ValidatorAdversarialGateReport(
        ValidatorAdversarialGateStatus status,
        List<CaseResult> cases,
        String fingerprint) {

    public ValidatorAdversarialGateReport {
        if (status == null || cases == null || cases.isEmpty()) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        if (cases.stream().anyMatch(Objects::isNull)) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        cases = cases.stream()
                .sorted(Comparator.comparing(CaseResult::caseCode))
                .toList();
        fingerprint = ValidatorVerificationSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(status, cases))) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.FINGERPRINT_MISMATCH);
        }
    }

    public static String computeFingerprint(ValidatorAdversarialGateStatus status, List<CaseResult> cases) {
        if (status == null || cases == null || cases.isEmpty() || cases.stream().anyMatch(Objects::isNull)) {
            throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        List<CaseResult> canonical = cases.stream()
                .sorted(Comparator.comparing(CaseResult::caseCode)).toList();
        return ValidatorVerificationSupport.hash("status", status,
                "cases", canonical.stream().map(value -> List.of(value.caseCode(), value.status().name(),
                        value.diagnosticCode().name(), value.fingerprint())).toList());
    }

    public record CaseResult(String caseCode, ValidatorAdversarialCaseStatus status,
                             ValidatorSecurityDiagnostic diagnosticCode, String fingerprint) {
        public CaseResult {
            caseCode = ValidatorVerificationSupport.token(caseCode);
            if (status == null || diagnosticCode == null) {
                throw ValidatorVerificationSupport.fail(ValidatorVerificationException.Code.INVALID_INPUT);
            }
            fingerprint = ValidatorVerificationSupport.fingerprint(fingerprint);
        }
    }
}
