package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioHandlingAssertionResultSchemaTest {
    private static final int MAXIMUM_RESULT_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesThePackagedStrictPayloadFreeResultSchema() {
        ObjectNode result = result();

        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                result,
                CapabilityMirrorProtocol
                        .SCENARIO_HANDLING_ASSERTION_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownFieldsUnknownReasonsAndInconsistentOutcomes() {
        ObjectNode unknownField = result();
        unknownField.put("response", "must-not-leak");
        ObjectNode unknownReason = result();
        unknownReason.put("reasonCode", "USER_DEFINED_REASON");
        ObjectNode inconsistentOutcome = result();
        inconsistentOutcome.put("outcome", "PASS");

        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(
                unknownField,
                CapabilityMirrorProtocol
                        .SCENARIO_HANDLING_ASSERTION_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(
                unknownReason,
                CapabilityMirrorProtocol
                        .SCENARIO_HANDLING_ASSERTION_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(
                inconsistentOutcome,
                CapabilityMirrorProtocol
                        .SCENARIO_HANDLING_ASSERTION_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID");
    }

    private static ObjectNode result() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioHandlingAssertionResult.v1");
        value.put("resultFingerprint", "");
        value.put("runId", "run-refund-1");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("planFingerprint", fingerprint('b'));
        ObjectNode ref = value.putObject("assertionRef");
        ref.put("kind", "CASE_HANDLING_ASSERTION");
        ref.put("id", "refund-output");
        ref.put("revision", 1);
        ref.put("fingerprint", fingerprint('c'));
        value.put("observation", "GRAPH_OUTPUT_SCHEMA");
        value.put("outcome", "INDETERMINATE");
        value.put("severity", "BLOCKER");
        value.put(
                "governanceCode",
                "RG.MIRROR.SCENARIO.REFUND_OUTPUT_INVALID");
        value.put(
                "reasonCode",
                "ASSERTION_EVIDENCE_FACT_UNAVAILABLE");
        ObjectNode observed = value.putObject("observed");
        ArrayNode statuses = observed.putArray("statuses");
        statuses.add("PASSED");
        observed.putArray("errorCodes");
        observed.putArray("fingerprints");
        ArrayNode sources = observed.putArray("sources");
        sources.add("SCENARIO_ASSERTION_EVALUATOR_V1");
        ArrayNode limitations = observed.putArray("limitations");
        limitations.add("MISSING_GRAPH_OUTPUT_SCHEMA_FACT");
        value.put(
                "resultFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        value, MAXIMUM_RESULT_BYTES));
        return value;
    }

    private static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }
}
