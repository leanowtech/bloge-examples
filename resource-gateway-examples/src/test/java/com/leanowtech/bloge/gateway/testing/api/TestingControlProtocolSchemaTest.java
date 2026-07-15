package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TestingControlProtocolSchemaTest {

    @Test
    void schemaBundleTracksJavaProtocolVersionsAndAllTenTerminalStates() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json")));

        assertThat(schema.at("/$defs/testExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionApiRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionResponse/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionApiResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionBatchRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionBatchRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testExecutionBatchResponse/properties/schemaVersion/const").asText())
                .isEqualTo(TestExecutionBatchResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/fixtureBundleRegistrationRequest/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureBundleRegistrationRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/storedFixtureBundle/properties/schemaVersion/const").asText())
                .isEqualTo(StoredFixtureBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testGraphTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestGraphTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorExecutionRequest/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorExecutionApiRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testOperatorTargetDescriptor/properties/schemaVersion/const").asText())
                .isEqualTo(TestOperatorTargetDescriptor.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/fixtureBundle/properties/schemaVersion/const").asText())
                .isEqualTo(FixtureBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/effectivePlan/properties/schemaVersion/const").asText())
                .isEqualTo(EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/testRunEvidence/properties/schemaVersion/const").asText())
                .isEqualTo(TestRunEvidence.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/runStatus/enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(TestRunEvidence.Status.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(TestRunEvidence.Status.values()).hasSize(10);
    }

    @Test
    void publicRequestRequiresExactlyOneFixtureSourceAndRejectsUnknownFields() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json")));
        JsonNode request = schema.at("/$defs/testExecutionRequest");

        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("oneOf")).hasSize(2);
        assertThat(request.path("required")).extracting(JsonNode::asText)
                .contains("target", "executionPurpose", "fixtureBundle", "fixtureBundleRef", "verbosity");
    }

    @Test
    void schemaBundleCoversEveryPublicTestingEndpointPayload() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.has("fixtureBundleRegistrationRequest")).isTrue();
        assertThat(definitions.has("storedFixtureBundle")).isTrue();
        assertThat(definitions.at("/fixtureBundleRegistrationRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/target");
        assertThat(definitions.at("/testExecutionRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/graphTarget");
        assertThat(definitions.at("/testOperatorExecutionRequest/properties/target/$ref").asText())
                .isEqualTo("#/$defs/operatorTarget");
        assertThat(definitions.at("/testOperatorExecutionRequest/properties/executionPurpose/const").asText())
                .isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(definitions.at("/testOperatorTargetDescriptor/required"))
                .extracting(JsonNode::asText)
                .contains("implementationFingerprint", "runtimeBindingStateFingerprint",
                        "schemaFingerprint", "composabilityFingerprint", "composabilityManifest",
                        "testabilityClass", "certificationEligible");
        assertThat(definitions.at("/operatorComposabilityManifest/properties/dependencyMode/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("NONE", "DECLARED", "OPAQUE");
        assertThat(definitions.at("/operatorComposabilityManifest/properties/executionServices/items/enum"))
                .extracting(JsonNode::asText)
                .contains("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG");
        assertThat(definitions.at("/testExecutionBatchRequest/properties/executions/maxItems").asInt())
                .isEqualTo(TestExecutionBatchRequest.MAX_EXECUTIONS);
        assertThat(definitions.at("/testExecutionBatchResponse/properties/executions/items/$ref").asText())
                .isEqualTo("#/$defs/testExecutionResponse");
        assertThat(definitions.at("/effectivePlan/additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.at("/testRunEvidence/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void evidenceSchemaFreezesOccurrenceAttemptAndEdgeCoordinates() throws Exception {
        JsonNode definitions = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-testing", "testing-control-plane-v1.schema.json"))).path("$defs");

        assertThat(definitions.at("/testRunEvidence/properties/nodeTrace/items/$ref").asText())
                .isEqualTo("#/$defs/nodeTrace");
        assertThat(definitions.at("/testRunEvidence/properties/edgeTrace/items/$ref").asText())
                .isEqualTo("#/$defs/edgeTrace");
        assertThat(definitions.at("/nodeTrace/required")).extracting(JsonNode::asText)
                .contains("invocationSiteId", "graphPath", "correlationKey", "occurrence",
                        "graphOccurrence", "attempts");
        assertThat(definitions.at("/nodeTrace/properties/attempts/items/$ref").asText())
                .isEqualTo("#/$defs/attemptTrace");
        assertThat(definitions.at("/attemptTrace/properties/attempt/minimum").asInt()).isZero();
        assertThat(definitions.at("/edgeTrace/required")).extracting(JsonNode::asText)
                .contains("graphPath", "correlationKey", "graphOccurrence",
                        "fromInvocationSiteId", "toInvocationSiteId");
        assertThat(definitions.at("/edgeTrace/properties/status/enum")).extracting(JsonNode::asText)
                .containsExactly("TRANSFERRED", "SKIPPED", "NOT_TRANSFERRED");
    }
}
