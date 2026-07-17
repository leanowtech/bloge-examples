package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted persistence boundary for a durable test control closure and its engine-state mutation.
 *
 * <p>The mutation receives the transaction-bound test-runtime {@link JdbcTemplate}. Implementations
 * must commit or roll back the mutation and checkpoint row together. Callers must not perform
 * network I/O or use another datasource inside the callback: those effects cannot participate in
 * this local transaction.</p>
 */
public interface DurableTestExecutionCheckpointRepository {

    /**
     * Reserves or resolves one caller-idempotent initial durable execution.
     *
     * <p>The reservation contains no business context or fixture payload. A live reservation is
     * never stolen; an expired reservation may be fenced by a new server owner, while committed or
     * rejected results remain immutable.</p>
     *
     * @param command exact authenticated creation intent and proposed server identities
     * @return acquired, in-progress, committed, or rejected reservation result
     */
    InitialCreationReservationResult reserveInitialCreation(
            InitialCreationCommand command);

    /**
     * Resolves an immutable creation result before mutable dependencies are re-authorized.
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified test or staging environment
     * @param clientRequestId caller-stable idempotency key
     * @param requestFingerprint canonical authenticated caller intent
     * @return committed or rejected result, or empty for absent/pending commands
     */
    Optional<InitialCreationReservationResult> findInitialCreationResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint);

    /**
     * Renews one exact live creation-preparation fence using the persistence-authority clock.
     *
     * <p>The transition leaves scope, authenticated intent, run and engine identities, owner, and
     * lease epoch unchanged. It advances only the database-authority update time, lease deadline,
     * and aggregate record fingerprint. A stale, expired, terminal, or differently owned
     * reservation fails closed.</p>
     *
     * @param reservation exact locally held pending reservation
     * @param leaseDuration bounded server-owned renewal duration
     * @return renewed pending reservation carrying the successor record fingerprint
     */
    InitialCreationReservation heartbeatInitialCreation(
            InitialCreationReservation reservation,
            Duration leaseDuration);

    /**
     * Atomically commits one acquired creation reservation, initial control checkpoint, complete
     * BLOGE aggregate, and local companion audit/evidence mutation.
     *
     * @param reservation exact acquired database fence
     * @param checkpoint sealed revision-zero suspended checkpoint
     * @param engineStateMutation exact staged BLOGE aggregate mutation
     * @param companionMutation local payload-free audit/evidence write
     * @return immutable committed result or its exact idempotent replay
     */
    InitialCreationReservationResult commitInitialCreation(
            InitialCreationReservation reservation,
            DurableTestExecutionCheckpoint checkpoint,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Atomically records a deterministic, payload-free creation rejection.
     *
     * @param reservation exact acquired database fence
     * @param rejectionCode bounded machine-stable reason
     * @param companionMutation local payload-free audit write
     * @return immutable rejected result or its exact replay
     */
    InitialCreationReservationResult rejectInitialCreation(
            InitialCreationReservation reservation,
            String rejectionCode,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Creates revision zero and atomically writes the associated engine-state closure.
     *
     * @param checkpoint sealed initial control checkpoint
     * @param engineStateMutation exact engine-state writes participating in the transaction
     * @return committed initial checkpoint, or the identical existing value on idempotent replay
     */
    DurableTestExecutionCheckpoint create(DurableTestExecutionCheckpoint checkpoint,
                                            BoundEngineStateMutation engineStateMutation);

    /**
     * Advances exactly one revision under the expected owner/epoch/revision fence.
     *
     * @param checkpoint sealed next control checkpoint
     * @param expectedFence exact current owner, lease epoch, and revision
     * @param engineStateMutation exact engine-state writes participating in the transaction
     * @return committed next checkpoint
     */
    DurableTestExecutionCheckpoint advance(DurableTestExecutionCheckpoint checkpoint,
                                             Fence expectedFence,
                                             BoundEngineStateMutation engineStateMutation);

    /**
     * Resolves a run only within the verified tenant and environment scope.
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified test or staging environment
     * @param runId governed durable run identity
     * @return verified checkpoint when the scoped run exists
     */
    Optional<DurableTestExecutionCheckpoint> find(String tenantId, String environmentId,
                                                   String runId);

    /**
     * Resolves a BLOGE execution only within the verified tenant and environment scope.
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified test or staging environment
     * @param engineExecutionId exact BLOGE execution identity
     * @return verified checkpoint when the scoped engine execution exists
     */
    Optional<DurableTestExecutionCheckpoint> findByEngineExecutionId(
            String tenantId, String environmentId, String engineExecutionId);

    /**
     * Finds a bounded, stably ordered page of expired recovery candidates in one verified scope.
     *
     * <p>The persistence implementation must obtain the cutoff from its own database clock, apply
     * every scope, lifecycle, expiry, and limit predicate in SQL, and integrity-verify every
     * returned checkpoint. This is candidate discovery only: callers must still re-authorize the
     * exact dependency closure and use {@link #acquireWorkerCommandIdempotently} for the fenced
     * state transition.</p>
     *
     * @param query server-bounded scope and page size
     * @return cyclic keyset page whose candidates each carry atomic scan progress
     */
    RecoveryCandidatePage findExpiredRecoveryCandidates(
            RecoveryCandidateQuery query);

    /**
     * Atomically commits one worker pull result and, when selected, claims its exact expired lease.
     *
     * <p>Both {@code ACQUIRED} and {@code NO_WORK} are immutable results under the same scoped
     * idempotency key. For an acquisition, exact lease CAS, authorization-bound hidden dispatch,
     * result record, and companion audit commit in one local transaction. A no-work result records
     * only the database observation time and companion audit. Losing a concurrent idempotency race
     * must roll back any lease transition before replaying the winner.</p>
     *
     * @param command authenticated pull identity independent of any selected run
     * @param selection exact authorized candidate, or empty after a bounded scan found no claimable work
     * @param scanProgress last candidate actually examined, or empty when the queue page was empty
     * @param companionMutation local payload-free audit mutation
     * @return immutable original result, marked as replay when already committed
     */
    default WorkerAcquisitionResult acquireWorkerCommandIdempotently(
            WorkerAcquisitionCommand command,
            Optional<WorkerAcquisitionSelection> selection,
            Optional<WorkerScanProgress> scanProgress,
            TestRuntimeTransactionMutation companionMutation) {
        return acquireWorkerCommandIdempotently(
                command, selection, scanProgress, List.of(), companionMutation);
    }

    /**
     * Atomically commits one worker pull result, scan progress, and deterministic deferrals.
     *
     * <p>Deferrals are advisory scheduling state bound to the exact source checkpoint fingerprint.
     * They must be written only when the supplied scan token wins compare-and-advance; stale scans
     * cannot amplify failure counters. Infrastructure failures must never be supplied as deferrals.
     * A successful selection clears a matching historical deferral in the same transaction.</p>
     *
     * @param command authenticated pull identity independent of any selected run
     * @param selection exact authorized candidate, or empty after no claimable work was found
     * @param scanProgress last candidate actually examined, or empty for an empty queue page
     * @param deferrals deterministic ineligible candidates observed in this exact scan
     * @param companionMutation local payload-free audit mutation
     * @return immutable original result, marked as replay when already committed
     */
    WorkerAcquisitionResult acquireWorkerCommandIdempotently(
            WorkerAcquisitionCommand command,
            Optional<WorkerAcquisitionSelection> selection,
            Optional<WorkerScanProgress> scanProgress,
            List<WorkerCandidateDeferral> deferrals,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Resolves an immutable worker pull outcome before rescanning or re-authorizing dependencies.
     *
     * @param scope verified worker tenant, organization, project, and environment
     * @param clientRequestId caller-stable idempotency key
     * @param requestFingerprint complete authenticated pull intent
     * @return exact committed acquisition or no-work result
     */
    Optional<WorkerAcquisitionResult> findWorkerAcquisitionResult(
            WorkerAcquisitionScope scope,
            String clientRequestId,
            String requestFingerprint);

    /**
     * Atomically fences an expired resumable execution and transfers it to a recovery owner.
     *
     * <p>The repository, rather than the caller, supplies the claim timestamp from its persistence
     * authority. A successful claim increments both lease epoch and control revision, enters
     * {@link DurableTestExecutionCheckpoint.Status#RESUMING}, and leaves every dependency and
     * engine-state closure unchanged.</p>
     *
     * @param claim exact expired lease and claimant intent
     * @return newly sealed recovery checkpoint
     */
    DurableTestExecutionCheckpoint claimExpiredLease(LeaseClaim claim);

    /**
     * Atomically reserves a caller idempotency key and claims one expired execution lease.
     *
     * <p>The immutable command result is retained independently from the mutable checkpoint row.
     * Consequently, a retry after the claim committed but its response was lost returns the exact
     * original result even when the live checkpoint has since advanced. Reusing the scoped key for
     * different command intent must fail closed.</p>
     *
     * @param command exact lease claim and caller-stable command identity
     * @return original claim result and whether this invocation replayed it
     */
    LeaseClaimResult claimExpiredLeaseIdempotently(ResumeLeaseCommand command);

    /**
     * Claims or replays one lease command and commits a companion local mutation atomically.
     *
     * <p>The companion mutation is applied for both a first commit and an idempotent replay. A
     * failure rolls back a new lease claim and its immutable command record; a replay has no lease
     * mutation to undo but still fails closed without returning the result.</p>
     *
     * @param command exact lease claim and caller-stable command identity
     * @param companionMutation local audit or evidence write bound to the same database transaction
     * @return original claim result and whether this invocation replayed it
     */
    LeaseClaimResult claimExpiredLeaseIdempotently(
            ResumeLeaseCommand command, TestRuntimeTransactionMutation companionMutation);

    /**
     * Resolves the immutable outcome of an already committed lease command without consulting the
     * live checkpoint row.
     *
     * <p>This read must verify the complete command intent and stored result integrity exactly as a
     * command replay would. It is deliberately separate from dependency re-authorization: a caller
     * retrying after a lost response is entitled to the original committed result even when the
     * graph, fixture authority, or live checkpoint has subsequently advanced.</p>
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified non-production environment authority
     * @param clientRequestId caller-stable idempotency key
     * @param requestFingerprint server-derived authorized caller intent
     * @return the original result marked as an idempotent replay, or empty when no key is reserved
     */
    Optional<LeaseClaimResult> findLeaseClaimResult(String tenantId,
                                                    String environmentId,
                                                    String clientRequestId,
                                                    String requestFingerprint);

    /**
     * Resolves the immutable worker handoff for one exact claimed owner fence.
     *
     * <p>This lookup reads historical command state rather than treating it as live authority. A
     * worker must separately compare the returned dispatch with the live checkpoint immediately
     * before execution.</p>
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified non-production environment authority
     * @param runId governed durable run identity
     * @param expectedFence exact owner, epoch, and revision issued by the claim
     * @param expectedCheckpointFingerprint exact claimed checkpoint identity
     * @return verified payload-free dispatch when that exact claim exists
     */
    Optional<DurableTestRecoveryDispatch> findRecoveryDispatch(
            String tenantId,
            String environmentId,
            String runId,
            Fence expectedFence,
            String expectedCheckpointFingerprint);

    /**
     * Atomically renews one live recovery fence and rotates its payload-free worker dispatch.
     *
     * <p>The source dispatch is a compare-and-set value, not a bearer credential. Implementations
     * must first prove that it came from a committed claim or predecessor heartbeat, validate it
     * against the live {@code RESUMING} checkpoint using database time, advance exactly one control
     * revision without changing engine or replay state, and retain the result for
     * ambiguous-response replay.</p>
     *
     * @param command exact source dispatch, bounded renewal, and idempotency identity
     * @return newly sealed checkpoint and successor dispatch, or the exact committed replay
     */
    RecoveryHeartbeatResult heartbeatRecoveryLeaseIdempotently(
            RecoveryHeartbeatCommand command);

    /**
     * Renews or replays one recovery heartbeat with a transaction-bound companion mutation.
     *
     * <p>The companion mutation is suitable for payload-free audit or evidence indexes. Its
     * failure rolls back the checkpoint CAS, heartbeat command record, and successor dispatch as
     * one local transaction.</p>
     *
     * @param command exact source dispatch, bounded renewal, and idempotency identity
     * @param companionMutation local audit or evidence write using the same transaction
     * @return newly sealed checkpoint and successor dispatch, or the exact committed replay
     */
    RecoveryHeartbeatResult heartbeatRecoveryLeaseIdempotently(
            RecoveryHeartbeatCommand command,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Atomically commits one terminal BLOGE mutation, terminal checkpoint, and blocking receipt.
     *
     * <p>The source dispatch must still be issued, live, and unexpired. A first commit applies the
     * exact engine mutation; an idempotent replay returns the immutable original result without
     * applying the mutation again.</p>
     *
     * @param command exact terminal intent and final payload-free state closure
     * @param engineStateMutation exact BLOGE aggregate mutation represented by the command
     * @return terminal checkpoint and promotion-blocking receipt
     */
    RecoveryTerminalResult terminalizeRecoveryIdempotently(
            RecoveryTerminalCommand command,
            BoundEngineStateMutation engineStateMutation);

    /**
     * Commits or replays one recovery terminal command with a local companion mutation.
     *
     * @param command exact terminal intent and final payload-free state closure
     * @param engineStateMutation exact BLOGE aggregate mutation represented by the command
     * @param companionMutation local evidence index or semantic audit write
     * @return terminal checkpoint and promotion-blocking receipt
     */
    RecoveryTerminalResult terminalizeRecoveryIdempotently(
            RecoveryTerminalCommand command,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Resolves an immutable terminal command result without consulting the live checkpoint.
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified non-production environment
     * @param clientRequestId caller-stable terminal idempotency key
     * @param requestFingerprint server-derived authenticated terminal intent
     * @return exact committed terminal result marked as a replay, or empty
     */
    Optional<RecoveryTerminalResult> findRecoveryTerminalResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint);

    /**
     * Atomically commits one cold-signal recovery step at either a new suspension or a terminal
     * boundary.
     *
     * <p>A suspended step relinquishes its recovery lease at the database linearization time, so a
     * later worker acquisition must freshly authorize and claim the new checkpoint. A terminal
     * step additionally creates the same promotion-blocking receipt used by the terminal-only v1
     * protocol. Engine state, control checkpoint, immutable command result, optional receipt, and
     * companion mutation share one local commit decision.</p>
     *
     * @param command exact source dispatch, resulting boundary, and idempotency identity
     * @param engineStateMutation exact BLOGE aggregate mutation represented by the command
     * @param companionMutation local payload-free audit or evidence write
     * @return immutable suspended or terminal step result
     */
    RecoveryStepResult advanceRecoveryStepIdempotently(
            RecoveryStepCommand command,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Resolves an immutable recovery-step result before dispatch lookup or engine execution.
     *
     * @param tenantId verified tenant authority
     * @param environmentId verified non-production environment
     * @param clientRequestId caller-stable recovery-step idempotency key
     * @param requestFingerprint server-derived authenticated step intent
     * @return exact committed result marked as a replay, or empty
     */
    Optional<RecoveryStepResult> findRecoveryStepResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint);

    /**
     * Atomically reserves the complete payload-free intent of one bounded recovery sequence.
     *
     * <p>The reservation must linearize before any child owner claim or recovery step. It binds
     * the outer idempotency key to the run, authenticated scope, signal count, and a fingerprint
     * covering every signal without persisting signal values. This prevents a retry from changing
     * a later, not-yet-executed signal after earlier steps have already committed.</p>
     *
     * @param command complete authenticated sequence intent
     * @param companionMutation local payload-free audit mutation in the same transaction
     * @return immutable reservation or its integrity-verified replay
     */
    RecoverySequenceReservation reserveRecoverySequenceIdempotently(
            RecoverySequenceCommand command,
            TestRuntimeTransactionMutation companionMutation);

    /**
     * Processes one database-leased page of expired recovery-sequence replay state.
     *
     * <p>Implementations must replace every selected outer request with a non-reversible
     * idempotency tombstone before erasing the detailed sequence and its server-derived child
     * commands. Tombstone insertion, verified child deletion, expired-tombstone purge, aggregate
     * counters, and lease release form one local transaction.</p>
     *
     * @param commandRetention exact response-replay window for detailed sequence commands
     * @param tombstoneRetention additional request-identity reservation window
     * @param pageSize maximum outer sequences and expired tombstones processed per category
     * @return committed page result or a lease-busy result from another replica
     */
    RecoverySequenceRetentionAttempt retainRecoverySequencePage(
            Duration commandRetention,
            Duration tombstoneRetention,
            int pageSize);

    /**
     * Returns aggregate-only recovery-sequence lifecycle state.
     *
     * @return integrity-verified database-clock retention snapshot
     */
    RecoverySequenceRetentionSnapshot recoverySequenceRetentionSnapshot();

    /**
     * Authenticated payload-free intent used to reserve an initial durable execution.
     *
     * @param clientRequestId caller-stable idempotency key scoped by tenant/environment
     * @param requestFingerprint canonical authenticated request, including business-input digest
     * @param authorizationFingerprint exact target/fixture/replay/plan/authority closure identity
     * @param scope verified tenant, organization, project, environment, and actor scope
     * @param proposedRunId server-minted run id used only when this command wins insertion
     * @param proposedEngineExecutionId server-minted BLOGE id used only on first insertion
     * @param claimantOwnerId current server attempt owner
     * @param leaseDuration bounded preparation lease
     */
    record InitialCreationCommand(
            String clientRequestId,
            String requestFingerprint,
            String authorizationFingerprint,
            DurableTestExecutionCheckpoint.Scope scope,
            String proposedRunId,
            String proposedEngineExecutionId,
            String claimantOwnerId,
            Duration leaseDuration) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);
        private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

        /** Rejects ambiguous identities and unbounded preparation leases. */
        public InitialCreationCommand {
            clientRequestId = identifier(clientRequestId, "clientRequestId");
            requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
            authorizationFingerprint = fingerprint(
                    authorizationFingerprint, "authorizationFingerprint");
            scope = Objects.requireNonNull(scope, "scope");
            proposedRunId = identifier(proposedRunId, "proposedRunId");
            proposedEngineExecutionId = identifier(
                    proposedEngineExecutionId, "proposedEngineExecutionId");
            claimantOwnerId = identifier(claimantOwnerId, "claimantOwnerId");
            leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.compareTo(MINIMUM_LEASE) < 0
                    || leaseDuration.compareTo(MAXIMUM_LEASE) > 0
                    || leaseDuration.getNano() != 0) {
                throw new IllegalArgumentException(
                        "leaseDuration must be whole seconds between one second and one hour");
            }
        }

        private static String identifier(String value, String field) {
            String normalized = requiredCreationValue(value, field);
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        field + " must be a bounded stable identifier");
            }
            return normalized;
        }

        private static String fingerprint(String value, String field) {
            String normalized = requiredCreationValue(value, field);
            if (!FINGERPRINT.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        field + " must be a canonical SHA-256 fingerprint");
            }
            return normalized;
        }
    }

    /** Lifecycle of one immutable caller creation command. */
    enum InitialCreationState {
        /** One server owner may prepare a staged execution under the current fence. */
        PENDING,
        /** Initial checkpoint and engine aggregate committed atomically. */
        COMMITTED,
        /** Exact request deterministically reached an unsupported or invalid runtime boundary. */
        REJECTED
    }

    /**
     * Content-addressed payload-free creation command state.
     *
     * @param schemaVersion internal command-record version
     * @param scope verified caller scope
     * @param clientRequestId caller idempotency key
     * @param requestFingerprint authenticated request identity
     * @param authorizationFingerprint exact executable dependency closure identity
     * @param runId server-minted durable run identity
     * @param engineExecutionId server-minted BLOGE execution identity
     * @param ownerId current preparation owner
     * @param leaseEpoch positive preparation fence generation
     * @param createdAt database-authority creation time
     * @param updatedAt database-authority latest transition time
     * @param leaseExpiresAt database-authority preparation deadline
     * @param state command lifecycle
     * @param rejectionCode machine-stable rejection, only for {@code REJECTED}
     * @param resultCheckpointFingerprint initial checkpoint identity, only for {@code COMMITTED}
     * @param recordFingerprint canonical identity of all preceding fields
     */
    record InitialCreationReservation(
            String schemaVersion,
            DurableTestExecutionCheckpoint.Scope scope,
            String clientRequestId,
            String requestFingerprint,
            String authorizationFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt,
            InitialCreationState state,
            String rejectionCode,
            String resultCheckpointFingerprint,
            String recordFingerprint) {
        /** Current internal creation command-record version. */
        public static final String SCHEMA_VERSION = "bloge.durableTestCreationCommandRecord.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects incomplete fences, timestamps, or state-dependent result fields. */
        public InitialCreationReservation {
            schemaVersion = requiredCreationValue(schemaVersion, "schemaVersion");
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "Unsupported durable creation command-record version");
            }
            scope = Objects.requireNonNull(scope, "scope");
            clientRequestId = identifier(clientRequestId, "clientRequestId");
            requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
            authorizationFingerprint = fingerprint(
                    authorizationFingerprint, "authorizationFingerprint");
            runId = identifier(runId, "runId");
            engineExecutionId = identifier(engineExecutionId, "engineExecutionId");
            ownerId = identifier(ownerId, "ownerId");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            state = Objects.requireNonNull(state, "state");
            rejectionCode = rejectionCode == null ? "" : rejectionCode.trim().toUpperCase(
                    java.util.Locale.ROOT);
            resultCheckpointFingerprint = resultCheckpointFingerprint == null
                    ? "" : resultCheckpointFingerprint.trim();
            recordFingerprint = fingerprint(recordFingerprint, "recordFingerprint");
            if (leaseEpoch <= 0 || updatedAt.isBefore(createdAt)
                    || leaseExpiresAt.isBefore(updatedAt)) {
                throw new IllegalArgumentException(
                        "Durable creation reservation fence or timestamps are invalid");
            }
            if (state == InitialCreationState.REJECTED) {
                if (!CODE.matcher(rejectionCode).matches()
                        || !resultCheckpointFingerprint.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Rejected creation requires only a bounded rejection code");
                }
            } else if (!rejectionCode.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only rejected creation may carry a rejection code");
            }
            if (state == InitialCreationState.COMMITTED) {
                fingerprint(resultCheckpointFingerprint, "resultCheckpointFingerprint");
            } else if (!resultCheckpointFingerprint.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only committed creation may carry a checkpoint result");
            }
        }

        private static String identifier(String value, String field) {
            String normalized = requiredCreationValue(value, field);
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        field + " must be a bounded stable identifier");
            }
            return normalized;
        }

        private static String fingerprint(String value, String field) {
            String normalized = requiredCreationValue(value, field);
            if (!FINGERPRINT.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        field + " must be a canonical SHA-256 fingerprint");
            }
            return normalized;
        }
    }

    /**
     * Reservation resolution returned to the creation orchestrator.
     *
     * @param reservation immutable command state
     * @param checkpoint original initial checkpoint only when committed
     * @param acquired whether this caller owns the pending preparation fence
     * @param idempotentReplay whether a prior terminal command result was replayed
     */
    record InitialCreationReservationResult(
            InitialCreationReservation reservation,
            DurableTestExecutionCheckpoint checkpoint,
            boolean acquired,
            boolean idempotentReplay) {
        /** Enforces state-specific ownership and result invariants. */
        public InitialCreationReservationResult {
            reservation = Objects.requireNonNull(reservation, "reservation");
            if (reservation.state() == InitialCreationState.COMMITTED) {
                checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
                if (!reservation.resultCheckpointFingerprint().equals(
                        checkpoint.checkpointFingerprint()) || acquired) {
                    throw new IllegalArgumentException(
                            "Committed creation result must bind its initial checkpoint");
                }
            } else if (checkpoint != null) {
                throw new IllegalArgumentException(
                        "Only committed creation may expose an initial checkpoint");
            }
            if (acquired && reservation.state() != InitialCreationState.PENDING) {
                throw new IllegalArgumentException(
                        "Only a pending creation reservation can be acquired");
            }
            if (idempotentReplay
                    && reservation.state() == InitialCreationState.PENDING) {
                throw new IllegalArgumentException(
                        "A pending creation is not an immutable replay result");
            }
        }
    }

    /**
     * Exact compare-and-set fence held by the caller.
     *
     * @param ownerId current process owner identity
     * @param leaseEpoch positive fencing generation
     * @param revision non-negative control revision
     */
    record Fence(String ownerId, long leaseEpoch, long revision) {
        /** Rejects incomplete or impossible fence values. */
        public Fence {
            ownerId = ownerId == null ? "" : ownerId.trim();
            if (ownerId.isBlank() || leaseEpoch <= 0 || revision < 0) {
                throw new IllegalArgumentException("Complete owner, lease epoch, and revision are required");
            }
        }
    }

    /**
     * Non-disclosing worker poll scope derived exclusively from verified identity.
     *
     * @param tenantId verified tenant authority
     * @param organizationId verified organization boundary
     * @param projectId verified project boundary
     * @param environmentId verified test or staging environment
     */
    record WorkerAcquisitionScope(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects incomplete or production acquisition scopes before persistence access. */
        public WorkerAcquisitionScope {
            tenantId = acquisitionIdentifier(tenantId, "tenantId");
            organizationId = acquisitionIdentifier(organizationId, "organizationId");
            projectId = acquisitionIdentifier(projectId, "projectId");
            environmentId = acquisitionRequired(environmentId, "environmentId")
                    .toLowerCase(Locale.ROOT);
            if (!Set.of("test", "staging").contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Worker acquisition requires a test or staging environment");
            }
        }

        /** Verifies that a candidate belongs to this complete non-disclosure scope. */
        public boolean contains(DurableTestExecutionCheckpoint checkpoint) {
            if (checkpoint == null) {
                return false;
            }
            DurableTestExecutionCheckpoint.Scope candidate = checkpoint.scope();
            return tenantId.equals(candidate.tenantId())
                    && organizationId.equals(candidate.organizationId())
                    && projectId.equals(candidate.projectId())
                    && environmentId.equals(candidate.environmentId());
        }

        private static String acquisitionIdentifier(String value, String field) {
            String normalized = acquisitionRequired(value, field);
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(field + " must be a bounded stable identifier");
            }
            return normalized;
        }

        private static String acquisitionRequired(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Bounded candidate discovery request owned by the server.
     *
     * @param scope exact verified worker scope
     * @param limit positive SQL page size no greater than 1,000
     */
    record RecoveryCandidateQuery(WorkerAcquisitionScope scope, int limit) {
        /** Prevents an accidental unbounded scheduler scan. */
        public RecoveryCandidateQuery {
            scope = Objects.requireNonNull(scope, "scope");
            if (limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException(
                        "Recovery candidate limit must be between 1 and 1000");
            }
        }
    }

    /**
     * One bounded cyclic candidate page read from a repeatable database snapshot.
     *
     * @param candidates integrity-verified candidates in cyclic keyset order
     */
    record RecoveryCandidatePage(List<RecoveryCandidate> candidates) {
        /** Freezes the page and rejects null candidate entries. */
        public RecoveryCandidatePage {
            List<RecoveryCandidate> required = Objects.requireNonNull(candidates, "candidates");
            if (required.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Recovery candidate page contains null entries");
            }
            candidates = List.copyOf(required);
        }
    }

    /**
     * Integrity-verified checkpoint plus the cursor position reached after examining it.
     *
     * @param checkpoint exact candidate checkpoint
     * @param progress atomic cursor progress through this candidate
     * @param activeDeferral verified scheduling backoff for this exact checkpoint, when active
     * @param activeQuarantine verified permanent quarantine for this exact checkpoint, when active
     */
    record RecoveryCandidate(
            DurableTestExecutionCheckpoint checkpoint,
            WorkerScanProgress progress,
            Optional<ActiveWorkerCandidateDeferral> activeDeferral,
            Optional<ActiveWorkerCandidateQuarantine> activeQuarantine) {
        /** Creates a candidate without deterministic-failure scheduling state. */
        public RecoveryCandidate(
                DurableTestExecutionCheckpoint checkpoint,
                WorkerScanProgress progress) {
            this(checkpoint, progress, Optional.empty(), Optional.empty());
        }

        /** Creates a candidate with legacy temporary-backoff projection only. */
        public RecoveryCandidate(
                DurableTestExecutionCheckpoint checkpoint,
                WorkerScanProgress progress,
                Optional<ActiveWorkerCandidateDeferral> activeDeferral) {
            this(checkpoint, progress, activeDeferral, Optional.empty());
        }

        /** Requires progress to identify the same candidate and scope. */
        public RecoveryCandidate {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            progress = Objects.requireNonNull(progress, "progress");
            activeDeferral = Objects.requireNonNull(activeDeferral, "activeDeferral");
            activeQuarantine = Objects.requireNonNull(activeQuarantine, "activeQuarantine");
            if (activeDeferral.isPresent() && activeQuarantine.isPresent()) {
                throw new IllegalArgumentException(
                        "Worker candidate cannot be both deferred and quarantined");
            }
            if (!progress.scope().contains(checkpoint)
                    || !progress.nextRunId().equals(checkpoint.runId())
                    || !progress.nextLeaseExpiresAt().equals(
                    checkpoint.lifecycle().leaseExpiresAt())
                    || !progress.nextUpdatedAt().equals(checkpoint.lifecycle().updatedAt())) {
                throw new IllegalArgumentException(
                        "Worker scan progress must identify its exact candidate");
            }
        }
    }

    /** Closed deterministic reasons that may receive a temporary worker scheduling backoff. */
    enum WorkerCandidateDeferralReason {
        /** Historical checkpoint lacks the exact target closure required for safe recovery. */
        LEGACY_PROTOCOL,
        /** Current authenticated policy deterministically denies this exact checkpoint closure. */
        AUTHORIZATION_DENIED,
        /** Current governed dependencies conflict with the persisted checkpoint closure. */
        AUTHORIZATION_CONFLICT
    }

    /**
     * One deterministic ineligibility observation to persist with a worker command.
     *
     * @param observedProgress exact candidate and cursor snapshot observed by the worker scan
     * @param checkpointFingerprint exact immutable closure observed by the worker scan
     * @param reason closed deterministic failure reason; infrastructure outages are forbidden
     * @param initialBackoff first retry delay, in whole seconds
     * @param maximumBackoff bounded retry delay cap, in whole seconds
     * @param quarantineThreshold positive consecutive same-reason failures before quarantine
     */
    record WorkerCandidateDeferral(
            WorkerScanProgress observedProgress,
            String checkpointFingerprint,
            WorkerCandidateDeferralReason reason,
            Duration initialBackoff,
            Duration maximumBackoff,
            int quarantineThreshold) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Duration MAXIMUM_ALLOWED_BACKOFF = Duration.ofHours(24);
        private static final int MAXIMUM_QUARANTINE_THRESHOLD = 1_000_000;

        /** Creates a compatibility observation that cannot quarantine under practical operation. */
        public WorkerCandidateDeferral(
                WorkerScanProgress observedProgress,
                String checkpointFingerprint,
                WorkerCandidateDeferralReason reason,
                Duration initialBackoff,
                Duration maximumBackoff) {
            this(observedProgress, checkpointFingerprint, reason, initialBackoff, maximumBackoff,
                    MAXIMUM_QUARANTINE_THRESHOLD);
        }

        /** Rejects unbounded, ambiguous, or infrastructure-shaped observations. */
        public WorkerCandidateDeferral {
            observedProgress = Objects.requireNonNull(observedProgress, "observedProgress");
            checkpointFingerprint = deferralRequired(
                    checkpointFingerprint, "checkpointFingerprint");
            reason = Objects.requireNonNull(reason, "reason");
            initialBackoff = boundedBackoff(initialBackoff, "initialBackoff");
            maximumBackoff = boundedBackoff(maximumBackoff, "maximumBackoff");
            if (!FINGERPRINT.matcher(checkpointFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "checkpointFingerprint must be a canonical SHA-256 fingerprint");
            }
            if (maximumBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "maximumBackoff must not be shorter than initialBackoff");
            }
            if (quarantineThreshold < 1
                    || quarantineThreshold > MAXIMUM_QUARANTINE_THRESHOLD) {
                throw new IllegalArgumentException(
                        "quarantineThreshold must be between 1 and 1000000");
            }
        }

        /** @return exact verified queue scope containing the observation */
        public WorkerAcquisitionScope scope() {
            return observedProgress.scope();
        }

        /** @return exact candidate run identity containing the observation */
        public String runId() {
            return observedProgress.nextRunId();
        }

        private static Duration boundedBackoff(Duration value, String field) {
            Duration result = Objects.requireNonNull(value, field);
            if (result.compareTo(Duration.ofSeconds(1)) < 0
                    || result.compareTo(MAXIMUM_ALLOWED_BACKOFF) > 0
                    || result.getNano() != 0) {
                throw new IllegalArgumentException(
                        field + " must be whole seconds between one second and 24 hours");
            }
            return result;
        }

        private static String deferralRequired(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Integrity-verified active scheduling backoff for one exact candidate checkpoint.
     *
     * @param reason closed deterministic failure reason
     * @param consecutiveFailures positive same-reason failure count
     * @param firstObservedAt database time of the first consecutive observation
     * @param lastObservedAt database time of the latest consecutive observation
     * @param retryAfter database time after which authorization may be attempted again
     */
    record ActiveWorkerCandidateDeferral(
            WorkerCandidateDeferralReason reason,
            long consecutiveFailures,
            Instant firstObservedAt,
            Instant lastObservedAt,
            Instant retryAfter) {
        /** Rejects impossible count or time ordering. */
        public ActiveWorkerCandidateDeferral {
            reason = Objects.requireNonNull(reason, "reason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            lastObservedAt = Objects.requireNonNull(lastObservedAt, "lastObservedAt");
            retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
            if (consecutiveFailures < 1
                    || firstObservedAt.isAfter(lastObservedAt)
                    || !retryAfter.isAfter(lastObservedAt)) {
                throw new IllegalArgumentException("Invalid active worker candidate deferral");
            }
        }
    }

    /**
     * Integrity-verified permanent scheduling quarantine for one exact candidate checkpoint.
     *
     * <p>The quarantine is permanent only for the exact checkpoint fingerprint projected beside
     * it. A fenced checkpoint transition invalidates the record atomically; mere passage of time
     * never makes the same poisoned closure eligible again.</p>
     *
     * @param reason closed deterministic failure reason
     * @param consecutiveFailures count that met or exceeded the governing threshold
     * @param quarantineThreshold policy threshold applied by the committing worker command
     * @param firstObservedAt database time of the first consecutive same-reason observation
     * @param quarantinedAt database time at which the threshold was crossed
     */
    record ActiveWorkerCandidateQuarantine(
            WorkerCandidateDeferralReason reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt) {
        /** Rejects impossible counts, thresholds, or time ordering. */
        public ActiveWorkerCandidateQuarantine {
            reason = Objects.requireNonNull(reason, "reason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            if (quarantineThreshold < 1 || quarantineThreshold > 1_000_000
                    || consecutiveFailures < quarantineThreshold
                    || firstObservedAt.isAfter(quarantinedAt)) {
                throw new IllegalArgumentException("Invalid active worker candidate quarantine");
            }
        }
    }

    /**
     * Compare-and-advance token for the persisted cyclic worker scan cursor.
     *
     * <p>The expected fingerprint identifies the cursor observed before the repeatable-read scan.
     * A concurrent winner may make this token stale; persistence must then leave the newer cursor
     * unchanged while still allowing an otherwise valid acquisition result to commit.</p>
     *
     * @param scope exact queue scope
     * @param expectedCursorFingerprint integrity fingerprint observed before scanning
     * @param nextCycleEpoch non-negative cyclic pass containing the examined candidate
     * @param nextLeaseExpiresAt candidate lease-order coordinate
     * @param nextUpdatedAt candidate update-order coordinate
     * @param nextRunId candidate stable tie-breaker
     */
    record WorkerScanProgress(
            WorkerAcquisitionScope scope,
            String expectedCursorFingerprint,
            long nextCycleEpoch,
            Instant nextLeaseExpiresAt,
            Instant nextUpdatedAt,
            String nextRunId) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects ambiguous or non-canonical cursor coordinates. */
        public WorkerScanProgress {
            scope = Objects.requireNonNull(scope, "scope");
            expectedCursorFingerprint = scanRequired(
                    expectedCursorFingerprint, "expectedCursorFingerprint");
            nextLeaseExpiresAt = Objects.requireNonNull(
                    nextLeaseExpiresAt, "nextLeaseExpiresAt");
            nextUpdatedAt = Objects.requireNonNull(nextUpdatedAt, "nextUpdatedAt");
            nextRunId = scanRequired(nextRunId, "nextRunId");
            if (!FINGERPRINT.matcher(expectedCursorFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "expectedCursorFingerprint must be a canonical SHA-256 fingerprint");
            }
            if (nextCycleEpoch < 0) {
                throw new IllegalArgumentException("nextCycleEpoch must be non-negative");
            }
            if (!IDENTIFIER.matcher(nextRunId).matches()) {
                throw new IllegalArgumentException("nextRunId must be a bounded stable identifier");
            }
        }

        private static String scanRequired(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Authenticated worker pull command independent of queue contents.
     *
     * @param clientRequestId caller-stable key scoped by {@code scope}
     * @param requestFingerprint canonical fingerprint of the complete principal and request
     * @param scope verified tenant, organization, project, and non-production environment
     */
    record WorkerAcquisitionCommand(
            String clientRequestId,
            String requestFingerprint,
            WorkerAcquisitionScope scope) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous command identity before queue state is consulted. */
        public WorkerAcquisitionCommand {
            clientRequestId = workerRequired(clientRequestId, "clientRequestId");
            requestFingerprint = workerRequired(requestFingerprint, "requestFingerprint");
            scope = Objects.requireNonNull(scope, "scope");
            if (!IDENTIFIER.matcher(clientRequestId).matches()) {
                throw new IllegalArgumentException(
                        "clientRequestId must be a bounded stable identifier");
            }
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 fingerprint");
            }
        }

        private static String workerRequired(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Exact dependency-authorized candidate selected outside the local database transaction.
     *
     * @param claim complete expired-checkpoint CAS intent
     * @param authorization payload-free proof bound to the selected source checkpoint
     */
    record WorkerAcquisitionSelection(
            LeaseClaim claim,
            DurableTestRecoveryAuthorization authorization) {
        /** Requires authorization to bind the exact selected source closure. */
        public WorkerAcquisitionSelection {
            claim = Objects.requireNonNull(claim, "claim");
            authorization = Objects.requireNonNull(authorization, "authorization");
            if (!authorization.sourceCheckpointFingerprint().equals(
                    claim.expectedCheckpointFingerprint())) {
                throw new IllegalArgumentException(
                        "Worker authorization must bind the selected source checkpoint");
            }
        }
    }

    /** Durable worker pull outcomes. */
    enum WorkerAcquisitionOutcome {
        /** One exact expired execution was fenced for this worker principal. */
        ACQUIRED,
        /** The bounded authorized scan produced no claimable execution. */
        NO_WORK
    }

    /**
     * Immutable outcome of one worker pull command.
     *
     * @param outcome acquired assignment or empty bounded observation
     * @param observedAt persistence-authority time at which the outcome linearized
     * @param checkpoint claimed checkpoint for {@code ACQUIRED}, otherwise {@code null}
     * @param dispatch hidden authorization handoff for {@code ACQUIRED}, otherwise {@code null}
     * @param idempotentReplay whether an earlier committed outcome was replayed
     */
    record WorkerAcquisitionResult(
            WorkerAcquisitionOutcome outcome,
            Instant observedAt,
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryDispatch dispatch,
            boolean idempotentReplay) {
        /** Enforces the mutually exclusive acquired and no-work result shapes. */
        public WorkerAcquisitionResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            if (outcome == WorkerAcquisitionOutcome.ACQUIRED) {
                checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
                dispatch = Objects.requireNonNull(dispatch, "dispatch");
                if (!dispatch.agreesWith(checkpoint)) {
                    throw new IllegalArgumentException(
                            "Worker dispatch must exactly match its acquired checkpoint");
                }
            } else if (checkpoint != null || dispatch != null) {
                throw new IllegalArgumentException(
                        "NO_WORK must not carry checkpoint or dispatch material");
            }
        }
    }

    /**
     * Exact compare-and-set request for one expired durable execution lease.
     *
     * @param tenantId verified tenant authority
     * @param environmentId non-production test or staging environment
     * @param runId governed durable run identity
     * @param expectedFence current owner, epoch, and revision
     * @param expectedCheckpointFingerprint exact control closure being claimed
     * @param claimantOwnerId recovery process identity
     * @param leaseDuration requested lease between one second and one hour
     */
    record LeaseClaim(String tenantId,
                      String environmentId,
                      String runId,
                      Fence expectedFence,
                      String expectedCheckpointFingerprint,
                      String claimantOwnerId,
                      Duration leaseDuration) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);
        private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

        /** Rejects incomplete claim authority before persistence is consulted. */
        public LeaseClaim {
            tenantId = identifier(tenantId, "tenantId");
            environmentId = required(environmentId, "environmentId").toLowerCase(
                    java.util.Locale.ROOT);
            if (!Set.of("test", "staging").contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Lease claims require a test or staging environment");
            }
            runId = identifier(runId, "runId");
            expectedFence = Objects.requireNonNull(expectedFence, "expectedFence");
            expectedCheckpointFingerprint = required(
                    expectedCheckpointFingerprint, "expectedCheckpointFingerprint");
            if (!FINGERPRINT.matcher(expectedCheckpointFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "expectedCheckpointFingerprint must be a canonical SHA-256 fingerprint");
            }
            claimantOwnerId = identifier(claimantOwnerId, "claimantOwnerId");
            leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.compareTo(MINIMUM_LEASE) < 0
                    || leaseDuration.compareTo(MAXIMUM_LEASE) > 0
                    || leaseDuration.getNano() != 0) {
                throw new IllegalArgumentException(
                        "leaseDuration must be whole seconds between one second and one hour");
            }
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }

        private static String identifier(String value, String field) {
            String normalized = required(value, field);
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(field + " must be a bounded stable identifier");
            }
            return normalized;
        }
    }

    /**
     * Durable transport command for an expired-lease claim.
     *
     * @param clientRequestId caller-stable idempotency key scoped by tenant and environment
     * @param requestFingerprint canonical fingerprint of the complete authorized command intent
     * @param claim exact fenced lease claim
     * @param authorization payload-free authorization receipt bound to the source checkpoint
     */
    record ResumeLeaseCommand(String clientRequestId,
                              String requestFingerprint,
                              LeaseClaim claim,
                              DurableTestRecoveryAuthorization authorization) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous command identities before any durable mutation can occur. */
        public ResumeLeaseCommand {
            clientRequestId = required(clientRequestId, "clientRequestId");
            if (!IDENTIFIER.matcher(clientRequestId).matches()) {
                throw new IllegalArgumentException(
                        "clientRequestId must be a bounded stable identifier");
            }
            requestFingerprint = required(requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 fingerprint");
            }
            claim = Objects.requireNonNull(claim, "claim");
            authorization = Objects.requireNonNull(authorization, "authorization");
            if (!authorization.sourceCheckpointFingerprint().equals(
                    claim.expectedCheckpointFingerprint())) {
                throw new IllegalArgumentException(
                        "Recovery authorization must bind the expected source checkpoint");
            }
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Immutable outcome of one durable resume lease command.
     *
     * @param checkpoint checkpoint produced by the original successful command
     * @param dispatch immutable authorization-bound worker handoff issued by the command
     * @param idempotentReplay whether an earlier committed result was replayed
     */
    record LeaseClaimResult(DurableTestExecutionCheckpoint checkpoint,
                            DurableTestRecoveryDispatch dispatch,
                            boolean idempotentReplay) {
        /** Requires a complete immutable command result. */
        public LeaseClaimResult {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            dispatch = Objects.requireNonNull(dispatch, "dispatch");
            if (!dispatch.agreesWith(checkpoint)) {
                throw new IllegalArgumentException(
                        "Recovery dispatch must exactly match its claim checkpoint");
            }
        }
    }

    /**
     * Idempotent transport command for one live recovery-worker heartbeat.
     *
     * @param clientRequestId caller-stable key scoped by dispatch tenant and environment
     * @param requestFingerprint server-derived fingerprint of authenticated worker intent
     * @param expectedDispatch exact current dispatch used as the compare-and-set value
     * @param leaseDuration requested lease extension between one second and one hour
     */
    record RecoveryHeartbeatCommand(
            String clientRequestId,
            String requestFingerprint,
            DurableTestRecoveryDispatch expectedDispatch,
            Duration leaseDuration) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);
        private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

        /** Rejects ambiguous heartbeat identity or unbounded lease extension. */
        public RecoveryHeartbeatCommand {
            clientRequestId = requiredHeartbeatValue(clientRequestId, "clientRequestId");
            if (!IDENTIFIER.matcher(clientRequestId).matches()) {
                throw new IllegalArgumentException(
                        "clientRequestId must be a bounded stable identifier");
            }
            requestFingerprint = requiredHeartbeatValue(
                    requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 fingerprint");
            }
            expectedDispatch = Objects.requireNonNull(expectedDispatch, "expectedDispatch");
            leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.compareTo(MINIMUM_LEASE) < 0
                    || leaseDuration.compareTo(MAXIMUM_LEASE) > 0
                    || leaseDuration.getNano() != 0) {
                throw new IllegalArgumentException(
                        "leaseDuration must be whole seconds between one second and one hour");
            }
        }

        private static String requiredHeartbeatValue(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Immutable outcome of one recovery heartbeat command.
     *
     * @param checkpoint one-revision successor checkpoint
     * @param dispatch successor dispatch controlling that exact checkpoint
     * @param idempotentReplay whether an earlier committed result was replayed
     */
    record RecoveryHeartbeatResult(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryDispatch dispatch,
            boolean idempotentReplay) {
        /** Requires exact checkpoint/dispatch agreement for every new or replayed result. */
        public RecoveryHeartbeatResult {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            dispatch = Objects.requireNonNull(dispatch, "dispatch");
            if (!dispatch.agreesWith(checkpoint)) {
                throw new IllegalArgumentException(
                        "Recovery heartbeat dispatch must exactly match its checkpoint");
            }
        }
    }

    /**
     * Idempotent command for one terminal recovery transition.
     *
     * <p>Version 1 requires explicit evidence gaps because pre-checkpoint node/edge/attempt trace is
     * not yet part of the durable closure. The resulting receipt is therefore always
     * promotion-blocking.</p>
     *
     * @param clientRequestId caller-stable key scoped by dispatch tenant and environment
     * @param requestFingerprint server-derived fingerprint of authenticated terminal intent
     * @param expectedDispatch exact current dispatch consumed by the terminal CAS
     * @param executionOutcome normalized terminal BLOGE outcome
     * @param terminalEngineState exact final BLOGE aggregate identity
     * @param fixtureConsumptionState final cumulative fixture cursor
     * @param executionServiceState final deterministic-provider state
     * @param evidenceGapCodes explicit bounded reasons complete evidence is unavailable
     */
    record RecoveryTerminalCommand(
            String clientRequestId,
            String requestFingerprint,
            DurableTestRecoveryDispatch expectedDispatch,
            DurableTestRecoveryTerminalReceipt.ExecutionOutcome executionOutcome,
            DurableTestExecutionCheckpoint.EngineState terminalEngineState,
            FixtureConsumptionStateSnapshot fixtureConsumptionState,
            ExecutionServiceStateSnapshot executionServiceState,
            List<String> evidenceGapCodes) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern GAP_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Rejects ambiguous terminal identity and implicit evidence completeness. */
        public RecoveryTerminalCommand {
            clientRequestId = requiredTerminalValue(clientRequestId, "clientRequestId");
            if (!IDENTIFIER.matcher(clientRequestId).matches()) {
                throw new IllegalArgumentException(
                        "clientRequestId must be a bounded stable identifier");
            }
            requestFingerprint = requiredTerminalValue(
                    requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 fingerprint");
            }
            expectedDispatch = Objects.requireNonNull(expectedDispatch, "expectedDispatch");
            executionOutcome = Objects.requireNonNull(executionOutcome, "executionOutcome");
            terminalEngineState = Objects.requireNonNull(
                    terminalEngineState, "terminalEngineState");
            fixtureConsumptionState = Objects.requireNonNull(
                    fixtureConsumptionState, "fixtureConsumptionState");
            executionServiceState = Objects.requireNonNull(
                    executionServiceState, "executionServiceState");
            if (!expectedDispatch.authorization().planFingerprint().equals(
                    executionServiceState.planFingerprint())) {
                throw new IllegalArgumentException(
                        "Terminal provider state must bind the authorized plan");
            }
            if (evidenceGapCodes == null || evidenceGapCodes.isEmpty()
                    || evidenceGapCodes.size() > 32) {
                throw new IllegalArgumentException(
                        "At least one bounded evidence gap is required");
            }
            List<String> normalizedGaps = evidenceGapCodes.stream()
                    .map(value -> requiredTerminalValue(value, "evidence gap")
                            .toUpperCase(java.util.Locale.ROOT))
                    .peek(value -> {
                        if (!GAP_CODE.matcher(value).matches()) {
                            throw new IllegalArgumentException(
                                    "Evidence gap must be a bounded stable code");
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
            if (normalizedGaps.size() != evidenceGapCodes.size()) {
                throw new IllegalArgumentException("Evidence gaps must be unique");
            }
            evidenceGapCodes = normalizedGaps;
        }

        private static String requiredTerminalValue(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Immutable result of one recovery terminal command.
     *
     * @param checkpoint exact terminal checkpoint
     * @param receipt payload-free promotion-blocking terminal receipt
     * @param idempotentReplay whether an earlier committed result was replayed
     */
    record RecoveryTerminalResult(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryTerminalReceipt receipt,
            boolean idempotentReplay) {
        /** Requires exact terminal receipt and checkpoint agreement. */
        public RecoveryTerminalResult {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (!receipt.terminalCheckpointFingerprint().equals(
                    checkpoint.checkpointFingerprint())
                    || checkpoint.lifecycle().status()
                    != DurableTestExecutionCheckpoint.Status.TERMINAL) {
                throw new IllegalArgumentException(
                        "Recovery terminal receipt must bind its terminal checkpoint");
            }
        }
    }

    /**
     * Payload-free command reserving one complete bounded recovery sequence.
     *
     * @param clientRequestId caller-stable outer idempotency key
     * @param requestFingerprint canonical authenticated intent covering every signal digest
     * @param scope verified tenant, organization, project, environment, and actor scope
     * @param runId exact durable execution controlled by the sequence
     * @param signalCount positive bounded number of supplied signals
     */
    record RecoverySequenceCommand(
            String clientRequestId,
            String requestFingerprint,
            DurableTestExecutionCheckpoint.Scope scope,
            String runId,
            int signalCount) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous identities and unbounded synchronous programs. */
        public RecoverySequenceCommand {
            clientRequestId = requiredSequenceValue(clientRequestId, "clientRequestId");
            requestFingerprint = requiredSequenceValue(
                    requestFingerprint, "requestFingerprint");
            scope = Objects.requireNonNull(scope, "scope");
            runId = requiredSequenceValue(runId, "runId");
            if (!IDENTIFIER.matcher(clientRequestId).matches()
                    || !IDENTIFIER.matcher(runId).matches()) {
                throw new IllegalArgumentException(
                        "Recovery-sequence identities must be bounded stable identifiers");
            }
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Recovery-sequence request fingerprint must be canonical SHA-256");
            }
            if (signalCount < 1 || signalCount > 16) {
                throw new IllegalArgumentException(
                        "Recovery sequence requires between one and sixteen signals");
            }
        }

        private static String requiredSequenceValue(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Immutable payload-free recovery-sequence intent reservation.
     *
     * @param command exact reserved intent
     * @param createdAt database-authority reservation time
     * @param recordFingerprint integrity identity of the stored reservation
     * @param idempotentReplay whether an earlier reservation was replayed
     */
    record RecoverySequenceReservation(
            RecoverySequenceCommand command,
            Instant createdAt,
            String recordFingerprint,
            boolean idempotentReplay) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Requires a complete immutable reservation identity. */
        public RecoverySequenceReservation {
            command = Objects.requireNonNull(command, "command");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
            if (!FINGERPRINT.matcher(recordFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Recovery-sequence record fingerprint must be canonical SHA-256");
            }
        }
    }

    /** Closed outcome vocabulary for one scheduled recovery-sequence retention attempt. */
    enum RecoverySequenceRetentionStatus {
        /** One fenced retention page committed successfully. */
        COMPLETED,
        /** Another replica currently owns the unexpired database lease. */
        LEASE_BUSY
    }

    /**
     * Aggregate-only result of one committed recovery-sequence retention page.
     *
     * @param sequencesTombstoned detailed outer requests replaced by keyed tombstones
     * @param recoveryStepsPurged server-derived recovery-step rows erased
     * @param ownerClaimsPurged server-derived owner-claim rows erased
     * @param heartbeatsPurged automatic heartbeat rows erased
     * @param tombstonesPurged expired keyed request identities erased
     * @param completedAt database-authority completion time
     */
    record RecoverySequenceRetentionResult(
            int sequencesTombstoned,
            int recoveryStepsPurged,
            int ownerClaimsPurged,
            int heartbeatsPurged,
            int tombstonesPurged,
            Instant completedAt) {
        /** Rejects negative counters and absent database time. */
        public RecoverySequenceRetentionResult {
            if (sequencesTombstoned < 0 || recoveryStepsPurged < 0
                    || ownerClaimsPurged < 0 || heartbeatsPurged < 0
                    || tombstonesPurged < 0) {
                throw new IllegalArgumentException(
                        "Recovery-sequence retention counters cannot be negative");
            }
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    /**
     * Result envelope distinguishing a committed page from normal cross-replica contention.
     *
     * @param status closed attempt status
     * @param result committed aggregate, present only for {@link
     *     RecoverySequenceRetentionStatus#COMPLETED}
     */
    record RecoverySequenceRetentionAttempt(
            RecoverySequenceRetentionStatus status,
            RecoverySequenceRetentionResult result) {
        /** Enforces exact status/result correspondence. */
        public RecoverySequenceRetentionAttempt {
            status = Objects.requireNonNull(status, "status");
            if ((status == RecoverySequenceRetentionStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException(
                        "Completed recovery-sequence retention requires exactly one result");
            }
        }

        /** @return a completed attempt around one immutable aggregate */
        public static RecoverySequenceRetentionAttempt completed(
                RecoverySequenceRetentionResult result) {
            return new RecoverySequenceRetentionAttempt(
                    RecoverySequenceRetentionStatus.COMPLETED,
                    Objects.requireNonNull(result, "result"));
        }

        /** @return normal lease contention without a committed page */
        public static RecoverySequenceRetentionAttempt leaseBusy() {
            return new RecoverySequenceRetentionAttempt(
                    RecoverySequenceRetentionStatus.LEASE_BUSY, null);
        }
    }

    /**
     * Aggregate-only operational view of recovery-sequence lifecycle control.
     *
     * @param leaseOwner current maintenance replica or empty when idle
     * @param leaseEpoch monotonic ownership generation
     * @param leaseUntil database-clock lease deadline
     * @param revision monotonic retention-state revision
     * @param totalSequencesTombstoned cumulative outer requests erased
     * @param totalRecoveryStepsPurged cumulative derived step rows erased
     * @param totalOwnerClaimsPurged cumulative derived claim rows erased
     * @param totalHeartbeatsPurged cumulative derived heartbeat rows erased
     * @param totalTombstonesPurged cumulative expired request identities erased
     * @param activeSequenceRecords current detailed outer-request count
     * @param tombstoneRecords current keyed tombstone count
     * @param lastSuccessAt last successfully committed page or {@code null}
     * @param observedAt database-authority snapshot time
     */
    record RecoverySequenceRetentionSnapshot(
            String leaseOwner,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalSequencesTombstoned,
            long totalRecoveryStepsPurged,
            long totalOwnerClaimsPurged,
            long totalHeartbeatsPurged,
            long totalTombstonesPurged,
            long activeSequenceRecords,
            long tombstoneRecords,
            Instant lastSuccessAt,
            Instant observedAt) {
        /** Validates complete non-negative aggregate state. */
        public RecoverySequenceRetentionSnapshot {
            leaseOwner = leaseOwner == null ? "" : leaseOwner.trim();
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            if (leaseEpoch < 0 || revision < 0 || totalSequencesTombstoned < 0
                    || totalRecoveryStepsPurged < 0 || totalOwnerClaimsPurged < 0
                    || totalHeartbeatsPurged < 0 || totalTombstonesPurged < 0
                    || activeSequenceRecords < 0 || tombstoneRecords < 0) {
                throw new IllegalArgumentException(
                        "Recovery-sequence retention snapshot counters cannot be negative");
            }
        }
    }

    /** Stable boundary outcome for one cold-signal recovery step. */
    enum RecoveryStepOutcome {
        /** The signal reached exactly one new restorable signal suspension. */
        SUSPENDED(null),
        /** BLOGE completed successfully. */
        COMPLETED(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED),
        /** BLOGE reached a business failure. */
        FAILED(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.FAILED),
        /** BLOGE recovery itself failed. */
        FAILED_RECOVERY(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.FAILED_RECOVERY),
        /** BLOGE execution was cancelled. */
        CANCELLED(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.CANCELLED),
        /** BLOGE execution was administratively terminated. */
        TERMINATED(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.TERMINATED);

        private final DurableTestRecoveryTerminalReceipt.ExecutionOutcome terminalOutcome;

        RecoveryStepOutcome(
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome terminalOutcome) {
            this.terminalOutcome = terminalOutcome;
        }

        /**
         * Returns whether this boundary ends the durable execution.
         *
         * @return {@code true} for every outcome except {@link #SUSPENDED}
         */
        public boolean terminal() {
            return terminalOutcome != null;
        }

        /**
         * Returns the terminal receipt outcome.
         *
         * @return terminal outcome
         * @throws IllegalStateException when this is the suspended outcome
         */
        public DurableTestRecoveryTerminalReceipt.ExecutionOutcome terminalOutcome() {
            if (terminalOutcome == null) {
                throw new IllegalStateException(
                        "A suspended recovery step has no terminal execution outcome");
            }
            return terminalOutcome;
        }
    }

    /**
     * Idempotent command for one signal and its next stable durable boundary.
     *
     * @param clientRequestId caller-stable key scoped by dispatch tenant and environment
     * @param requestFingerprint server-derived authenticated signal-intent fingerprint
     * @param expectedDispatch exact current dispatch consumed by this step
     * @param outcome next suspended or terminal BLOGE boundary
     * @param engineState exact next BLOGE aggregate identity
     * @param fixtureConsumptionState cumulative fixture cursor at the next boundary
     * @param executionServiceState deterministic-provider state at the next boundary
     * @param evidenceGapCodes explicit reasons this step cannot certify complete historical trace
     */
    record RecoveryStepCommand(
            String clientRequestId,
            String requestFingerprint,
            DurableTestRecoveryDispatch expectedDispatch,
            RecoveryStepOutcome outcome,
            DurableTestExecutionCheckpoint.EngineState engineState,
            FixtureConsumptionStateSnapshot fixtureConsumptionState,
            ExecutionServiceStateSnapshot executionServiceState,
            List<String> evidenceGapCodes) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern GAP_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Rejects ambiguous boundary identity and implicit evidence completeness. */
        public RecoveryStepCommand {
            clientRequestId = requiredStepValue(clientRequestId, "clientRequestId");
            if (!IDENTIFIER.matcher(clientRequestId).matches()) {
                throw new IllegalArgumentException(
                        "clientRequestId must be a bounded stable identifier");
            }
            requestFingerprint = requiredStepValue(
                    requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 fingerprint");
            }
            expectedDispatch = Objects.requireNonNull(expectedDispatch, "expectedDispatch");
            outcome = Objects.requireNonNull(outcome, "outcome");
            engineState = Objects.requireNonNull(engineState, "engineState");
            fixtureConsumptionState = Objects.requireNonNull(
                    fixtureConsumptionState, "fixtureConsumptionState");
            executionServiceState = Objects.requireNonNull(
                    executionServiceState, "executionServiceState");
            if (!expectedDispatch.authorization().planFingerprint().equals(
                    executionServiceState.planFingerprint())) {
                throw new IllegalArgumentException(
                        "Recovery-step provider state must bind the authorized plan");
            }
            if (outcome == RecoveryStepOutcome.SUSPENDED
                    && !"SUSPEND".equals(engineState.boundaryType())) {
                throw new IllegalArgumentException(
                        "A suspended recovery step requires a SUSPEND engine boundary");
            }
            if (outcome.terminal() && "SUSPEND".equals(engineState.boundaryType())) {
                throw new IllegalArgumentException(
                        "A terminal recovery step cannot retain a SUSPEND engine boundary");
            }
            if (evidenceGapCodes == null || evidenceGapCodes.isEmpty()
                    || evidenceGapCodes.size() > 32) {
                throw new IllegalArgumentException(
                        "At least one bounded evidence gap is required");
            }
            List<String> normalizedGaps = evidenceGapCodes.stream()
                    .map(value -> requiredStepValue(value, "evidence gap")
                            .toUpperCase(Locale.ROOT))
                    .peek(value -> {
                        if (!GAP_CODE.matcher(value).matches()) {
                            throw new IllegalArgumentException(
                                    "Evidence gap must be a bounded stable code");
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
            if (normalizedGaps.size() != evidenceGapCodes.size()) {
                throw new IllegalArgumentException("Evidence gaps must be unique");
            }
            evidenceGapCodes = normalizedGaps;
        }

        private static String requiredStepValue(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    /**
     * Immutable result of one recovery step.
     *
     * @param outcome suspended or terminal boundary outcome
     * @param checkpoint exact resulting checkpoint
     * @param terminalReceipt terminal receipt, present only for terminal outcomes
     * @param idempotentReplay whether an earlier committed result was replayed
     */
    record RecoveryStepResult(
            RecoveryStepOutcome outcome,
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryTerminalReceipt terminalReceipt,
            boolean idempotentReplay) {
        /** Enforces the mutually exclusive suspended and terminal result shapes. */
        public RecoveryStepResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (outcome.terminal()) {
                terminalReceipt = Objects.requireNonNull(
                        terminalReceipt, "terminalReceipt");
                if (checkpoint.lifecycle().status()
                        != DurableTestExecutionCheckpoint.Status.TERMINAL
                        || !terminalReceipt.terminalCheckpointFingerprint().equals(
                        checkpoint.checkpointFingerprint())
                        || terminalReceipt.executionOutcome() != outcome.terminalOutcome()) {
                    throw new IllegalArgumentException(
                            "Terminal recovery-step receipt must bind its outcome and checkpoint");
                }
            } else if (terminalReceipt != null
                    || checkpoint.lifecycle().status()
                    != DurableTestExecutionCheckpoint.Status.SUSPENDED) {
                throw new IllegalArgumentException(
                        "Suspended recovery-step result cannot carry a terminal receipt");
            }
        }
    }

    /**
     * Engine mutation that declares the exact formal engine-state value covered by its writes.
     *
     * <p>Repositories validate this binding before opening a transaction. This prevents a caller
     * from committing one frozen BLOGE closure under another checkpoint reference, boundary, or
     * state version even when both values are individually well formed.</p>
     */
    interface BoundEngineStateMutation {
        /**
         * Returns the exact BLOGE execution identity whose rows are mutated.
         *
         * @return trusted engine execution identifier
         */
        String engineExecutionId();

        /**
         * Returns the exact control-plane engine state represented by this mutation.
         *
         * @return formal engine-state closure
         */
        DurableTestExecutionCheckpoint.EngineState engineState();

        /**
         * Applies one idempotent engine-state transition through the transaction-bound JDBC facade.
         *
         * @param jdbc JDBC facade bound to the repository transaction
         */
        void apply(JdbcTemplate jdbc);
    }

    private static String requiredCreationValue(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
