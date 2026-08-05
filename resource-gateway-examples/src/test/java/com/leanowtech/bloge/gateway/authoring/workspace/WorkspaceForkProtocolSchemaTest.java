package com.leanowtech.bloge.gateway.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceForkProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemasRemainStrictAndMatchThePublicRecordFields() throws Exception {
        JsonNode seed = schema("bloge-workspace-seed-bundle-v1.schema.json");
        JsonNode command = schema("bloge-workspace-fork-command-v1.schema.json");
        JsonNode receipt = schema("bloge-workspace-fork-receipt-v1.schema.json");

        assertRecordFields(seed, WorkspaceSeedBundle.class);
        assertRecordFields(command, WorkspaceForkCommand.class);
        assertRecordFields(receipt, WorkspaceForkReceipt.class);
        assertRecordFields(seed.at("/$defs/templateIdentity"),
                WorkspaceSeedBundle.TemplateIdentity.class);
        assertRecordFields(seed.at("/$defs/runtimeProfile"),
                WorkspaceSeedBundle.RuntimeProfile.class);
        assertRecordFields(receipt.at("/$defs/graphCoordinate"),
                WorkspaceForkReceipt.GraphCoordinate.class);
        assertRecordFields(receipt.at("/$defs/contractCoordinate"),
                WorkspaceForkReceipt.ContractCoordinate.class);
        assertRecordFields(receipt.at("/$defs/assetCoordinate"),
                WorkspaceForkReceipt.AssetCoordinate.class);

        assertThat(seed.at("/properties/scenarioDraftSets/maxItems").asInt()).isEqualTo(1);
        assertThat(receipt.at("/properties/scenarioSuiteCoordinates/minItems").asInt()).isEqualTo(1);
        assertThat(command.at("/properties/seed/$ref").asText())
                .isEqualTo("bloge-workspace-seed-bundle-v1.schema.json");
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
        return mapper.readTree(Files.readString(
                Path.of("..", "docs", "schemas", name)));
    }

    private static Set<String> iterable(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
