package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDraftSaveProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commandAndReceiptSchemasRemainStrictAndMatchTheVersionedRecords() throws Exception {
        JsonNode command = schema("bloge-graph-draft-save-command-v1.schema.json");
        JsonNode receipt = schema("bloge-graph-draft-save-receipt-v1.schema.json");
        JsonNode draft = schema("bloge-visual-graph-draft-v1.schema.json");

        assertRecordFields(command, GraphDraftSaveCommand.class);
        assertRecordFields(receipt, StoredGraphDraftSaveReceipt.class);
        assertThat(command.at("/properties/schemaVersion/const").asText())
                .isEqualTo(GraphDraftSaveCommand.SCHEMA_VERSION);
        assertThat(receipt.at("/properties/schemaVersion/const").asText())
                .isEqualTo(StoredGraphDraftSaveReceipt.SCHEMA_VERSION);
        assertThat(draft.at("/properties/draftId/minLength").isMissingNode()).isTrue();
        assertThat(draft.at("/properties/revision/minimum").asLong()).isZero();
    }

    @Test
    void nodeFixtureKeepsOutputRequiredAndMetadataOptionalWithSafeDefaults() throws Exception {
        JsonNode fixture = schema("bloge-visual-graph-draft-v1.schema.json").at("/$defs/nodeFixture");

        assertThat(iterable(fixture.path("required")))
                .containsExactly("output");
        assertThat(fixture.at("/properties/resourceFidelity/default").asText())
                .isEqualTo("OUTPUT_LEVEL");
        assertThat(iterable(fixture.at("/properties/resourceFidelity/enum")))
                .containsExactlyInAnyOrder("OUTPUT_LEVEL", "PROTOCOL_DERIVED", "TRANSPORT_LEVEL");
        assertThat(fixture.at("/properties/governedRef/$ref").asText())
                .isEqualTo("#/$defs/governedFixtureRef");

        JsonNode governed = schema("bloge-visual-graph-draft-v1.schema.json").at("/$defs/governedFixtureRef");
        assertThat(governed.path("additionalProperties").asBoolean()).isFalse();
        assertThat(iterable(governed.path("required")))
                .containsExactlyInAnyOrder("fixtureAssetId", "revision", "schemaFingerprint");
    }

    private void assertRecordFields(JsonNode schema, Class<?> recordType) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        Set<String> schemaFields = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaFields::add);
        Set<String> recordFields = new LinkedHashSet<>(Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .toList());
        assertThat(schemaFields).isEqualTo(recordFields);
        assertThat(iterable(schema.path("required"))).containsExactlyInAnyOrderElementsOf(recordFields);
    }

    private JsonNode schema(String name) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", name)));
    }

    private static Set<String> iterable(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
