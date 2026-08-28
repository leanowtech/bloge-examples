package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the persistence-only governed fixture metadata added to graph drafts.
 */
class GraphDraftNodeFixtureContractTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesGovernedCoordinateAndRequestedFidelityWithoutMaterial() throws Exception {
        String json = """
                {
                  "output": null,
                  "governedRef": {
                    "fixtureAssetId": "order-fixture",
                    "revision": 5,
                    "schemaFingerprint": "%s"
                  },
                  "resourceFidelity": "PROTOCOL_DERIVED"
                }
                """.formatted(FINGERPRINT);

        GraphDraft.NodeFixture fixture = objectMapper.readValue(json, GraphDraft.NodeFixture.class);

        assertThat(fixture.governedRef()).isEqualTo(
                new GraphDraft.GovernedFixtureRef("order-fixture", 5, FINGERPRINT));
        assertThat(fixture.resourceFidelity()).isEqualTo(GraphDraft.NodeFixture.ResourceFidelity.PROTOCOL_DERIVED);
        assertThat(fixture.output()).isNull();
        assertThat(fixture.expectedInput()).isNull();
        assertThat(objectMapper.writeValueAsString(fixture)).contains("order-fixture", FINGERPRINT);
    }

    @Test
    void retainsLegacyFixtureWireShapeAndNormalizesDefaultFidelity() throws Exception {
        GraphDraft.NodeFixture fixture = new GraphDraft.NodeFixture(
                "sample", Map.of("customerId", "c-1"));

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(fixture));

        assertThat(serialized.get("output").asText()).isEqualTo("sample");
        assertThat(serialized.get("expectedInput").get("customerId").asText()).isEqualTo("c-1");
        assertThat(fixture.governedRef()).isNull();
        assertThat(fixture.resourceFidelity()).isEqualTo(GraphDraft.NodeFixture.ResourceFidelity.OUTPUT_LEVEL);
    }

    @Test
    void rejectsGovernedCoordinatesThatAreNotExact() {
        assertThatThrownBy(() -> new GraphDraft.GovernedFixtureRef("order-fixture", 0, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphDraft.GovernedFixtureRef("order-fixture", 1, "alpha-schema"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
