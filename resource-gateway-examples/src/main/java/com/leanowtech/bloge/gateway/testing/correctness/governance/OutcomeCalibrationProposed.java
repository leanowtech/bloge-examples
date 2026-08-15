package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;

import java.time.Instant;

/** Payload-free outbox event for the business review queue. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeCalibrationProposed(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef proposalRef,
        PublicationRef publicationRef,
        ExactAssetRef evidenceCompanionRef,
        ExactTargetRef target,
        String suiteRunId,
        OutcomeCalibrationProposal.MismatchKind mismatchKind,
        String reasonCode,
        String actorId,
        String correlationId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "OutcomeCalibrationProposed.v1";

    public OutcomeCalibrationProposed {
        schemaVersion = version(schemaVersion);
        eventId = required(eventId, "eventId");
        suiteRunId = required(suiteRunId, "suiteRunId");
        reasonCode = required(reasonCode, "reasonCode");
        actorId = required(actorId, "actorId");
        correlationId = required(correlationId, "correlationId");
        if (scope == null || proposalRef == null || publicationRef == null
                || evidenceCompanionRef == null || target == null
                || mismatchKind == null || occurredAt == null) {
            throw new IllegalArgumentException("Calibration proposal event coordinate is required");
        }
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) throw new IllegalArgumentException(
                "Unsupported calibration proposal event schemaVersion");
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
