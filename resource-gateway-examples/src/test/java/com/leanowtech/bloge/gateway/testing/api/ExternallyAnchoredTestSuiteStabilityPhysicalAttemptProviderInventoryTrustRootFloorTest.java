package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloorTest {

    @Test
    void commitsExactManagedRootHeadExternallyBeforeLocalFloor() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true, true);
        RecordingFloor local = new RecordingFloor(order, true);
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                local, anchor);
        var first = generation(1, 'a', null);
        var second = generation(2, 'b', first);

        floor.accept(first);
        floor.accept(second);

        assertThat(order).containsExactly("external-1", "local-1", "external-2", "local-2");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst()).satisfies(head -> {
            assertThat(head.streamKind()).isEqualTo(
                    TestSuiteStabilityExternalSequenceAnchor.StreamKind
                            .SERVING_INVENTORY_TRUST_ROOT);
            assertThat(head.scopeId()).isEqualTo("physical-provider-fleet");
            assertThat(head.streamId())
                    .isEqualTo(
                            ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor
                                    .streamId("provider-inventory-roots"))
                    .startsWith("physical-provider-root-")
                    .hasSize(87);
            assertThat(head.headFingerprint()).isEqualTo(first.materialFingerprint());
            assertThat(head.previousHeadFingerprint()).isEmpty();
        });
        assertThat(anchor.heads.getLast().previousHeadFingerprint())
                .isEqualTo(first.materialFingerprint());
        assertThat(floor.durable()).isTrue();
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isTrue();
    }

    @Test
    void externalFailureNeverAdvancesLocalManagedRootFloor() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true, true);
        anchor.fail = true;
        RecordingFloor local = new RecordingFloor(order, true);
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                local, anchor);

        assertThatThrownBy(() -> floor.accept(generation(1, 'a', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThat(order).containsExactly("external-1");
        assertThat(local.generations).isEmpty();
    }

    @Test
    void exactRetryReusesSameExternalHeadAndRepairsLocalFailure() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true, true);
        RecordingFloor local = new RecordingFloor(order, true);
        local.failOnce = true;
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                local, anchor);
        var generation = generation(1, 'a', null);

        assertThatThrownBy(() -> floor.accept(generation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("local commit failed");
        floor.accept(generation);

        assertThat(order).containsExactly("external-1", "local-1", "external-1", "local-1");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst()).isEqualTo(anchor.heads.getLast());
        assertThat(local.generations).containsExactly(generation);
    }

    @Test
    void rejectsUnsafeAnchorAndNonDurableLocalFloor() {
        RecordingFloor durable = new RecordingFloor(new ArrayList<>(), true);

        assertThatThrownBy(() -> new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                durable, new RecordingAnchor(new ArrayList<>(), false, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable or unsafe");
        assertThatThrownBy(() -> new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                new RecordingFloor(new ArrayList<>(), false),
                new RecordingAnchor(new ArrayList<>(), true, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable local floor");
    }

    @Test
    void boundedStreamIdentityIsDomainSeparatedAndByzantineTruthIsHonest() {
        String maximum = "r".repeat(255);
        String physical =
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor
                        .streamId(maximum);

        assertThat(physical)
                .startsWith("physical-provider-root-")
                .hasSize(87)
                .isNotEqualTo(
                        ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor
                                .streamId("s".repeat(255)))
                .isNotEqualTo(
                        ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor
                                .streamId(maximum));

        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                new RecordingFloor(new ArrayList<>(), true),
                new RecordingAnchor(new ArrayList<>(), true, false));
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isFalse();
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation
            generation(
            long sequence,
            char material,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation previous) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation
                        .SCHEMA_VERSION,
                "physical-provider-fleet", "provider-inventory-roots", sequence,
                fingerprint(material), previous == null ? "" : previous.materialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAnchor implements
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor {

        private final List<String> order;
        private final boolean available;
        private final boolean byzantine;
        private final List<TestSuiteStabilityExternalSequenceAnchor.Head> heads =
                new ArrayList<>();
        private boolean fail;

        private RecordingAnchor(List<String> order, boolean available, boolean byzantine) {
            this.order = order;
            this.available = available;
            this.byzantine = byzantine;
        }

        @Override
        public void accept(TestSuiteStabilityExternalSequenceAnchor.Head head) {
            order.add("external-" + head.sequence());
            if (fail) {
                throw new TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.Reason
                                .QUORUM_NOT_MET);
            }
            heads.add(head);
        }

        @Override
        public TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor() {
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            int faults = byzantine ? 1 : 0;
            return new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                    TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                    available, available, available, available && byzantine,
                    authorities, threshold, faults, authorities, Map.of());
        }

        @Override
        public TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot() {
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            int faults = byzantine ? 1 : 0;
            return new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                    TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                    available, available ? "HEALTHY" : "UNAVAILABLE", null,
                    heads.size(), 0, 0, authorities, threshold, faults, authorities);
        }
    }

    private static final class RecordingFloor implements
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor {

        private final List<String> order;
        private final boolean durable;
        private final List<Generation> generations = new ArrayList<>();
        private boolean failOnce;

        private RecordingFloor(List<String> order, boolean durable) {
            this.order = order;
            this.durable = durable;
        }

        @Override
        public void accept(Generation generation) {
            order.add("local-" + generation.sequence());
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("local commit failed");
            }
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return durable;
        }
    }
}
