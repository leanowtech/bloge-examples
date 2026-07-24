package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Rebuildable retention and multi-hold projection for one terminal Scenario batch.
 *
 * @param schemaVersion protocol version
 * @param scope complete enterprise scope
 * @param requestId batch request identity
 * @param jobId stable batch identity
 * @param manifestFingerprint immutable ordered batch closure
 * @param evidenceBundleFingerprint original signed batch evidence identity
 * @param status retained or purged
 * @param revision latest signed lifecycle revision
 * @param retainUntil minimum immutable retention boundary
 * @param activeHoldIds sorted independent legal-hold identities
 * @param updatedAt latest database-authoritative transition time
 * @param latestEvent latest signed lifecycle event
 */
public record ScenarioRehearsalBatchRetentionState(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String requestId,
        String jobId,
        String manifestFingerprint,
        String evidenceBundleFingerprint,
        Status status,
        long revision,
        Instant retainUntil,
        List<String> activeHoldIds,
        Instant updatedAt,
        ScenarioRehearsalBatchRetentionEvent latestEvent
) {
    /** Current batch-retention projection version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchRetentionState.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Materialized lifecycle state. */
    public enum Status {
        RETAINED,
        PURGED
    }

    /** Enforces projection, latest-event, and active-hold correspondence. */
    public ScenarioRehearsalBatchRetentionState {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch retention state schema");
        }
        scope = Objects.requireNonNull(scope, "scope");
        requestId = required(requestId, "requestId");
        jobId = required(jobId, "jobId");
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        status = Objects.requireNonNull(status, "status");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        retainUntil = Objects.requireNonNull(
                retainUntil, "retainUntil");
        TreeSet<String> holds = new TreeSet<>();
        for (String holdId
                : activeHoldIds == null
                ? List.<String>of() : activeHoldIds) {
            String hold = required(holdId, "holdId");
            if (!holds.add(hold)) {
                throw new IllegalArgumentException(
                        "active hold ids must be unique");
            }
        }
        activeHoldIds = List.copyOf(holds);
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        latestEvent = Objects.requireNonNull(
                latestEvent, "latestEvent");
        if (!scope.equals(latestEvent.scope())
                || !requestId.equals(latestEvent.requestId())
                || !jobId.equals(latestEvent.jobId())
                || !manifestFingerprint.equals(
                latestEvent.manifestFingerprint())
                || !evidenceBundleFingerprint.equals(
                latestEvent.evidenceBundleFingerprint())
                || revision != latestEvent.revision()
                || !retainUntil.equals(
                latestEvent.retainUntil())
                || !updatedAt.equals(
                latestEvent.occurredAt())) {
            throw new IllegalArgumentException(
                    "batch retention state differs from its latest signed event");
        }
        if (status == Status.PURGED
                && (!activeHoldIds.isEmpty()
                || !latestEvent.deletionProof())) {
            throw new IllegalArgumentException(
                    "purged batch state requires no holds and a signed deletion proof");
        }
        if (status == Status.RETAINED
                && latestEvent.type()
                == ScenarioRehearsalBatchRetentionEvent.Type.PURGED) {
            throw new IllegalArgumentException(
                    "retained batch state cannot expose a deletion proof");
        }
    }

    /** @return true when at least one independent hold blocks deletion */
    public boolean held() {
        return !activeHoldIds.isEmpty();
    }

    /** @return signed logical-deletion proof when purged, otherwise {@code null} */
    public ScenarioRehearsalBatchRetentionEvent deletionProof() {
        return status == Status.PURGED
                ? latestEvent : null;
    }

    private static String required(
            String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = normalized(value);
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
