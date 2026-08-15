package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** ANEKE-authored decision body; Resource Gateway derives scope, receiver, and exact Publication. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessGovernanceFeedbackRequest(
        String feedbackId,
        String publicationFingerprint,
        String sourceSystem,
        String sourceProtocolVersion,
        String sourceDecisionId,
        long sourceDecisionRevision,
        String sourceDecisionFingerprint,
        CorrectnessGovernanceFeedback.GateDecision decision,
        CorrectnessGovernanceFeedback.WorkbookStatus workbookStatus,
        CorrectnessGovernanceFeedback.OwnerApprovalStatus ownerApprovalStatus,
        CorrectnessGovernanceFeedback.BreakingMigrationStatus breakingMigrationStatus,
        List<CorrectnessGovernanceFeedback.Finding> findings,
        Instant producedAt,
        Instant expiresAt
) {
    public CorrectnessGovernanceFeedbackRequest {
        feedbackId = normalized(feedbackId);
        publicationFingerprint = normalized(publicationFingerprint);
        sourceSystem = normalized(sourceSystem);
        sourceProtocolVersion = normalized(sourceProtocolVersion);
        sourceDecisionId = normalized(sourceDecisionId);
        sourceDecisionFingerprint = normalized(sourceDecisionFingerprint);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
