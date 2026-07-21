package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Acquisition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.FleetManifest;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.Lease;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinatorTest {

    @Test
    void inventoryFingerprintIsOrderCanonicalAndGenerationIndependent() {
        Lane first = lane("tenant", "roots-a", 'a');
        Lane second = lane("tenant", "roots-b", 'b');

        String original = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                .inventoryFingerprint(snapshot(1L, second, first));
        String reorderedAndAdvanced = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                .inventoryFingerprint(snapshot(9L, first, second));
        String descriptorDrift = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                .inventoryFingerprint(snapshot(9L, first,
                        lane("tenant", "roots-b", 'c')));

        assertThat(original).matches("sha256:[a-f0-9]{64}")
                .isEqualTo(reorderedAndAdvanced)
                .isNotEqualTo(descriptorDrift);
    }

    @Test
    void emptyInventoryFingerprintPinsTheWireCanonicalization() {
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                .inventoryFingerprint(snapshot(37L)))
                .isEqualTo("sha256:be7d2082bbb6cf74951ff9adfecdecc68ef698c3a182adb2"
                        + "133157b7392f7c5a");
    }

    @Test
    void stablePartitionMappingIsBoundedAndSensitiveToTheCompleteLaneKey() {
        LaneKey key = new LaneKey("tenant-a", "roots-a");
        int original = ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                .partitionFor(key, 17);

        assertThat(original).isBetween(0, 16)
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                        .partitionFor(new LaneKey("tenant-a", "roots-a"), 17));
        assertThat(List.of(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                        key, 17),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                        new LaneKey("tenant-b", "roots-a"), 17),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                        new LaneKey("tenant-a", "roots-b"), 17)))
                .containsExactly(5, 6, 6);
        assertThatThrownBy(() ->
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(key, 65))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manifestFactoryBindsGenerationFingerprintAndFixedTopology() {
        var source = snapshot(4L, lane("tenant", "roots-a", 'a'));

        FleetManifest manifest = FleetManifest.from("bootstrap-recovery", source, 8);

        assertThat(manifest.inventoryGeneration()).isEqualTo(4L);
        assertThat(manifest.inventoryFingerprint()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                        .inventoryFingerprint(source));
        assertThat(manifest.partitionCount()).isEqualTo(8);
        assertThatThrownBy(() -> FleetManifest.from("bootstrap-recovery", source, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaseRejectsCursorFromAnotherPartitionAndAcquisitionShapeMismatch() {
        FleetManifest manifest = new FleetManifest(FleetManifest.SCHEMA_VERSION,
                "bootstrap-recovery", 1L, fingerprint('a'), 4);
        LaneKey owned = keyForPartition(2, 4);
        LaneKey foreign = keyForPartition(1, 4);

        Lease lease = new Lease(Lease.SCHEMA_VERSION, manifest, 2, 1L, 1L,
                "a".repeat(32), "worker-a", "b".repeat(32), 30L,
                Instant.parse("2026-07-21T00:00:30Z"), owned);

        assertThat(lease.owns(owned)).isTrue();
        assertThat(lease.owns(foreign)).isFalse();
        assertThatThrownBy(() -> new Lease(Lease.SCHEMA_VERSION, manifest, 2, 1L, 1L,
                "a".repeat(32), "worker-a", "b".repeat(32), 30L,
                Instant.parse("2026-07-21T00:00:30Z"), foreign))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Acquisition(Acquisition.SCHEMA_VERSION,
                AcquisitionStatus.BUSY, lease)).isInstanceOf(IllegalArgumentException.class);
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
