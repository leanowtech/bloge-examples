package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageGenerationProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freezesExplainableCoverageAndSourceBoundCandidateProtocols() throws Exception {
        JsonNode projection = schema("bloge-coverage-projection-v1.schema.json");
        JsonNode candidates = schema("bloge-coverage-candidate-set-v1.schema.json");

        assertThat(projection.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(candidates.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(projection.at("/properties/dimensions/prefixItems"))
                .extracting(item -> item.at("/allOf/1/properties/dimension/const").asText())
                .containsExactly("CASE", "CONTRACT", "DAG", "DEPENDENCY", "ASSERTION", "EVIDENCE");
        assertThat(projection.path("properties").has("score")).isFalse();
        assertThat(projection.path("properties").has("percentage")).isFalse();
        assertThat(projection.at("/$defs/fact/required"))
                .extracting(JsonNode::asText)
                .contains("factId", "coordinate", "coveredByCaseIds", "action");
        assertThat(projection.at("/$defs/generation/properties/kind/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("SET_INPUT", "DELETE_INPUT", "DEPENDENCY_BEHAVIOR", "ERROR_CONTRACT");

        assertThat(candidates.at("/$defs/source/required"))
                .extracting(JsonNode::asText)
                .contains("targetFingerprint", "contractFingerprint", "scenarioDraftSetId",
                        "scenarioDraftSetRevision", "coverageProjectionFingerprint");
        assertThat(candidates.at("/$defs/candidate/required"))
                .extracting(JsonNode::asText)
                .contains("generatorId", "generatorVersion", "seed", "source", "rationale",
                        "contributionFactIds", "workUnits", "expectedBehavior", "promotionEligible",
                        "proposal");
        assertThat(candidates.at("/$defs/candidate/properties/promotionEligible/const").asBoolean(true))
                .isFalse();
        assertThat(candidates.at("/$defs/candidate/properties/proposal/$ref").asText())
                .isEqualTo("bloge-scenario-draft-set-v1.schema.json#/$defs/scenario");
        assertThat(candidates.at("/$defs/candidate/properties/expectedBehavior/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("READY", "NEEDS_AUTHOR", "BLOCKED");
        assertThat(candidates.at("/$defs/budget/properties/maxCandidates/maximum").asInt())
                .isEqualTo(500);
        assertThat(candidates.at("/$defs/budget/properties/maxWorkUnits/maximum").asInt())
                .isEqualTo(10_000);
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }
}
