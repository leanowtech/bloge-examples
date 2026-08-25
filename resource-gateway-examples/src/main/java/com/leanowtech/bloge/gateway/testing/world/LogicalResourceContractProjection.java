package com.leanowtech.bloge.gateway.testing.world;

import java.util.List;

/** Auditable projection result that keeps inferred structure separate from unconfirmed semantics. */
public record LogicalResourceContractProjection(
        LogicalResourceContract contract,
        ReviewStatus reviewStatus,
        String descriptorFingerprint,
        List<String> unknownFields
) {
    public LogicalResourceContractProjection {
        if (contract == null || reviewStatus == null || descriptorFingerprint == null
                || !descriptorFingerprint.matches("sha256:[0-9a-f]{64}")
                || reviewStatus == ReviewStatus.CONFIRMED && contract.semantics().requiresReview()) {
            throw LogicalResourceContractException.projectionInvalid();
        }
        unknownFields = unknownFields == null ? List.of() : unknownFields.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public enum ReviewStatus { REQUIRES_CONFIRMATION, CONFIRMED }
}
