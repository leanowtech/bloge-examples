package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesStrictPayloadFreeRequestAndFrozenManifest() {
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                request(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_REQUEST_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                manifest(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_MANIFEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_MANIFEST_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                cancellation(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_CANCELLATION_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_CANCELLATION_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                job(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_JOB_INVALID"))
                .doesNotThrowAnyException();
        ObjectNode legacyJob = job();
        legacyJob.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_V1);
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                legacyJob,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_V1_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_JOB_V1_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                page(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_PAGE_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                finalization(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_FINALIZATION_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                remediationRequest(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_REQUEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_FINALIZATION_REMEDIATION_REQUEST_INVALID"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(
                remediationReceipt(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_FINALIZATION_REMEDIATION_RECEIPT_INVALID"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRuntimeOverridesWrongPlanKindsAndUnknownManifestFields() {
        ObjectNode override = request();
        override.put("priority", "HIGH");
        ObjectNode wrongKind = request();
        ((ObjectNode) wrongKind.path("entries").get(0)
                .path("compiledPlanRef"))
                .put("kind", "MIRROR_PLAN");
        ObjectNode payload = manifest();
        payload.putObject("context")
                .put("customerId", "must-not-leak");
        ObjectNode wrongRun = manifest();
        ((ObjectNode) wrongRun.path("entries").get(0))
                .put("aggregateRunId", "scenario-run-not-a-hash");
        ObjectNode leakedJob = job();
        leakedJob.putObject("payload")
                .put("customerId", "must-not-leak");
        ObjectNode invalidPage = page();
        ((ObjectNode) invalidPage.path("items").get(0))
                .put("attemptCount", 99);

        assertInvalid(
                override,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_SCHEMA_RESOURCE);
        assertInvalid(
                wrongKind,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_SCHEMA_RESOURCE);
        assertInvalid(
                payload,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_MANIFEST_SCHEMA_RESOURCE);
        assertInvalid(
                wrongRun,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_MANIFEST_SCHEMA_RESOURCE);
        assertInvalid(
                leakedJob,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_SCHEMA_RESOURCE);
        assertInvalid(
                invalidPage,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_SCHEMA_RESOURCE);
    }

    private static ObjectNode request() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_V1);
        value.put("requestId", "nightly-support-regression");
        ArrayNode entries = value.putArray("entries");
        ObjectNode entry = entries.addObject();
        entry.put("entryId", "refund");
        entry.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        return value;
    }

    private static ObjectNode manifest() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_MANIFEST_V1);
        value.put(
                "batchId",
                "scenario-batch-" + "b".repeat(64));
        value.put("manifestFingerprint", fingerprint('c'));
        value.set("scope", scope());
        value.put("requestId", "nightly-support-regression");
        ArrayNode entries = value.putArray("entries");
        ObjectNode entry = entries.addObject();
        entry.put("entryIndex", 0);
        entry.put("entryId", "refund");
        entry.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        entry.put(
                "aggregateRequestId",
                "nightly-support-regression:plan:000");
        entry.put(
                "aggregateRunId",
                "scenario-" + "d".repeat(64));
        entry.put("caseCount", 12);
        entry.put("executionTimeout", "PT5M");
        value.put("totalCases", 12);
        return value;
    }

    private static ObjectNode cancellation() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_CANCELLATION_REQUEST_V1);
        value.put("commandId", "cancel-001");
        value.put("reasonCode", "OWNER_REQUEST");
        return value;
    }

    private static ObjectNode job() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_V2);
        value.put(
                "jobId",
                "scenario-batch-" + "b".repeat(64));
        value.put("requestId", "nightly-support-regression");
        value.put("requestFingerprint", fingerprint('a'));
        value.put("manifestFingerprint", fingerprint('c'));
        value.set("scope", scope());
        value.put("status", "QUEUED");
        value.put("failureMode", "COLLECT_ALL");
        value.put("priority", "NORMAL");
        value.put("maximumItemAttempts", 3);
        ObjectNode summary = value.putObject("summary");
        summary.put("totalItems", 1);
        summary.put("completedItems", 0);
        summary.put("passedItems", 0);
        summary.put("failedItems", 0);
        summary.put("indeterminateItems", 0);
        summary.put("cancelledItems", 0);
        value.put("deadlineAt", "2026-07-25T08:00:00Z");
        value.put("failureCode", "");
        value.put("cancellationRequestId", "");
        value.put("cancellationReasonCode", "");
        value.put("createdAt", "2026-07-24T08:00:00Z");
        value.put("updatedAt", "2026-07-24T08:00:00Z");
        value.putNull("completedAt");
        value.put("recordFingerprint", fingerprint('f'));
        return value;
    }

    private static ObjectNode page() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_V1);
        value.put(
                "jobId",
                "scenario-batch-" + "b".repeat(64));
        value.put("manifestFingerprint", fingerprint('c'));
        ObjectNode item = value.putArray("items").addObject();
        item.put("itemIndex", 0);
        item.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        item.put(
                "childRequestId",
                "nightly-support-regression:plan:000");
        item.put("status", "PENDING");
        item.put("attemptCount", 0);
        item.put("runId", "");
        item.put("evidenceBundleFingerprint", "");
        item.put("workbookSeedFingerprint", "");
        item.put("failureCode", "");
        item.putNull("startedAt");
        item.putNull("completedAt");
        value.putNull("nextIndex");
        return value;
    }

    private static ObjectNode finalization() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS_V1);
        value.put(
                "jobId",
                "scenario-batch-" + "b".repeat(64));
        value.put("state", "RETRY_WAIT");
        value.put("attemptCount", 2);
        value.put(
                "nextEligibleAt",
                "2026-07-25T08:00:05Z");
        value.put(
                "leaseExpiresAt",
                "1970-01-01T00:00:00Z");
        value.put(
                "signingStartedAt",
                "2026-07-25T08:00:00Z");
        value.put(
                "failureCode",
                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_SIGNER_UNAVAILABLE");
        value.put("evidenceBundleFingerprint", "");
        value.put("createdAt", "2026-07-25T08:00:00Z");
        value.put("updatedAt", "2026-07-25T08:00:01Z");
        value.putNull("finalizedAt");
        return value;
    }

    private static ObjectNode remediationRequest() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_REQUEST_V1);
        value.put("commandId", "remediation-001");
        value.put("expectedAttemptCount", 2);
        value.put(
                "expectedUpdatedAt",
                "2026-07-25T08:00:01Z");
        value.put("reasonCode", "KMS_POLICY_REPAIRED");
        return value;
    }

    private static ObjectNode remediationReceipt() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT_V1);
        value.put("receiptFingerprint", fingerprint('f'));
        value.put("commandId", "remediation-001");
        value.put(
                "jobId",
                "scenario-batch-" + "b".repeat(64));
        value.put("remediationGeneration", 1);
        value.put(
                "previousIntentFingerprint",
                fingerprint('d'));
        value.put(
                "currentIntentFingerprint",
                fingerprint('e'));
        value.put("previousAttemptCount", 2);
        value.put("acceptedAt", "2026-07-25T08:01:00Z");
        value.put(
                "effectiveRetainUntil",
                "2026-08-24T08:01:00Z");
        value.put("reasonCode", "KMS_POLICY_REPAIRED");
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "support");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint(fingerprint));
        return value;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertInvalid(
            ObjectNode value,
            String schema) {
        assertThatThrownBy(() ->
                CapabilityMirrorSchemaValidator.require(
                        value,
                        schema,
                        "RG.MIRROR.CLIENT.SCENARIO_BATCH_INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
