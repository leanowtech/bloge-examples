package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureMirrorCorpusBindingsTest {
    @Test
    void parsesCanonicalExactPublicationBindings() {
        MirrorArtifactRef capability = ref("CAPABILITY", "operator:customer.lookup", '1');
        MirrorArtifactRef publication = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "customer-corpus", '2');

        FixtureMirrorCorpusBindings parsed = FixtureMirrorCorpusBindings.from(
                fixture(Map.of(
                        "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                        "publications", List.of(Map.of(
                                "capabilityRef", wire(capability),
                                "publicationRef", wire(publication))))));

        assertThat(parsed.configured()).isTrue();
        assertThat(parsed.publications()).containsExactly(
                new FixtureMirrorCorpusBindings.PublicationBinding(
                        capability, publication));
    }

    @Test
    void rejectsUnknownFieldsDuplicateCapabilitiesAndNonCanonicalOrder() {
        MirrorArtifactRef capabilityA = ref("CAPABILITY", "operator:a", '1');
        MirrorArtifactRef capabilityB = ref("CAPABILITY", "operator:b", '2');
        MirrorArtifactRef publicationA = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "corpus-a", '3');
        MirrorArtifactRef publicationB = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "corpus-b", '4');

        assertThatThrownBy(() -> FixtureMirrorCorpusBindings.from(fixture(Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", List.of(Map.of(
                        "capabilityRef", wire(capabilityA),
                        "publicationRef", wire(publicationA),
                        "fallback", true))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly capabilityRef and publicationRef");

        assertThatThrownBy(() -> FixtureMirrorCorpusBindings.from(fixture(Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", List.of(
                        binding(capabilityA, publicationA),
                        binding(capabilityA, publicationB))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate or forked capability coordinate");

        MirrorArtifactRef forkedCapability = new MirrorArtifactRef(
                capabilityA.kind(), capabilityA.id(), capabilityA.revision(),
                fingerprint('9'));
        assertThatThrownBy(() -> FixtureMirrorCorpusBindings.from(fixture(Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", List.of(
                        binding(capabilityA, publicationA),
                        binding(forkedCapability, publicationB))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate or forked capability coordinate");

        assertThatThrownBy(() -> FixtureMirrorCorpusBindings.from(fixture(Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", List.of(
                        binding(capabilityB, publicationB),
                        binding(capabilityA, publicationA))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical capability order");
    }

    @Test
    void absenceDoesNotRequireAnyServingAuthority() {
        FixtureMirrorCorpusBindings parsed =
                FixtureMirrorCorpusBindings.from(fixture(null));

        assertThat(parsed.configured()).isFalse();
        assertThat(parsed.publications()).isEmpty();
    }

    @Test
    void fixedFixtureAndStrictSchemaMatchTheProducerParser() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        JsonFixture protocol = new JsonFixture(
                mapper.readTree(Files.readString(schemaPath(
                        "fixture-mirror-corpus-bindings-v1.fixture.json"))),
                mapper.readTree(Files.readString(schemaPath(
                        "fixture-mirror-corpus-bindings-v1.schema.json"))));
        FixtureMirrorCorpusBindings parsed = FixtureMirrorCorpusBindings.from(
                fixture(mapper.convertValue(protocol.fixture(), Map.class)));

        assertThat(parsed.publications()).hasSize(2);
        assertThat(protocol.schema().path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(protocol.schema().at(
                "/$defs/publicationBinding/additionalProperties").asBoolean())
                .isFalse();
        assertThat(protocol.schema().at("/properties/publications/maxItems").asInt())
                .isEqualTo(FixtureMirrorCorpusBindings.MAXIMUM_PUBLICATIONS);
        assertThat(protocol.schema().path("required"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactlyInAnyOrder("schemaVersion", "publications");
        assertThat(Files.readString(schemaPath(
                "fixture-mirror-corpus-bindings-v1.schema.json")))
                .doesNotContain("requestBody", "responseBody", "rawPayload", "secret");
    }

    private static FixtureBundle fixture(Object mirrorCorpus) {
        return new FixtureBundle("", "fixture", 1, fingerprint('a'), "CONFIDENTIAL",
                Instant.parse("2026-07-23T08:00:00Z"), 42L, List.of(), List.of(),
                mirrorCorpus == null ? Map.of() : Map.of(
                        FixtureMirrorCorpusBindings.METADATA_KEY, mirrorCorpus));
    }

    private static Map<String, Object> binding(
            MirrorArtifactRef capability, MirrorArtifactRef publication) {
        return Map.of(
                "capabilityRef", wire(capability),
                "publicationRef", wire(publication));
    }

    private static Map<String, Object> wire(MirrorArtifactRef ref) {
        return Map.of(
                "kind", ref.kind(),
                "id", ref.id(),
                "revision", ref.revision(),
                "fingerprint", ref.fingerprint());
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", filename);
    }

    private record JsonFixture(
            com.fasterxml.jackson.databind.JsonNode fixture,
            com.fasterxml.jackson.databind.JsonNode schema) {
    }
}
