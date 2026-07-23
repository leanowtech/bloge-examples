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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceGatewayMirrorSessionClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CapturedRequest> requests = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/mirror/sessions", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsReadsCommandsAndDestroysThroughVerifiedMirrorEnvelopes()
            throws Exception {
        ResourceGatewayTestClient client = client();
        ObjectNode payload = payload();
        ObjectNode create = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_CREATE_REQUEST_V1)
                .put("requestId", "create-refund-1");
        create.set("payload", payload);
        ObjectNode command = command(payload);

        JsonNode created = client.createMirrorSession(create);
        JsonNode found = client.findMirrorSession("refund-session-1");
        JsonNode result = client.executeMirrorSessionCommand(
                "refund-session-1", command);
        JsonNode destroyed = client.destroyMirrorSession("refund-session-1");

        assertThat(created.path("stateRevision").asLong()).isZero();
        assertThat(found.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(result.path("receipt").path("revisionAfter").asLong())
                .isEqualTo(1);
        assertThat(destroyed.path("status").asText()).isEqualTo("DESTROYED");
        assertThat(requests).extracting(CapturedRequest::method)
                .containsExactly("POST", "GET", "POST", "DELETE");
        assertThat(requests).extracting(CapturedRequest::purpose)
                .containsOnly("MIRROR_REHEARSAL");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization())
                    .isEqualTo("Bearer mirror-test-token");
            assertThat(request.correlationId()).isNotBlank();
        });
        assertThat(requests.get(2).path())
                .isEqualTo("/api/mirror/sessions/refund-session-1/commands");
        assertThat(requests.get(2).body().path("input").path("requestId").asText())
                .isEqualTo("refund-command-1");
    }

    @Test
    void rejectsUnsafeIdsAndMismatchedDescriptorsWithoutLeakingPayloads() {
        ResourceGatewayTestClient client = client();

        assertThatThrownBy(() -> client.findMirrorSession("tenant/session"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path-safe");
        assertThat(requests).isEmpty();

        assertThatThrownBy(() -> client.findMirrorSession("other-session"))
                .isInstanceOf(ResourceGatewayTestException.class)
                .extracting(failure ->
                        ((ResourceGatewayTestException) failure).code())
                .isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID");
    }

    private ResourceGatewayTestClient client() {
        return ResourceGatewayTestClient.builder(URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort()))
                .bearerToken(() -> "mirror-test-token")
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        JsonNode body = requestBody.length == 0
                ? JSON.createObjectNode() : JSON.readTree(requestBody);
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Purpose"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                body));
        ObjectNode payload = payload();
        boolean command = exchange.getRequestURI().getPath().endsWith("/commands");
        boolean destroy = "DELETE".equals(exchange.getRequestMethod());
        JsonNode responsePayload;
        String kind;
        String version;
        if (command) {
            ObjectNode descriptor = descriptor(payload, 1, "ACTIVE", null);
            responsePayload = commandResult(descriptor);
            kind = "MIRROR_SESSION_COMMAND_RESULT";
            version = CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_RESULT_V1;
        } else {
            responsePayload = descriptor(
                    payload,
                    destroy ? 1 : 0,
                    destroy ? "DESTROYED" : "ACTIVE",
                    destroy ? "2026-07-24T00:00:02Z" : null);
            kind = "MIRROR_SESSION_DESCRIPTOR";
            version = CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_V1;
        }
        ObjectNode envelope = JSON.createObjectNode()
                .put("protocol", CapabilityMirrorProtocol.INTEGRATION_PROTOCOL)
                .put("protocolVersion",
                        CapabilityMirrorProtocol.INTEGRATION_PROTOCOL_V1)
                .put("payloadKind", kind)
                .put("payloadSchemaVersion", version);
        envelope.set("payload", responsePayload);
        byte[] response = JSON.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static ObjectNode payload() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_PAYLOAD_V1);
        value.set("stateModel", fixture.path("stateModel").deepCopy());
        value.putArray("writeEffects")
                .add(fixture.path("writeEffect").deepCopy());
        value.set("state", fixture.path("initialState").deepCopy());
        return seal(value);
    }

    private static ObjectNode descriptor(
            ObjectNode payload,
            long revision,
            String status,
            String destroyedAt) {
        JsonNode state = payload.path("state");
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_V1)
                .put("sessionId", state.path("sessionId").asText())
                .put("planFingerprint", state.path("planFingerprint").asText())
                .put("stateRevision", revision)
                .put("status", status)
                .put("worldFingerprint", state.path("worldFingerprint").asText())
                .put("stateFingerprint", state.path("fingerprint").asText())
                .put("createdAt", "2026-07-24T00:00:00Z")
                .put("updatedAt", destroyTime(status))
                .put("expiresAt", "2026-07-24T01:00:00Z");
        value.set("scope", state.path("scope").deepCopy());
        value.set("stateModelRef", state.path("stateModelRef").deepCopy());
        value.set("writeEffectRefs", state.path("writeEffectRefs").deepCopy());
        if (destroyedAt == null) {
            value.putNull("destroyedAt");
        } else {
            value.put("destroyedAt", destroyedAt);
        }
        return seal(value);
    }

    private static String destroyTime(String status) {
        return "DESTROYED".equals(status)
                ? "2026-07-24T00:00:02Z"
                : "2026-07-24T00:00:01Z";
    }

    private static ObjectNode command(ObjectNode payload) {
        JsonNode state = payload.path("state");
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_REQUEST_V1)
                .put("expectedStateFingerprint",
                        state.path("fingerprint").asText());
        value.set(
                "writeEffectRef", state.path("writeEffectRefs").get(0).deepCopy());
        value.putObject("input")
                .put("requestId", "refund-command-1")
                .put("orderId", "O-100")
                .put("amount", 25);
        return value;
    }

    private static ObjectNode commandResult(ObjectNode descriptor) {
        ObjectNode response = JSON.createObjectNode()
                .put("refundId", "R-100")
                .put("status", "CREATED");
        ObjectNode receipt = JSON.createObjectNode()
                .put("idempotencyKey", "refund-command-1")
                .put("commandFingerprint", "sha256:" + "1".repeat(64))
                .put("revisionBefore", 0)
                .put("revisionAfter", 1)
                .put("responseFingerprint",
                        EvidenceVerificationSupport.sha256(response))
                .put("resultingWorldFingerprint",
                        descriptor.path("worldFingerprint").asText())
                .put("committedAt", "2026-07-24T00:00:01Z");
        receipt.putArray("eventIds").add("refund-created-1");
        receipt.set("response", response);
        seal(receipt);
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_RESULT_V1)
                .put("replayed", false);
        value.set("descriptor", descriptor);
        value.set("receipt", receipt);
        return value;
    }

    private static ObjectNode seal(ObjectNode value) {
        value.put("fingerprint", "");
        value.put("fingerprint", EvidenceVerificationSupport.sha256(value));
        return value;
    }

    private record CapturedRequest(
            String method,
            String path,
            String purpose,
            String authorization,
            String correlationId,
            JsonNode body
    ) {
    }
}
