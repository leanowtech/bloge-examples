package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioImportProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freezesStrictPlanRequestResultAndPayloadFreeReceiptSchemas() throws Exception {
        JsonNode plan = schema("bloge-scenario-materialization-plan-v1.schema.json");
        JsonNode receipt = schema("bloge-scenario-materialization-receipt-v1.schema.json");
        JsonNode request = schema("bloge-scenario-import-materialization-request-v1.schema.json");
        JsonNode result = schema("bloge-scenario-import-materialization-result-v1.schema.json");

        assertThat(plan.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(receipt.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(result.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(fieldNames(plan.at("/$defs/source/properties")))
                .containsExactlyInAnyOrder(
                        "kind", "fingerprint", "encoding", "delimiter", "parser", "classification")
                .doesNotContain("text", "sourceText", "payload");
        assertThat(fieldNames(receipt.at("/$defs/row/properties")))
                .containsExactlyInAnyOrder(
                        "identityFingerprint", "rowFingerprint", "scenarioId", "status", "diagnosticCode")
                .doesNotContain("identity", "input", "output", "expected", "actual", "payload");
        assertThat(request.at("/properties/plan/$ref").asText())
                .isEqualTo("bloge-scenario-materialization-plan-v1.schema.json");
        assertThat(result.at("/properties/receipt/$ref").asText())
                .isEqualTo("bloge-scenario-materialization-receipt-v1.schema.json");
        assertThat(plan.at("/$defs/budget/properties/maxRows/maximum").asInt()).isEqualTo(500);
        assertThat(plan.at("/$defs/budget/properties/maxColumns/maximum").asInt()).isEqualTo(100);
        assertThat(plan.at("/$defs/budget/properties/maxBytes/maximum").asInt()).isEqualTo(1_048_576);
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
