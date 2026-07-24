package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalRetentionSchemaTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsStrictCommandsAndSignedRetentionProjection() {
        assertThatCode(() -> require(
                legalHoldCommand(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                purgeCommand(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_PURGE_COMMAND_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                retentionEvent(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_EVENT_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                retentionState(),
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_STATE_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownCommandFieldsAndIncoherentDeletionProofs() {
        ObjectNode command = legalHoldCommand();
        command.put("rawPayload", "must-not-cross-the-boundary");
        assertInvalid(
                command,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_SCHEMA_RESOURCE);

        ObjectNode event = retentionEvent();
        event.put("childEvidenceDisposition", "NOT_APPLICABLE");
        assertInvalid(
                event,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_EVENT_SCHEMA_RESOURCE);

        ObjectNode state = retentionState();
        state.putArray("activeHoldIds").add("legal-hold-a");
        assertInvalid(
                state,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_STATE_SCHEMA_RESOURCE);
    }

    private static ObjectNode legalHoldCommand() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_V1);
        value.put("commandId", "hold-command-1");
        value.put("holdId", "legal-hold-a");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.LITIGATION");
        return value;
    }

    private static ObjectNode purgeCommand() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_PURGE_COMMAND_V1);
        value.put("commandId", "purge-command-1");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");
        return value;
    }

    private static ObjectNode retentionState() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_STATE_V1);
        value.set("scope", scope());
        value.put("runId", runId());
        value.put("requestId", "scenario-request-1");
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
                        .SCENARIO_REHEARSAL_RETENTION_EVENT_V1);
        value.put("eventId", "event-2");
        value.put("commandId", "purge-command-1");
        value.set("scope", scope());
        value.put("requestId", "scenario-request-1");
        value.put("runId", runId());
        value.put("revision", 2);
        value.put("type", "PURGED");
        value.put("retainUntil", "2026-07-24T08:00:00Z");
        value.put("occurredAt", "2026-07-25T08:00:00Z");
        value.put("actorId", "governance-admin");
        value.put("reasonCode",
                "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");
        value.put("holdId", "");
        value.put("evidenceBundleFingerprint", fingerprint('a'));
        value.put("previousEventFingerprint", fingerprint('b'));
        value.put("deletedCaseProgressCount", 3);
        value.put("childEvidenceDisposition", "RETAINED");
        ObjectNode seal = value.putObject("evidenceSeal");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint('c'));
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "scenario-retention-key-1");
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

    private static String runId() {
        return "scenario-" + "9".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void require(
            ObjectNode value, String resource) {
        CapabilityMirrorSchemaValidator.require(
                value,
                resource,
                "RG.MIRROR.CLIENT.SCENARIO_RETENTION_INVALID");
    }

    private static void assertInvalid(
            ObjectNode value, String resource) {
        assertThatThrownBy(() -> require(value, resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_RETENTION_INVALID");
    }
}
