package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hash-chained payload-free fact for one committed continuous-assessment transition.
 *
 * <p>The event embeds the complete content-addressed projection after the transition instead of
 * duplicating a partial set of mutable columns. It also binds the previous event and the opaque
 * worker actor fingerprint, allowing an independent consumer to replay the exact governance
 * state without exposing worker identity or business payload.</p>
 */
public record AuthoritativeOutcomeContinuousAssessmentLifecycleEvent(
        String schemaVersion,
        long eventOrdinal,
        Transition transition,
        Instant occurredAt,
        String actorFingerprint,
        AuthoritativeOutcomeContinuousAssessmentProjection projection,
        String previousEventFingerprint,
        String eventFingerprint
) {
    /** Current append-only lifecycle event protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentLifecycleEvent.v1";
    /** Maximum canonical lifecycle event size. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            512 * 1024;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed committed transition vocabulary. */
    public enum Transition {
        /** A new immutable projection intent was registered. */
        REGISTERED,
        /** A pre-lifecycle projection was explicitly adopted as the audit baseline. */
        MIGRATED,
        /** A due projection acquired a fenced worker lease. */
        CLAIMED,
        /** A new immutable completeness assessment was adopted. */
        ASSESSMENT_PUBLISHED,
        /** An unchanged source closure renewed freshness without new evidence. */
        SOURCE_UNCHANGED,
        /** A retryable worker or dependency failure released the lease. */
        RETRY_SCHEDULED,
        /** An expired worker lease was fenced and requeued. */
        LEASE_EXPIRED,
        /** A non-retryable or exhausted projection entered terminal quarantine. */
        QUARANTINED
    }

    /** Enforces ordinal, transition, actor, projection, and chain shape. */
    public AuthoritativeOutcomeContinuousAssessmentLifecycleEvent {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment lifecycle event schemaVersion");
        }
        if (eventOrdinal < 1) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle event ordinal is invalid");
        }
        transition = Objects.requireNonNull(
                transition, "transition");
        occurredAt = Objects.requireNonNull(
                occurredAt, "occurredAt");
        actorFingerprint = optionalFingerprint(
                actorFingerprint, "actorFingerprint");
        projection = Objects.requireNonNull(
                projection, "projection");
        if (!occurredAt.equals(projection.updatedAt())) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle event time is inconsistent");
        }
        previousEventFingerprint = optionalFingerprint(
                previousEventFingerprint,
                "previousEventFingerprint");
        if ((eventOrdinal == 1)
                != previousEventFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle predecessor is inconsistent");
        }
        eventFingerprint = optionalFingerprint(
                eventFingerprint, "eventFingerprint");
        requireShape(
                transition,
                actorFingerprint,
                projection);
    }

    /** Seals one event whose predecessor and resulting projection are already fixed. */
    public AuthoritativeOutcomeContinuousAssessmentLifecycleEvent seal(
            ObjectMapper mapper) {
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent material =
                withEventFingerprint("");
        return material.withEventFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Verifies the embedded projection and lifecycle content address. */
    public void verify(ObjectMapper mapper) {
        projection.verify(
                Objects.requireNonNull(mapper, "mapper"));
        if (eventFingerprint.isBlank()
                || !eventFingerprint.equals(
                seal(mapper).eventFingerprint())) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle event fingerprint mismatch");
        }
    }

    AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
    withEventFingerprint(String value) {
        return new AuthoritativeOutcomeContinuousAssessmentLifecycleEvent(
                schemaVersion,
                eventOrdinal,
                transition,
                occurredAt,
                actorFingerprint,
                projection,
                previousEventFingerprint,
                value);
    }

    private static void requireShape(
            Transition transition,
            String actorFingerprint,
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection) {
        AuthoritativeOutcomeContinuousAssessmentProjection.Status
                status = projection.status();
        switch (transition) {
            case REGISTERED -> {
                if (status
                        != AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.QUEUED
                        || projection.attemptCount() != 0
                        || projection.leaseEpoch() != 0
                        || !actorFingerprint.isBlank()) {
                    invalidShape();
                }
            }
            case MIGRATED -> {
                // The projection itself remains authoritative; no pre-upgrade history is invented.
            }
            case CLAIMED -> {
                if (status
                        != AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.RUNNING
                        || projection.attemptCount() < 1
                        || projection.leaseEpoch() < 1
                        || actorFingerprint.isBlank()
                        || !actorFingerprint.equals(
                        projection.leaseOwnerFingerprint())) {
                    invalidShape();
                }
            }
            case ASSESSMENT_PUBLISHED, SOURCE_UNCHANGED -> {
                if (status
                        != AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.QUEUED
                        || projection.lastAssessmentRef() == null
                        || actorFingerprint.isBlank()
                        || !projection.failureCode().isBlank()) {
                    invalidShape();
                }
            }
            case RETRY_SCHEDULED, LEASE_EXPIRED -> {
                if (status
                        != AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.RETRY_WAIT
                        || actorFingerprint.isBlank()
                        || projection.failureCode().isBlank()) {
                    invalidShape();
                }
            }
            case QUARANTINED -> {
                if (status
                        != AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.QUARANTINED
                        || actorFingerprint.isBlank()
                        || projection.failureCode().isBlank()
                        || projection.terminalAt() == null) {
                    invalidShape();
                }
            }
        }
    }

    private static void invalidShape() {
        throw new IllegalArgumentException(
                "continuous assessment lifecycle transition shape is invalid");
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
