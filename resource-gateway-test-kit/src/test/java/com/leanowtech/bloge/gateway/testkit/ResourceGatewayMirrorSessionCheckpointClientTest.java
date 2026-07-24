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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceGatewayMirrorSessionCheckpointClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private MirrorSessionCheckpointVerifierTest.Fixture fixture;
    private final List<Request> requests = new ArrayList<>();
    private boolean staleRecovery;

    @BeforeEach
    void setUp() throws Exception {
        fixture = MirrorSessionCheckpointVerifierTest.fixture();
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsAndRecoversThroughIndependentlyVerifiedContracts() {
        ResourceGatewayTestClient client = client();

        JsonNode checkpoint =
                client.createMirrorSessionCheckpoint(
                        "refund-session-1");
        JsonNode recovery = client.recoverMirrorSession(
                "refund-session-1", checkpoint);

        assertThat(checkpoint.path("payloadPolicy").asText())
                .isEqualTo("HASH_ONLY");
        assertThat(recovery.path("runBinding")
                .path("sessionId").asText())
                .isEqualTo("refund-session-1");
        assertThat(requests).extracting(Request::path)
                .containsExactly(
                        "/api/mirror/sessions/refund-session-1/checkpoints",
                        "/api/integration/evidence-keys/checkpoint-key-1",
                        "/api/integration/evidence-keys/checkpoint-key-1",
                        "/api/mirror/sessions/refund-session-1/recoveries");
        assertThat(requests).extracting(Request::purpose)
                .containsExactly(
                        "MIRROR_REHEARSAL",
                        "TEST_EXECUTION",
                        "TEST_EXECUTION",
                        "MIRROR_REHEARSAL");
        assertThat(EvidenceVerificationSupport.sha256(
                requests.get(3).body()))
                .isEqualTo(EvidenceVerificationSupport.sha256(
                        fixture.bundle()));
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization())
                    .isEqualTo("Bearer checkpoint-token");
            assertThat(request.correlationId()).isNotBlank();
        });
    }

    @Test
    void rejectsARecoveryResponseForAnotherStateHead() {
        staleRecovery = true;
        ResourceGatewayTestClient client = client();
        JsonNode checkpoint =
                client.createMirrorSessionCheckpoint(
                        "refund-session-1");

        assertThatThrownBy(() -> client.recoverMirrorSession(
                "refund-session-1", checkpoint))
                .isInstanceOf(ResourceGatewayTestException.class)
                .extracting(failure ->
                        ((ResourceGatewayTestException) failure).code())
                .isEqualTo(
                        "RG.TESTKIT.RESPONSE_CONTRACT_INVALID");
    }

    @Test
    void rejectsTamperedCheckpointBeforeRecoveryTransport() {
        ResourceGatewayTestClient client = client();
        ObjectNode checkpoint = (ObjectNode) client
                .createMirrorSessionCheckpoint(
                        "refund-session-1");
        checkpoint.put("bundleFingerprint",
                "sha256:" + "9".repeat(64));

        assertThatThrownBy(() -> client.recoverMirrorSession(
                "refund-session-1", checkpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a verified checkpoint");
        assertThat(requests).extracting(Request::path)
                .doesNotContain(
                        "/api/mirror/sessions/refund-session-1/recoveries");
    }

    private ResourceGatewayTestClient client() {
        return ResourceGatewayTestClient.builder(URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()))
                .bearerToken(() -> "checkpoint-token")
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private void handle(HttpExchange exchange)
            throws IOException {
        byte[] requestBody =
                exchange.getRequestBody().readAllBytes();
        JsonNode body = requestBody.length == 0
                ? JSON.createObjectNode()
                : JSON.readTree(requestBody);
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(
                exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst(
                        "X-Purpose"),
                exchange.getRequestHeaders().getFirst(
                        "Authorization"),
                exchange.getRequestHeaders().getFirst(
                        "X-Correlation-Id"),
                body));
        if (path.startsWith(
                "/api/integration/evidence-keys/")) {
            respond(exchange,
                    "EVIDENCE_VERIFICATION_KEY",
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                    verificationKey());
            return;
        }
        if (path.endsWith("/checkpoints")) {
            respond(exchange,
                    "MIRROR_SESSION_CHECKPOINT_BUNDLE",
                    CapabilityMirrorProtocol
                            .MIRROR_SESSION_CHECKPOINT_BUNDLE_V1,
                    fixture.bundle());
            return;
        }
        if (path.endsWith("/recoveries")) {
            ObjectNode recovery =
                    fixture.recovery().deepCopy();
            if (staleRecovery) {
                ((ObjectNode) recovery.path("runBinding"))
                        .put("expectedStateFingerprint",
                                "sha256:" + "8".repeat(64));
                MirrorSessionCheckpointVerifierTest.seal(
                        recovery);
            }
            respond(exchange,
                    "MIRROR_SESSION_RECOVERY_RESULT",
                    CapabilityMirrorProtocol
                            .MIRROR_SESSION_RECOVERY_RESULT_V1,
                    recovery);
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private ObjectNode verificationKey() {
        EvidenceVerificationKey key = fixture.key();
        return JSON.createObjectNode()
                .put("schemaVersion", key.schemaVersion())
                .put("keyId", key.keyId())
                .put("algorithm", key.algorithm())
                .put("encodedPublicKey",
                        key.encodedPublicKey())
                .put("createdAt", key.createdAt().toString())
                .put("state", key.state())
                .put("provider", key.provider());
    }

    private static void respond(
            HttpExchange exchange,
            String kind,
            String version,
            JsonNode payload) throws IOException {
        ObjectNode envelope = JSON.createObjectNode()
                .put("protocol",
                        CapabilityMirrorProtocol.INTEGRATION_PROTOCOL)
                .put("protocolVersion",
                        CapabilityMirrorProtocol
                                .INTEGRATION_PROTOCOL_V1)
                .put("payloadKind", kind)
                .put("payloadSchemaVersion", version);
        envelope.set("payload", payload);
        byte[] response = JSON.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record Request(
            String method,
            String path,
            String purpose,
            String authorization,
            String correlationId,
            JsonNode body
    ) {
    }
}
