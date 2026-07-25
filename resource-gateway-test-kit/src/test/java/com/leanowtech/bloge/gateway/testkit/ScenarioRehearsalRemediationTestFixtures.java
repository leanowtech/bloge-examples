package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ScenarioRehearsalRemediationTestFixtures {
    static final ObjectMapper JSON =
            new ObjectMapper();
    static final String REMEDIATION_ID =
            "scenario-remediation-" + "c".repeat(64);
    static final String PREDECESSOR_ID =
            "scenario-batch-" + "d".repeat(64);
    static final String SUCCESSOR_ID =
            "scenario-batch-" + "e".repeat(64);

    private ScenarioRehearsalRemediationTestFixtures() {
    }

    static Fixture submitted() {
        ObjectNode preview = JSON.createObjectNode();
        preview.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_V1);
        preview.put("previewRequestId", "preview-a");
        preview.put(
                "expectedWorkbookSeedFingerprint",
                fingerprint('a'));
        preview.put("strategy", "RERUN_EXACT");
        preview.putArray("replacements");
        preview.set("governanceTicketRef", ticket());
        preview.put(
                "reasonCode",
                "TRANSIENT_EXECUTION_RECHECK");

        ObjectNode successor = JSON.createObjectNode();
        successor.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_V1);
        successor.put("requestId", REMEDIATION_ID);
        ObjectNode entry =
                successor.putArray("entries").addObject();
        entry.put("entryId", "entry-a");
        entry.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "plan-a",
                        'b'));

        ObjectNode plan = JSON.createObjectNode();
        plan.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_PLAN_V1);
        plan.put("planFingerprint", "");
        plan.set("scope", scope());
        plan.put("remediationId", REMEDIATION_ID);
        plan.put("previewRequestId", "preview-a");
        plan.put("predecessorJobId", PREDECESSOR_ID);
        plan.put(
                "predecessorWorkbookSeedFingerprint",
                fingerprint('a'));
        plan.put(
                "predecessorEvidenceBundleFingerprint",
                fingerprint('f'));
        plan.put("predecessorStatus", "FAILED");
        plan.putArray("predecessorBlockers")
                .add("BATCH_ITEM_FAILED")
                .add("BATCH_STATUS_FAILED")
                .add("CHILD_WORKBOOK_BLOCKED");
        plan.put("strategy", "RERUN_EXACT");
        plan.put(
                "reasonCode",
                "TRANSIENT_EXECUTION_RECHECK");
        plan.putArray("replacements");
        plan.set("successorRequest", successor);
        plan.put(
                "successorRequestFingerprint",
                EvidenceVerificationSupport.sha256(
                        successor));
        plan.set("governanceTicketRef", ticket());
        ObjectNode policy =
                plan.putObject("approvalPolicy");
        policy.putArray("requiredRoles")
                .add("OWNER")
                .add("INDEPENDENT_REVIEWER");
        policy.put("minimumDistinctActors", 2);
        policy.put("serverPolicyGeneration", 1);
        policy.put(
                "serverPolicyFingerprint",
                fingerprint('1'));
        plan.put("generatedAt", "2026-07-25T10:00:00Z");
        plan.put("expiresAt", "2026-07-26T10:00:00Z");
        seal(plan, "planFingerprint");

        ObjectNode ownerCommand =
                approvalCommand(
                        plan,
                        "approve-owner-a",
                        0,
                        "OWNER");
        ObjectNode owner = approval(
                plan,
                "approve-owner-a",
                1,
                "",
                "OWNER",
                "owner-a",
                "2026-07-25T10:30:00Z");
        ObjectNode reviewer = approval(
                plan,
                "approve-reviewer-b",
                2,
                owner.path("approvalFingerprint")
                        .asText(),
                "INDEPENDENT_REVIEWER",
                "reviewer-b",
                "2026-07-25T10:45:00Z");

        ObjectNode submit = JSON.createObjectNode();
        submit.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_V1);
        submit.put("commandId", "submit-a");
        submit.put(
                "remediationPlanFingerprint",
                plan.path("planFingerprint").asText());
        submit.put("expectedApprovalGeneration", 2);
        submit.put(
                "expectedApprovalHeadFingerprint",
                reviewer.path("approvalFingerprint")
                        .asText());
        submit.put("reasonCode", "APPROVALS_COMPLETE");

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_V1);
        receipt.put("receiptFingerprint", "");
        receipt.put(
                "sourceCommandFingerprint",
                EvidenceVerificationSupport.sha256(
                        submit));
        receipt.set("scope", scope());
        receipt.put("remediationId", REMEDIATION_ID);
        receipt.put(
                "remediationPlanFingerprint",
                plan.path("planFingerprint").asText());
        receipt.put("predecessorJobId", PREDECESSOR_ID);
        receipt.put("successorJobId", SUCCESSOR_ID);
        receipt.put(
                "successorRequestFingerprint",
                plan.path(
                        "successorRequestFingerprint")
                        .asText());
        receipt.put("approvalGeneration", 2);
        receipt.put(
                "approvalHeadFingerprint",
                reviewer.path("approvalFingerprint")
                        .asText());
        receipt.put("acceptedBy", "owner-a");
        receipt.put("delegatedBy", "");
        receipt.put("acceptedAt", "2026-07-25T11:00:00Z");
        seal(receipt, "receiptFingerprint");

        ObjectNode lineage = JSON.createObjectNode();
        lineage.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_LINEAGE_V1);
        lineage.put("lineageFingerprint", "");
        lineage.put("state", "SUBMITTED");
        lineage.set("plan", plan);
        lineage.putArray("approvals")
                .add(owner)
                .add(reviewer);
        lineage.put("approvalGeneration", 2);
        lineage.put(
                "approvalHeadFingerprint",
                reviewer.path("approvalFingerprint")
                        .asText());
        lineage.set("receipt", receipt);
        seal(lineage, "lineageFingerprint");

        return new Fixture(
                preview,
                plan,
                ownerCommand,
                owner,
                submit,
                receipt,
                lineage);
    }

    private static ObjectNode approvalCommand(
            ObjectNode plan,
            String commandId,
            int generation,
            String role) {
        ObjectNode command = JSON.createObjectNode();
        command.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_V1);
        command.put("commandId", commandId);
        command.put(
                "remediationPlanFingerprint",
                plan.path("planFingerprint").asText());
        command.put(
                "expectedApprovalGeneration",
                generation);
        command.put("role", role);
        command.put("decision", "APPROVE");
        command.set("governanceTicketRef", ticket());
        command.put(
                "reasonCode",
                "APPROVED_AS_REVIEWED");
        return command;
    }

    private static ObjectNode approval(
            ObjectNode plan,
            String commandId,
            int generation,
            String previous,
            String role,
            String actor,
            String decidedAt) {
        ObjectNode command = approvalCommand(
                plan,
                commandId,
                generation - 1,
                role);
        ObjectNode approval = JSON.createObjectNode();
        approval.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_V1);
        approval.put("approvalFingerprint", "");
        approval.put(
                "sourceCommandFingerprint",
                EvidenceVerificationSupport.sha256(
                        command));
        approval.set("scope", scope());
        approval.put("remediationId", REMEDIATION_ID);
        approval.put(
                "remediationPlanFingerprint",
                plan.path("planFingerprint").asText());
        approval.put("generation", generation);
        approval.put(
                "previousApprovalFingerprint",
                previous);
        approval.put("role", role);
        approval.put("decision", "APPROVE");
        approval.set("governanceTicketRef", ticket());
        approval.put(
                "reasonCode",
                "APPROVED_AS_REVIEWED");
        approval.put("actorId", actor);
        approval.put("delegatedBy", "");
        approval.put("decidedAt", decidedAt);
        seal(approval, "approvalFingerprint");
        return approval;
    }

    private static ObjectNode scope() {
        ObjectNode scope = JSON.createObjectNode();
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "project-a");
        scope.put("environmentId", "test");
        scope.put("region", "sg");
        return scope;
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

    private static void seal(
            ObjectNode value,
            String field) {
        value.put(
                field,
                EvidenceVerificationSupport.sha256(
                        value));
    }

    private static String fingerprint(char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }

    record Fixture(
            ObjectNode preview,
            ObjectNode plan,
            ObjectNode approvalCommand,
            ObjectNode approval,
            ObjectNode submitCommand,
            ObjectNode receipt,
            ObjectNode lineage
    ) {
    }
}
