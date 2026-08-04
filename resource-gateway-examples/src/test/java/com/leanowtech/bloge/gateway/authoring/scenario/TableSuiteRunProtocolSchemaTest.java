package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TableSuiteRunProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freezesStrictCommandBatchAndDeltaSchemasWithPayloadFreeDurableEvidence() throws Exception {
        JsonNode graph = schema("bloge-visual-graph-draft-v1.schema.json");
        JsonNode command = schema("bloge-table-suite-run-command-v1.schema.json");
        JsonNode batch = schema("bloge-table-suite-run-batch-v1.schema.json");
        JsonNode delta = schema("bloge-table-suite-run-delta-v1.schema.json");

        assertThat(graph.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(command.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(batch.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(delta.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(command.at("/properties/graphDraft/$ref").asText())
                .isEqualTo("bloge-visual-graph-draft-v1.schema.json");
        assertThat(command.at("/properties/contract/$ref").asText())
                .isEqualTo("bloge-contract-draft-v1.schema.json");
        assertThat(command.at("/properties/draftSet/$ref").asText())
                .isEqualTo("bloge-scenario-draft-set-v1.schema.json");
        assertThat(command.at("/$defs/selection/properties/mode/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("ALL", "SELECTED", "FAILED", "CHANGED", "AFFECTED");
        assertThat(command.at("/$defs/preflight/properties/maxConcurrency/const").asInt())
                .isEqualTo(1);
        assertThat(command.at("/$defs/preflight/properties/effectProfile/const").asText())
                .isEqualTo("SIDE_EFFECT_FREE");
        assertThat(batch.at("/$defs/attempt/properties/expectedFingerprint").isMissingNode())
                .isTrue();
        assertThat(fieldNamesRecursively(batch))
                .contains("expectedFingerprint", "actualFingerprint", "runFingerprint")
                .doesNotContain("input", "output", "expected", "actual", "payload",
                        "fixtures", "graphDraft", "contract", "draftSet");
        assertThat(fieldNamesRecursively(delta))
                .doesNotContain("input", "output", "expected", "actual", "payload", "fixtures");
        assertThat(delta.path("required"))
                .extracting(JsonNode::asText)
                .contains("resetRequired");
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }

    private static Set<String> fieldNamesRecursively(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collectPropertyNames(node, names);
        return names;
    }

    private static void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            JsonNode properties = node.path("properties");
            if (properties.isObject()) properties.fieldNames().forEachRemaining(names::add);
            node.elements().forEachRemaining(child -> collectPropertyNames(child, names));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectPropertyNames(child, names));
        }
    }
}
