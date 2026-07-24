package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed payload-free retention, legal-hold, or deletion event for one Scenario batch.
 *
 * <p>A {@link Type#PURGED} event is a logical-deletion proof for Resource Gateway's database
 * transaction. It records exact batch-level row counts and explicitly preserves child Scenario
 * evidence and audit facts. It does not claim physical-media erasure or external WORM anchoring.</p>
 *
 * @param schemaVersion protocol version
 * @param eventId immutable event identity
 * @param commandId caller idempotency identity
 * @param scope complete enterprise scope
 * @param requestId batch request identity
 * @param jobId stable batch identity
 * @param manifestFingerprint immutable ordered batch closure
 * @param revision monotonic lifecycle revision
 * @param type closed transition type
 * @param retainUntil immutable minimum retention boundary
 * @param occurredAt database-authoritative transition time
 * @param actorId authenticated governance actor
 * @param reasonCode stable governance reason code
 * @param holdId legal-hold identity, otherwise blank
 * @param evidenceBundleFingerprint original signed batch evidence identity
 * @param previousEventFingerprint preceding event address, blank at revision one
 * @param deletedJobCount deleted batch-job rows, zero or one
 * @param deletedItemCount deleted batch-item rows
 * @param deletedBatchEvidenceCount deleted batch-evidence rows, zero or one
 * @param childEvidenceDisposition explicit child Scenario evidence disposition
 * @param auditDisposition explicit batch lifecycle and operation-audit disposition
 * @param evidenceSeal detached signature over {@link #eventFingerprint()}
 */
public record ScenarioRehearsalBatchRetentionEvent(
        String schemaVersion,
        String eventId,
        String commandId,
        CapabilitySnapshot.Scope scope,
        String requestId,
        String jobId,
        String manifestFingerprint,
        long revision,
        Type type,
        Instant retainUntil,
        Instant occurredAt,
        String actorId,
        String reasonCode,
        String holdId,
        String evidenceBundleFingerprint,
        String previousEventFingerprint,
        int deletedJobCount,
        int deletedItemCount,
        int deletedBatchEvidenceCount,
        PreservedDisposition childEvidenceDisposition,
        PreservedDisposition auditDisposition,
        VisualRunEvidenceSeal evidenceSeal
) {
    /** Current signed batch-retention event version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchRetentionEvent.v1";
    private static final int MAXIMUM_FINGERPRINT_MATERIAL_BYTES =
            64 * 1024;
    private static final ObjectMapper FINGERPRINT_MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON_CODE =
            Pattern.compile("RG\\.MIRROR\\.[A-Z0-9_.-]{1,224}");

    /** Closed retention transition vocabulary. */
    public enum Type {
        RETENTION_REGISTERED,
        HOLD_PLACED,
        HOLD_RELEASED,
        PURGED
    }

    /** Explicit disposition for records outside the deleted batch aggregate. */
    public enum PreservedDisposition {
        NOT_APPLICABLE,
        RETAINED
    }

    /** Enforces bounded identity, deletion counts, and transition correspondence. */
    public ScenarioRehearsalBatchRetentionEvent {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch retention event schema");
        }
        eventId = identifier(eventId, "eventId", 128);
        commandId = identifier(commandId, "commandId", 256);
        scope = Objects.requireNonNull(scope, "scope");
        requestId = identifier(requestId, "requestId", 256);
        jobId = identifier(jobId, "jobId", 512);
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint", false);
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        type = Objects.requireNonNull(type, "type");
        retainUntil = Objects.requireNonNull(
                retainUntil, "retainUntil");
        occurredAt = Objects.requireNonNull(
                occurredAt, "occurredAt");
        actorId = identifier(actorId, "actorId", 255);
        reasonCode = normalized(reasonCode);
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable Mirror code");
        }
        holdId = optionalIdentifier(
                holdId, "holdId", 256);
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint", false);
        previousEventFingerprint = fingerprint(
                previousEventFingerprint,
                "previousEventFingerprint", revision == 1);
        if ((revision == 1)
                != previousEventFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "only revision one may omit the previous event");
        }
        if (deletedJobCount < 0
                || deletedJobCount > 1
                || deletedItemCount < 0
                || deletedItemCount
                > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES
                || deletedBatchEvidenceCount < 0
                || deletedBatchEvidenceCount > 1) {
            throw new IllegalArgumentException(
                    "Scenario batch deletion counts are invalid");
        }
        childEvidenceDisposition = Objects.requireNonNull(
                childEvidenceDisposition,
                "childEvidenceDisposition");
        auditDisposition = Objects.requireNonNull(
                auditDisposition, "auditDisposition");
        evidenceSeal = evidenceSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : evidenceSeal;
        requireTypeShape(
                type,
                holdId,
                deletedJobCount,
                deletedItemCount,
                deletedBatchEvidenceCount,
                childEvidenceDisposition,
                auditDisposition);
    }

    /**
     * Computes the canonical content address excluding the detached seal.
     *
     * @return stable event fingerprint
     */
    public String eventFingerprint() {
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("signatureDomain",
                "RESOURCE_GATEWAY_SCENARIO_BATCH_RETENTION_V1");
        material.put("schemaVersion", schemaVersion);
        material.put("eventId", eventId);
        material.put("commandId", commandId);
        material.put("scope", scope);
        material.put("requestId", requestId);
        material.put("jobId", jobId);
        material.put("manifestFingerprint",
                manifestFingerprint);
        material.put("revision", revision);
        material.put("type", type);
        material.put("retainUntil", retainUntil);
        material.put("occurredAt", occurredAt);
        material.put("actorId", actorId);
        material.put("reasonCode", reasonCode);
        material.put("holdId", holdId);
        material.put("evidenceBundleFingerprint",
                evidenceBundleFingerprint);
        material.put("previousEventFingerprint",
                previousEventFingerprint);
        material.put("deletedJobCount", deletedJobCount);
        material.put("deletedItemCount", deletedItemCount);
        material.put("deletedBatchEvidenceCount",
                deletedBatchEvidenceCount);
        material.put("childEvidenceDisposition",
                childEvidenceDisposition);
        material.put("auditDisposition", auditDisposition);
        return VisualBundleFingerprint.fromCanonicalValue(
                FINGERPRINT_MAPPER,
                material,
                MAXIMUM_FINGERPRINT_MATERIAL_BYTES);
    }

    /**
     * Returns the exact event with its detached evidence seal.
     *
     * @param seal signature over {@link #eventFingerprint()}
     * @return signed immutable event
     */
    public ScenarioRehearsalBatchRetentionEvent withEvidenceSeal(
            VisualRunEvidenceSeal seal) {
        return new ScenarioRehearsalBatchRetentionEvent(
                schemaVersion, eventId, commandId, scope,
                requestId, jobId, manifestFingerprint,
                revision, type, retainUntil, occurredAt,
                actorId, reasonCode, holdId,
                evidenceBundleFingerprint,
                previousEventFingerprint,
                deletedJobCount, deletedItemCount,
                deletedBatchEvidenceCount,
                childEvidenceDisposition, auditDisposition,
                seal);
    }

    /** @return true when this signed event is a logical-deletion proof */
    public boolean deletionProof() {
        return type == Type.PURGED
                && evidenceSeal.signed();
    }

    private static void requireTypeShape(
            Type type,
            String holdId,
            int deletedJobs,
            int deletedItems,
            int deletedEvidence,
            PreservedDisposition childDisposition,
            PreservedDisposition audits) {
        boolean holdEvent = type == Type.HOLD_PLACED
                || type == Type.HOLD_RELEASED;
        if (holdEvent != !holdId.isBlank()) {
            throw new IllegalArgumentException(
                    "hold identity and transition type are inconsistent");
        }
        boolean purge = type == Type.PURGED;
        boolean deletionCounts = deletedJobs == 1
                && deletedItems > 0
                && deletedEvidence == 1;
        boolean preserved = childDisposition
                == PreservedDisposition.RETAINED
                && audits == PreservedDisposition.RETAINED;
        if (purge != deletionCounts
                || purge != preserved) {
            throw new IllegalArgumentException(
                    "purge must delete one batch closure and preserve child evidence and audits");
        }
        if (!purge && (deletedJobs != 0
                || deletedItems != 0
                || deletedEvidence != 0
                || childDisposition
                != PreservedDisposition.NOT_APPLICABLE
                || audits
                != PreservedDisposition.NOT_APPLICABLE)) {
            throw new IllegalArgumentException(
                    "non-purge events cannot report deletion disposition");
        }
    }

    private static String identifier(
            String value, String field, int maximum) {
        String normalized = normalized(value);
        if (normalized.length() > maximum
                || !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value, String field, int maximum) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && (normalized.length() > maximum
                || !IDENTIFIER.matcher(normalized).matches())) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field, boolean blankAllowed) {
        String normalized = normalized(value);
        if (normalized.isBlank() && blankAllowed) {
            return "";
        }
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
