package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Database-authoritative registry and fenced queue for continuous completeness projections.
 *
 * <p>Implementations coordinate registration, due-work claims, lease expiry recovery, bounded
 * freshness, retries, and assessment-cursor adoption with database time. The selected-population
 * and assessment repositories remain the immutable evidence authorities.</p>
 */
public interface AuthoritativeOutcomeContinuousAssessmentRepository {
    /** Bounded credential-free worker identity syntax. */
    Pattern OWNER_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    /** Stable payload-free failure vocabulary syntax. */
    Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Registration result. */
    record Admission(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            Instant observedAt,
            boolean idempotentReplay
    ) {
        /** Requires one concrete projection and its database-clock observation. */
        public Admission {
            projection = Objects.requireNonNull(
                    projection, "projection");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            if (observedAt.isBefore(
                    projection.updatedAt())) {
                throw new IllegalArgumentException(
                        "admission observation predates projection");
            }
        }
    }

    /** Exact current worker fence. */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String projectionId,
            String ownerId,
            long epoch,
            Instant expiresAt
    ) {
        /** Validates complete positive lease coordinates. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            projectionId = required(
                    projectionId, "projectionId");
            ownerId = required(ownerId, "ownerId");
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
            if (epoch < 1
                    || !OWNER_ID.matcher(ownerId).matches()) {
                throw new IllegalArgumentException(
                        "continuous assessment lease coordinates are invalid");
            }
        }
    }

    /** Queue claim result. */
    record Claim(
            Outcome outcome,
            Instant observedAt,
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            Lease lease
    ) {
        /** Enforces exact acquired-field correspondence. */
        public Claim {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            boolean acquired = outcome == Outcome.ACQUIRED;
            if (acquired != (projection != null
                    && lease != null)) {
                throw new IllegalArgumentException(
                        "continuous assessment claim fields are inconsistent");
            }
            if (acquired
                    && (!projection.scope().equals(
                    lease.scope())
                    || !projection.projectionId().equals(
                    lease.projectionId())
                    || projection.status()
                    != AuthoritativeOutcomeContinuousAssessmentProjection
                    .Status.RUNNING
                    || projection.leaseEpoch()
                    != lease.epoch()
                    || !projection.leaseExpiresAt()
                    .equals(lease.expiresAt()))) {
                throw new IllegalArgumentException(
                        "continuous assessment claim fence is inconsistent");
            }
        }

        /** Claim disposition. */
        public enum Outcome {
            ACQUIRED,
            NO_WORK
        }

        /** Creates one bounded database-clock no-work result. */
        public static Claim noWork(Instant observedAt) {
            return new Claim(
                    Outcome.NO_WORK,
                    observedAt,
                    null,
                    null);
        }
    }

    /** Registers one immutable projection intent or recovers an exact retry. */
    Admission register(
            CapabilitySnapshot.Scope scope,
            AuthoritativeOutcomeContinuousAssessmentRequest
                    request);

    /** Reads one exact scoped projection and a database observation time. */
    Optional<ObservedProjection> find(
            CapabilitySnapshot.Scope scope,
            String projectionId);

    /** @return one database-clock observation without claiming work */
    Instant observedAt();

    /** Claims the next due projection in one region/environment partition. */
    Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy);

    /**
     * Completes a successful check by adopting an exact immutable assessment and source closure.
     *
     * <p>The adopted assessment may jump over the projection cursor when a previous owner
     * committed evidence and lost its lease or response before advancing this rebuildable head.</p>
     */
    AuthoritativeOutcomeContinuousAssessmentProjection publish(
            Lease lease,
            MirrorArtifactRef assessmentRef,
            String observationSetFingerprint,
            String dispositionSetFingerprint,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy);

    /** Completes a successful unchanged-source check without publishing another assessment. */
    AuthoritativeOutcomeContinuousAssessmentProjection unchanged(
            Lease lease,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy);

    /** Requeues or quarantines one bounded worker or dependency failure. */
    AuthoritativeOutcomeContinuousAssessmentProjection fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy);

    /** Projection plus the exact database time used to interpret its half-open freshness window. */
    record ObservedProjection(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            Instant observedAt
    ) {
        /** Requires complete status coordinates. */
        public ObservedProjection {
            projection = Objects.requireNonNull(
                    projection, "projection");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
        }

        /** @return consumer-facing freshness at the database observation */
        public AuthoritativeOutcomeContinuousAssessmentProjection
                .Freshness freshness() {
            return projection.freshnessAt(observedAt);
        }
    }

    /** Closed payload-free repository rejection vocabulary. */
    enum Reason {
        CONTENT_CONFLICT,
        PROJECTION_NOT_FOUND,
        LEASE_LOST,
        ASSESSMENT_INVALID,
        STORED_STATE_CORRUPT
    }

    /** Stable repository failure carrying no payload or raw worker identity. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable continuous-assessment repository violation. */
        public Violation(Reason reason) {
            super("Continuous assessment repository rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }

    private static String required(
            String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }
        return exact;
    }
}
