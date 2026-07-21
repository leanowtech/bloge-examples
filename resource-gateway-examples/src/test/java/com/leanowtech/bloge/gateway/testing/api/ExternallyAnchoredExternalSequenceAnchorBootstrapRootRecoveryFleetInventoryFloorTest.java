package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryFloorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publicationAndWitnessBecomeOneExternalHeadBeforeLocalCommit() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingPublicationFloor local = new RecordingPublicationFloor(order, true);
        var floor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, local, anchor);
        var first = publication("fleet-a", 1, 'a', 'b', null);
        var second = publication("fleet-a", 2, 'c', 'd', first);

        floor.accept(first);
        floor.accept(second);

        assertThat(order).containsExactly("external-1", "local-1", "external-2", "local-2");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst().previousHeadFingerprint()).isEmpty();
        assertThat(anchor.heads.getLast().previousHeadFingerprint())
                .isEqualTo(anchor.heads.getFirst().headFingerprint());
        assertThat(anchor.heads.getFirst()).satisfies(head -> {
            assertThat(head.scopeId()).isEqualTo("tenant-a/staging");
            assertThat(head.streamKind()).isEqualTo(
                    TestSuiteStabilityExternalSequenceAnchor.StreamKind
                            .SERVING_INVENTORY_PUBLICATION);
            assertThat(head.streamId()).startsWith("recovery-fleet-publication-")
                    .doesNotContain("fleet-a");
        });
        assertThat(floor.durable()).isTrue();
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isTrue();
    }

    @Test
    void externalStreamIdentitySeparatesFleetAndRootSetWithoutDisclosingEither() {
        String publicationA = ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams
                .publication("fleet-a");
        String publicationB = ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams
                .publication("fleet-b");
        String rootA = ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams
                .trustRoot("fleet-a", "roots-a");
        String rootB = ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalStreams
                .trustRoot("fleet-a", "roots-b");

        assertThat(publicationA).isNotEqualTo(publicationB);
        assertThat(rootA).isNotEqualTo(rootB).isNotEqualTo(publicationA);
        assertThat(List.of(publicationA, publicationB, rootA, rootB))
                .allSatisfy(value -> assertThat(value)
                        .doesNotContain("fleet-a", "fleet-b", "roots-a", "roots-b"));
    }

    @Test
    void trustRootMapsAtomicMaterialDirectlyAndCommitsExternalFirst() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingTrustRootFloor local = new RecordingTrustRootFloor(order, true);
        var floor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                local, anchor);
        var generation = trustRoot(1, 'e', null);

        floor.accept(generation);

        assertThat(order).containsExactly("external-1", "local-1");
        assertThat(anchor.heads.getFirst()).satisfies(head -> {
            assertThat(head.streamKind()).isEqualTo(
                    TestSuiteStabilityExternalSequenceAnchor.StreamKind
                            .SERVING_INVENTORY_TRUST_ROOT);
            assertThat(head.headFingerprint()).isEqualTo(generation.materialFingerprint());
            assertThat(head.streamId()).startsWith("recovery-fleet-trust-root-")
                    .doesNotContain("fleet-a", "roots-a");
        });
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isTrue();
    }

    @Test
    void externalFailureNeverTouchesEitherLocalFloor() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        anchor.fail = true;
        RecordingPublicationFloor publications = new RecordingPublicationFloor(order, true);
        RecordingTrustRootFloor roots = new RecordingTrustRootFloor(order, true);
        var publicationFloor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, publications, anchor);
        var rootFloor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                roots, anchor);

        assertThatThrownBy(() -> publicationFloor.accept(
                publication("fleet-a", 1, 'a', 'b', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThatThrownBy(() -> rootFloor.accept(trustRoot(1, 'c', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);

        assertThat(order).containsExactly("external-1", "external-1");
        assertThat(publications.generations).isEmpty();
        assertThat(roots.generations).isEmpty();
    }

    @Test
    void wrappersRejectNonDurableLocalStateAndUnsafeExternalAuthority() {
        RecordingAnchor safe = new RecordingAnchor(new ArrayList<>(), true);
        RecordingAnchor unavailable = new RecordingAnchor(new ArrayList<>(), true);
        unavailable.available = false;

        assertThatThrownBy(() -> new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, new RecordingPublicationFloor(new ArrayList<>(), false), safe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable local floor");
        assertThatThrownBy(() -> new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                new RecordingTrustRootFloor(new ArrayList<>(), false), safe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable local floor");
        assertThatThrownBy(() -> new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, new RecordingPublicationFloor(new ArrayList<>(), true),
                unavailable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable or unsafe");
        assertThatThrownBy(() -> new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                new RecordingTrustRootFloor(new ArrayList<>(), true), unavailable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable or unsafe");
    }

    @Test
    void byzantineClaimExactlyTracksTheExternalDescriptor() {
        RecordingAnchor crashFaultOnly = new RecordingAnchor(new ArrayList<>(), false);
        var publicationFloor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, new RecordingPublicationFloor(new ArrayList<>(), true),
                crashFaultOnly);
        var rootFloor = new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                new RecordingTrustRootFloor(new ArrayList<>(), true), crashFaultOnly);

        assertThat(publicationFloor.externallyAnchored()).isTrue();
        assertThat(rootFloor.externallyAnchored()).isTrue();
        assertThat(publicationFloor.byzantineQuorumAnchored()).isFalse();
        assertThat(rootFloor.byzantineQuorumAnchored()).isFalse();
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            .Generation publication(
            String fleetId,
            long sequence,
            char publication,
            char witness,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation
                    previous) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                .Generation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                        .Generation.SCHEMA_VERSION,
                "tenant-a/staging", fleetId, sequence, 17L, fingerprint('9'),
                fingerprint(publication), fingerprint(witness),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State.ACTIVE,
                previous == null ? "" : previous.publicationMaterialFingerprint(),
                previous == null ? "" : previous.witnessMaterialFingerprint());
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
            .Generation trustRoot(
            long sequence,
            char material,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor.Generation
                    previous) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
                .Generation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor.Generation
                        .SCHEMA_VERSION,
                "tenant-a/staging", "fleet-a", "roots-a", sequence, fingerprint(material),
                previous == null ? "" : previous.materialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAnchor
            implements ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor {

        private final List<String> order;
        private final boolean byzantine;
        private final List<TestSuiteStabilityExternalSequenceAnchor.Head> heads =
                new ArrayList<>();
        private boolean available = true;
        private boolean fail;

        private RecordingAnchor(List<String> order, boolean byzantine) {
            this.order = order;
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
            if (!available) {
                return new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                        false, false, false, false, 0, 0, 0, 0, Map.of());
            }
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            int faults = byzantine ? 1 : 0;
            return new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                    TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                    true, true, true, byzantine, authorities, threshold, faults,
                    authorities, Map.of());
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

    private static final class RecordingPublicationFloor
            implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {

        private final List<String> order;
        private final boolean durable;
        private final List<Generation> generations = new ArrayList<>();

        private RecordingPublicationFloor(List<String> order, boolean durable) {
            this.order = order;
            this.durable = durable;
        }

        @Override
        public void accept(Generation generation) {
            order.add("local-" + generation.sequence());
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return durable;
        }
    }

    private static final class RecordingTrustRootFloor
            implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {

        private final List<String> order;
        private final boolean durable;
        private final List<Generation> generations = new ArrayList<>();

        private RecordingTrustRootFloor(List<String> order, boolean durable) {
            this.order = order;
            this.durable = durable;
        }

        @Override
        public void accept(Generation generation) {
            order.add("local-" + generation.sequence());
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return durable;
        }
    }
}
