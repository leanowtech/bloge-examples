package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ScenarioRehearsalRemediationComparisonTestFixtures {
    private static final ObjectMapper JSON =
            new ObjectMapper();
    private static final String PREDECESSOR_RUN =
            "scenario-" + "1".repeat(64);
    private static final String SUCCESSOR_RUN =
            "scenario-" + "2".repeat(64);

    private ScenarioRehearsalRemediationComparisonTestFixtures() {
    }

    static Fixture resolved() {
        ScenarioRehearsalRemediationTestFixtures.Fixture
                remediation =
                ScenarioRehearsalRemediationTestFixtures
                        .submitted();
        ObjectNode predecessor = workbook(
                ScenarioRehearsalRemediationTestFixtures
                        .PREDECESSOR_ID,
                fingerprint('a'),
                fingerprint('9'),
                fingerprint('f'),
                "FAILED",
                "FAILED",
                PREDECESSOR_RUN,
                "FAIL",
                false);
        ObjectNode successor = workbook(
                ScenarioRehearsalRemediationTestFixtures
                        .SUCCESSOR_ID,
                fingerprint('7'),
                remediation.plan().path(
                        "successorRequestFingerprint")
                        .asText(),
                fingerprint('8'),
                "SUCCEEDED",
                "PASSED",
                SUCCESSOR_RUN,
                "PASS",
                true);
        ObjectNode comparison = comparison(
                remediation, predecessor, successor);
        return new Fixture(
                remediation.lineage(),
                predecessor,
                successor,
                comparison);
    }

    private static ObjectNode workbook(
            String jobId,
            String seedFingerprint,
            String requestFingerprint,
            String evidenceFingerprint,
            String batchStatus,
            String entryStatus,
            String runId,
            String outcome,
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_V1);
        value.put(
                "seedFingerprint",
                seedFingerprint);
        value.set("scope", scope());
        value.put("jobId", jobId);
        value.put("requestId", "batch-request-a");
        value.put(
                "requestFingerprint",
                requestFingerprint);
        value.put(
                "manifestFingerprint",
                fingerprint('3'));
        value.put(
                "terminalJobFingerprint",
                fingerprint('4'));
        value.put(
                "evidenceBundleFingerprint",
                evidenceFingerprint);
        value.put(
                "evidenceIndexFingerprint",
                fingerprint('5'));
        value.put("evidenceKeyId", "workbook-key-a");
        value.set(
                "workbookSeal",
                seal(fingerprint('6')));
        value.set(
                "retentionProof",
                retention(
                        jobId,
                        evidenceFingerprint));
        value.put("status", batchStatus);
        value.set(
                "summary",
                batchSummary(passing));
        value.putArray("entries")
                .add(entry(
                        entryStatus,
                        runId,
                        outcome,
                        passing));
        value.put("gateReady", passing);
        ArrayNode blockers =
                value.putArray("blockers");
        if (!passing) {
            blockers.add("BATCH_ITEM_FAILED");
            blockers.add("BATCH_STATUS_FAILED");
            blockers.add("CHILD_WORKBOOK_BLOCKED");
        }
        return value;
    }

    private static ObjectNode entry(
            String status,
            String runId,
            String outcome,
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put("entryIndex", 0);
        value.put("entryId", "entry-a");
        value.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "plan-a",
                        'b'));
        value.put("childRequestId", "child-a");
        value.put("expectedRunId", runId);
        value.put("status", status);
        value.put("attemptCount", 1);
        value.put("runId", runId);
        value.put(
                "childEvidenceBundleFingerprint",
                fingerprint('d'));
        value.put(
                "childWorkbookSeedFingerprint",
                fingerprint('e'));
        value.put("failureCode", "");
        value.set(
                "childWorkbook",
                childWorkbook(
                        runId, outcome, passing));
        return value;
    }

    private static ObjectNode childWorkbook(
            String runId,
            String outcome,
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_WORKBOOK_SEED_V1);
        value.put(
                "seedFingerprint",
                fingerprint('e'));
        value.put("runId", runId);
        value.put("requestId", "child-a");
        value.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "plan-a",
                        'b'));
        value.set(
                "scenarioPackRef",
                ref("SCENARIO_PACK", "pack-a", 'c'));
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "capability-a", 'd'));
        value.put(
                "evidenceBundleFingerprint",
                fingerprint('d'));
        value.put(
                "resultFingerprint",
                fingerprint('f'));
        value.put("evidenceKeyId", "evidence-key-a");
        value.put(
                "retentionProofFingerprint",
                fingerprint('1'));
        value.put("outcome", outcome);
        value.set(
                "summary",
                correctnessSummary(passing));
        value.put("gateReady", passing);
        ArrayNode blockers =
                value.putArray("blockers");
        if (!passing) {
            blockers.add("BLOCKER_ASSERTION_FAILED");
        }
        return value;
    }

    private static ObjectNode comparison(
            ScenarioRehearsalRemediationTestFixtures
                    .Fixture remediation,
            ObjectNode predecessor,
            ObjectNode successor) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_V1);
        value.put("comparisonFingerprint", "");
        value.set("scope", scope());
        value.put(
                "remediationId",
                ScenarioRehearsalRemediationTestFixtures
                        .REMEDIATION_ID);
        value.put(
                "lineageFingerprint",
                remediation.lineage().path(
                        "lineageFingerprint").asText());
        value.put(
                "remediationPlanFingerprint",
                remediation.plan().path(
                        "planFingerprint").asText());
        value.put(
                "receiptFingerprint",
                remediation.receipt().path(
                        "receiptFingerprint").asText());
        value.set(
                "predecessor",
                workbookSnapshot(
                        predecessor, false));
        value.set(
                "successor",
                workbookSnapshot(
                        successor, true));
        value.put("gateTransition", "RESOLVED");
        value.putArray("resolvedBlockers")
                .add("BATCH_ITEM_FAILED")
                .add("BATCH_STATUS_FAILED")
                .add("CHILD_WORKBOOK_BLOCKED");
        value.putArray("remainingBlockers");
        value.putArray("introducedBlockers");
        value.putArray("entries")
                .add(entryComparison(
                        predecessor.path("entries")
                                .get(0),
                        successor.path("entries")
                                .get(0)));
        value.put(
                "comparisonFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                value,
                                ScenarioRehearsalRemediationComparisonVerifier
                                        .MAXIMUM_COMPARISON_BYTES));
        return value;
    }

    private static ObjectNode workbookSnapshot(
            ObjectNode workbook,
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "workbookSchemaVersion",
                workbook.path("schemaVersion")
                        .asText());
        value.set(
                "scope",
                workbook.path("scope").deepCopy());
        value.put(
                "jobId",
                workbook.path("jobId").asText());
        value.put(
                "seedFingerprint",
                workbook.path("seedFingerprint")
                        .asText());
        value.put(
                "requestFingerprint",
                workbook.path("requestFingerprint")
                        .asText());
        value.put(
                "manifestFingerprint",
                workbook.path("manifestFingerprint")
                        .asText());
        value.put(
                "evidenceBundleFingerprint",
                workbook.path(
                        "evidenceBundleFingerprint")
                        .asText());
        value.put(
                "evidenceIndexFingerprint",
                workbook.path(
                        "evidenceIndexFingerprint")
                        .asText());
        value.set(
                "workbookSeal",
                workbook.path("workbookSeal")
                        .deepCopy());
        value.put(
                "status",
                workbook.path("status").asText());
        value.set(
                "summary",
                workbook.path("summary").deepCopy());
        ObjectNode correctness =
                correctnessSummary(passing);
        correctness.put("evidenceBackedEntries", 1);
        value.set(
                "correctnessSummary",
                correctness);
        value.put("gateReady", passing);
        value.set(
                "blockers",
                workbook.path("blockers").deepCopy());
        return value;
    }

    private static ObjectNode entryComparison(
            com.fasterxml.jackson.databind.JsonNode predecessor,
            com.fasterxml.jackson.databind.JsonNode successor) {
        ObjectNode value = JSON.createObjectNode();
        value.put("entryIndex", 0);
        value.put("entryId", "entry-a");
        value.put("planChanged", false);
        value.put("gateTransition", "RESOLVED");
        value.putArray("resolvedBlockers")
                .add("BLOCKER_ASSERTION_FAILED")
                .add("ENTRY_STATUS_FAILED");
        value.putArray("remainingBlockers");
        value.putArray("introducedBlockers");
        value.set(
                "predecessor",
                entrySnapshot(predecessor, false));
        value.set(
                "successor",
                entrySnapshot(successor, true));
        return value;
    }

    private static ObjectNode entrySnapshot(
            com.fasterxml.jackson.databind.JsonNode entry,
            boolean passing) {
        ObjectNode child =
                (ObjectNode) entry.path(
                        "childWorkbook");
        ObjectNode value = JSON.createObjectNode();
        value.set(
                "compiledPlanRef",
                entry.path("compiledPlanRef")
                        .deepCopy());
        value.put(
                "status",
                entry.path("status").asText());
        value.put(
                "failureCode",
                entry.path("failureCode").asText());
        value.put(
                "runId",
                entry.path("runId").asText());
        value.put(
                "childEvidenceBundleFingerprint",
                entry.path(
                        "childEvidenceBundleFingerprint")
                        .asText());
        value.put(
                "childWorkbookSeedFingerprint",
                entry.path(
                        "childWorkbookSeedFingerprint")
                        .asText());
        value.set(
                "scenarioPackRef",
                child.path("scenarioPackRef")
                        .deepCopy());
        value.set(
                "targetCapabilityRef",
                child.path("targetCapabilityRef")
                        .deepCopy());
        value.put(
                "outcome",
                child.path("outcome").asText());
        value.set(
                "summary",
                child.path("summary").deepCopy());
        value.put("gateReady", passing);
        ArrayNode blockers =
                value.putArray("blockers");
        if (!passing) {
            blockers.add("BLOCKER_ASSERTION_FAILED");
            blockers.add("ENTRY_STATUS_FAILED");
        }
        return value;
    }

    private static ObjectNode batchSummary(
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put("totalItems", 1);
        value.put("completedItems", 1);
        value.put(
                "passedItems",
                passing ? 1 : 0);
        value.put(
                "failedItems",
                passing ? 0 : 1);
        value.put("indeterminateItems", 0);
        value.put("cancelledItems", 0);
        return value;
    }

    private static ObjectNode correctnessSummary(
            boolean passing) {
        ObjectNode value = JSON.createObjectNode();
        value.put("totalCases", 1);
        value.put(
                "passedCases",
                passing ? 1 : 0);
        value.put(
                "failedCases",
                passing ? 0 : 1);
        value.put("indeterminateCases", 0);
        value.put("assertionResults", 1);
        value.put(
                "blockerFailures",
                passing ? 0 : 1);
        value.put("blockerIndeterminate", 0);
        value.put("warningFailures", 0);
        value.put("warningIndeterminate", 0);
        return value;
    }

    private static ObjectNode retention(
            String jobId,
            String evidenceFingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1);
        value.put("eventId", "retention-a");
        value.put("commandId", "register-a");
        value.set("scope", scope());
        value.put("requestId", "batch-request-a");
        value.put("jobId", jobId);
        value.put(
                "manifestFingerprint",
                fingerprint('3'));
        value.put("revision", 1);
        value.put("type", "RETENTION_REGISTERED");
        value.put(
                "retainUntil",
                "2026-08-25T10:00:00Z");
        value.put(
                "occurredAt",
                "2026-07-25T10:00:00Z");
        value.put("actorId", "system");
        value.put(
                "reasonCode",
                "RG.MIRROR.RETENTION.REGISTERED");
        value.put("holdId", "");
        value.put(
                "evidenceBundleFingerprint",
                evidenceFingerprint);
        value.put("previousEventFingerprint", "");
        value.put("deletedJobCount", 0);
        value.put("deletedItemCount", 0);
        value.put(
                "deletedBatchEvidenceCount", 0);
        value.put(
                "childEvidenceDisposition",
                "NOT_APPLICABLE");
        value.put(
                "auditDisposition",
                "NOT_APPLICABLE");
        value.set(
                "evidenceSeal",
                seal(fingerprint('7')));
        return value;
    }

    private static ObjectNode seal(
            String materialFingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put(
                "materialFingerprint",
                materialFingerprint);
        value.put("algorithm", "Ed25519");
        value.put("keyId", "workbook-key-a");
        value.put(
                "signedAt",
                "2026-07-25T10:00:00Z");
        value.put("signature", "test-signature");
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

    private static ObjectNode ref(
            String kind,
            String id,
            char material) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put(
                "fingerprint",
                fingerprint(material));
        return value;
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material)
                .repeat(64);
    }

    record Fixture(
            ObjectNode lineage,
            ObjectNode predecessor,
            ObjectNode successor,
            ObjectNode comparison
    ) {
    }
}
