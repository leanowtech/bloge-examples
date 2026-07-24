package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchRetentionSchemaTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsStrictSignedBatchRetentionProjection() {
        assertThatCode(() -> require(
                retentionEvent(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                retentionState(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownFieldsAndIncoherentBatchDeletionProofs() {
        ObjectNode event = retentionEvent();
        event.put("rawPayload", "must-not-cross-the-boundary");
        assertInvalid(
                event,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_SCHEMA_RESOURCE);

        event = retentionEvent();
        event.put("deletedItemCount", 0);
        assertInvalid(
                event,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_SCHEMA_RESOURCE);

        event = retentionEvent();
        event.put("auditDisposition", "NOT_APPLICABLE");
        assertInvalid(
                event,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_SCHEMA_RESOURCE);

        ObjectNode state = retentionState();
        state.putArray("activeHoldIds").add("legal-hold-a");
        assertInvalid(
                state,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_SCHEMA_RESOURCE);
    }

    private static ObjectNode retentionState() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1);
        value.set("scope", scope());
        value.put("requestId", "batch-request-1");
        value.put("jobId", jobId());
        value.put("manifestFingerprint", fingerprint('9'));
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("status", "PURGED");
        value.put("revision", 2);
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.putArray("activeHoldIds");
        value.put("updatedAt", "2026-07-25T08:00:00Z");
        value.set("latestEvent", retentionEvent());
        return value;
    }

    private static ObjectNode retentionEvent() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1);
        value.put("eventId", "batch-retention-event-2");
        value.put("commandId", "purge-command-1");
        value.set("scope", scope());
        value.put("requestId", "batch-request-1");
        value.put("jobId", jobId());
        value.put("manifestFingerprint", fingerprint('9'));
        value.put("revision", 2);
        value.put("type", "PURGED");
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.put("occurredAt", "2026-07-25T08:00:00Z");
        value.put("actorId", "governance-admin");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.BATCH_RETENTION_EXPIRED");
        value.put("holdId", "");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("previousEventFingerprint", fingerprint('b'));
        value.put("deletedJobCount", 1);
        value.put("deletedItemCount", 3);
        value.put("deletedBatchEvidenceCount", 1);
        value.put("childEvidenceDisposition", "RETAINED");
        value.put("auditDisposition", "RETAINED");
        ObjectNode seal = value.putObject("evidenceSeal");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint('c'));
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "scenario-batch-retention-key-1");
        seal.put("signedAt", "2026-07-25T08:00:01Z");
        seal.put("signature", "c2lnbmF0dXJl");
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

    private static String jobId() {
        return "scenario-batch-" + "8".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void require(
            ObjectNode value, String resource) {
        CapabilityMirrorSchemaValidator.require(
                value,
                resource,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_RETENTION_INVALID");
    }

    private static void assertInvalid(
            ObjectNode value, String resource) {
        assertThatThrownBy(() -> require(value, resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_BATCH_RETENTION_INVALID");
    }
}
