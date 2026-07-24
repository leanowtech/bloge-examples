package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Rebuildable materialized retention and multi-hold state for one Scenario aggregate.
 *
 * @param schemaVersion protocol version
 * @param scope complete enterprise scope
 * @param runId stable aggregate run identity
 * @param requestId aggregate request identity
 * @param evidenceBundleFingerprint original signed evidence identity
 * @param status retained or purged
 * @param revision latest signed lifecycle revision
 * @param retainUntil minimum retention boundary
 * @param activeHoldIds sorted active legal-hold identities
 * @param updatedAt latest database-authoritative transition time
 * @param latestEvent latest signed event
 */
public record ScenarioRehearsalRetentionState(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String runId,
        String requestId,
        String evidenceBundleFingerprint,
        Status status,
        long revision,
        Instant retainUntil,
        List<String> activeHoldIds,
        Instant updatedAt,
        ScenarioRehearsalRetentionEvent latestEvent
) {
    /** Current protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRetentionState.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Materialized lifecycle state. */
    public enum Status {
        RETAINED,
        PURGED
    }

    /** Enforces state, signed-event, and multi-hold correspondence. */
    public ScenarioRehearsalRetentionState {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario retention state schema");
        }
        scope = Objects.requireNonNull(scope, "scope");
        runId = required(runId, "runId");
        requestId = required(requestId, "requestId");
        evidenceBundleFingerprint = required(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        if (!FINGERPRINT.matcher(
                evidenceBundleFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "evidence fingerprint must be canonical SHA-256");
        }
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
                || !runId.equals(latestEvent.runId())
                || !requestId.equals(latestEvent.requestId())
                || !evidenceBundleFingerprint.equals(
                latestEvent.evidenceBundleFingerprint())
                || revision != latestEvent.revision()
                || !retainUntil.equals(
                latestEvent.retainUntil())
                || !updatedAt.equals(latestEvent.occurredAt())) {
            throw new IllegalArgumentException(
                    "retention state differs from its latest signed event");
        }
        if (status == Status.PURGED
                && (!activeHoldIds.isEmpty()
                || !latestEvent.deletionProof())) {
            throw new IllegalArgumentException(
                    "purged state requires no holds and a signed deletion proof");
        }
        if (status == Status.RETAINED
                && latestEvent.type()
                == ScenarioRehearsalRetentionEvent.Type.PURGED) {
            throw new IllegalArgumentException(
                    "retained state cannot expose a deletion proof");
        }
    }

    /** @return true when at least one independent hold blocks deletion */
    public boolean held() {
        return !activeHoldIds.isEmpty();
    }

    /** @return signed deletion proof when purged, otherwise {@code null} */
    public ScenarioRehearsalRetentionEvent deletionProof() {
        return status == Status.PURGED ? latestEvent : null;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
