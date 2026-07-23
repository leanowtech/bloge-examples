package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureMirrorClusterBindingsTest {
    private static final MirrorArtifactRef CAPABILITY =
            ref("CAPABILITY", "operator:customer.lookup", 3, '1');
    private static final MirrorArtifactRef CORPUS =
            ref("CAPABILITY_CORPUS_PUBLICATION", "customer-corpus", 7, '2');
    private static final MirrorArtifactRef CLUSTER =
            ref("CAPABILITY_CORPUS_CLUSTER_PUBLICATION", "customer-cluster", 2, '3');

    @Test
    void parsesExactClusterSelectionAndCrossChecksCorpusBinding() {
        FixtureMirrorCorpusBindings corpora = new FixtureMirrorCorpusBindings(
                true, List.of(new FixtureMirrorCorpusBindings.PublicationBinding(
                CAPABILITY, CORPUS)));

        FixtureMirrorClusterBindings parsed =
                FixtureMirrorClusterBindings.from(
                        fixture(clusterMetadata(List.of(binding(
                                CAPABILITY, CORPUS, CLUSTER)))), corpora);

        assertThat(parsed.configured()).isTrue();
        assertThat(parsed.clusters()).containsExactly(
                new FixtureMirrorClusterBindings.ClusterBinding(
                        CAPABILITY, CORPUS, CLUSTER));
    }

    @Test
    void rejectsUnknownFieldsAndFractionalOrOversizedRevisions() {
        FixtureMirrorCorpusBindings corpora = corpusBindings();
        Map<String, Object> unknown = new LinkedHashMap<>(
                binding(CAPABILITY, CORPUS, CLUSTER));
        unknown.put("optional", true);
        assertInvalid(
                fixture(clusterMetadata(List.of(unknown))), corpora);

        Map<String, Object> fractional = new LinkedHashMap<>(
                binding(CAPABILITY, CORPUS, CLUSTER));
        fractional.put("clusterPublicationRef", wire(
                CLUSTER.kind(), CLUSTER.id(), 2.5d, CLUSTER.fingerprint()));
        assertInvalid(
                fixture(clusterMetadata(List.of(fractional))), corpora);

        Map<String, Object> oversized = new LinkedHashMap<>(
                binding(CAPABILITY, CORPUS, CLUSTER));
        oversized.put("clusterPublicationRef", wire(
                CLUSTER.kind(), CLUSTER.id(),
                new java.math.BigInteger("9223372036854775808"),
                CLUSTER.fingerprint()));
        assertInvalid(
                fixture(clusterMetadata(List.of(oversized))), corpora);
    }

    @Test
    void rejectsDuplicateForkedAndNonCanonicalClusterCoordinates() {
        FixtureMirrorCorpusBindings corpora = corpusBindings();
        MirrorArtifactRef second = ref(
                "CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                "z-customer-cluster", 1, '4');
        assertInvalid(
                fixture(clusterMetadata(List.of(
                        binding(CAPABILITY, CORPUS, second),
                        binding(CAPABILITY, CORPUS, CLUSTER)))),
                corpora);

        MirrorArtifactRef fork = ref(
                CLUSTER.kind(), CLUSTER.id(), CLUSTER.revision(), '5');
        assertInvalid(
                fixture(clusterMetadata(List.of(
                        binding(CAPABILITY, CORPUS, CLUSTER),
                        binding(CAPABILITY, CORPUS, fork)))),
                corpora);
    }

    @Test
    void rejectsAClusterThatRetargetsAnotherCorpusPublication() {
        MirrorArtifactRef otherCorpus = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "other-corpus", 1, '6');

        assertInvalid(
                fixture(clusterMetadata(List.of(binding(
                        CAPABILITY, otherCorpus, CLUSTER)))),
                corpusBindings());
    }

    private static FixtureMirrorCorpusBindings corpusBindings() {
        return new FixtureMirrorCorpusBindings(
                true, List.of(new FixtureMirrorCorpusBindings.PublicationBinding(
                CAPABILITY, CORPUS)));
    }

    private static void assertInvalid(
            FixtureBundle fixture,
            FixtureMirrorCorpusBindings corpora) {
        assertThatThrownBy(() -> FixtureMirrorClusterBindings.from(
                fixture, corpora))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "fixtureBundle.metadata.mirrorClusters");
    }

    private static Map<String, Object> clusterMetadata(
            List<Map<String, Object>> bindings) {
        return Map.of(
                FixtureMirrorClusterBindings.METADATA_KEY,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorClusterBindings.SCHEMA_VERSION,
                        "clusters",
                        new ArrayList<>(bindings)));
    }

    private static Map<String, Object> binding(
            MirrorArtifactRef capability,
            MirrorArtifactRef corpus,
            MirrorArtifactRef cluster) {
        return Map.of(
                "capabilityRef", wire(capability),
                "corpusPublicationRef", wire(corpus),
                "clusterPublicationRef", wire(cluster));
    }

    private static Map<String, Object> wire(MirrorArtifactRef ref) {
        return wire(
                ref.kind(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static Map<String, Object> wire(
            String kind, String id, Object revision, String fingerprint) {
        return Map.of(
                "kind", kind,
                "id", id,
                "revision", revision,
                "fingerprint", fingerprint);
    }

    private static FixtureBundle fixture(Map<String, Object> metadata) {
        return new FixtureBundle(
                "",
                "cluster-fixture",
                1,
                "sha256:" + "9".repeat(64),
                "CONFIDENTIAL",
                Instant.parse("2026-07-23T00:00:00Z"),
                42L,
                List.of(),
                List.of(),
                metadata);
    }

    private static MirrorArtifactRef ref(
            String kind, String id, long revision, char fingerprint) {
        return new MirrorArtifactRef(
                kind, id, revision,
                "sha256:" + String.valueOf(fingerprint).repeat(64));
    }
}
