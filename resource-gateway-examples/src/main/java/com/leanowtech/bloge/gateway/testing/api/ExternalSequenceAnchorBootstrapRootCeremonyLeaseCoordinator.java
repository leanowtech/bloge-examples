package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatResult;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains database-fenced execution claims while opaque ceremony signers are running.
 *
 * <p>Every heartbeat replaces the local claim with a journal-issued successor version. Callers
 * must {@link LeaseGuard#freeze()} before completion or failure release; freeze stops scheduling,
 * waits for an in-flight heartbeat, and returns the only claim that may still mutate the journal.
 * An ambiguous database failure is retried once with the same heartbeat request id so a commit
 * followed by response loss resolves through the journal's bounded idempotency slot.</p>
 *
 * <p>The coordinator improves liveness but never extends checker approval or material execution
 * deadlines. Losing a heartbeat makes ownership uncertain and therefore prevents any generated
 * artifact from being committed or exposed.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator
        implements AutoCloseable {

    private static final long MINIMUM_LEASE_SECONDS = 3L;
    private static final long MAXIMUM_LEASE_SECONDS = 300L;

    private final ExternalSequenceAnchorBootstrapRootCeremonyJournal journal;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Set<LeaseGuard> activeGuards = ConcurrentHashMap.newKeySet();

    /**
     * Creates one process-local daemon scheduler for all active ceremony attempts.
     *
     * @param journal durable database-clock claim authority
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal) {
        this(journal, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-bootstrap-root-ceremony-heartbeat");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal,
            ScheduledExecutorService scheduler) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (!journal.durable()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root ceremony heartbeat requires a durable journal");
        }
    }

    /**
     * Starts automatic renewal of one newly acquired execution claim.
     *
     * @param claim exact live claim returned by the journal
     * @param snapshot acquisition snapshot corresponding to the exact claim
     * @param leaseDurationSeconds whole-second successor lease from 3 through 300 seconds
     * @return closeable guard that must be frozen before terminal journal mutation
     */
    public LeaseGuard monitor(
            ExecutionClaim claim,
            CeremonySnapshot snapshot,
            long leaseDurationSeconds) {
        ExecutionClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        CeremonySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        long requiredLease = requiredLease(leaseDurationSeconds);
        if (requiredSnapshot.state()
                != ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.EXECUTING
                || !requiredSnapshot.ceremonyId().equals(requiredClaim.ceremonyId())
                || !requiredSnapshot.claimOwner().equals(requiredClaim.workerId())
                || requiredSnapshot.claimVersion() != requiredClaim.claimVersion()
                || !requiredSnapshot.claimUntil().equals(requiredClaim.claimUntil())
                || !requiredSnapshot.proposal().equals(requiredClaim.proposal())) {
            throw new IllegalArgumentException(
                    "Ceremony lease monitor requires the exact acquisition projection");
        }

        LeaseGuard guard = new LeaseGuard(requiredClaim, requiredSnapshot, requiredLease);
        long intervalMillis = Math.max(1L,
                Math.multiplyExact(requiredLease, 1_000L) / 3L);
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "Bootstrap-root ceremony lease coordinator is closed");
            }
            activeGuards.add(guard);
            try {
                if (renewalRequired(requiredClaim, requiredSnapshot)) {
                    guard.future = scheduler.scheduleWithFixedDelay(guard::heartbeat,
                            intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
                    if (!guard.held()) {
                        guard.cancelFuture();
                    }
                }
            } catch (RuntimeException schedulingFailure) {
                guard.invalidate(new LeaseLostException(
                        "Bootstrap-root ceremony heartbeat could not be scheduled",
                        null, requiredSnapshot, schedulingFailure));
                throw schedulingFailure;
            }
        }
        return guard;
    }

    /** Stops all future heartbeats without interrupting caller-owned signer operations. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (LeaseGuard guard : List.copyOf(activeGuards)) {
                guard.invalidate(new LeaseLostException(
                        "Bootstrap-root ceremony coordinator closed during execution",
                        null, guard.lastVerifiedSnapshot(), null));
            }
            scheduler.shutdownNow();
        }
    }

    /** One automatically renewed exact execution fence. */
    public final class LeaseGuard implements AutoCloseable {
        private final Object lock = new Object();
        private final long leaseDurationSeconds;
        private ExecutionClaim current;
        private CeremonySnapshot snapshot;
        private LeaseLostException failure;
        private boolean stopped;
        private volatile ScheduledFuture<?> future;

        private LeaseGuard(
                ExecutionClaim claim,
                CeremonySnapshot snapshot,
                long leaseDurationSeconds) {
            this.current = claim;
            this.snapshot = snapshot;
            this.leaseDurationSeconds = leaseDurationSeconds;
        }

        /**
         * Reports whether no renewal result has made local ownership uncertain.
         *
         * @return {@code true} while the guard may still be frozen for mutation
         */
        public boolean held() {
            synchronized (lock) {
                return !stopped && failure == null;
            }
        }

        /**
         * Stops renewal, waits for an in-flight heartbeat, and returns the latest exact claim.
         *
         * <p>The method remains idempotent after a successful freeze. Once a heartbeat is rejected
         * or unavailable it always throws and never falls back to an older claim.</p>
         *
         * @return latest database-issued execution claim
         * @throws LeaseLostException when ownership is stale, expired, or uncertain
         */
        public ExecutionClaim freeze() {
            ExecutionClaim frozen;
            LeaseLostException heartbeatFailure;
            synchronized (lifecycleLock) {
                synchronized (lock) {
                    stopped = true;
                    frozen = current;
                    heartbeatFailure = failure;
                }
                activeGuards.remove(this);
            }
            cancelFuture();
            if (heartbeatFailure != null) {
                throw heartbeatFailure;
            }
            return frozen;
        }

        private void heartbeat() {
            boolean removeGuard = false;
            boolean cancelRenewal = false;
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                if (!renewalRequired(current, snapshot)) {
                    cancelRenewal = true;
                } else {
                    HeartbeatCommand command = new HeartbeatCommand(
                            HeartbeatCommand.SCHEMA_VERSION,
                            "ceremony-heartbeat-" + UUID.randomUUID(),
                            current, leaseDurationSeconds);
                    try {
                        apply(command, heartbeatWithAmbiguousCommitRetry(command));
                    } catch (RuntimeException unavailable) {
                        failure = new LeaseLostException(
                                "Bootstrap-root ceremony heartbeat authority is unavailable",
                                null, snapshot, unavailable);
                        stopped = true;
                    }
                    removeGuard = failure != null;
                    cancelRenewal = removeGuard || !renewalRequired(current, snapshot);
                }
            }
            if (removeGuard) {
                activeGuards.remove(this);
            }
            if (cancelRenewal) {
                cancelFuture();
            }
        }

        private HeartbeatResult heartbeatWithAmbiguousCommitRetry(HeartbeatCommand command) {
            try {
                return journal.heartbeat(command);
            } catch (RuntimeException firstFailure) {
                try {
                    return journal.heartbeat(command);
                } catch (RuntimeException retryFailure) {
                    retryFailure.addSuppressed(firstFailure);
                    throw retryFailure;
                }
            }
        }

        private void apply(HeartbeatCommand command, HeartbeatResult result) {
            HeartbeatResult required = Objects.requireNonNull(result, "heartbeat result");
            CeremonySnapshot returnedSnapshot = required.snapshot();
            if (required.disposition() == HeartbeatDisposition.RENEWED
                    || required.disposition() == HeartbeatDisposition.IDEMPOTENT_REPLAY) {
                ExecutionClaim successor = required.claim();
                if (!validSuccessor(command, successor, returnedSnapshot)) {
                    failure = new LeaseLostException(
                            "Bootstrap-root ceremony heartbeat returned a malformed successor",
                            required.disposition(), snapshot, null);
                    stopped = true;
                    return;
                }
                current = successor;
                snapshot = returnedSnapshot;
                return;
            }
            if (!returnedSnapshot.ceremonyId().equals(current.ceremonyId())
                    || !returnedSnapshot.proposal().equals(current.proposal())) {
                failure = new LeaseLostException(
                        "Bootstrap-root ceremony heartbeat returned an unrelated snapshot",
                        required.disposition(), snapshot, null);
                stopped = true;
                return;
            }
            snapshot = returnedSnapshot;
            failure = new LeaseLostException(
                    "Bootstrap-root ceremony heartbeat no longer owns a live fence",
                    required.disposition(), snapshot, null);
            stopped = true;
        }

        private boolean validSuccessor(
                HeartbeatCommand command,
                ExecutionClaim successor,
                CeremonySnapshot returnedSnapshot) {
            long successorVersion;
            long successorHeartbeatCount;
            try {
                successorVersion = Math.addExact(current.claimVersion(), 1L);
                successorHeartbeatCount = Math.addExact(snapshot.heartbeatCount(), 1L);
            } catch (ArithmeticException exhausted) {
                return false;
            }
            return command.claim().equals(current)
                    && successor.ceremonyId().equals(current.ceremonyId())
                    && successor.workerId().equals(current.workerId())
                    && successor.proposal().equals(current.proposal())
                    && successor.claimVersion() == successorVersion
                    && successor.claimUntil().isAfter(current.claimUntil())
                    && returnedSnapshot.state()
                    == ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.EXECUTING
                    && returnedSnapshot.ceremonyId().equals(successor.ceremonyId())
                    && returnedSnapshot.proposal().equals(successor.proposal())
                    && returnedSnapshot.claimOwner().equals(successor.workerId())
                    && returnedSnapshot.claimVersion() == successor.claimVersion()
                    && returnedSnapshot.claimUntil().equals(successor.claimUntil())
                    && returnedSnapshot.heartbeatRequestId()
                    .equals(command.heartbeatRequestId())
                    && returnedSnapshot.heartbeatCount() == successorHeartbeatCount
                    && returnedSnapshot.attemptCount() == snapshot.attemptCount()
                    && returnedSnapshot.heartbeatAt() != null
                    && returnedSnapshot.updatedAt().equals(returnedSnapshot.heartbeatAt())
                    && returnedSnapshot.approvalUntil() != null
                    && !successor.claimUntil().isAfter(returnedSnapshot.approvalUntil());
        }

        /** Stops renewal without asserting that the last observed claim remains live. */
        @Override
        public void close() {
            synchronized (lock) {
                stopped = true;
            }
            activeGuards.remove(this);
            cancelFuture();
        }

        private void invalidate(LeaseLostException lost) {
            synchronized (lock) {
                if (failure == null) {
                    failure = lost;
                }
                stopped = true;
            }
            activeGuards.remove(this);
            cancelFuture();
        }

        private CeremonySnapshot lastVerifiedSnapshot() {
            synchronized (lock) {
                return snapshot;
            }
        }

        private void cancelFuture() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    /** Raised when a guarded signer attempt must discard its uncommitted generated artifact. */
    public static final class LeaseLostException extends IllegalStateException {
        /** Bounded journal classification, or {@code null} for local uncertainty. */
        private final HeartbeatDisposition disposition;

        /** Last integrity-verified snapshot, which may predate the ownership loss. */
        private final CeremonySnapshot lastVerifiedSnapshot;

        /**
         * Creates one payload-free local ownership-loss signal.
         *
         * @param message bounded diagnostic without provider or key material
         * @param disposition journal classification, or {@code null} for local/database failure
         * @param lastVerifiedSnapshot latest integrity-verified projection before ownership loss
         * @param cause internal scheduling or journal failure, or {@code null}
         */
        public LeaseLostException(
                String message,
                HeartbeatDisposition disposition,
                CeremonySnapshot lastVerifiedSnapshot,
                Throwable cause) {
            super(message, cause);
            this.disposition = disposition;
            this.lastVerifiedSnapshot = Objects.requireNonNull(
                    lastVerifiedSnapshot, "lastVerifiedSnapshot");
        }

        /**
         * Returns the bounded journal disposition when one was observed.
         *
         * @return heartbeat disposition, or {@code null} for transport/scheduling uncertainty
         */
        public HeartbeatDisposition disposition() {
            return disposition;
        }

        /**
         * Returns the latest integrity-verified projection known to the guard.
         *
         * @return last verified snapshot, which may be stale after ownership becomes uncertain
         */
        public CeremonySnapshot lastVerifiedSnapshot() {
            return lastVerifiedSnapshot;
        }
    }

    private static long requiredLease(long value) {
        if (value < MINIMUM_LEASE_SECONDS || value > MAXIMUM_LEASE_SECONDS) {
            throw new IllegalArgumentException(
                    "Ceremony auto-heartbeat lease must be from three through 300 seconds");
        }
        return value;
    }

    private static boolean renewalRequired(
            ExecutionClaim claim,
            CeremonySnapshot snapshot) {
        return snapshot.approvalUntil() != null
                && claim.claimUntil().isBefore(snapshot.approvalUntil());
    }
}
