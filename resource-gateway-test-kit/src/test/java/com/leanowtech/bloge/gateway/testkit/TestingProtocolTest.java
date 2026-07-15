package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestingProtocolTest {

    @Test
    void packagedSchemaTracksEveryTestKitWireVersion() throws Exception {
        try (InputStream input = TestingProtocolTest.class.getResourceAsStream(
                TestingProtocol.SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode definitions = new ObjectMapper().readTree(input).path("$defs");

            assertConstant(definitions, "testExecutionRequest", TestingProtocol.TEST_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testExecutionResponse", TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testExecutionBatchRequest",
                    TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
            assertConstant(definitions, "testExecutionBatchResponse",
                    TestingProtocol.TEST_EXECUTION_BATCH_RESPONSE_V1);
            assertConstant(definitions, "fixtureBundle", TestingProtocol.FIXTURE_BUNDLE_V1);
            assertConstant(definitions, "fixtureRule", TestingProtocol.FIXTURE_RULE_V1);
            assertConstant(definitions, "fixtureBundleRegistrationRequest",
                    TestingProtocol.FIXTURE_REGISTRATION_REQUEST_V1);
            assertConstant(definitions, "storedFixtureBundle", TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
            assertConstant(definitions, "testGraphTargetDescriptor",
                    TestingProtocol.GRAPH_TARGET_DESCRIPTOR_V1);
            assertConstant(definitions, "testOperatorExecutionRequest",
                    TestingProtocol.OPERATOR_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testOperatorTargetDescriptor",
                    TestingProtocol.OPERATOR_TARGET_DESCRIPTOR_V2);
            assertThat(TestingProtocol.OPERATOR_TARGET_DESCRIPTOR_V1)
                    .isEqualTo("bloge.testOperatorTargetDescriptor.v1");
            assertConstant(definitions, "testRunEvidence", TestingProtocol.TEST_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testSuite", TestingProtocol.TEST_SUITE_V1);
            assertConstant(definitions, "testSuiteRegistrationRequest",
                    TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
            assertConstant(definitions, "storedTestSuite", TestingProtocol.STORED_TEST_SUITE_V1);
            assertConstant(definitions, "testSuiteExecutionRequest",
                    TestingProtocol.TEST_SUITE_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testSuiteExecutionResponse",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testSuiteRunEvidence",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testSuiteCatalogMaterialization",
                    TestingProtocol.TEST_SUITE_CATALOG_MATERIALIZATION_V1);
        }
    }

    private static void assertConstant(JsonNode definitions, String definition, String expected) {
        assertThat(definitions.at("/" + definition + "/properties/schemaVersion/const").asText())
                .as(definition + " schema version")
                .isEqualTo(expected);
    }
}
