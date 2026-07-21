package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

        assertThat(worker.runCycle().attemptedCount()).isZero();
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

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            int maximumLanesPerCycle) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
                inventory, "fleet-worker", new Policy(30L, maximumLanesPerCycle));
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
