package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;

import java.time.Instant;

/** Payload-free durable receipt for an idempotent canonical Case approval. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioCanonicalApprovalReceipt(
        String schemaVersion,
        EnterpriseScope scope,
        String idempotencyKeyFingerprint,
        String requestFingerprint,
        ExactCaseRef caseRef,
        ScenarioClosureReport closure,
        String actorId,
        Instant createdAt
) {
    public static final String SCHEMA_VERSION = "bloge.scenarioCanonicalApprovalReceipt.v1";

    public ScenarioCanonicalApprovalReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported Scenario approval receipt schemaVersion");
        }
        if (scope == null || caseRef == null || closure == null || !closure.complete()) {
            throw new IllegalArgumentException(
                    "Complete canonical Scenario approval coordinates are required");
        }
        if (!"SCENARIO_DRAFT_SET".equals(caseRef.scenarioDraftSetRef().kind())) {
            throw new IllegalArgumentException(
                    "Scenario approval result must reference a SCENARIO_DRAFT_SET");
        }
        if (!caseRef.caseId().equals(closure.scenarioId())
                || closure.phase() != ScenarioClosureReport.ClosurePhase.CANONICAL) {
            throw new IllegalArgumentException(
                    "Scenario approval closure must match the canonical Case");
        }
        idempotencyKeyFingerprint = fingerprint(
                idempotencyKeyFingerprint, "idempotencyKeyFingerprint");
        requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
        actorId = required(actorId, "actorId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
