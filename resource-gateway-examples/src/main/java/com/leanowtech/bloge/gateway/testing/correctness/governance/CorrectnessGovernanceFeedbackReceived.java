package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;

import java.time.Instant;

/** Payload-free acknowledgement that Resource Gateway projected one ANEKE decision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessGovernanceFeedbackReceived(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef feedbackRef,
        PublicationRef publicationRef,
        CorrectnessGovernanceFeedback.GateDecision decision,
        String sourceSystem,
        String sourceDecisionId,
        long sourceDecisionRevision,
        String actorId,
        String correlationId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "CorrectnessGovernanceFeedbackReceived.v1";

    public CorrectnessGovernanceFeedbackReceived {
        schemaVersion = version(schemaVersion);
        eventId = required(eventId, "eventId");
        sourceSystem = required(sourceSystem, "sourceSystem");
        sourceDecisionId = required(sourceDecisionId, "sourceDecisionId");
        actorId = required(actorId, "actorId");
        correlationId = required(correlationId, "correlationId");
        if (scope == null || feedbackRef == null || publicationRef == null
                || decision == null || sourceDecisionRevision < 1 || occurredAt == null) {
            throw new IllegalArgumentException("Governance feedback event coordinate is required");
        }
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) throw new IllegalArgumentException(
                "Unsupported governance feedback event schemaVersion");
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
