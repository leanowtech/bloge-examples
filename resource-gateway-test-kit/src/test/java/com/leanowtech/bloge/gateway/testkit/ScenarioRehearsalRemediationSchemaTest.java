package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalRemediationSchemaTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void validatesCompleteReviewedRemediationProtocol() {
        assertValid(
                preview(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_SCHEMA_RESOURCE);
        assertValid(
                plan(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PLAN_SCHEMA_RESOURCE);
        assertValid(
                approvalCommand(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_SCHEMA_RESOURCE);
        assertValid(
                approval(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_SCHEMA_RESOURCE);
        assertValid(
                submit(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_SCHEMA_RESOURCE);
        assertValid(
                receipt(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_SCHEMA_RESOURCE);
    }

    @Test
    void rejectsPolicyWeakeningPayloadLeakAndBrokenApprovalChain() {
        ObjectNode leaked = preview();
        leaked.putObject("patch")
                .put("assertionSeverity", "WARNING");
        ObjectNode rerunWithReplacement = preview();
        rerunWithReplacement.put("strategy", "RERUN_EXACT");
        rerunWithReplacement.put(
                "reasonCode",
                "TRANSIENT_EXECUTION_RECHECK");
        ObjectNode weakPolicy = plan();
        ObjectNode approvalPolicy =
                (ObjectNode) weakPolicy.path("approvalPolicy");
        approvalPolicy.putArray("requiredRoles")
                .add("OWNER");
        approvalPolicy.put("minimumDistinctActors", 1);
        ObjectNode brokenChain = approval();
        brokenChain.put("generation", 2);
        brokenChain.put("previousApprovalFingerprint", "");
        ObjectNode underApproved = submit();
        underApproved.put("expectedApprovalGeneration", 1);

        assertInvalid(
                leaked,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_SCHEMA_RESOURCE);
        assertInvalid(
                rerunWithReplacement,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_SCHEMA_RESOURCE);
        assertInvalid(
                weakPolicy,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PLAN_SCHEMA_RESOURCE);
        assertInvalid(
                brokenChain,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_SCHEMA_RESOURCE);
        assertInvalid(
                underApproved,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_SCHEMA_RESOURCE);
    }

    private static ObjectNode preview() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_V1);
        value.put("previewRequestId", "preview-a");
        value.put(
                "expectedWorkbookSeedFingerprint",
                fingerprint('a'));
        value.put("strategy", "REPLACE_COMPILED_PLANS");
        value.putArray("replacements")
                .add(replacement());
        value.set("governanceTicketRef", ticket());
        value.put("reasonCode", "SCENARIO_REVISION");
        return value;
    }

    private static ObjectNode plan() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PLAN_V1);
        value.put("planFingerprint", fingerprint('b'));
        value.set("scope", scope());
        value.put(
                "remediationId",
                "scenario-remediation-" + "c".repeat(64));
        value.put("previewRequestId", "preview-a");
        value.put(
                "predecessorJobId",
                "scenario-batch-" + "d".repeat(64));
        value.put(
                "predecessorWorkbookSeedFingerprint",
                fingerprint('a'));
        value.put(
                "predecessorEvidenceBundleFingerprint",
                fingerprint('e'));
        value.put("predecessorStatus", "FAILED");
        value.putArray("predecessorBlockers")
                .add("BLOCKER_ASSERTION_FAILED")
                .add("REHEARSAL_FAILED");
        value.put("strategy", "REPLACE_COMPILED_PLANS");
        value.put("reasonCode", "SCENARIO_REVISION");
        value.putArray("replacements")
                .add(replacement());
        ObjectNode successor = value.putObject("successorRequest");
        successor.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_V1);
        successor.put(
                "requestId",
                "scenario-remediation-" + "c".repeat(64));
        ObjectNode entry =
                successor.putArray("entries").addObject();
        entry.put("entryId", "entry-a");
        entry.set(
                "compiledPlanRef",
                compiledPlan("plan-new", 'c'));
        value.put(
                "successorRequestFingerprint",
                fingerprint('f'));
        value.set("governanceTicketRef", ticket());
        ObjectNode policy = value.putObject("approvalPolicy");
        policy.putArray("requiredRoles")
                .add("OWNER")
                .add("INDEPENDENT_REVIEWER");
        policy.put("minimumDistinctActors", 2);
        policy.put("serverPolicyGeneration", 7);
        policy.put(
                "serverPolicyFingerprint",
                fingerprint('7'));
        value.put("generatedAt", "2026-07-25T10:00:00Z");
        value.put("expiresAt", "2026-07-26T10:00:00Z");
        return value;
    }

    private static ObjectNode approvalCommand() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_V1);
        value.put("commandId", "approve-owner-a");
        value.put(
                "remediationPlanFingerprint",
                fingerprint('b'));
        value.put("expectedApprovalGeneration", 0);
        value.put("role", "OWNER");
        value.put("decision", "APPROVE");
        value.set("governanceTicketRef", ticket());
        value.put("reasonCode", "APPROVED_AS_REVIEWED");
        return value;
    }

    private static ObjectNode approval() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_V1);
        value.put("approvalFingerprint", fingerprint('1'));
        value.put("sourceCommandFingerprint", fingerprint('2'));
        value.set("scope", scope());
        value.put(
                "remediationId",
                "scenario-remediation-" + "c".repeat(64));
        value.put(
                "remediationPlanFingerprint",
                fingerprint('b'));
        value.put("generation", 1);
        value.put("previousApprovalFingerprint", "");
        value.put("role", "OWNER");
        value.put("decision", "APPROVE");
        value.set("governanceTicketRef", ticket());
        value.put("reasonCode", "APPROVED_AS_REVIEWED");
        value.put("actorId", "owner-a");
        value.put("delegatedBy", "");
        value.put("decidedAt", "2026-07-25T10:30:00Z");
        return value;
    }

    private static ObjectNode submit() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_V1);
        value.put("commandId", "submit-a");
        value.put(
                "remediationPlanFingerprint",
                fingerprint('b'));
        value.put("expectedApprovalGeneration", 2);
        value.put(
                "expectedApprovalHeadFingerprint",
                fingerprint('3'));
        value.put("reasonCode", "APPROVALS_COMPLETE");
        return value;
    }

    private static ObjectNode receipt() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_V1);
        value.put("receiptFingerprint", fingerprint('4'));
        value.put("sourceCommandFingerprint", fingerprint('5'));
        value.set("scope", scope());
        value.put(
                "remediationId",
                "scenario-remediation-" + "c".repeat(64));
        value.put(
                "remediationPlanFingerprint",
                fingerprint('b'));
        value.put(
                "predecessorJobId",
                "scenario-batch-" + "d".repeat(64));
        value.put(
                "successorJobId",
                "scenario-batch-" + "e".repeat(64));
        value.put(
                "successorRequestFingerprint",
                fingerprint('f'));
        value.put("approvalGeneration", 2);
        value.put(
                "approvalHeadFingerprint",
                fingerprint('3'));
        value.put("acceptedBy", "owner-a");
        value.put("delegatedBy", "");
        value.put("acceptedAt", "2026-07-25T11:00:00Z");
        return value;
    }

    private static ObjectNode replacement() {
        ObjectNode value = JSON.createObjectNode();
        value.put("entryIndex", 0);
        value.put("entryId", "entry-a");
        value.set(
                "expectedCompiledPlanRef",
                compiledPlan("plan-old", 'f'));
        value.set(
                "replacementCompiledPlanRef",
                compiledPlan("plan-new", 'c'));
        return value;
    }

    private static ObjectNode compiledPlan(
            String id,
            char fingerprint) {
        return ref(
                "COMPILED_REHEARSAL_PLAN",
                id,
                fingerprint);
    }

    private static ObjectNode ticket() {
        return ref(
                "GOVERNANCE_REVIEW_TICKET",
                "ticket-a",
                '6');
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put(
                "fingerprint",
                fingerprint(fingerprint));
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "project-a");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertValid(
            ObjectNode value,
            String schema) {
        assertThatCode(() ->
                CapabilityMirrorSchemaValidator.require(
                        value,
                        schema,
                        "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_INVALID"))
                .doesNotThrowAnyException();
    }

    private static void assertInvalid(
            ObjectNode value,
            String schema) {
        assertThatThrownBy(() ->
                CapabilityMirrorSchemaValidator.require(
                        value,
                        schema,
                        "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_INVALID");
    }
}
