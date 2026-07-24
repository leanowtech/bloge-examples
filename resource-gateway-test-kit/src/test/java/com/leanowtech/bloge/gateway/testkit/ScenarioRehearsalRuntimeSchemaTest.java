package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalRuntimeSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesStrictExecutionCaseAndAggregateProtocols() {
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                request(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_EXECUTION_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_EXECUTION_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                caseResult(),
                CapabilityMirrorProtocol
                        .SCENARIO_CASE_REHEARSAL_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_CASE_RESULT_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                result(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RESULT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_RESULT_INVALID"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRuntimeOverridesFalsePassAndUnknownAggregateFields() {
        ObjectNode override = request();
        override.putObject("context").put("customerId", "smuggled");
        ObjectNode falsePass = caseResult();
        falsePass.put("outcome", "PASS");
        falsePass.put("diagnosticCode", "");
        ObjectNode unknown = result();
        unknown.put("input", "must-not-leak");

        assertInvalid(
                override,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_EXECUTION_REQUEST_SCHEMA_RESOURCE);
        assertInvalid(
                falsePass,
                CapabilityMirrorProtocol
                        .SCENARIO_CASE_REHEARSAL_RESULT_SCHEMA_RESOURCE);
        assertInvalid(
                unknown,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RESULT_SCHEMA_RESOURCE);
    }

    private static ObjectNode request() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalExecutionRequest.v1");
        value.put("requestId", "scenario-request-1");
        value.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "support-rehearsal-compiled", 'a'));
        return value;
    }

    private static ObjectNode caseResult() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioCaseRehearsalResult.v1");
        value.put("resultFingerprint", fingerprint('b'));
        value.put("caseIndex", 0);
        value.set(
                "scenarioCaseRef",
                ref("SCENARIO_CASE", "support-golden", 'c'));
        value.put("caseType", "GOLDEN");
        value.set(
                "testSuiteRef",
                ref("TEST_SUITE", "support-suite", 'd'));
        value.put("testCaseId", "golden");
        value.set(
                "mirrorPlanRef",
                ref("MIRROR_PLAN", "support-plan", 'e'));
        value.set(
                "fixtureBundleRef",
                ref("FIXTURE_BUNDLE", "support-fixture", 'f'));
        value.putNull("sessionCheckpointRef");
        value.put("childRequestId", "scenario-request-1:case:000");
        value.put("outcome", "INDETERMINATE");
        value.put("runId", "");
        value.put("evidenceBundleFingerprint", "");
        value.putNull("evidenceStatus");
        value.putNull("evidenceClass");
        value.putArray("assertionResults");
        value.put(
                "diagnosticCode",
                "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE");
        value.put("startedAt", "2026-07-24T08:00:00Z");
        value.put("completedAt", "2026-07-24T08:00:01Z");
        return value;
    }

    private static ObjectNode result() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalResult.v1");
        value.put("resultFingerprint", fingerprint('0'));
        value.put("requestId", "scenario-request-1");
        value.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "support-rehearsal-compiled", 'a'));
        ObjectNode scope = value.putObject("scope");
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "support");
        scope.put("environmentId", "test");
        scope.put("region", "sg");
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "support", '1'));
        value.put("outcome", "INDETERMINATE");
        ArrayNode cases = value.putArray("caseResults");
        cases.add(caseResult());
        ObjectNode summary = value.putObject("summary");
        summary.put("totalCases", 1);
        summary.put("passedCases", 0);
        summary.put("failedCases", 0);
        summary.put("indeterminateCases", 1);
        summary.put("assertionResults", 0);
        summary.put("blockerFailures", 0);
        summary.put("blockerIndeterminate", 0);
        summary.put("warningFailures", 0);
        summary.put("warningIndeterminate", 0);
        value.put("startedAt", "2026-07-24T08:00:00Z");
        value.put("completedAt", "2026-07-24T08:00:01Z");
        return value;
    }

    private static ObjectNode ref(
            String kind, String id, char material) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint(material));
        return value;
    }

    private static void assertInvalid(
            ObjectNode value, String resource) {
        assertThatThrownBy(() ->
                CapabilityMirrorSchemaValidator.require(
                        value, resource,
                        "RG.MIRROR.CLIENT.SCENARIO_RUNTIME_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SCENARIO_RUNTIME_INVALID");
    }

    private static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }
}
