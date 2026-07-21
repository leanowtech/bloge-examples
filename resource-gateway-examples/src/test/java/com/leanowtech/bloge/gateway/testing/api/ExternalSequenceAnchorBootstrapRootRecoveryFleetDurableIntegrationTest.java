package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetDurableIntegrationTest {

    @Test
    void reconstructedReplicasSharePartitionRotationAndPerPartitionLaneCursor() {
        try (TestRuntimeDatabase database = database()) {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            var firstCoordinator = coordinator(database, mapper);
            var secondCoordinator = coordinator(database, mapper);
            List<String> visits = new ArrayList<>();
            Lane zeroA = recordingPartitionLane(0, 2, 0, 'a', visits);
            Lane zeroB = recordingPartitionLane(0, 2, 1, 'b', visits);
            Lane one = recordingPartitionLane(1, 2, 0, 'c', visits);
            List<Lane> zeroOrder = List.of(zeroA, zeroB).stream()
                    .sorted(Comparator.comparing(Lane::key)).toList();
            var inventory = (ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory) () ->
                    snapshot(1L, one, zeroB, zeroA);

            var firstReplica = worker(inventory, firstCoordinator, "worker-a");
            var secondReplica = worker(inventory, secondCoordinator, "worker-b");
            var reconstructedReplica = worker(inventory,
                    coordinator(database, mapper), "worker-c");

            assertThat(firstReplica.runCycle().lanes()).extracting(
                    result -> result.laneKey().rootSetId())
                    .containsExactly(zeroOrder.getFirst().key().rootSetId());
            assertThat(secondReplica.runCycle().lanes()).extracting(
                    result -> result.laneKey().rootSetId())
                    .containsExactly(one.key().rootSetId());
            assertThat(reconstructedReplica.runCycle().lanes()).extracting(
                    result -> result.laneKey().rootSetId())
                    .containsExactly(zeroOrder.getLast().key().rootSetId());
            assertThat(visits).containsExactly(
                    zeroOrder.getFirst().key().rootSetId(), one.key().rootSetId(),
                    zeroOrder.getLast().key().rootSetId());
        }
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            String workerId) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
                inventory, workerId, new Policy(30L, 1), coordinator,
                "bootstrap-recovery", 2);
    }

    private static DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
            coordinator(TestRuntimeDatabase database, ObjectMapper mapper) {
        var coordinator =
                new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
                        database.jdbc(), mapper, database.transactionManager());
        coordinator.init();
        return coordinator;
    }

    private static Lane recordingPartitionLane(
            int partitionId,
            int partitionCount,
            int ordinal,
            char fingerprint,
            List<String> visits) {
        int found = 0;
        for (int candidate = 0; candidate < 10_000; candidate++) {
            String rootSetId = "roots-" + candidate;
            LaneKey key = new LaneKey("tenant", rootSetId);
            if (ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                    key, partitionCount) == partitionId && found++ == ordinal) {
                Lane lane = lane("tenant", rootSetId, fingerprint);
                when(lane.service().recover(anyString(), anyLong(), any()))
                        .thenAnswer(invocation -> {
                            visits.add(rootSetId);
                            return new ExternalSequenceAnchorBootstrapRootCeremonyService
                                    .RecoveryExecutionResult(
                                    ExternalSequenceAnchorBootstrapRootCeremonyService
                                            .RecoveryStatus.NO_ACTIVE_CEREMONY,
                                    null, null, null);
                        });
                return lane;
            }
        }
        throw new AssertionError("No partition lane fixture found");
    }

    private static TestRuntimeDatabase database() {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-recovery-worker-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 6));
    }
}
