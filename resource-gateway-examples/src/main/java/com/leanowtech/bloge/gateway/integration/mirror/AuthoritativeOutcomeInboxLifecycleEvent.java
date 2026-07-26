package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chained payload-free lifecycle fact for one authoritative outcome inbox lineage.
 *
 * <p>Each event binds the previous event fingerprint and current observation head. The mutable
 * inbox projection can therefore be audited or rebuilt without exposing subject, attribution,
 * source-record, connector, or worker identities.</p>
 */
public record AuthoritativeOutcomeInboxLifecycleEvent(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String observationId,
        long eventOrdinal,
        Transition transition,
        AuthoritativeOutcomeInboxEntry.Status status,
        long observationRevision,
        String observationFingerprint,
        String predecessorObservationFingerprint,
        AuthoritativeOutcomeObservation.Reconciliation reconciliation,
        long attemptCount,
        int consecutiveFailures,
        long leaseEpoch,
        String ownerFingerprint,
        Instant occurredAt,
        String failureCode,
        String previousEventFingerprint,
        String eventFingerprint
) {
    /** Exact append-only lifecycle protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeInboxLifecycle.v1";
    /** Maximum canonical event size. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            256 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Validates sequence, content-address, and transition coordinates. */
    public AuthoritativeOutcomeInboxLifecycleEvent {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported authoritative outcome inbox lifecycle schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope");
        observationId = requiredIdentifier(
                observationId, "observationId");
        if (eventOrdinal < 1
                || observationRevision < 1
                || attemptCount < 0
                || consecutiveFailures < 0
                || leaseEpoch < 0) {
            throw new IllegalArgumentException(
                    "lifecycle counters are invalid");
        }
        transition = Objects.requireNonNull(
                transition, "transition");
        status = Objects.requireNonNull(status, "status");
        observationFingerprint = requiredFingerprint(
                observationFingerprint,
                "observationFingerprint");
        predecessorObservationFingerprint =
                optionalFingerprint(
                        predecessorObservationFingerprint,
                        "predecessorObservationFingerprint");
        reconciliation = Objects.requireNonNull(
                reconciliation, "reconciliation");
        ownerFingerprint = optionalFingerprint(
                ownerFingerprint, "ownerFingerprint");
        occurredAt = Objects.requireNonNull(
                occurredAt, "occurredAt");
        failureCode = normalized(failureCode);
        if (!failureCode.isBlank()
                && !FAILURE_CODE.matcher(
                failureCode).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        previousEventFingerprint = optionalFingerprint(
                previousEventFingerprint,
                "previousEventFingerprint");
        if ((eventOrdinal == 1)
                != previousEventFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "lifecycle predecessor does not match event ordinal");
        }
        eventFingerprint = optionalFingerprint(
                eventFingerprint, "eventFingerprint");
    }

    /** Closed durable lifecycle transition vocabulary. */
    public enum Transition {
        OBSERVATION_APPENDED,
        CLAIMED,
        HEARTBEAT,
        NO_CHANGE,
        RETRY_SCHEDULED,
        LEASE_EXPIRED,
        SUCCESSOR_APPENDED,
        SETTLED,
        QUARANTINED
    }

    /** Seals this append-only event with its chain predecessor already fixed. */
    public AuthoritativeOutcomeInboxLifecycleEvent seal(
            ObjectMapper mapper) {
        AuthoritativeOutcomeInboxLifecycleEvent material =
                withEventFingerprint("");
        return material.withEventFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Verifies this event's content address. */
    public void verify(ObjectMapper mapper) {
        if (eventFingerprint.isBlank()
                || !eventFingerprint.equals(
                seal(mapper).eventFingerprint())) {
            throw new IllegalArgumentException(
                    "authoritative outcome lifecycle fingerprint mismatch");
        }
    }

    AuthoritativeOutcomeInboxLifecycleEvent withEventFingerprint(
            String value) {
        return new AuthoritativeOutcomeInboxLifecycleEvent(
                schemaVersion,
                scope,
                observationId,
                eventOrdinal,
                transition,
                status,
                observationRevision,
                observationFingerprint,
                predecessorObservationFingerprint,
                reconciliation,
                attemptCount,
                consecutiveFailures,
                leaseEpoch,
                ownerFingerprint,
                occurredAt,
                failureCode,
                previousEventFingerprint,
                value);
    }

    private static String requiredIdentifier(
            String value, String field) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String requiredFingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
