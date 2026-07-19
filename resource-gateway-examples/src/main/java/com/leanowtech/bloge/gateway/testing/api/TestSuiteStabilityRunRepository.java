package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Database-authoritative progress, execution fence, terminal, and compact-observation store for
 * stability analyses.
 */
public interface TestSuiteStabilityRunRepository {
    /**
     * Returns persistence-authoritative time for lease and retention decisions.
     *
     * @return database time for durable adapters
     */
    default Instant currentTime() {
        return Instant.now();
    }

    /**
     * Atomically replays a terminal result, observes a live owner, or acquires an expired/new fence.
     *
     * @param request exact scope, intent, owner, and lease duration
     * @return one unambiguous claim outcome
     */
    TestSuiteStabilityLeaseClaim claim(TestSuiteStabilityLeaseRequest request);

    /**
     * Renews only the exact database-clock-live owner and epoch.
     *
     * @param lease caller's latest exact fence
     * @param leaseDuration bounded successor duration
     * @return renewed successor, or empty after expiry, takeover, completion, or release
     */
    Optional<TestSuiteStabilityExecutionLease> renew(
            TestSuiteStabilityExecutionLease lease, Duration leaseDuration);

    /**
     * Atomically appends the next source attempt, advances progress retention, and renews ownership.
     *
     * @param lease caller's latest exact live fence
     * @param attempt next contiguous verified source reference
     * @param leaseDuration bounded successor lease duration
     * @param progressRetention bounded sliding progress retention
     * @return renewed fence and durable successor journal
     */
    TestSuiteStabilityProgressCheckpoint checkpoint(
            TestSuiteStabilityExecutionLease lease,
            TestSuiteStabilityExecutionProgress.AttemptReference attempt,
            Duration leaseDuration,
            Duration progressRetention);

    /**
     * Releases exact live ownership after a local failure; expiry handles crashes and store outages.
     *
     * @param lease exact owner fence
     * @return true only when this caller deleted its still-live lease
     */
    boolean release(TestSuiteStabilityExecutionLease lease);

    /**
     * Atomically terminalizes a parent execution and consumes any progress and live lease.
     *
     * @param request exact cancellation, deadline, or worker-failure stop intent
     * @return original immutable stop tombstone
     */
    TestSuiteStabilityExecutionStop stop(TestSuiteStabilityExecutionStopRequest request);

    /**
     * Atomically verifies a full journal/live fence, inserts terminal evidence, and consumes both.
     *
     * @param record complete signed terminal analysis
     * @param observation independently signed compact longitudinal projection
     * @param lease exact live owner fence
     * @return stored immutable record
     */
    TestSuiteStabilityRunRecord complete(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityObservation observation,
            TestSuiteStabilityExecutionLease lease);

    /**
     * Deletes a bounded oldest-first page of expired orphan leases.
     *
     * @param limit maximum rows to delete
     * @return number of deleted rows
     */
    int purgeExpiredLeases(int limit);

    /**
     * Deletes a bounded oldest-first page of expired terminal stop tombstones.
     *
     * @param limit positive bounded page size
     * @return number of tombstones deleted
     */
    int purgeExpiredStops(int limit);

    /**
     * Resolves one retained progress journal and owner liveness at database time.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param stabilityRunId deterministic parent identity
     * @return active or takeover-ready progress snapshot
     */
    Optional<TestSuiteStabilityProgressSnapshot> findProgress(
            String tenantId, String environmentId, String stabilityRunId);

    /**
     * Resolves a retained stop tombstone in the verified scope.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param stabilityRunId deterministic parent identity
     * @return live retained stop, if present
     */
    Optional<TestSuiteStabilityExecutionStop> findStop(
            String tenantId, String environmentId, String stabilityRunId);

    /**
     * Resolves one retained analysis inside the verified scope.
     *
     * @param tenantId verified tenant id
     * @param environmentId verified environment id
     * @param stabilityRunId deterministic analysis id
     * @return retained analysis, if present
     */
    Optional<TestSuiteStabilityRunRecord> find(
            String tenantId, String environmentId, String stabilityRunId);

    /**
     * Resolves a retained idempotent result inside the verified scope.
     *
     * @param tenantId verified tenant id
     * @param environmentId verified environment id
     * @param clientRequestId caller parent idempotency key
     * @return retained analysis, if present
     */
    Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
            String tenantId, String environmentId, String clientRequestId);

    /**
     * Resolves one bounded chronological exact-suite terminal history window.
     *
     * <p>The result distinguishes expired matching rows from query truncation so callers cannot
     * mistake a retained subset for a complete longitudinal sample.</p>
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @param fromInclusive inclusive persistence-time lower boundary
     * @param toExclusive exclusive persistence-time upper boundary
     * @param maximumRuns hard retained-source budget
     * @return persistence-authoritative bounded history facts
     */
    default TestSuiteStabilityHistoryWindow history(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant fromInclusive,
            Instant toExclusive,
            int maximumRuns) {
        throw new UnsupportedOperationException(
                "Stability history windows are unavailable in this repository");
    }

    /**
     * Resolves the payload-free rollout floor and latest compact-observation coordinate.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @return current durable ledger head, if this scope has any post-rollout observation
     */
    default Optional<TestSuiteStabilityObservationLedgerHead> observationLedgerHead(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return Optional.empty();
    }

    /**
     * Reads a bounded contiguous compact-observation page after one committed sequence.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @param afterSequence exclusive non-negative sequence cursor
     * @param limit positive bounded page size
     * @return ordered independently signed ledger entries
     */
    default List<TestSuiteStabilityObservationLedgerEntry> observations(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long afterSequence,
            int limit) {
        throw new UnsupportedOperationException(
                "Stability observation ledger is unavailable in this repository");
    }

    /**
     * Reads one floor/head-pinned compact-observation page under the exact-suite ledger lock.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @param afterSequence exclusive retained sequence cursor
     * @param limit positive bounded page size
     * @return complete locked range, or empty before this scope has any observation
     */
    default Optional<TestSuiteStabilityObservationLedgerRange> observationRange(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long afterSequence,
            int limit) {
        throw new UnsupportedOperationException(
                "Stability observation range snapshots are unavailable in this repository");
    }

    /**
     * Resolves the exact first retained compact-observation coordinate and retirement chain tip.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @return current durable floor, if the ledger exists
     */
    default Optional<TestSuiteStabilityObservationLedgerFloor> observationLedgerFloor(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return Optional.empty();
    }

    /**
     * Prepares a bounded exact floor-retirement intent under the exact-suite ledger lock.
     *
     * <p>The returned evidence is not authority to delete. A caller must sign and immediately verify
     * it, then pass the complete signed retirement to {@link #commitObservationFloorRetirement}.
     * The commit rechecks the exact floor, head, and active rows before any mutation.</p>
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @param cutoffExclusive exclusive database append-time retention boundary
     * @param minimumRetainedEntries minimum active suffix that must remain
     * @param maximumRetiredEntries maximum prefix entries archived by this transaction
     * @param retentionPolicyFingerprint immutable external policy identity
     * @return exact candidate evidence, or empty when no prefix is eligible
     */
    default Optional<TestSuiteStabilityObservationFloorRetirementEvidence>
            planObservationFloorRetirement(
                    String tenantId,
                    String environmentId,
                    TestSuiteExecutionRequest.SuiteRef suiteRef,
                    Instant cutoffExclusive,
                    int minimumRetainedEntries,
                    int maximumRetiredEntries,
                    String retentionPolicyFingerprint) {
        throw new UnsupportedOperationException(
                "Stability observation floor retirement is unavailable in this repository");
    }

    /**
     * Atomically archives an exact prefix, records its signed retirement, advances the floor, and
     * removes only the archived active rows.
     *
     * <p>This is an internal trusted persistence boundary. The caller must verify the detached
     * signature immediately before invocation; the repository rechecks canonical material and
     * current database state but does not own external trust-key resolution.</p>
     *
     * @param retirement complete independently verifiable retirement
     * @return committed successor floor; exact replay returns the same value
     */
    default TestSuiteStabilityObservationLedgerFloor commitObservationFloorRetirement(
            TestSuiteStabilityObservationFloorRetirement retirement) {
        throw new UnsupportedOperationException(
                "Stability observation floor retirement is unavailable in this repository");
    }

    /**
     * Resolves one immutable signed floor-retirement record.
     *
     * @param retirementId deterministic retirement identity
     * @return exact committed record, if present
     */
    default Optional<TestSuiteStabilityObservationFloorRetirement> findObservationFloorRetirement(
            String retirementId) {
        return Optional.empty();
    }
}
