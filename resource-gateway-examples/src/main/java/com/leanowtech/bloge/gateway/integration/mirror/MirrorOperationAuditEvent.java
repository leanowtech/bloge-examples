package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free durable audit fact for one protected Mirror API operation.
 *
 * <p>The event deliberately keeps only enterprise scope, trace coordinates, closed result
 * dimensions, and stable resource identities. Request context, fixture values, replay values,
 * graph values, evidence payloads, exception messages, and stack traces are not representable.</p>
 *
 * @param sequence database-assigned append sequence, or zero before persistence
 * @param occurredAt database-authoritative commit time, or {@code null} before persistence
 * @param tenantId authenticated tenant coordinate
 * @param organizationId authenticated organization coordinate
 * @param projectId authenticated project coordinate
 * @param environmentId authenticated environment coordinate
 * @param region authenticated region coordinate
 * @param correlationId request trace coordinate
 * @param actorType authenticated actor type
 * @param actorId authenticated actor identity
 * @param operation closed Mirror operation
 * @param outcome closed terminal outcome
 * @param reason low-cardinality failure class, or {@link Reason#NONE} on success
 * @param reasonCode exact stable failure code, blank on success
 * @param requestId execution request id or authority key-set id when available
 * @param planId mirror plan id or authority deployment-scope id when available
 * @param runId terminal run id or authority publication fingerprint when available
 * @param durationMillis bounded operation duration observed by the serving process
 */
public record MirrorOperationAuditEvent(
        long sequence,
        Instant occurredAt,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String correlationId,
        String actorType,
        String actorId,
        Operation operation,
        Outcome outcome,
        Reason reason,
        String reasonCode,
        String requestId,
        String planId,
        String runId,
        long durationMillis
) {
    private static final Pattern REASON_CODE = Pattern.compile(
            "RG\\.MIRROR\\.[A-Z0-9_]+(?:\\.[A-Z0-9_]+)*");

    /** Protected service operations with a permanently bounded metric and audit vocabulary. */
    public enum Operation {
        /** Compile and append or idempotently recover an immutable Mirror Plan. */
        PLAN_CREATE,
        /** Read one verified Mirror Plan in the authenticated scope. */
        PLAN_READ,
        /** Execute or idempotently recover one sealed Mirror generation. */
        RUN_CREATE,
        /** Read one payload-free terminal Mirror Run summary. */
        RUN_READ,
        /** Read one independently verified signed Mirror evidence bundle. */
        EVIDENCE_READ,
        /** Execute or idempotently recover one exact compiled Scenario rehearsal. */
        SCENARIO_REHEARSAL_CREATE,
        /** Read one independently verified signed Scenario aggregate. */
        SCENARIO_REHEARSAL_EVIDENCE_READ,
        /** Verify and append one deployment-isolation authority key-set generation. */
        AUTHORITY_KEY_SET_PUBLISH,
        /** Read and re-verify the current deployment-isolation authority key-set floor. */
        AUTHORITY_KEY_SET_READ,
        /** Verify and append one deployment-isolation attestation revision. */
        ISOLATION_ATTESTATION_INGEST,
        /** Read one atomic current deployment-isolation attestation and status bundle. */
        ISOLATION_ATTESTATION_READ,
        /** Irreversibly revoke one exact current deployment-isolation attestation. */
        ISOLATION_ATTESTATION_REVOKE,
        /** Admit or quarantine one signed payload-free capability observation. */
        OBSERVATION_INGEST,
        /** Record one terminal owner review of a quarantined observation. */
        OBSERVATION_REVIEW,
        /** Freeze admitted observations into an immutable corpus revision candidate. */
        CORPUS_CANDIDATE_CREATE,
        /** Publish one exact reviewed corpus revision as the serving head. */
        CORPUS_PUBLISH,
        /** Publish one exact owner-reviewed recorded retry trajectory. */
        CORPUS_TRAJECTORY_PUBLISH,
        /** Publish one exact externally validated recorded cluster. */
        CORPUS_CLUSTER_PUBLISH
    }

    /** Terminal operation outcomes. */
    public enum Outcome {
        /** The protected result and its mandatory success audit committed. */
        SUCCEEDED,
        /** Caller input, authority, identity, or immutable state rejected the operation. */
        REJECTED,
        /** Infrastructure or an unexpected service condition prevented completion. */
        FAILED
    }

    /** Low-cardinality failure classes suitable for metrics, dashboards, and alert routing. */
    public enum Reason {
        /** Successful operation with no failure code. */
        NONE,
        /** Malformed, incomplete, or unsafe caller input. */
        INVALID_REQUEST,
        /** Missing identity authority, purpose, scope, or clearance. */
        FORBIDDEN,
        /** Resource absence, including intentionally hidden cross-scope identities. */
        NOT_FOUND,
        /** Immutable generation, idempotency, lease, or current-state conflict. */
        CONFLICT,
        /** Reviewed plan or execution authority expired. */
        EXPIRED,
        /** Bounded admission, quota, or concurrency capacity was exhausted. */
        CAPACITY,
        /** A required runtime, coordinator, store, or signer was unavailable. */
        UNAVAILABLE,
        /** The mandatory operation audit could not be constructed or committed. */
        AUDIT_UNAVAILABLE,
        /** Unclassified internal failure represented without exception detail. */
        UNEXPECTED
    }

    /** Enforces the payload-free closed audit contract before a row can be persisted. */
    public MirrorOperationAuditEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException("Mirror operation audit sequence must be non-negative");
        }
        tenantId = bounded(tenantId, 255, "tenantId");
        organizationId = bounded(organizationId, 255, "organizationId");
        projectId = bounded(projectId, 255, "projectId");
        environmentId = bounded(environmentId, 255, "environmentId");
        region = bounded(region, 96, "region");
        correlationId = bounded(correlationId, 160, "correlationId");
        actorType = bounded(actorType, 64, "actorType").toUpperCase(Locale.ROOT);
        actorId = bounded(actorId, 255, "actorId");
        operation = Objects.requireNonNull(operation, "operation");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reason = Objects.requireNonNull(reason, "reason");
        reasonCode = bounded(reasonCode, 160, "reasonCode").toUpperCase(Locale.ROOT);
        requestId = bounded(requestId, 512, "requestId");
        planId = bounded(planId, 512, "planId");
        runId = bounded(runId, 512, "runId");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("Mirror operation audit duration must be non-negative");
        }
        boolean success = outcome == Outcome.SUCCEEDED;
        if (success != (reason == Reason.NONE && reasonCode.isBlank())) {
            throw new IllegalArgumentException(
                    "Mirror operation success and failure reason fields are inconsistent");
        }
        if (!reasonCode.isBlank() && !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Mirror operation audit reason code is invalid");
        }
        if (sequence > 0 && occurredAt == null) {
            throw new IllegalArgumentException(
                    "Persisted Mirror operation audit events require database time");
        }
    }

    /**
     * Returns the same immutable fact with database-assigned persistence coordinates.
     *
     * @param assignedSequence positive append sequence assigned by the audit store
     * @param databaseTime database-authoritative occurrence time
     * @return immutable persisted form of this event
     */
    public MirrorOperationAuditEvent persisted(long assignedSequence, Instant databaseTime) {
        if (assignedSequence < 1 || databaseTime == null) {
            throw new IllegalArgumentException("Mirror operation audit persistence coordinates are required");
        }
        return new MirrorOperationAuditEvent(assignedSequence, databaseTime, tenantId,
                organizationId, projectId, environmentId, region, correlationId, actorType,
                actorId, operation, outcome, reason, reasonCode, requestId, planId, runId,
                durationMillis);
    }

    private static String bounded(String value, int maximum, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its audit bound");
        }
        return normalized;
    }
}
