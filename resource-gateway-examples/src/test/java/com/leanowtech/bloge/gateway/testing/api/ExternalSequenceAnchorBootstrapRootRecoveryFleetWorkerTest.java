package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Acquisition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AbandonStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.CompletionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Lease;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetWorkerTest {

    @Test
    void boundedCursorVisitsEveryLaneFairlyAcrossThreeCycles() {
        List<String> visits = new ArrayList<>();
        Lane third = recordingLane("tenant", "roots-c", 'c', visits);
        Lane first = recordingLane("tenant", "roots-a", 'a', visits);
        Lane second = recordingLane("tenant", "roots-b", 'b', visits);
        var worker = worker(() -> snapshot(1L, third, first, second), 2);

        assertThat(worker.runCycle().lanes()).extracting(result -> result.laneKey().rootSetId())
                .containsExactly("roots-a", "roots-b");
        assertThat(worker.runCycle().lanes()).extracting(result -> result.laneKey().rootSetId())
                .containsExactly("roots-c", "roots-a");
        assertThat(worker.runCycle().lanes()).extracting(result -> result.laneKey().rootSetId())
                .containsExactly("roots-b", "roots-c");
        assertThat(visits).containsExactly(
                "roots-a", "roots-b", "roots-c", "roots-a", "roots-b", "roots-c");
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleCount()).isEqualTo(3L);
            assertThat(runtime.laneAttemptCount()).isEqualTo(6L);
            assertThat(runtime.cycleFailureCount()).isZero();
            assertThat(runtime.lastInventoryGeneration()).isOne();
        });
    }

    @Test
    void poisonLaneIsCollapsedAndCannotStarveItsSuccessor() {
        Lane poison = lane("tenant", "roots-a", 'a');
        Lane healthy = lane("tenant", "roots-b", 'b');
        when(poison.service().recover(anyString(), anyLong(), any()))
                .thenThrow(new IllegalStateException("provider detail must not escape"));
        when(healthy.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        var worker = worker(() -> snapshot(1L, healthy, poison), 1);

        var failed = worker.runCycle();
        var succeeded = worker.runCycle();

        assertThat(failed.failedCount()).isOne();
        assertThat(failed.lanes().getFirst()).satisfies(result -> {
            assertThat(result.laneKey().rootSetId()).isEqualTo("roots-a");
            assertThat(result.runtimeFailure()).isTrue();
            assertThat(result.status()).isNull();
        });
        assertThat(succeeded.lanes().getFirst().laneKey().rootSetId()).isEqualTo("roots-b");
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.laneFailureCount()).isOne();
            assertThat(runtime.lastCompletedCycleHadLaneFailures()).isFalse();
            assertThat(runtime.lastCycleFailed()).isFalse();
        });
    }

    @Test
    void inventoryRollbackFailsBeforeAnyRolledBackLaneRuns() {
        Lane current = lane("tenant", "roots-a", 'a');
        Lane rolledBack = lane("tenant", "roots-b", 'b');
        when(current.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        AtomicReference<Snapshot> inventory = new AtomicReference<>(snapshot(2L, current));
        var worker = worker(inventory::get, 1);
        worker.runCycle();
        inventory.set(snapshot(1L, rolledBack));

        assertThatThrownBy(worker::runCycle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back");
        verify(rolledBack.service(), never()).recover(anyString(), anyLong(), any());
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleFailureCount()).isOne();
            assertThat(runtime.lastCycleFailed()).isTrue();
            assertThat(runtime.lastInventoryGeneration()).isEqualTo(2L);
        });
    }

    @Test
    void sameGenerationRejectsDescriptorAndRuntimeObjectReplacement() {
        Lane original = lane("tenant", "roots-a", 'a');
        Lane descriptorDrift = lane("tenant", "roots-a", 'b');
        Lane runtimeDrift = lane("tenant", "roots-a", 'a');
        when(original.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        AtomicReference<Snapshot> inventory = new AtomicReference<>(snapshot(4L, original));
        var worker = worker(inventory::get, 1);
        worker.runCycle();

        inventory.set(snapshot(4L, descriptorDrift));
        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drifted");
        inventory.set(snapshot(4L, runtimeDrift));
        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drifted");
        verify(descriptorDrift.service(), never()).recover(anyString(), anyLong(), any());
        verify(runtimeDrift.service(), never()).recover(anyString(), anyLong(), any());
    }

    @Test
    void newerGenerationMayReplaceRuntimeAndContinuesAfterThePriorCursor() {
        Lane first = lane("tenant", "roots-b", 'b');
        Lane oldSecond = lane("tenant", "roots-c", 'c');
        when(first.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        when(oldSecond.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        AtomicReference<Snapshot> inventory = new AtomicReference<>(
                snapshot(1L, first, oldSecond));
        var worker = worker(inventory::get, 1);
        assertThat(worker.runCycle().lanes().getFirst().laneKey().rootSetId())
                .isEqualTo("roots-b");

        Lane insertedPrefix = lane("tenant", "roots-a", 'a');
        Lane newSecond = lane("tenant", "roots-c", 'd');
        when(insertedPrefix.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        when(newSecond.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        inventory.set(snapshot(2L, insertedPrefix, first, newSecond));

        assertThat(worker.runCycle().lanes().getFirst().laneKey().rootSetId())
                .isEqualTo("roots-c");
        assertThat(worker.runCycle().lanes().getFirst().laneKey().rootSetId())
                .isEqualTo("roots-a");
        verify(oldSecond.service(), never()).recover(anyString(), anyLong(), any());
        assertThat(worker.runtimeSnapshot().lastInventoryGeneration()).isEqualTo(2L);
    }

    @Test
    void acquiredLanePreservesBoundedExecutionStatusAndAggregateCount() {
        Lane lane = lane("tenant", "roots-a", 'a');
        RecoveryExecutionResult acquired = executed(ExecutionStatus.PRODUCED);
        when(lane.service().recover(anyString(), anyLong(), any()))
                .thenReturn(acquired);
        var worker = worker(() -> snapshot(1L, lane), 1);

        var cycle = worker.runCycle();

        assertThat(cycle.acquiredCount()).isOne();
        assertThat(cycle.lanes().getFirst().executionStatus()).isEqualTo(
                ExecutionStatus.PRODUCED);
        assertThat(worker.runtimeSnapshot().laneAcquiredCount()).isOne();
    }

    @Test
    void inventoryFailureAndFatalLaneErrorRemainVisibleAndPropagate() {
        var unavailable = worker(() -> {
            throw new IllegalStateException("inventory unavailable");
        }, 1);
        assertThatThrownBy(unavailable::runCycle).isInstanceOf(IllegalStateException.class);
        assertThat(unavailable.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleFailureCount()).isOne();
            assertThat(runtime.laneAttemptCount()).isZero();
            assertThat(runtime.lastCycleFailed()).isTrue();
        });

        Lane fatal = lane("tenant", "roots-a", 'a');
        when(fatal.service().recover(anyString(), anyLong(), any()))
                .thenThrow(new AssertionError("fatal runtime"));
        var fatalWorker = worker(() -> snapshot(1L, fatal), 1);
        assertThatThrownBy(fatalWorker::runCycle).isInstanceOf(AssertionError.class);
        assertThat(fatalWorker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.active()).isFalse();
            assertThat(runtime.cycleFailureCount()).isOne();
            assertThat(runtime.laneFailureCount()).isZero();
        });
    }

    @Test
    void emptyGenerationIsHealthyAndCloseRejectsAllLaterCyclesWithoutInventoryAccess() {
        @SuppressWarnings("unchecked")
        var inventory = (ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory) mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class);
        when(inventory.snapshot()).thenReturn(snapshot(1L));
        var worker = worker(inventory, 1);

        var cycle = worker.runCycle();

        assertThat(cycle.attemptedCount()).isZero();
        assertThat(cycle.disposition()).isEqualTo(CycleDisposition.COMPLETED);
        worker.close();
        worker.close();

        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap-root recovery fleet worker is closed");
        verify(inventory).snapshot();
        assertThat(worker.runtimeSnapshot().closed()).isTrue();
    }

    @Test
    void closeWaitsForTheAdmittedCycleAndThenPublishesQuiescentState() throws Exception {
        Lane lane = lane("tenant", "roots-a", 'a');
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(lane.service().recover(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return noWork();
        });
        var worker = worker(() -> snapshot(1L, lane), 1);
        Thread cycle = Thread.ofPlatform().start(worker::runCycle);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            worker.close();
            closeReturned.countDown();
        });

        assertThat(closeReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.active()).isTrue();
            assertThat(runtime.closed()).isFalse();
        });
        release.countDown();
        cycle.join(Duration.ofSeconds(2));
        closer.join(Duration.ofSeconds(2));

        assertThat(cycle.isAlive()).isFalse();
        assertThat(closer.isAlive()).isFalse();
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.active()).isFalse();
            assertThat(runtime.closed()).isTrue();
            assertThat(runtime.cycleCount()).isOne();
        });
    }

    @Test
    void durableAssignmentVisitsOnlyItsPartitionRenewsAndCommitsLastAttemptedCursor() {
        int partitions = 3;
        int assignedPartition = 1;
        Lane first = partitionLane(assignedPartition, partitions, 0, 'a');
        Lane second = partitionLane(assignedPartition, partitions, 1, 'b');
        List<Lane> ordered = List.of(first, second).stream()
                .sorted(java.util.Comparator.comparing(Lane::key)).toList();
        Lane foreign = partitionLane(2, partitions, 0, 'c');
        when(first.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        when(second.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        var coordinator = durableCoordinator();
        AtomicReference<Lease> acquired = new AtomicReference<>();
        AtomicReference<Lease> renewed = new AtomicReference<>();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation -> {
            AcquisitionCommand command = invocation.getArgument(0);
            Lease lease = lease(command, assignedPartition, null);
            acquired.set(lease);
            return Acquisition.acquired(lease);
        });
        when(coordinator.renew(any(Lease.class))).thenAnswer(invocation -> {
            Lease current = invocation.getArgument(0);
            Lease next = renewed(current);
            renewed.set(next);
            return Optional.of(next);
        });
        when(coordinator.complete(any(Lease.class), nullable(LaneKey.class)))
                .thenReturn(CompletionStatus.COMPLETED);
        var worker = durableWorker(() -> snapshot(7L, foreign, second, first),
                coordinator, partitions, 2);

        var cycle = worker.runCycle();

        assertThat(cycle.lanes()).extracting(result -> result.laneKey().rootSetId())
                .containsExactly(ordered.getFirst().key().rootSetId(),
                        ordered.getLast().key().rootSetId());
        verify(foreign.service(), never()).recover(anyString(), anyLong(), any());
        verify(coordinator).renew(acquired.get());
        verify(coordinator).complete(renewed.get(), ordered.getLast().key());
        assertThat(acquired.get().manifest().inventoryGeneration()).isEqualTo(7L);
        assertThat(acquired.get().manifest().inventoryFingerprint())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                        .inventoryFingerprint(snapshot(7L, foreign, second, first)));
    }

    @Test
    void durableBusyCycleDoesNotPollOrCompleteAnyLane() {
        Lane lane = lane("tenant", "roots-a", 'a');
        var coordinator = durableCoordinator();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenReturn(Acquisition.busy());
        var worker = durableWorker(() -> snapshot(3L, lane), coordinator, 2, 1);

        var cycle = worker.runCycle();

        assertThat(cycle.attemptedCount()).isZero();
        assertThat(cycle.disposition()).isEqualTo(CycleDisposition.COORDINATOR_BUSY);

        verify(lane.service(), never()).recover(anyString(), anyLong(), any());
        verify(coordinator, never()).renew(any());
        verify(coordinator, never()).complete(any(), nullable(LaneKey.class));
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleCount()).isOne();
            assertThat(runtime.cycleFailureCount()).isZero();
            assertThat(runtime.lastInventoryGeneration()).isEqualTo(3L);
        });
    }

    @Test
    void durableAcquisitionRejectsCoordinatorIdentityDriftBeforePolling() {
        Lane lane = lane("tenant", "roots-a", 'a');
        var coordinator = durableCoordinator();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation -> {
            AcquisitionCommand command = invocation.getArgument(0);
            int partition = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .partitionFor(lane.key(), command.manifest().partitionCount());
            Lease drifted = new Lease(Lease.SCHEMA_VERSION, command.manifest(), partition,
                    1L, 1L, "a".repeat(32), "other-worker", command.commandId(),
                    command.leaseDurationSeconds(),
                    java.time.Instant.parse("2026-07-21T00:00:30Z"), null);
            return Acquisition.acquired(drifted);
        });
        var worker = durableWorker(() -> snapshot(1L, lane), coordinator, 1, 1);

        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drifted acquisition");

        verify(lane.service(), never()).recover(anyString(), anyLong(), any());
        verify(coordinator, never()).renew(any());
        verify(coordinator, never()).complete(any(), nullable(LaneKey.class));
        verify(coordinator, never()).abandon(any());
    }

    @Test
    void fencedRenewalStopsPartitionWithoutAdvancingItsCursor() {
        int partitions = 2;
        Lane first = partitionLane(0, partitions, 0, 'a');
        Lane second = partitionLane(0, partitions, 1, 'b');
        List<Lane> ordered = List.of(first, second).stream()
                .sorted(java.util.Comparator.comparing(Lane::key)).toList();
        when(ordered.getFirst().service().recover(anyString(), anyLong(), any()))
                .thenReturn(noWork());
        var coordinator = durableCoordinator();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation ->
                Acquisition.acquired(lease(invocation.getArgument(0), 0, null)));
        when(coordinator.renew(any(Lease.class))).thenReturn(Optional.empty());
        var worker = durableWorker(() -> snapshot(1L, first, second),
                coordinator, partitions, 2);

        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fenced during cycle");

        verify(ordered.getFirst().service()).recover(anyString(), anyLong(), any());
        verify(ordered.getLast().service(), never()).recover(anyString(), anyLong(), any());
        verify(coordinator, never()).complete(any(), nullable(LaneKey.class));
        verify(coordinator).abandon(any(Lease.class));
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleFailureCount()).isOne();
            assertThat(runtime.laneAttemptCount()).isOne();
            assertThat(runtime.lastCycleFailed()).isTrue();
        });
    }

    @Test
    void fencedCompletionFailsCycleInsteadOfPublishingUncommittedProgress() {
        Lane lane = lane("tenant", "roots-a", 'a');
        when(lane.service().recover(anyString(), anyLong(), any())).thenReturn(noWork());
        var coordinator = durableCoordinator();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation -> {
            AcquisitionCommand command = invocation.getArgument(0);
            int partition = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .partitionFor(lane.key(), command.manifest().partitionCount());
            return Acquisition.acquired(lease(command, partition, null));
        });
        when(coordinator.complete(any(), any())).thenReturn(CompletionStatus.FENCED);
        var worker = durableWorker(() -> snapshot(1L, lane), coordinator, 2, 1);

        assertThatThrownBy(worker::runCycle).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completion was fenced");
        verify(coordinator).abandon(any(Lease.class));
        assertThat(worker.runtimeSnapshot()).satisfies(runtime -> {
            assertThat(runtime.cycleFailureCount()).isOne();
            assertThat(runtime.lastInventoryGeneration()).isZero();
        });
    }

    @Test
    void backgroundHeartbeatRenewsDuringOneSlowLaneAndCompletionUsesLatestRevision()
            throws Exception {
        Lane slow = lane("tenant", "roots-a", 'a');
        CountDownLatch renewed = new CountDownLatch(1);
        var coordinator = durableCoordinator();
        AtomicReference<Lease> initial = new AtomicReference<>();
        AtomicReference<Lease> completed = new AtomicReference<>();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation -> {
            AcquisitionCommand command = invocation.getArgument(0);
            int partition = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .partitionFor(slow.key(), command.manifest().partitionCount());
            Lease lease = lease(command, partition, null);
            initial.set(lease);
            return Acquisition.acquired(lease);
        });
        when(coordinator.renew(any(Lease.class))).thenAnswer(invocation -> {
            Lease current = invocation.getArgument(0);
            Lease next = new Lease(Lease.SCHEMA_VERSION, current.manifest(),
                    current.partitionId(), current.fleetEpoch(), current.leaseEpoch(),
                    current.leaseToken(), current.workerId(), current.commandId(),
                    current.leaseDurationSeconds(), current.leaseExpiresAt().plusSeconds(3L),
                    current.cursorExclusive());
            renewed.countDown();
            return Optional.of(next);
        });
        when(coordinator.complete(any(Lease.class), any(LaneKey.class)))
                .thenAnswer(invocation -> {
                    completed.set(invocation.getArgument(0));
                    return CompletionStatus.COMPLETED;
                });
        when(slow.service().recover(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
            return noWork();
        });
        var worker = durableWorker(() -> snapshot(1L, slow), coordinator, 1, 1, 3L);

        assertThat(worker.runCycle().disposition()).isEqualTo(CycleDisposition.COMPLETED);

        assertThat(completed.get().leaseExpiresAt()).isAfter(initial.get().leaseExpiresAt());
        verify(coordinator).renew(initial.get());
    }

    @Test
    void fatalDurableLaneAbandonsTheLatestLeaseWithoutCompletingCursor() {
        Lane fatal = lane("tenant", "roots-a", 'a');
        when(fatal.service().recover(anyString(), anyLong(), any()))
                .thenThrow(new AssertionError("fatal runtime"));
        var coordinator = durableCoordinator();
        when(coordinator.acquire(any(AcquisitionCommand.class))).thenAnswer(invocation -> {
            AcquisitionCommand command = invocation.getArgument(0);
            int partition = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .partitionFor(fatal.key(), command.manifest().partitionCount());
            return Acquisition.acquired(lease(command, partition, null));
        });
        var worker = durableWorker(() -> snapshot(1L, fatal), coordinator, 1, 1);

        assertThatThrownBy(worker::runCycle).isInstanceOf(AssertionError.class);

        verify(coordinator).abandon(any(Lease.class));
        verify(coordinator, never()).complete(any(), nullable(LaneKey.class));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            int maximumLanesPerCycle) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
                inventory, "fleet-worker", new Policy(30L, maximumLanesPerCycle));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker durableWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            int partitionCount,
            int maximumLanesPerCycle) {
        return durableWorker(inventory, coordinator, partitionCount,
                maximumLanesPerCycle, 30L);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker durableWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            int partitionCount,
            int maximumLanesPerCycle,
            long leaseDurationSeconds) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
                inventory, "fleet-worker", new Policy(
                        leaseDurationSeconds, maximumLanesPerCycle),
                coordinator, "bootstrap-recovery", partitionCount);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
            durableCoordinator() {
        var coordinator = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.class);
        when(coordinator.durable()).thenReturn(true);
        when(coordinator.abandon(any(Lease.class))).thenReturn(AbandonStatus.ABANDONED);
        return coordinator;
    }

    private static Lease lease(
            AcquisitionCommand command, int partitionId, LaneKey cursorExclusive) {
        return new Lease(Lease.SCHEMA_VERSION, command.manifest(), partitionId,
                1L, 1L, "a".repeat(32), command.workerId(),
                command.commandId(), command.leaseDurationSeconds(),
                java.time.Instant.parse("2026-07-21T00:00:30Z"), cursorExclusive);
    }

    private static Lease renewed(Lease current) {
        return new Lease(Lease.SCHEMA_VERSION, current.manifest(), current.partitionId(),
                current.fleetEpoch(), current.leaseEpoch(), current.leaseToken(),
                current.workerId(), current.commandId(), current.leaseDurationSeconds(),
                current.leaseExpiresAt().plusSeconds(1L), current.cursorExclusive());
    }

    private static Lane partitionLane(
            int partitionId, int partitionCount, int ordinal, char fingerprint) {
        int found = 0;
        for (int candidate = 0; candidate < 10_000; candidate++) {
            String rootSetId = "roots-" + candidate;
            LaneKey key = new LaneKey("tenant", rootSetId);
            if (ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                    key, partitionCount) == partitionId && found++ == ordinal) {
                return lane("tenant", rootSetId, fingerprint);
            }
        }
        throw new AssertionError("No partition lane fixture found");
    }

    private static Lane recordingLane(
            String scopeId, String rootSetId, char fingerprint, List<String> visits) {
        Lane lane = lane(scopeId, rootSetId, fingerprint);
        when(lane.service().recover(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            visits.add(rootSetId);
            return noWork();
        });
        return lane;
    }

    private static RecoveryExecutionResult noWork() {
        return new RecoveryExecutionResult(RecoveryStatus.NO_ACTIVE_CEREMONY,
                null, null, null);
    }

    private static RecoveryExecutionResult executed(ExecutionStatus status) {
        RecoveryExecutionResult result = mock(RecoveryExecutionResult.class);
        var execution = mock(ExternalSequenceAnchorBootstrapRootCeremonyService
                .ExecutionResult.class);
        when(result.status()).thenReturn(RecoveryStatus.EXECUTED);
        when(result.execution()).thenReturn(execution);
        when(execution.status()).thenReturn(status);
        return result;
    }
}
