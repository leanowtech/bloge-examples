package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable, database-time, owner/epoch-fenced checkpoint authority for production outcome streams.
 *
 * <p>The checkpoint is deliberately two phase. A fetched page is staged with its complete
 * payload-free protocol material before any observation is admitted. The cursor advances only
 * after every page mutation is an exact durable inbox replay or append. A crash at any point
 * therefore leaves either the previous committed cursor or an exact staged page.</p>
 */
public interface AuthoritativeOutcomeSourceCheckpointRepository {
    /** Bounded worker identity syntax. */
    Pattern OWNER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    /** Stable persisted failure-code syntax. */
    Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Registers or exactly replays one live stream baseline. */
    Admission registerLive(Registration registration);

    /** Registers or exactly replays one externally verified backfill command and stream. */
    Admission registerBackfill(AuthoritativeOutcomeConnectorControlCommand command);

    /** Fences one exact connector generation after external command verification. */
    Revocation revokeGeneration(AuthoritativeOutcomeConnectorControlCommand command);

    /** Claims at most one eligible stream in the exact region/environment partition. */
    Claim claimNext(String region, String environmentId, String ownerId, Policy policy);

    /** Renews the exact owner/epoch lease. */
    Lease heartbeat(Lease lease, Policy policy);

    /** Durably pins one exact contiguous page without advancing the source cursor. */
    Snapshot stage(Lease lease, AuthoritativeOutcomeSourcePage page);

    /** Commits only the exact staged page after all inbox mutations are durable. */
    Snapshot commit(Lease lease, String pageFingerprint, Policy policy);

    /** Releases a no-change live turn or terminally completes one backfill stream. */
    Snapshot release(Lease lease, Release release, Policy policy);

    /** Applies bounded retry or quarantine semantics without source error disclosure. */
    Snapshot fail(Lease lease, String failureCode, boolean retryable, Policy policy);

    /** Reads one tamper-checked checkpoint. */
    Optional<Snapshot> find(StreamKey key);

    /** @return current database coordination time */
    Instant observedAt();

    /** @return true only for a cross-restart implementation */
    boolean durable();

    /** Stream lifecycle states. */
    enum Status {
        ACTIVE,
        RUNNING,
        COMPLETE,
        REVOKED,
        QUARANTINED
    }

    /** Lease release outcomes. */
    enum Release {
        IDLE,
        STREAM_COMPLETE
    }

    /** Exact stream coordinate. */
    record StreamKey(
            CapabilitySnapshot.Scope scope,
            String connectorId,
            long connectorGeneration,
            AuthoritativeOutcomeSourcePage.StreamKind streamKind,
            String streamId
    ) {
        /** Enforces stable source and stream identity. */
        public StreamKey {
            scope = Objects.requireNonNull(scope, "scope");
            connectorId = identifier(connectorId, "connectorId");
            if (connectorGeneration < 1) {
                throw invalid("connectorGeneration must be positive");
            }
            streamKind = Objects.requireNonNull(streamKind, "streamKind");
            streamId = identifier(streamId, "streamId");
            if (streamKind == AuthoritativeOutcomeSourcePage.StreamKind.LIVE
                    && !"live".equals(streamId)) {
                throw invalid("live stream key must use the fixed id");
            }
        }
    }

    /** Immutable stream baseline registration. */
    record Registration(
            StreamKey key,
            String baselinePageFingerprint,
            MirrorArtifactRef baselineCursorRef
    ) {
        /** Enforces an exact payload-free source baseline. */
        public Registration {
            key = Objects.requireNonNull(key, "key");
            if (key.streamKind() != AuthoritativeOutcomeSourcePage.StreamKind.LIVE) {
                throw invalid("direct registration is allowed only for live streams");
            }
            baselinePageFingerprint = fingerprint(
                    baselinePageFingerprint, "baselinePageFingerprint");
            baselineCursorRef = cursorRef(baselineCursorRef, "baselineCursorRef");
        }
    }

    /** Durable registration outcome. */
    record Admission(Snapshot snapshot, boolean idempotentReplay) {
        /** Requires one complete checkpoint snapshot. */
        public Admission {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Durable generation revocation outcome. */
    record Revocation(
            MirrorArtifactRef commandRef,
            int affectedStreamCount,
            boolean idempotentReplay
    ) {
        /** Requires one addressed revocation command and bounded count. */
        public Revocation {
            commandRef = Objects.requireNonNull(commandRef, "commandRef");
            if (!AuthoritativeOutcomeConnectorControlCommand.ARTIFACT_KIND.equals(
                    commandRef.kind()) || affectedStreamCount < 0) {
                throw invalid("connector generation revocation result is invalid");
            }
        }
    }

    /** Exact worker fence. */
    record Lease(StreamKey key, String ownerId, long epoch, Instant expiresAt) {
        /** Enforces complete positive lease coordinates. */
        public Lease {
            key = Objects.requireNonNull(key, "key");
            ownerId = identifier(ownerId, "ownerId");
            if (!OWNER_ID.matcher(ownerId).matches() || epoch < 1) {
                throw invalid("source checkpoint lease is invalid");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Queue claim result including a crash-recoverable staged page when present. */
    record Claim(
            Outcome outcome,
            Instant observedAt,
            Snapshot snapshot,
            AuthoritativeOutcomeSourcePage stagedPage,
            Lease lease
    ) {
        /** Enforces acquired-field and staged-page closure. */
        public Claim {
            outcome = Objects.requireNonNull(outcome, "outcome");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            boolean acquired = outcome == Outcome.ACQUIRED;
            if (acquired != (snapshot != null && lease != null)) {
                throw invalid("source checkpoint claim fields are inconsistent");
            }
            if (acquired && (!snapshot.key().equals(lease.key())
                    || snapshot.leaseEpoch() != lease.epoch()
                    || !snapshot.leaseExpiresAt().equals(lease.expiresAt())
                    || (stagedPage != null) != snapshot.hasStagedPage())) {
                throw invalid("source checkpoint claim lineage is inconsistent");
            }
            if (stagedPage != null
                    && !stagedPage.pageFingerprint().equals(
                    snapshot.stagedPageFingerprint())) {
                throw invalid("staged source page does not match checkpoint");
            }
        }

        /** Claim disposition. */
        public enum Outcome {
            ACQUIRED,
            NO_WORK
        }

        /** Creates one bounded no-work result. */
        public static Claim noWork(Instant observedAt) {
            return new Claim(Outcome.NO_WORK, observedAt, null, null, null);
        }
    }

    /**
     * Payload-free durable checkpoint projection.
     *
     * @param key exact source stream
     * @param controlCommandRef backfill authority command, absent for live
     * @param baselinePageFingerprint immutable deployment/command baseline
     * @param baselineCursorRef immutable opaque baseline cursor reference
     * @param committedSequence committed page sequence from the baseline
     * @param committedPageFingerprint committed page-chain head
     * @param committedCursorRef current opaque cursor reference
     * @param committedWatermarkRef current source watermark, absent at baseline
     * @param eventTimeThrough inclusive source event-time coverage, epoch at baseline
     * @param status durable stream state
     * @param stagedPageFingerprint exact pinned page, blank when none
     * @param attemptCount total acquired turns
     * @param consecutiveFailures current failure streak
     * @param nextEligibleAt database-time retry/idle cursor
     * @param leaseEpoch monotonic worker fence
     * @param leaseExpiresAt current lease expiry or epoch without a lease
     * @param failureCode stable payload-free failure, blank when healthy
     * @param createdAt durable registration time
     * @param updatedAt latest committed transition time
     */
    record Snapshot(
            StreamKey key,
            MirrorArtifactRef controlCommandRef,
            String baselinePageFingerprint,
            MirrorArtifactRef baselineCursorRef,
            long committedSequence,
            String committedPageFingerprint,
            MirrorArtifactRef committedCursorRef,
            MirrorArtifactRef committedWatermarkRef,
            Instant eventTimeThrough,
            Status status,
            String stagedPageFingerprint,
            long attemptCount,
            int consecutiveFailures,
            Instant nextEligibleAt,
            long leaseEpoch,
            Instant leaseExpiresAt,
            String failureCode,
            Instant createdAt,
            Instant updatedAt
    ) {
        /** Enforces stream-type, baseline, progress, lease, and state closure. */
        public Snapshot {
            key = Objects.requireNonNull(key, "key");
            if (key.streamKind() == AuthoritativeOutcomeSourcePage.StreamKind.LIVE) {
                if (controlCommandRef != null) {
                    throw invalid("live checkpoint cannot reference a control command");
                }
            } else {
                controlCommandRef = Objects.requireNonNull(
                        controlCommandRef, "controlCommandRef");
                if (!AuthoritativeOutcomeConnectorControlCommand.ARTIFACT_KIND.equals(
                        controlCommandRef.kind())) {
                    throw invalid("backfill checkpoint command kind is invalid");
                }
            }
            baselinePageFingerprint = fingerprint(
                    baselinePageFingerprint, "baselinePageFingerprint");
            baselineCursorRef = cursorRef(baselineCursorRef, "baselineCursorRef");
            committedPageFingerprint = fingerprint(
                    committedPageFingerprint, "committedPageFingerprint");
            committedCursorRef = cursorRef(committedCursorRef, "committedCursorRef");
            if (committedSequence < 0) {
                throw invalid("committedSequence must not be negative");
            }
            eventTimeThrough = Objects.requireNonNull(
                    eventTimeThrough, "eventTimeThrough");
            if (committedSequence == 0) {
                if (!committedPageFingerprint.equals(baselinePageFingerprint)
                        || !committedCursorRef.equals(baselineCursorRef)
                        || committedWatermarkRef != null
                        || !Instant.EPOCH.equals(eventTimeThrough)) {
                    throw invalid("baseline checkpoint progress is inconsistent");
                }
            } else {
                committedWatermarkRef = Objects.requireNonNull(
                        committedWatermarkRef, "committedWatermarkRef");
                if (!AuthoritativeOutcomeSourcePage.WATERMARK_KIND.equals(
                        committedWatermarkRef.kind())) {
                    throw invalid("committed source watermark kind is invalid");
                }
            }
            status = Objects.requireNonNull(status, "status");
            stagedPageFingerprint = optionalFingerprint(
                    stagedPageFingerprint, "stagedPageFingerprint");
            if (!stagedPageFingerprint.isBlank()
                    && (status == Status.COMPLETE || status == Status.REVOKED)) {
                throw invalid("terminal source checkpoint cannot retain a staged page");
            }
            if (attemptCount < 0 || consecutiveFailures < 0 || leaseEpoch < 0) {
                throw invalid("source checkpoint counters must not be negative");
            }
            nextEligibleAt = Objects.requireNonNull(nextEligibleAt, "nextEligibleAt");
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            failureCode = Objects.requireNonNullElse(failureCode, "").trim();
            if (!failureCode.isBlank() && !FAILURE_CODE.matcher(failureCode).matches()) {
                throw invalid("source checkpoint failureCode is invalid");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw invalid("source checkpoint update time is invalid");
            }
        }

        /** @return whether one exact page survives for replay */
        public boolean hasStagedPage() {
            return !stagedPageFingerprint.isBlank();
        }

        /** @return exact request position at the committed cursor */
        public AuthoritativeOutcomeSource.Position position() {
            return new AuthoritativeOutcomeSource.Position(
                    key.scope(), key.connectorId(), key.connectorGeneration(),
                    key.streamKind(), key.streamId(), committedSequence,
                    committedPageFingerprint, committedCursorRef);
        }
    }

    /** Server-owned lease, retry, quarantine, and idle policy. */
    record Policy(
            Duration leaseDuration,
            Duration baseRetryDelay,
            Duration maximumRetryDelay,
            Duration idleDelay,
            int maximumConsecutiveFailures
    ) {
        /** Enforces bounded whole-second controls. */
        public Policy {
            leaseDuration = duration(leaseDuration, Duration.ofSeconds(1), Duration.ofMinutes(10));
            baseRetryDelay = duration(baseRetryDelay, Duration.ofSeconds(1), Duration.ofHours(1));
            maximumRetryDelay = duration(
                    maximumRetryDelay, baseRetryDelay, Duration.ofDays(1));
            idleDelay = duration(idleDelay, Duration.ofSeconds(1), Duration.ofHours(1));
            if (maximumConsecutiveFailures < 1 || maximumConsecutiveFailures > 100) {
                throw invalid("maximumConsecutiveFailures is outside the supported bound");
            }
        }

        /** Returns bounded exponential retry delay for the one-based failure streak. */
        public Duration retryDelay(int consecutiveFailures) {
            long factor = 1L << Math.min(30, Math.max(0, consecutiveFailures - 1));
            Duration value;
            try {
                value = baseRetryDelay.multipliedBy(factor);
            } catch (ArithmeticException overflow) {
                value = maximumRetryDelay;
            }
            return value.compareTo(maximumRetryDelay) > 0
                    ? maximumRetryDelay : value;
        }
    }

    /** Stable repository rejection vocabulary. */
    enum Reason {
        NOT_FOUND,
        CONTENT_CONFLICT,
        COMMAND_INVALID,
        GENERATION_REVOKED,
        LEASE_LOST,
        PAGE_CONFLICT,
        TERMINAL_STREAM,
        STORAGE_INVALID
    }

    /** Stable repository violation. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one bounded checkpoint violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome source checkpoint rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }

    private static String identifier(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!OWNER_ID.matcher(exact).matches()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = optionalFingerprint(value, field);
        if (exact.isBlank()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!exact.isBlank() && !exact.matches("sha256:[a-f0-9]{64}")) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static MirrorArtifactRef cursorRef(MirrorArtifactRef value, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!AuthoritativeOutcomeSourcePage.CURSOR_KIND.equals(exact.kind())) {
            throw invalid(field + " must reference " + AuthoritativeOutcomeSourcePage.CURSOR_KIND);
        }
        return exact;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum) {
        Duration exact = Objects.requireNonNull(value, "duration");
        if (exact.compareTo(minimum) < 0 || exact.compareTo(maximum) > 0
                || exact.getNano() != 0) {
            throw invalid("source checkpoint duration is outside the supported bound");
        }
        return exact;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
