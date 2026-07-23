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

class FixtureMirrorTrajectoryBindingsTest {
    @Test
    void parsesCanonicalTrajectoryBindingsAgainstExactCorpusSelections() {
        MirrorArtifactRef capability = ref("CAPABILITY", "operator:customer.lookup", '1');
        MirrorArtifactRef corpus = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "customer-corpus", '2');
        MirrorArtifactRef trajectory = ref(
                CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                "customer-retry", '3');
        FixtureBundle fixture = fixture(
                List.of(corpusBinding(capability, corpus)),
                List.of(trajectoryBinding(capability, corpus, trajectory)));

        FixtureMirrorTrajectoryBindings parsed =
                FixtureMirrorTrajectoryBindings.from(
                        fixture, FixtureMirrorCorpusBindings.from(fixture));

        assertThat(parsed.configured()).isTrue();
        assertThat(parsed.trajectories()).containsExactly(
                new FixtureMirrorTrajectoryBindings.TrajectoryBinding(
                        capability, corpus, trajectory));
    }

    @Test
    void rejectsMissingCorpusSelectionDuplicatesAndNonCanonicalOrder() {
        MirrorArtifactRef capabilityA = ref("CAPABILITY", "operator:a", '1');
        MirrorArtifactRef capabilityB = ref("CAPABILITY", "operator:b", '2');
        MirrorArtifactRef corpusA = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "corpus-a", '3');
        MirrorArtifactRef corpusB = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND, "corpus-b", '4');
        MirrorArtifactRef trajectoryA = ref(
                CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                "trajectory-a", '5');
        MirrorArtifactRef trajectoryB = ref(
                CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                "trajectory-b", '6');

        FixtureBundle missingCorpus = fixture(
                List.of(corpusBinding(capabilityA, corpusA)),
                List.of(trajectoryBinding(capabilityB, corpusB, trajectoryB)));
        assertThatThrownBy(() -> FixtureMirrorTrajectoryBindings.from(
                missingCorpus, FixtureMirrorCorpusBindings.from(missingCorpus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact mirrorCorpus publication");

        FixtureBundle duplicate = fixture(
                List.of(corpusBinding(capabilityA, corpusA)),
                List.of(
                        trajectoryBinding(capabilityA, corpusA, trajectoryA),
                        trajectoryBinding(capabilityA, corpusA, trajectoryA)));
        assertThatThrownBy(() -> FixtureMirrorTrajectoryBindings.from(
                duplicate, FixtureMirrorCorpusBindings.from(duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate or forked trajectory coordinate");

        FixtureBundle reversed = fixture(
                List.of(
                        corpusBinding(capabilityA, corpusA),
                        corpusBinding(capabilityB, corpusB)),
                List.of(
                        trajectoryBinding(capabilityB, corpusB, trajectoryB),
                        trajectoryBinding(capabilityA, corpusA, trajectoryA)));
        assertThatThrownBy(() -> FixtureMirrorTrajectoryBindings.from(
                reversed, FixtureMirrorCorpusBindings.from(reversed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical capability and trajectory order");
    }

    @Test
    void absenceDoesNotAlterExistingCorpusOnlyFixtures() {
        FixtureBundle fixture = fixture(
                List.of(corpusBinding(
                        new MirrorArtifactRef(
                                "CAPABILITY",
                                "operator:customer.lookup",
                                3,
                                fingerprint('1')),
                        ref(CapabilityCorpusPublication.ARTIFACT_KIND,
                                "customer-corpus", '2'))),
                null);

        FixtureMirrorTrajectoryBindings parsed =
                FixtureMirrorTrajectoryBindings.from(
                        fixture, FixtureMirrorCorpusBindings.from(fixture));

        assertThat(parsed.configured()).isFalse();
        assertThat(parsed.trajectories()).isEmpty();
    }

    @Test
    void rejectsValuesThatStrictSchemaWouldRejectBeforeNormalization() {
        MirrorArtifactRef capability =
                ref("CAPABILITY", "operator:customer.lookup", '1');
        MirrorArtifactRef corpus = ref(
                CapabilityCorpusPublication.ARTIFACT_KIND,
                "customer-corpus",
                '2');
        MirrorArtifactRef trajectory = ref(
                CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                "customer-retry",
                '3');
        List<Map<String, Object>> corpora =
                List.of(corpusBinding(capability, corpus));

        FixtureBundle explicitNull = fixtureWithTrajectoryMetadata(
                corpora, null);
        assertThatThrownBy(() -> FixtureMirrorTrajectoryBindings.from(
                explicitNull,
                FixtureMirrorCorpusBindings.from(explicitNull)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an object");

        Map<String, Object> lowercaseKind =
                mutableTrajectoryBinding(capability, corpus, trajectory);
        mutableRef(lowercaseKind, "capabilityRef")
                .put("kind", "capability");
        assertInvalidReference(corpora, lowercaseKind);

        Map<String, Object> paddedIdentifier =
                mutableTrajectoryBinding(capability, corpus, trajectory);
        mutableRef(paddedIdentifier, "trajectoryPublicationRef")
                .put("id", " customer-retry ");
        assertInvalidReference(corpora, paddedIdentifier);

        Map<String, Object> oversizedIdentifier =
                mutableTrajectoryBinding(capability, corpus, trajectory);
        mutableRef(oversizedIdentifier, "trajectoryPublicationRef")
                .put("id", "a".repeat(513));
        assertInvalidReference(corpora, oversizedIdentifier);

        Map<String, Object> fractionalRevision =
                mutableTrajectoryBinding(capability, corpus, trajectory);
        mutableRef(fractionalRevision, "trajectoryPublicationRef")
                .put("revision", new java.math.BigDecimal("1.5"));
        assertInvalidReference(corpora, fractionalRevision);

        Map<String, Object> overflowingRevision =
                mutableTrajectoryBinding(capability, corpus, trajectory);
        mutableRef(overflowingRevision, "trajectoryPublicationRef")
                .put("revision", new java.math.BigInteger(
                        "9223372036854775808"));
        assertInvalidReference(corpora, overflowingRevision);
    }

    @Test
    void fixedFixtureAndStrictSchemaMatchTheProducerParser()
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode fixtureJson = mapper.readTree(
                Files.readString(schemaPath(
                        "fixture-mirror-trajectory-bindings-v1.fixture.json")));
        com.fasterxml.jackson.databind.JsonNode schema = mapper.readTree(
                Files.readString(schemaPath(
                        "fixture-mirror-trajectory-bindings-v1.schema.json")));
        @SuppressWarnings("unchecked")
        Map<String, Object> wire =
                mapper.convertValue(fixtureJson, Map.class);
        FixtureBundle fixture = fixture(
                List.of(corpusBinding(
                        new MirrorArtifactRef(
                                "CAPABILITY",
                                "operator:customer.lookup",
                                3,
                                fingerprint('1')),
                        new MirrorArtifactRef(
                                CapabilityCorpusPublication.ARTIFACT_KIND,
                                "customer-lookup-corpus",
                                7,
                                fingerprint('2')))),
                (List<Map<String, Object>>) wire.get("trajectories"));

        FixtureMirrorTrajectoryBindings parsed =
                FixtureMirrorTrajectoryBindings.from(
                        fixture, FixtureMirrorCorpusBindings.from(fixture));

        assertThat(parsed.trajectories()).hasSize(2);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at(
                "/$defs/trajectoryBinding/additionalProperties").asBoolean())
                .isFalse();
        assertThat(schema.at("/properties/trajectories/maxItems").asInt())
                .isEqualTo(
                        FixtureMirrorTrajectoryBindings.MAXIMUM_TRAJECTORIES);
        assertThat(Files.readString(schemaPath(
                "fixture-mirror-trajectory-bindings-v1.schema.json")))
                .doesNotContain(
                        "requestBody",
                        "responseBody",
                        "rawPayload",
                        "secret",
                        "fallbackToLatest");
    }

    private static FixtureBundle fixture(
            List<Map<String, Object>> corpora,
            List<Map<String, Object>> trajectories) {
        Object trajectoryMetadata = trajectories == null ? ABSENT : Map.of(
                "schemaVersion",
                "resourceGateway.fixtureMirrorTrajectoryBindings.v1",
                "trajectories", trajectories);
        return fixtureWithTrajectoryMetadata(corpora, trajectoryMetadata);
    }

    private static final Object ABSENT = new Object();

    private static FixtureBundle fixtureWithTrajectoryMetadata(
            List<Map<String, Object>> corpora,
            Object trajectoryMetadata) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(FixtureMirrorCorpusBindings.METADATA_KEY, Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", corpora));
        if (trajectoryMetadata != ABSENT) {
            metadata.put("mirrorTrajectories", trajectoryMetadata);
        }
        return new FixtureBundle("", "fixture", 1, fingerprint('a'), "CONFIDENTIAL",
                Instant.parse("2026-07-23T08:00:00Z"), 42L, List.of(), List.of(),
                metadata);
    }

    private static void assertInvalidReference(
            List<Map<String, Object>> corpora,
            Map<String, Object> binding) {
        FixtureBundle fixture = fixtureWithTrajectoryMetadata(
                corpora,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorTrajectoryBindings.SCHEMA_VERSION,
                        "trajectories",
                        List.of(binding)));
        assertThatThrownBy(() -> FixtureMirrorTrajectoryBindings.from(
                fixture, FixtureMirrorCorpusBindings.from(fixture)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "fixtureBundle.metadata.mirrorTrajectories")
                .hasMessageContaining("at index 0");
    }

    private static Map<String, Object> mutableTrajectoryBinding(
            MirrorArtifactRef capability,
            MirrorArtifactRef corpus,
            MirrorArtifactRef trajectory) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("capabilityRef", new java.util.LinkedHashMap<>(
                wire(capability)));
        result.put("corpusPublicationRef", new java.util.LinkedHashMap<>(
                wire(corpus)));
        result.put("trajectoryPublicationRef", new java.util.LinkedHashMap<>(
                wire(trajectory)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableRef(
            Map<String, Object> binding,
            String field) {
        return (Map<String, Object>) binding.get(field);
    }

    private static Map<String, Object> corpusBinding(
            MirrorArtifactRef capability,
            MirrorArtifactRef publication) {
        return Map.of(
                "capabilityRef", wire(capability),
                "publicationRef", wire(publication));
    }

    private static Map<String, Object> trajectoryBinding(
            MirrorArtifactRef capability,
            MirrorArtifactRef corpus,
            MirrorArtifactRef trajectory) {
        return Map.of(
                "capabilityRef", wire(capability),
                "corpusPublicationRef", wire(corpus),
                "trajectoryPublicationRef", wire(trajectory));
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
                : Path.of(
                        "docs", "schemas", "resource-gateway-mirror", filename);
    }
}
