package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureMirrorClusterBindingsVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureMirrorClusterBindingsVerifier verifier =
            new FixtureMirrorClusterBindingsVerifier();

    @Test
    void verifiesPackagedFixtureAndMatchingCorpusSelection() {
        JsonNode clusters = CapabilityMirrorProtocol
                .fixtureMirrorClusterBindingsFixture();
        JsonNode corpora = corpus(
                clusters.path("clusters").get(0).path("capabilityRef"),
                clusters.path("clusters").get(0)
                        .path("corpusPublicationRef"));

        assertThat(verifier.verify(clusters, corpora))
                .satisfies(result -> {
                    assertThat(result.verified()).isTrue();
                    assertThat(result.checkedBindings()).isEqualTo(2);
                });
    }

    @Test
    void rejectsUnknownFieldsDuplicateCoordinatesAndCorpusMismatch() {
        JsonNode fixture = CapabilityMirrorProtocol
                .fixtureMirrorClusterBindingsFixture();
        JsonNode unknown = fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown)
                .put("optional", true);
        assertThat(verifier.verify(unknown).outcome())
                .isEqualTo(
                        FixtureMirrorClusterBindingsVerifier.Outcome
                                .SCHEMA_INVALID);

        JsonNode duplicate = fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate
                .path("clusters")).set(
                1, duplicate.path("clusters").get(0).deepCopy());
        assertThat(verifier.verify(duplicate).outcome())
                .isEqualTo(
                        FixtureMirrorClusterBindingsVerifier.Outcome
                                .DUPLICATE_CLUSTER);

        JsonNode fork = fixture.deepCopy();
        var forkedRef = (com.fasterxml.jackson.databind.node.ObjectNode) fork
                .path("clusters").get(1).path("clusterPublicationRef");
        JsonNode firstCluster = fork.path("clusters").get(0)
                .path("clusterPublicationRef");
        forkedRef.put("id", firstCluster.path("id").asText());
        forkedRef.put("revision", firstCluster.path("revision").asLong());
        forkedRef.put("fingerprint", "sha256:" + "f".repeat(64));
        assertThat(verifier.verify(fork).outcome())
                .isEqualTo(
                        FixtureMirrorClusterBindingsVerifier.Outcome
                                .DUPLICATE_CLUSTER);

        JsonNode wrongCorpus = corpus(
                fixture.path("clusters").get(0).path("capabilityRef"),
                artifact(
                        "CAPABILITY_CORPUS_PUBLICATION",
                        "other-corpus",
                        1,
                        '9'));
        assertThat(verifier.verify(fixture, wrongCorpus).outcome())
                .isEqualTo(
                        FixtureMirrorClusterBindingsVerifier.Outcome
                                .CORPUS_BINDING_MISMATCH);
    }

    private JsonNode corpus(JsonNode capability, JsonNode publication) {
        var root = mapper.createObjectNode();
        root.put(
                "schemaVersion",
                CapabilityMirrorProtocol.FIXTURE_MIRROR_CORPUS_BINDINGS_V1);
        var bindings = root.putArray("publications");
        var binding = bindings.addObject();
        binding.set("capabilityRef", capability.deepCopy());
        binding.set("publicationRef", publication.deepCopy());
        return root;
    }

    private JsonNode artifact(
            String kind, String id, long revision, char fingerprint) {
        var ref = mapper.createObjectNode();
        ref.put("kind", kind);
        ref.put("id", id);
        ref.put("revision", revision);
        ref.put(
                "fingerprint",
                "sha256:" + String.valueOf(fingerprint).repeat(64));
        return ref;
    }
}
