package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
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
     */
    record ResumeLeaseCommand(String clientRequestId,
                              String requestFingerprint,
                              LeaseClaim claim) {
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
     * @param idempotentReplay whether an earlier committed result was replayed
     */
    record LeaseClaimResult(DurableTestExecutionCheckpoint checkpoint,
                            boolean idempotentReplay) {
        /** Requires a complete immutable command result. */
        public LeaseClaimResult {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
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
}
