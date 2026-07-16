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
            assertConstant(definitions, "testExecutionResponseV1",
                    TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testExecutionResponseV2",
                    TestingProtocol.TEST_EXECUTION_RESPONSE_V2);
            assertConstant(definitions, "testEvidenceIntegrity",
                    TestingProtocol.TEST_EVIDENCE_INTEGRITY_V1);
            assertThat(definitions.at("/testExecutionResponse/oneOf")).hasSize(2);
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
            assertConstant(definitions, "testSuiteV2", TestingProtocol.TEST_SUITE_V2);
            assertConstant(definitions, "testSuiteRegistrationRequest",
                    TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
            assertConstant(definitions, "storedTestSuite", TestingProtocol.STORED_TEST_SUITE_V1);
            assertConstant(definitions, "testSuiteExecutionRequest",
                    TestingProtocol.TEST_SUITE_EXECUTION_REQUEST_V1);
            assertConstant(definitions, "testSuiteExecutionResponseV1",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V1);
            assertConstant(definitions, "testSuiteExecutionResponseV2",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V2);
            assertConstant(definitions, "testSuiteExecutionResponseV3",
                    TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3);
            assertThat(definitions.at("/testSuiteExecutionResponse/oneOf")).hasSize(3);
            assertConstant(definitions, "testSuiteRunEvidence",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testSuiteRunEvidenceV2",
                    TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2);
            assertConstant(definitions, "testSuiteRunAttestation",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1);
            assertConstant(definitions, "testSuiteRunAttestationV2",
                    TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V2);
            assertConstant(definitions, "testSuiteEvidenceBundleV1",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1);
            assertConstant(definitions, "testSuiteEvidenceBundleV2",
                    TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V2);
            assertThat(definitions.at("/testSuiteEvidenceBundle/oneOf")).hasSize(2);
            assertConstant(definitions, "evidenceVerificationKeySet",
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
            assertConstant(definitions, "testSuiteCatalogMaterialization",
                    TestingProtocol.TEST_SUITE_CATALOG_MATERIALIZATION_V1);
        }
        try (InputStream input = TestingProtocolTest.class.getResourceAsStream(
                TestingProtocol.EVIDENCE_TRUST_BUNDLE_SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode schema = new ObjectMapper().readTree(input);
            assertThat(schema.at("/properties/schemaVersion/const").asText())
                    .isEqualTo(TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1);
            assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                    .isEqualTo(TestingProtocol.EVIDENCE_KEY_SET_TRUST_PUBLICATION_V1);
            assertThat(schema.at("/properties/publications/maxItems").asInt()).isEqualTo(256);
        }
    }

    private static void assertConstant(JsonNode definitions, String definition, String expected) {
        assertThat(definitions.at("/" + definition + "/properties/schemaVersion/const").asText())
                .as(definition + " schema version")
                .isEqualTo(expected);
    }
}
