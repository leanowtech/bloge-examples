package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            assertConstant(definitions, "testRunEvidenceV1", TestingProtocol.TEST_RUN_EVIDENCE_V1);
            assertConstant(definitions, "testRunEvidenceV2", TestingProtocol.TEST_RUN_EVIDENCE_V2);
            assertConstant(definitions, "executionServiceStateSnapshot",
                    TestingProtocol.EXECUTION_SERVICE_STATE_SNAPSHOT_V1);
            assertConstant(definitions, "durableTestWorkerAcquisitionRequest",
                    TestingProtocol.DURABLE_WORKER_ACQUISITION_REQUEST_V1);
            assertConstant(definitions, "durableTestWorkerAcquisitionResponse",
                    TestingProtocol.DURABLE_WORKER_ACQUISITION_RESPONSE_V1);
            assertConstant(definitions, "durableTestRecoveryStepRequest",
                    TestingProtocol.DURABLE_RECOVERY_STEP_REQUEST_V1);
            assertConstant(definitions, "durableTestRecoveryStepResponse",
                    TestingProtocol.DURABLE_RECOVERY_STEP_RESPONSE_V1);
            assertThat(definitions.at("/testRunEvidence/oneOf")).hasSize(2);
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

    @Test
    void packagedSchemaEnforcesExecutionServiceRestoreEligibilityInvariant() throws Exception {
        String value = """
                {
                  "schemaVersion":"bloge.executionServiceStateSnapshot.v1",
                  "planFingerprint":"sha256:%s",
                  "bindingSetFingerprint":"sha256:%s",
                  "logicalTime":null,
                  "randomScopeCursors":{},
                  "uuidScopeCursors":{},
                  "usages":[],
                  "restorable":true,
                  "restoreGaps":[],
                  "snapshotFingerprint":"sha256:%s"
                }
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));
        ObjectMapper mapper = new ObjectMapper();

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                mapper.readTree(value), "executionServiceStateSnapshot"));

        JsonNode invalid = mapper.readTree(value);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid)
                .putArray("restoreGaps").add("RANDOM requires a seed.");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                invalid, "executionServiceStateSnapshot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionServiceStateSnapshot")
                .hasMessageNotContaining("RANDOM requires");
    }

    @Test
    void packagedSchemaEnforcesMutuallyExclusiveWorkerAcquisitionOutcomes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode noWork = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestWorkerAcquisitionResponse.v1",
                  "outcome":"NO_WORK",
                  "observedAt":"2026-07-17T00:00:00Z",
                  "assignment":null,
                  "idempotentReplay":false
                }
                """);
        JsonNode acquired = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestWorkerAcquisitionResponse.v1",
                  "outcome":"ACQUIRED",
                  "observedAt":"2026-07-17T00:00:00Z",
                  "assignment":{
                    "runId":"run-a",
                    "status":"RESUMING",
                    "ownerId":"worker-a",
                    "leaseEpoch":2,
                    "revision":3,
                    "leaseExpiresAt":"2026-07-17T00:02:00Z",
                    "checkpointFingerprint":"sha256:%s",
                    "target":{"kind":"GRAPH","id":"graph-a","fingerprint":"sha256:%s"}
                  },
                  "idempotentReplay":false
                }
                """.formatted("a".repeat(64), "b".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                noWork, "durableTestWorkerAcquisitionResponse"));
        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                acquired, "durableTestWorkerAcquisitionResponse"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) noWork)
                .set("assignment", acquired.path("assignment"));
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                noWork, "durableTestWorkerAcquisitionResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestWorkerAcquisitionResponse")
                .hasMessageNotContaining("run-a");
    }

    @Test
    void packagedSchemaBindsRecoveryStepOutcomeToItsTerminalReceipt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode suspended = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoveryStepResponse.v1",
                  "runId":"run-a",
                  "outcome":"SUSPENDED",
                  "status":"SUSPENDED",
                  "ownerId":"worker-a",
                  "leaseEpoch":2,
                  "revision":3,
                  "observedAt":"2026-07-17T00:00:00Z",
                  "checkpointFingerprint":"sha256:%s",
                  "boundary":{
                    "nodeId":"approval-2",
                    "boundaryType":"SUSPEND",
                    "boundarySequence":4,
                    "stateVersion":3
                  },
                  "terminal":null,
                  "idempotentReplay":false
                }
                """.formatted("a".repeat(64)));
        JsonNode completed = mapper.readTree("""
                {
                  "schemaVersion":"bloge.durableTestRecoveryStepResponse.v1",
                  "runId":"run-a",
                  "outcome":"COMPLETED",
                  "status":"TERMINAL",
                  "ownerId":"worker-a",
                  "leaseEpoch":2,
                  "revision":4,
                  "observedAt":"2026-07-17T00:01:00Z",
                  "checkpointFingerprint":"sha256:%s",
                  "boundary":{
                    "nodeId":"complete",
                    "boundaryType":"NODE_BOUNDARY",
                    "boundarySequence":5,
                    "stateVersion":4
                  },
                  "terminal":{
                    "executionOutcome":"COMPLETED",
                    "completedAt":"2026-07-17T00:01:00Z",
                    "receiptFingerprint":"sha256:%s",
                    "evidenceStatus":"EVIDENCE_INCOMPLETE",
                    "evidenceGapCodes":["PRE_CHECKPOINT_TRACE_UNAVAILABLE"]
                  },
                  "idempotentReplay":false
                }
                """.formatted("b".repeat(64), "c".repeat(64)));

        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                suspended, "durableTestRecoveryStepResponse"));
        assertThatNoException().isThrownBy(() -> TestingProtocolSchemaValidator.require(
                completed, "durableTestRecoveryStepResponse"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) completed.path("terminal"))
                .put("executionOutcome", "FAILED");
        assertThatThrownBy(() -> TestingProtocolSchemaValidator.require(
                completed, "durableTestRecoveryStepResponse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durableTestRecoveryStepResponse")
                .hasMessageNotContaining("run-a");
    }

    private static void assertConstant(JsonNode definitions, String definition, String expected) {
        assertThat(definitions.at("/" + definition + "/properties/schemaVersion/const").asText())
                .as(definition + " schema version")
                .isEqualTo(expected);
    }
}
