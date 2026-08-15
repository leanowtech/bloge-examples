package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Payload-free command: affected exact refs are resolved from the evidence companion. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeCalibrationRequest(
        String proposalId,
        String suiteRunId,
        String evidenceCompanionFingerprint,
        List<String> affectedCaseIds,
        List<String> affectedOracleIds,
        OutcomeCalibrationProposal.MismatchKind mismatchKind,
        String reasonCode,
        String businessRationale,
        String proposedRegressionTitle
) {
    public OutcomeCalibrationRequest {
        proposalId = normalized(proposalId);
        suiteRunId = normalized(suiteRunId);
        evidenceCompanionFingerprint = normalized(evidenceCompanionFingerprint);
        affectedCaseIds = normalizedList(affectedCaseIds);
        affectedOracleIds = normalizedList(affectedOracleIds);
        reasonCode = normalized(reasonCode);
        businessRationale = normalized(businessRationale);
        proposedRegressionTitle = normalized(proposedRegressionTitle);
    }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream().map(OutcomeCalibrationRequest::normalized)
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
