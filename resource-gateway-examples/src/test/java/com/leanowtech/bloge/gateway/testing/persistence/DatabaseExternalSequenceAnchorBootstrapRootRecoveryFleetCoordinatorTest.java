package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AbandonStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.CompletionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.FleetManifest;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Lease;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinatorTest {

    private TestRuntimeDatabase database;
    private DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator first;
    private DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator second;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-recovery-fleet-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 8));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        first = coordinator(mapper);
        second = coordinator(mapper);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void replicasAcquireDistinctPartitionsCyclicallyAndResumeDurableCursor() {
        FleetManifest manifest = manifest(1L, 'a', 3);
        Lease zero = acquire(first, manifest, "worker-a");
        Lease one = acquire(second, manifest, "worker-b");
        Lease two = acquire(first, manifest, "worker-c");

        assertThat(List.of(zero.partitionId(), one.partitionId(), two.partitionId()))
                .containsExactly(0, 1, 2);
        assertThat(acquisition(second, manifest, "worker-d").status())
                .isEqualTo(AcquisitionStatus.BUSY);

        LaneKey cursor = keyForPartition(0, 3);
        assertThat(second.complete(zero, cursor)).isEqualTo(CompletionStatus.COMPLETED);
        Lease resumed = acquire(coordinator(new ObjectMapper().findAndRegisterModules()),
                manifest, "worker-d");

        assertThat(resumed.partitionId()).isZero();
        assertThat(resumed.cursorExclusive()).isEqualTo(cursor);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions
                WHERE fleet_id = ?
                """, Integer.class, manifest.fleetId())).isEqualTo(3);
    }

    @Test
    void renewalCreatesExactRevisionAndFencesItsStaleCopy() {
        FleetManifest manifest = manifest(1L, 'a', 1);
        Lease original = acquire(first, manifest, "worker-a");
        Lease renewed = second.renew(original).orElseThrow();

        assertThat(renewed.leaseEpoch()).isEqualTo(original.leaseEpoch());
        assertThat(renewed.leaseExpiresAt()).isAfter(original.leaseExpiresAt());
        assertThat(first.complete(original, keyForPartition(0, 1)))
                .isEqualTo(CompletionStatus.FENCED);
        assertThat(first.renew(original)).isEmpty();
        assertThat(second.complete(renewed, keyForPartition(0, 1)))
                .isEqualTo(CompletionStatus.COMPLETED);
    }

    @Test
    void acquisitionCommandRetryReturnsTheExactLeaseAndRejectsReplayDrift() {
        FleetManifest manifest = manifest(1L, 'a', 2);
        String commandId = "c".repeat(32);
        AcquisitionCommand command = command(manifest, "worker-a", commandId, 30L);

        Lease original = first.acquire(command).lease();
        Lease replayed = second.acquire(command).lease();

        assertThat(replayed).isEqualTo(original);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions
                WHERE fleet_id = ? AND lease_owner IS NOT NULL
                """, Integer.class, manifest.fleetId())).isOne();
        assertThatThrownBy(() -> second.acquire(
                command(manifest, "worker-b", commandId, 30L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replay drifted");
        assertThatThrownBy(() -> second.acquire(
                command(manifest, "worker-a", commandId, 31L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replay drifted");
    }

    @Test
    void abandonmentReleasesImmediatelyWithoutAdvancingTheCommittedCursor() {
        FleetManifest manifest = manifest(1L, 'a', 1);
        LaneKey committedCursor = keyForPartition(0, 1);
        Lease initial = acquire(first, manifest, "worker-a");
        assertThat(first.complete(initial, committedCursor))
                .isEqualTo(CompletionStatus.COMPLETED);
        Lease failedCycle = acquire(first, manifest, "worker-a");

        assertThat(second.abandon(failedCycle)).isEqualTo(AbandonStatus.ABANDONED);
        Lease resumed = acquire(second, manifest, "worker-b");

        assertThat(resumed.cursorExclusive()).isEqualTo(committedCursor);
        assertThat(resumed.leaseEpoch()).isEqualTo(failedCycle.leaseEpoch() + 1L);
        assertThat(first.complete(failedCycle, committedCursor))
                .isEqualTo(CompletionStatus.FENCED);
        assertThat(first.abandon(failedCycle)).isEqualTo(AbandonStatus.FENCED);
    }

    @Test
    void newerInventoryGenerationFencesEveryOldLeaseAndRejectsRollbackOrDrift() {
        FleetManifest firstGeneration = manifest(1L, 'a', 2);
        Lease old = acquire(first, firstGeneration, "worker-a");
        FleetManifest secondGeneration = manifest(2L, 'b', 2);

        Lease current = acquire(second, secondGeneration, "worker-b");

        assertThat(current.fleetEpoch()).isEqualTo(old.fleetEpoch() + 1L);
        assertThat(first.renew(old)).isEmpty();
        assertThat(first.complete(old, keyForPartition(old.partitionId(), 2)))
                .isEqualTo(CompletionStatus.FENCED);
        assertThatThrownBy(() -> acquisition(first, manifest(2L, 'c', 2), "worker-c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same generation");
        assertThatThrownBy(() -> acquisition(first, firstGeneration, "worker-c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rolled back");
        assertThatThrownBy(() -> acquisition(first, manifest(3L, 'c', 3), "worker-c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topology is immutable");
    }

    @Test
    void expiredPartitionIsTakenOverAndOldOwnerCannotAdvanceItsCursor() throws Exception {
        FleetManifest manifest = manifest(1L, 'a', 1);
        Lease expired = acquire(first, manifest, "worker-a");

        Thread.sleep(Duration.ofMillis(3_150));
        Lease takeover = acquire(second, manifest, "worker-b");

        assertThat(takeover.leaseEpoch()).isEqualTo(expired.leaseEpoch() + 1L);
        assertThat(takeover.workerId()).isEqualTo("worker-b");
        assertThat(first.complete(expired, keyForPartition(0, 1)))
                .isEqualTo(CompletionStatus.FENCED);
        assertThat(second.complete(takeover, keyForPartition(0, 1)))
                .isEqualTo(CompletionStatus.COMPLETED);
    }

    @Test
    void competingReplicasLinearizeOnePartitionToOneWinner() throws Exception {
        FleetManifest manifest = manifest(1L, 'a', 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<AcquisitionStatus> left = workers.submit(() -> acquireAfter(
                    start, first, manifest, "worker-a"));
            Future<AcquisitionStatus> right = workers.submit(() -> acquireAfter(
                    start, second, manifest, "worker-b"));
            start.countDown();

            assertThat(List.of(left.get(), right.get())).containsExactlyInAnyOrder(
                    AcquisitionStatus.ACQUIRED, AcquisitionStatus.BUSY);
        }
    }

    @Test
    void crossPartitionCursorIsRejectedBeforeMutationAndCorruptionFailsClosed() {
        FleetManifest manifest = manifest(1L, 'a', 2);
        Lease lease = acquire(first, manifest, "worker-a");
        LaneKey foreign = keyForPartition(1 - lease.partitionId(), 2);

        assertThatThrownBy(() -> first.complete(lease, foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another partition");

        database.jdbc().update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_recovery_fleets
                SET record_fingerprint = ? WHERE fleet_id = ?
                """, fingerprint('f'), manifest.fleetId());
        assertThatThrownBy(() -> acquisition(second, manifest, "worker-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    private DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator(
            ObjectMapper mapper) {
        var result = new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
                database.jdbc(), mapper, database.transactionManager());
        result.init();
        return result;
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Acquisition
            acquisition(
                    DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator target,
                    FleetManifest manifest,
                    String workerId) {
        return target.acquire(new AcquisitionCommand(AcquisitionCommand.SCHEMA_VERSION,
                manifest, workerId, UUID.randomUUID().toString().replace("-", ""), 3L));
    }

    private static AcquisitionCommand command(
            FleetManifest manifest,
            String workerId,
            String commandId,
            long leaseDurationSeconds) {
        return new AcquisitionCommand(AcquisitionCommand.SCHEMA_VERSION, manifest,
                workerId, commandId, leaseDurationSeconds);
    }

    private static Lease acquire(
            DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator target,
            FleetManifest manifest,
            String workerId) {
        return acquisition(target, manifest, workerId).lease();
    }

    private static AcquisitionStatus acquireAfter(
            CountDownLatch start,
            DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator target,
            FleetManifest manifest,
            String workerId) throws InterruptedException {
        start.await();
        return acquisition(target, manifest, workerId).status();
    }

    private static FleetManifest manifest(long generation, char fingerprint, int partitions) {
        return new FleetManifest(FleetManifest.SCHEMA_VERSION, "bootstrap-recovery",
                generation, fingerprint(fingerprint), partitions);
    }

    private static LaneKey keyForPartition(int partitionId, int partitionCount) {
        for (int candidate = 0; candidate < 10_000; candidate++) {
            LaneKey key = new LaneKey("tenant", "roots-" + candidate);
            if (ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                    key, partitionCount) == partitionId) {
                return key;
            }
        }
        throw new AssertionError("No partition fixture found");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
