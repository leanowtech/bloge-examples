package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Database-authoritative append-only observation inbox and fenced reconciliation queue.
 *
 * <p>Implementations append immutable signed observation revisions, maintain a rebuildable current
 * head, and coordinate workers with database time plus owner/epoch fencing. Revision one requires a
 * blank predecessor; every later revision requires the exact current observation fingerprint.
 * Exact retries are idempotent. A late or corrected fact can only appear in a successor revision,
 * never by rewriting historical JSON.</p>
 */
public interface AuthoritativeOutcomeInboxRepository {
    /** Bounded credential-free worker identity syntax. */
    Pattern OWNER_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    /** Bounded persisted failure vocabulary syntax. */
    Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Durable append result. */
    record Admission(
            AuthoritativeOutcomeInboxEntry entry,
            boolean idempotentReplay
    ) {
        /** Requires a concrete verified head. */
        public Admission {
            entry = Objects.requireNonNull(entry, "entry");
        }
    }

    /** Exact current worker fence and predecessor observation. */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long observationRevision,
            String observationFingerprint,
            String ownerId,
            long epoch,
            Instant expiresAt
    ) {
        /** Validates complete positive lease coordinates. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            observationId = required(
                    observationId, "observationId");
            observationFingerprint = required(
                    observationFingerprint,
                    "observationFingerprint");
            ownerId = required(ownerId, "ownerId");
            if (observationRevision < 1
                    || epoch < 1
                    || !OWNER_ID.matcher(ownerId).matches()
                    || !observationFingerprint.matches(
                    "sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "authoritative outcome inbox lease coordinates are invalid");
            }
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
        }
    }

    /** Queue claim result. */
    record Claim(
            Outcome outcome,
            Instant observedAt,
            AuthoritativeOutcomeInboxEntry entry,
            AuthoritativeOutcomeObservation observation,
            Lease lease
    ) {
        /** Enforces exact acquired-field correspondence. */
        public Claim {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            boolean acquired = outcome == Outcome.ACQUIRED;
            if (acquired != (entry != null
                    && observation != null
                    && lease != null)) {
                throw new IllegalArgumentException(
                        "authoritative outcome inbox claim fields are inconsistent");
            }
            if (acquired
                    && (!entry.scope().equals(lease.scope())
                    || !entry.observationId().equals(
                    lease.observationId())
                    || entry.currentRevision()
                    != lease.observationRevision()
                    || !entry.currentObservationFingerprint()
                    .equals(lease.observationFingerprint())
                    || !observation.artifactRef().equals(
                    new MirrorArtifactRef(
                            AuthoritativeOutcomeObservation
                                    .ARTIFACT_KIND,
                            lease.observationId(),
                            lease.observationRevision(),
                            lease.observationFingerprint())))) {
                throw new IllegalArgumentException(
                        "authoritative outcome claim lineage is inconsistent");
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
                    null,
                    null);
        }
    }

    /** Renewed mutable projection and replacement fence. */
    record Heartbeat(
            AuthoritativeOutcomeInboxEntry entry,
            Lease lease
    ) {
        /** Requires exact running entry and lease correspondence. */
        public Heartbeat {
            entry = Objects.requireNonNull(entry, "entry");
            lease = Objects.requireNonNull(lease, "lease");
            if (entry.status()
                    != AuthoritativeOutcomeInboxEntry.Status.RUNNING
                    || !entry.scope().equals(lease.scope())
                    || !entry.observationId().equals(
                    lease.observationId())
                    || entry.currentRevision()
                    != lease.observationRevision()
                    || !entry.currentObservationFingerprint()
                    .equals(lease.observationFingerprint())
                    || entry.leaseEpoch() != lease.epoch()
                    || !entry.leaseExpiresAt().equals(
                    lease.expiresAt())) {
                throw new IllegalArgumentException(
                        "authoritative outcome heartbeat fields are inconsistent");
            }
        }
    }

    /**
     * Appends one independently verified signed revision or recovers an exact retry.
     *
     * @param observation immutable signed revision
     * @param expectedPredecessorFingerprint blank for revision one, exact current head otherwise
     */
    Admission append(
            AuthoritativeOutcomeObservation observation,
            String expectedPredecessorFingerprint);

    /** Reads one exact immutable revision after local integrity verification. */
    Optional<AuthoritativeOutcomeObservation> findObservation(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long revision);

    /** Reads the current immutable revision after local integrity verification. */
    Optional<AuthoritativeOutcomeObservation> findLatestObservation(
            CapabilitySnapshot.Scope scope,
            String observationId);

    /** Reads the current integrity-verified mutable head. */
    Optional<AuthoritativeOutcomeInboxEntry> findEntry(
            CapabilitySnapshot.Scope scope,
            String observationId);

    /** @return one database-clock observation without claiming work */
    Instant observedAt();

    /** Claims the next due pending head in one region/environment partition. */
    Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            AuthoritativeOutcomeInboxPolicy policy);

    /** Renews a currently fenced lease. */
    Heartbeat heartbeat(
            Lease lease,
            AuthoritativeOutcomeInboxPolicy policy);

    /**
     * Atomically appends a signed successor and advances or settles the same durable head.
     *
     * <p>The successor must be revision {@code lease.observationRevision + 1} and preserve all
     * immutable observation coordinates. Exact commit-response-loss replay is idempotent.</p>
     */
    AuthoritativeOutcomeInboxEntry publishSuccessor(
            Lease lease,
            AuthoritativeOutcomeObservation successor,
            AuthoritativeOutcomeInboxPolicy policy);

    /** Requeues one valid no-change connector response without consuming failure budget. */
    AuthoritativeOutcomeInboxEntry noChange(
            Lease lease,
            AuthoritativeOutcomeInboxPolicy policy);

    /** Requeues or quarantines one bounded dependency or worker failure. */
    AuthoritativeOutcomeInboxEntry fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            AuthoritativeOutcomeInboxPolicy policy);

    /** Reads a bounded oldest-first append-only lifecycle suffix. */
    List<AuthoritativeOutcomeInboxLifecycleEvent> lifecycle(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long afterOrdinal,
            int limit);

    /** Closed payload-free repository rejection vocabulary. */
    enum Reason {
        LINEAGE_CONFLICT,
        CONTENT_CONFLICT,
        OBSERVATION_NOT_FOUND,
        LEASE_LOST,
        SUCCESSOR_INVALID,
        STORED_STATE_CORRUPT
    }

    /** Stable repository failure carrying no payload, connector exception, or worker identity. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable durable-inbox violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome inbox rejected: "
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
