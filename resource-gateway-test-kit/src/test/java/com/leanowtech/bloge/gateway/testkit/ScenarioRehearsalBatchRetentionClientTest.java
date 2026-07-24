package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchRetentionClientTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-25T08:00:01Z");

    private final List<CapturedRequest> requests =
            new ArrayList<>();
    private HttpServer server;
    private KeyPair keyPair;
    private ObjectNode retained;
    private ObjectNode purged;

    @BeforeEach
    void startServer() throws Exception {
        keyPair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        retained = signedState(false);
        purged = signedState(true);
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsAndPurgesWithLeastPrivilegeAndIndependentVerification() {
        ResourceGatewayTestClient client = client();

        JsonNode found =
                client.findScenarioRehearsalBatchRetention(
                        jobId());
        JsonNode deletionProof =
                client.purgeScenarioRehearsalBatch(
                        jobId(), purgeCommand());

        assertThat(found.path("status").asText())
                .isEqualTo("RETAINED");
        assertThat(deletionProof.path("status").asText())
                .isEqualTo("PURGED");
        assertThat(requests).extracting(
                        CapturedRequest::purpose)
                .containsExactly(
                        "GOVERNANCE_EVIDENCE_INGESTION",
                        "TEST_EXECUTION",
                        "PAYLOAD_RETENTION_ADMIN",
                        "TEST_EXECUTION");
        assertThat(requests.get(0).method())
                .isEqualTo("GET");
        assertThat(requests.get(0).path())
                .endsWith("/" + jobId() + "/retention");
        assertThat(requests.get(2).method())
                .isEqualTo("POST");
        assertThat(requests.get(2).path())
                .endsWith("/" + jobId()
                        + "/retention/purge");
        assertThat(requests.get(2).body()
                .path("commandId").asText())
                .isEqualTo("purge-command-1");
    }

    @Test
    void rejectsInvalidCommandsBeforeTransport() {
        ObjectNode command = purgeCommand();
        command.put("rawPayload", "must-not-leave-process");

        assertThatThrownBy(() ->
                client().purgeScenarioRehearsalBatch(
                        jobId(), command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_BATCH_RETENTION_COMMAND_INVALID");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsCrossBatchProjectionSubstitution() {
        retained.put(
                "jobId",
                "scenario-batch-" + "7".repeat(64));

        assertThatThrownBy(() ->
                client().findScenarioRehearsalBatchRetention(
                        jobId()))
                .isInstanceOfSatisfying(
                        ResourceGatewayTestException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(
                                        "RG.TESTKIT.RESPONSE_CONTRACT_INVALID"));
        assertThat(requests).hasSize(1);
    }

    private ResourceGatewayTestClient client() {
        return ResourceGatewayTestClient.builder(
                        URI.create("http://127.0.0.1:"
                                + server.getAddress().getPort()))
                .bearerToken(() -> "test-token")
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private void handle(HttpExchange exchange)
            throws IOException {
        byte[] bodyBytes =
                exchange.getRequestBody().readAllBytes();
        JsonNode body = bodyBytes.length == 0
                ? JSON.nullNode()
                : JSON.readTree(bodyBytes);
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders()
                        .getFirst("X-Purpose"),
                body));
        String path =
                exchange.getRequestURI().getRawPath();
        if (path.startsWith(
                "/api/integration/evidence-keys/")) {
            respond(exchange, keyEnvelope());
            return;
        }
        ObjectNode state = path.endsWith("/purge")
                ? purged : retained;
        respond(exchange, mirrorEnvelope(state));
    }

    private static void respond(
            HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes =
                body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String mirrorEnvelope(
            JsonNode state) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put(
                "protocol",
                CapabilityMirrorProtocol.INTEGRATION_PROTOCOL);
        envelope.put(
                "protocolVersion",
                CapabilityMirrorProtocol.INTEGRATION_PROTOCOL_V1);
        envelope.put(
                "payloadKind",
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATE");
        envelope.put(
                "payloadSchemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1);
        envelope.set("payload", state);
        return envelope.toString();
    }

    private String keyEnvelope() {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put(
                "payloadKind",
                "EVIDENCE_VERIFICATION_KEY");
        envelope.put(
                "payloadSchemaVersion",
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1);
        ObjectNode payload =
                envelope.putObject("payload");
        payload.put(
                "schemaVersion",
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1);
        payload.put(
                "keyId",
                "scenario-batch-retention-key-1");
        payload.put("algorithm", "Ed25519");
        payload.put(
                "encodedPublicKey",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()));
        payload.put(
                "createdAt",
                "2026-07-25T07:00:00Z");
        payload.put("state", "ACTIVE");
        payload.put("provider", "test");
        return envelope.toString();
    }

    private ObjectNode signedState(
            boolean deletionProof) throws Exception {
        ObjectNode event = event(deletionProof);
        String fingerprint =
                ScenarioRehearsalBatchRetentionVerifier
                        .eventFingerprint(event);
        ObjectNode seal =
                event.withObject("evidenceSeal");
        seal.put("materialFingerprint", fingerprint);
        seal.put("signature", sign(fingerprint));

        ObjectNode state = JSON.createObjectNode();
        state.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1);
        state.set("scope", scope());
        state.put("requestId", "batch-request-1");
        state.put("jobId", jobId());
        state.put(
                "manifestFingerprint", fingerprint('9'));
        state.put(
                "evidenceBundleFingerprint",
                fingerprint('a'));
        state.put(
                "status",
                deletionProof ? "PURGED" : "RETAINED");
        state.put(
                "revision",
                deletionProof ? 2 : 1);
        state.put(
                "retainUntil",
                "2026-07-24T08:00:00Z");
        state.putArray("activeHoldIds");
        state.put(
                "updatedAt",
                "2026-07-25T08:00:00Z");
        state.set("latestEvent", event);
        return state;
    }

    private ObjectNode event(
            boolean deletionProof) {
        ObjectNode event = JSON.createObjectNode();
        event.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1);
        event.put(
                "eventId",
                deletionProof
                        ? "batch-retention-event-2"
                        : "batch-retention-event-1");
        event.put(
                "commandId",
                deletionProof
                        ? "purge-command-1"
                        : "register-batch");
        event.set("scope", scope());
        event.put("requestId", "batch-request-1");
        event.put("jobId", jobId());
        event.put(
                "manifestFingerprint", fingerprint('9'));
        event.put(
                "revision",
                deletionProof ? 2 : 1);
        event.put(
                "type",
                deletionProof
                        ? "PURGED"
                        : "RETENTION_REGISTERED");
        event.put(
                "retainUntil",
                "2026-07-24T08:00:00Z");
        event.put(
                "occurredAt",
                "2026-07-25T08:00:00Z");
        event.put("actorId", "governance-admin");
        event.put(
                "reasonCode",
                deletionProof
                        ? "RG.MIRROR.REHEARSAL.BATCH_RETENTION_EXPIRED"
                        : "RG.MIRROR.REHEARSAL.BATCH_RETENTION_REGISTERED");
        event.put("holdId", "");
        event.put(
                "evidenceBundleFingerprint",
                fingerprint('a'));
        event.put(
                "previousEventFingerprint",
                deletionProof ? fingerprint('b') : "");
        event.put(
                "deletedJobCount",
                deletionProof ? 1 : 0);
        event.put(
                "deletedItemCount",
                deletionProof ? 3 : 0);
        event.put(
                "deletedBatchEvidenceCount",
                deletionProof ? 1 : 0);
        event.put(
                "childEvidenceDisposition",
                deletionProof
                        ? "RETAINED" : "NOT_APPLICABLE");
        event.put(
                "auditDisposition",
                deletionProof
                        ? "RETAINED" : "NOT_APPLICABLE");
        ObjectNode seal =
                event.putObject("evidenceSeal");
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put(
                "materialFingerprint",
                fingerprint('c'));
        seal.put("algorithm", "Ed25519");
        seal.put(
                "keyId",
                "scenario-batch-retention-key-1");
        seal.put("signedAt", SIGNED_AT.toString());
        seal.put("signature", "placeholder");
        return event;
    }

    private String sign(String fingerprint)
            throws Exception {
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                signer.sign());
    }

    private static ObjectNode purgeCommand() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_PURGE_COMMAND_V1);
        value.put("commandId", "purge-command-1");
        value.put(
                "reasonCode",
                "RG.MIRROR.REHEARSAL.BATCH_RETENTION_EXPIRED");
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
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }

    private record CapturedRequest(
            String method,
            String path,
            String purpose,
            JsonNode body
    ) {
    }
}
