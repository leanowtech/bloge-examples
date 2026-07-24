package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed append-only retention, legal-hold, or deletion-proof event for one Scenario aggregate.
 *
 * <p>The event is payload-free. A {@link Type#PURGED} event is the deletion proof: it preserves
 * the deleted aggregate evidence content address, deleted case-progress count, and the explicit
 * fact that child Mirror evidence was not cascaded.</p>
 *
 * @param schemaVersion protocol version
 * @param eventId immutable event identity
 * @param commandId caller idempotency identity
 * @param scope complete enterprise scope
 * @param requestId aggregate execution request identity
 * @param runId stable aggregate run identity
 * @param revision monotonic lifecycle revision
 * @param type closed transition type
 * @param retainUntil immutable minimum retention boundary
 * @param occurredAt database-authoritative transition time
 * @param actorId authenticated governance actor
 * @param reasonCode stable governance reason code
 * @param holdId legal-hold identity, otherwise blank
 * @param evidenceBundleFingerprint original signed aggregate identity
 * @param previousEventFingerprint preceding lifecycle event address, blank at revision one
 * @param deletedCaseProgressCount deleted aggregate progress rows, zero except on purge
 * @param childEvidenceDisposition explicit child-evidence deletion disposition
 * @param evidenceSeal detached signature over {@link #eventFingerprint()}
 */
public record ScenarioRehearsalRetentionEvent(
        String schemaVersion,
        String eventId,
        String commandId,
        CapabilitySnapshot.Scope scope,
        String requestId,
        String runId,
        long revision,
        Type type,
        Instant retainUntil,
        Instant occurredAt,
        String actorId,
        String reasonCode,
        String holdId,
        String evidenceBundleFingerprint,
        String previousEventFingerprint,
        int deletedCaseProgressCount,
        ChildEvidenceDisposition childEvidenceDisposition,
        VisualRunEvidenceSeal evidenceSeal
) {
    /** Current protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRetentionEvent.v1";
    private static final int MAXIMUM_FINGERPRINT_MATERIAL_BYTES =
            64 * 1024;
    private static final ObjectMapper FINGERPRINT_MAPPER =
            new ObjectMapper().findAndRegisterModules();
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

    /** Explicit cascade semantics recorded by a deletion proof. */
    public enum ChildEvidenceDisposition {
        NOT_APPLICABLE,
        RETAINED
    }

    /** Enforces strict payload-free transition correspondence. */
    public ScenarioRehearsalRetentionEvent {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario retention event schema");
        }
        eventId = bounded(eventId, 128, "eventId");
        commandId = bounded(commandId, 256, "commandId");
        scope = Objects.requireNonNull(scope, "scope");
        requestId = bounded(requestId, 256, "requestId");
        runId = bounded(runId, 512, "runId");
        if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(runId)) {
            throw new IllegalArgumentException(
                    "runId must be a canonical Scenario rehearsal identity");
        }
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        type = Objects.requireNonNull(type, "type");
        retainUntil = Objects.requireNonNull(
                retainUntil, "retainUntil");
        occurredAt = Objects.requireNonNull(
                occurredAt, "occurredAt");
        actorId = bounded(actorId, 255, "actorId");
        reasonCode = normalized(reasonCode);
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable Mirror code");
        }
        holdId = normalized(holdId);
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
        if (deletedCaseProgressCount < 0
                || deletedCaseProgressCount
                > ScenarioPack.MAXIMUM_CASES) {
            throw new IllegalArgumentException(
                    "deleted case count is outside the Scenario closure");
        }
        childEvidenceDisposition = Objects.requireNonNull(
                childEvidenceDisposition,
                "childEvidenceDisposition");
        evidenceSeal = evidenceSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : evidenceSeal;
        requireTypeShape(
                type, holdId, deletedCaseProgressCount,
                childEvidenceDisposition);
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
                "RESOURCE_GATEWAY_SCENARIO_RETENTION_V1");
        material.put("schemaVersion", schemaVersion);
        material.put("eventId", eventId);
        material.put("commandId", commandId);
        material.put("scope", scope);
        material.put("requestId", requestId);
        material.put("runId", runId);
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
        material.put("deletedCaseProgressCount",
                deletedCaseProgressCount);
        material.put("childEvidenceDisposition",
                childEvidenceDisposition);
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
    public ScenarioRehearsalRetentionEvent withEvidenceSeal(
            VisualRunEvidenceSeal seal) {
        return new ScenarioRehearsalRetentionEvent(
                schemaVersion, eventId, commandId, scope,
                requestId, runId, revision, type, retainUntil,
                occurredAt,
                actorId, reasonCode, holdId,
                evidenceBundleFingerprint,
                previousEventFingerprint,
                deletedCaseProgressCount,
                childEvidenceDisposition, seal);
    }

    /** @return true when this signed event is a deletion proof */
    public boolean deletionProof() {
        return type == Type.PURGED && evidenceSeal.signed();
    }

    private static void requireTypeShape(
            Type type,
            String holdId,
            int deletedCount,
            ChildEvidenceDisposition childDisposition) {
        boolean holdEvent = type == Type.HOLD_PLACED
                || type == Type.HOLD_RELEASED;
        if (holdEvent != !holdId.isBlank()) {
            throw new IllegalArgumentException(
                    "hold identity and transition type are inconsistent");
        }
        boolean purge = type == Type.PURGED;
        if (purge
                != (childDisposition
                == ChildEvidenceDisposition.RETAINED)) {
            throw new IllegalArgumentException(
                    "purge must explicitly retain child evidence");
        }
        if (!purge && deletedCount != 0) {
            throw new IllegalArgumentException(
                    "only purge may report deleted progress");
        }
    }

    private static String fingerprint(
            String value, String field, boolean blankAllowed) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            if (blankAllowed) {
                return "";
            }
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String bounded(
            String value, int maximum, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " is blank or exceeds its bound");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
