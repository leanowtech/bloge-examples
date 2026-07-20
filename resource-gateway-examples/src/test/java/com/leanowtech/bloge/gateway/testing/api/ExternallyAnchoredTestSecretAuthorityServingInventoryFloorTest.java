package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternallyAnchoredTestSecretAuthorityServingInventoryFloorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publicationAndWitnessBecomeOneExternalHeadBeforeLocalCommit() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingPublicationFloor local = new RecordingPublicationFloor(order);
        var floor = new ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor(
                objectMapper, local, anchor);
        var first = publication(1, 'a', 'b', null);
        var second = publication(2, 'c', 'd', first);

        floor.accept(first);
        floor.accept(second);

        assertThat(order).containsExactly("external-1", "local-1", "external-2", "local-2");
        assertThat(anchor.heads).hasSize(2);
        assertThat(anchor.heads.getFirst().streamKind()).isEqualTo(
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_PUBLICATION);
        assertThat(anchor.heads.getFirst().streamId()).isEqualTo(
                ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor.STREAM_ID);
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
    void rootUsesNamespacedStreamAndCommitsExternalFirst() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingTrustRootFloor local = new RecordingTrustRootFloor(order);
        var floor = new ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor(
                local, anchor);
        var generation = trustRoot(1, 'a', null);

        floor.accept(generation);

        assertThat(order).containsExactly("external-1", "local-1");
        assertThat(anchor.heads.getFirst().streamKind()).isEqualTo(
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT);
        assertThat(anchor.heads.getFirst().streamId())
                .isEqualTo(
                        ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor
                                .streamId("inventory-roots"))
                .startsWith("test-secret-root-")
                .hasSize(81);
        assertThat(anchor.heads.getFirst().headFingerprint())
                .isEqualTo(generation.materialFingerprint());
        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isTrue();
    }

    @Test
    void externalFailureNeverTouchesEitherLocalFloor() {
        List<String> publicationOrder = new ArrayList<>();
        RecordingAnchor publicationAnchor = new RecordingAnchor(publicationOrder, true);
        publicationAnchor.fail = true;
        RecordingPublicationFloor publicationLocal =
                new RecordingPublicationFloor(publicationOrder);
        var publicationFloor =
                new ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor(
                        objectMapper, publicationLocal, publicationAnchor);

        assertThatThrownBy(() -> publicationFloor.accept(publication(1, 'a', 'b', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThat(publicationOrder).containsExactly("external-1");
        assertThat(publicationLocal.generations).isEmpty();

        List<String> rootOrder = new ArrayList<>();
        RecordingAnchor rootAnchor = new RecordingAnchor(rootOrder, true);
        rootAnchor.fail = true;
        RecordingTrustRootFloor rootLocal = new RecordingTrustRootFloor(rootOrder);
        var rootFloor = new ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor(
                rootLocal, rootAnchor);

        assertThatThrownBy(() -> rootFloor.accept(trustRoot(1, 'a', null)))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class);
        assertThat(rootOrder).containsExactly("external-1");
        assertThat(rootLocal.generations).isEmpty();
    }

    @Test
    void exactRetryRepairsLocalFailureAgainstSameExternalHead() {
        List<String> order = new ArrayList<>();
        RecordingAnchor anchor = new RecordingAnchor(order, true);
        RecordingPublicationFloor local = new RecordingPublicationFloor(order);
        local.failOnce = true;
        var floor = new ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor(
                objectMapper, local, anchor);
        var generation = publication(1, 'a', 'b', null);

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
    void unsafeAnchorAndNonDurableLocalFloorAreRejectedAtConstruction() {
        RecordingAnchor unavailable = new RecordingAnchor(new ArrayList<>(), false);
        RecordingPublicationFloor durable = new RecordingPublicationFloor(new ArrayList<>());

        assertThatThrownBy(() ->
                new ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor(
                        objectMapper, durable, unavailable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable or unsafe");

        RecordingTrustRootFloor nonDurable = new RecordingTrustRootFloor(new ArrayList<>());
        nonDurable.durable = false;
        assertThatThrownBy(() ->
                new ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor(
                        nonDurable, new RecordingAnchor(new ArrayList<>(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable local floor");
    }

    @Test
    void nonByzantineExternalAnchorIsReportedHonestly() {
        RecordingAnchor anchor = new RecordingAnchor(new ArrayList<>(), true, false);
        var floor = new ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor(
                new RecordingTrustRootFloor(new ArrayList<>()), anchor);

        assertThat(floor.externallyAnchored()).isTrue();
        assertThat(floor.byzantineQuorumAnchored()).isFalse();
    }

    @Test
    void maximumLengthRootSetIdStillProducesOneBoundedDomainSeparatedStream() {
        String maximumRootSetId = "r".repeat(255);

        assertThat(ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor
                .streamId(maximumRootSetId))
                .startsWith("test-secret-root-")
                .hasSize(81)
                .isNotEqualTo(
                        ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor
                                .streamId("s".repeat(255)));
    }

    @Test
    void domainAdapterDelegatesWithoutChangingTheStableV1Head() {
        RecordingGenericAnchor generic = new RecordingGenericAnchor();
        TestSecretAuthorityExternalSequenceAnchor adapter =
                TestSecretAuthorityExternalSequenceAnchor.adapt(generic);
        var head = new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                "secret-fleet", "test-secret/root-a", 1, fingerprint('a'), "");

        adapter.accept(head);

        assertThat(generic.heads).containsExactly(head);
        assertThat(adapter.descriptor()).isSameAs(generic.descriptor());
        assertThat(adapter.snapshot()).isSameAs(generic.snapshot());
    }

    @Test
    void healthIsAggregateOnlyAndFailsClosedWhenSnapshotBreaks() {
        RecordingAnchor anchor = new RecordingAnchor(new ArrayList<>(), true);
        var health = new TestSecretAuthorityExternalSequenceAnchorHealth(anchor).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsOnlyKeys(
                "schemaVersion", "status", "lastSuccessfulAnchorAt", "successCount",
                "failureCount", "conflictCount", "authorityCount", "signatureThreshold",
                "maximumFaults", "independentFailureDomainCount", "trustStatus",
                "trustPublicationSequence", "trustAuthorityCount",
                "trustActiveAuthorityCount", "trustLastSuccessfulRefreshAt",
                "trustRefreshSuccessCount", "trustRefreshFailureCount",
                "bootstrapRootStatus", "bootstrapRootHeadSequence",
                "bootstrapRootTransitionCount", "bootstrapRootAuthorityCount",
                "bootstrapRootActiveAuthorityCount", "bootstrapRootHeadExpiresAt",
                "bootstrapRootLastSuccessfulRefreshAt",
                "bootstrapRootRefreshSuccessCount", "bootstrapRootRefreshFailureCount");
        assertThat(health.getDetails())
                .containsEntry("trustStatus", "UNAVAILABLE")
                .containsEntry("trustPublicationSequence", 0L)
                .containsEntry("trustAuthorityCount", 0)
                .containsEntry("trustActiveAuthorityCount", 0)
                .containsEntry("trustLastSuccessfulRefreshAt", "")
                .containsEntry("trustRefreshSuccessCount", 0L)
                .containsEntry("trustRefreshFailureCount", 0L)
                .containsEntry("bootstrapRootStatus", "UNAVAILABLE")
                .containsEntry("bootstrapRootHeadSequence", 0L)
                .containsEntry("bootstrapRootTransitionCount", 0)
                .containsEntry("bootstrapRootAuthorityCount", 0)
                .containsEntry("bootstrapRootActiveAuthorityCount", 0)
                .containsEntry("bootstrapRootHeadExpiresAt", "")
                .containsEntry("bootstrapRootLastSuccessfulRefreshAt", "")
                .containsEntry("bootstrapRootRefreshSuccessCount", 0L)
                .containsEntry("bootstrapRootRefreshFailureCount", 0L);
        assertThat(health.getDetails().toString())
                .doesNotContain("endpoint", "stream", "fingerprint", "authorityId", "key");

        anchor.snapshotFails = true;
        assertThat(new TestSecretAuthorityExternalSequenceAnchorHealth(anchor)
                .health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static TestSecretAuthorityServingInventoryPublicationFloor.Generation publication(
            long sequence,
            char publication,
            char witness,
            TestSecretAuthorityServingInventoryPublicationFloor.Generation previous) {
        return new TestSecretAuthorityServingInventoryPublicationFloor.Generation(
                TestSecretAuthorityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                "secret-fleet", sequence, fingerprint(publication), fingerprint(witness),
                previous == null ? "" : previous.publicationMaterialFingerprint(),
                previous == null ? "" : previous.witnessMaterialFingerprint());
    }

    private static TestSecretAuthorityServingInventoryTrustRootFloor.Generation trustRoot(
            long sequence,
            char material,
            TestSecretAuthorityServingInventoryTrustRootFloor.Generation previous) {
        return new TestSecretAuthorityServingInventoryTrustRootFloor.Generation(
                TestSecretAuthorityServingInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                "secret-fleet", "inventory-roots", sequence, fingerprint(material),
                previous == null ? "" : previous.materialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAnchor
            implements TestSecretAuthorityExternalSequenceAnchor {

        private final List<String> order;
        private final boolean available;
        private final boolean byzantine;
        private final List<TestSuiteStabilityExternalSequenceAnchor.Head> heads =
                new ArrayList<>();
        private boolean fail;
        private boolean snapshotFails;

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
            if (snapshotFails) {
                throw new IllegalStateException("snapshot failed");
            }
            int authorities = byzantine ? 4 : 1;
            int threshold = byzantine ? 3 : 1;
            int faults = byzantine ? 1 : 0;
            return new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                    TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                    available, available ? "HEALTHY" : "UNAVAILABLE", null,
                    heads.size(), 0, 0, authorities, threshold, faults, authorities);
        }
    }

    private static final class RecordingGenericAnchor
            implements TestSuiteStabilityExternalSequenceAnchor {

        private final List<Head> heads = new ArrayList<>();
        private final Descriptor descriptor = new Descriptor(
                Descriptor.SCHEMA_VERSION, true, true, true, true,
                4, 3, 1, 4, Map.of());
        private final Snapshot snapshot = new Snapshot(
                Snapshot.SCHEMA_VERSION, true, "HEALTHY", null,
                0, 0, 0, 4, 3, 1, 4);

        @Override
        public void accept(Head head) {
            heads.add(head);
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }
    }

    private static final class RecordingPublicationFloor
            implements TestSecretAuthorityServingInventoryPublicationFloor {

        private final List<String> order;
        private final List<Generation> generations = new ArrayList<>();
        private boolean failOnce;

        private RecordingPublicationFloor(List<String> order) {
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
            return true;
        }
    }

    private static final class RecordingTrustRootFloor
            implements TestSecretAuthorityServingInventoryTrustRootFloor {

        private final List<String> order;
        private final List<Generation> generations = new ArrayList<>();
        private boolean durable = true;

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
            return durable;
        }
    }
}
