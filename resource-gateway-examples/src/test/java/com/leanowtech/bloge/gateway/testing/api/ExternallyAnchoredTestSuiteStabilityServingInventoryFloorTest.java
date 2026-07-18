package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternallyAnchoredTestSuiteStabilityServingInventoryFloorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publicationAndWitnessBecomeOneExternalHeadBeforeLocalCommit() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order);
        RecordingPublicationFloor local = new RecordingPublicationFloor(order);
        var floor = new ExternallyAnchoredTestSuiteStabilityServingInventoryPublicationFloor(
                objectMapper, local, anchor);
        var first = publication(1, 'a', 'b', null);
        var second = publication(2, 'c', 'd', first);

        floor.accept(first);
        floor.accept(second);

        assertThat(order).containsExactly("external-1", "local-1", "external-2", "local-2");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst().previousHeadFingerprint()).isEmpty();
        assertThat(anchor.heads.getLast().previousHeadFingerprint())
                .isEqualTo(anchor.heads.getFirst().headFingerprint());
        assertThat(anchor.heads.getLast().streamKind()).isEqualTo(
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION);
        assertThat(floor.durable()).isTrue();
        assertThat(floor.externallyAnchored()).isTrue();
    }

    @Test
    void trustRootMapsCanonicalMaterialDirectlyAndCommitsExternalFirst() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order);
        RecordingTrustRootFloor local = new RecordingTrustRootFloor(order);
        var floor = new ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor(
                local, anchor);
        var generation = trustRoot(1, 'a', null);

        floor.accept(generation);

        assertThat(order).containsExactly("external-1", "local-1");
        assertThat(anchor.heads.getFirst().headFingerprint())
                .isEqualTo(generation.materialFingerprint());
        assertThat(anchor.heads.getFirst().streamId()).isEqualTo("inventory-roots");
        assertThat(floor.externallyAnchored()).isTrue();
    }

    @Test
    void externalFailureNeverTouchesLocalFloor() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order);
        anchor.fail = true;
        RecordingTrustRootFloor local = new RecordingTrustRootFloor(order);
        var floor = new ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor(
                local, anchor);

        assertThatThrownBy(() -> floor.accept(trustRoot(1, 'a', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThat(order).containsExactly("external-1");
        assertThat(local.generations).isEmpty();
    }

    private static TestSuiteStabilityServingInventoryPublicationFloor.Generation publication(
            long sequence,
            char publication,
            char witness,
            TestSuiteStabilityServingInventoryPublicationFloor.Generation previous) {
        return new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                "stability-fleet", sequence, fingerprint(publication), fingerprint(witness),
                previous == null ? "" : previous.publicationMaterialFingerprint(),
                previous == null ? "" : previous.witnessMaterialFingerprint());
    }

    private static TestSuiteStabilityServingInventoryTrustRootFloor.Generation trustRoot(
            long sequence,
            char material,
            TestSuiteStabilityServingInventoryTrustRootFloor.Generation previous) {
        return new TestSuiteStabilityServingInventoryTrustRootFloor.Generation(
                TestSuiteStabilityServingInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                "stability-fleet", "inventory-roots", sequence, fingerprint(material),
                previous == null ? "" : previous.materialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAnchor
            implements TestSuiteStabilityExternalSequenceAnchor {

        private final List<String> order;
        private final List<Head> heads = new ArrayList<>();
        private boolean fail;

        private RecordingAnchor(List<String> order) {
            this.order = order;
        }

        @Override
        public void accept(Head head) {
            order.add("external-" + head.sequence());
            if (fail) {
                throw new ExternalAnchorException(
                        ExternalAnchorException.Reason.QUORUM_NOT_MET);
            }
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
    }

    private static final class RecordingPublicationFloor
            implements TestSuiteStabilityServingInventoryPublicationFloor {

        private final List<String> order;
        private final List<Generation> generations = new ArrayList<>();

        private RecordingPublicationFloor(List<String> order) {
            this.order = order;
        }

        @Override
        public void accept(Generation generation) {
            order.add("local-" + generation.sequence());
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class RecordingTrustRootFloor
            implements TestSuiteStabilityServingInventoryTrustRootFloor {

        private final List<String> order;
        private final List<Generation> generations = new ArrayList<>();

        private RecordingTrustRootFloor(List<String> order) {
            this.order = order;
        }

        @Override
        public void accept(Generation generation) {
            order.add("local-" + generation.sequence());
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
