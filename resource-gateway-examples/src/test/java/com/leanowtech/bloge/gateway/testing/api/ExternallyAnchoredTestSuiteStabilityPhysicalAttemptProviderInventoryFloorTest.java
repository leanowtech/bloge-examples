package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryFloorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publicationAndWitnessBecomeOneDomainSeparatedExternalHeadBeforeLocalCommit() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingFloor local = new RecordingFloor(order);
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, local, anchor);
        var first = generation(1, 'a', 'b', null);
        var second = generation(2, 'c', 'd', first);

        floor.accept(first);
        floor.accept(second);

        assertThat(order).containsExactly(
                "external-1", "local-1", "external-2", "local-2");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst().streamKind()).isEqualTo(
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION);
        assertThat(anchor.heads.getFirst().streamId()).isEqualTo(
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
                        .STREAM_ID);
        assertThat(anchor.heads.getFirst().previousHeadFingerprint()).isEmpty();
        assertThat(anchor.heads.getLast().previousHeadFingerprint())
                .isEqualTo(anchor.heads.getFirst().headFingerprint());
        assertThat(anchor.heads.getFirst().headFingerprint())
                .isNotEqualTo(first.publicationMaterialFingerprint())
                .isNotEqualTo(first.witnessMaterialFingerprint());
        assertThat(floor.durable()).isTrue();
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isTrue();
    }

    @Test
    void externalFailureNeverTouchesLocalFloor() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        anchor.fail = true;
        RecordingFloor local = new RecordingFloor(order);
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, local, anchor);

        assertThatThrownBy(() -> floor.accept(generation(1, 'a', 'b', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThat(order).containsExactly("external-1");
        assertThat(local.generations).isEmpty();
    }

    @Test
    void exactRetryRepairsLocalFailureAgainstTheSameExternalHead() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingFloor local = new RecordingFloor(order);
        local.failOnce = true;
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, local, anchor);
        var generation = generation(1, 'a', 'b', null);

        assertThatThrownBy(() -> floor.accept(generation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("local commit failed");
        floor.accept(generation);

        assertThat(order).containsExactly(
                "external-1", "local-1", "external-1", "local-1");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst()).isEqualTo(anchor.heads.getLast());
        assertThat(local.generations).containsExactly(generation);
    }

    @Test
    void unavailableAnchorAndNonDurableLocalFloorAreRejectedAtConstruction() {
        RecordingFloor durable = new RecordingFloor(new ArrayList<>());

        assertThatThrownBy(() -> new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, durable, new RecordingAnchor(new ArrayList<>(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable or unsafe");

        RecordingFloor nonDurable = new RecordingFloor(new ArrayList<>());
        nonDurable.durable = false;
        assertThatThrownBy(() -> new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, nonDurable, new RecordingAnchor(new ArrayList<>(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable local floor");
    }

    @Test
    void nonByzantineExternalAnchorIsReportedHonestly() {
        RecordingAnchor anchor = new RecordingAnchor(new ArrayList<>(), true, false);
        var floor = new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, new RecordingFloor(new ArrayList<>()), anchor);

        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isFalse();
    }

    @Test
    void domainAdapterOwnsLifecycleAndHealthRemainsAggregateOnly() {
        RecordingGenericAnchor generic = new RecordingGenericAnchor();
        var adapter = TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
                .adapt(generic);
        var head = new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION,
                "provider-fleet", "physical-provider-stream", 1, fingerprint('a'), "");

        adapter.accept(head);
        var health = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth(
                adapter).health();
        adapter.close();

        assertThat(generic.heads).containsExactly(head);
        assertThat(generic.closed).isTrue();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "HEALTHY")
                .containsEntry("authorityCount", 4)
                .containsEntry("signatureThreshold", 3);
        assertThat(health.getDetails().toString()).doesNotContain(
                "provider-fleet", "physical-provider-stream", "sha256:",
                "endpoint", "authorityId", "keyId", "challenge");
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation
            generation(
            long sequence,
            char publication,
            char witness,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation
                    previous) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.Generation
                        .SCHEMA_VERSION,
                "provider-fleet", sequence, fingerprint(publication), fingerprint(witness),
                previous == null ? "" : previous.publicationMaterialFingerprint(),
                previous == null ? "" : previous.witnessMaterialFingerprint());
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

        private RecordingAnchor(List<String> order, boolean available) {
            this(order, available, true);
        }

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
            int faults = byzantine ? 1 : 0;
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            return new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                    TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                    available, available, available, available && byzantine,
                    available ? authorities : 0, available ? threshold : 0,
                    available ? faults : 0, available ? authorities : 0, Map.of());
        }

        @Override
        public TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot() {
            int faults = byzantine ? 1 : 0;
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            return new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                    TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                    available, available ? "HEALTHY" : "UNAVAILABLE", null,
                    heads.size(), 0, 0, available ? authorities : 0,
                    available ? threshold : 0, available ? faults : 0,
                    available ? authorities : 0);
        }
    }

    private static final class RecordingFloor implements
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor {

        private final List<String> order;
        private final List<Generation> generations = new ArrayList<>();
        private boolean durable = true;
        private boolean failOnce;

        private RecordingFloor(List<String> order) {
            this.order = order;
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

    private static final class RecordingGenericAnchor
            implements TestSuiteStabilityExternalSequenceAnchor {

        private final List<Head> heads = new ArrayList<>();
        private boolean closed;

        @Override
        public void accept(Head head) {
            heads.add(head);
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, true, true,
                    4, 3, 1, 4, Map.of());
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(Snapshot.SCHEMA_VERSION, true, "HEALTHY", null,
                    heads.size(), 0, 0, 4, 3, 1, 4);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
