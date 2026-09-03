package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the documented Codex MCP operating flow against the real Spring service graph. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.environment-id=test",
                "spring.datasource.url=jdbc:h2:mem:agent-tdd-mcp-ops;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class AgentTddMcpOperationalWorkflowTest {
    private static final String TOOL_REF = "codex-wallet-ops-test";
    private static final String CASE_SET_REF = "codex-wallet-cases-test";

    @Autowired
    private WritableResourceRegistry resources;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;
    private int requestId;

    @BeforeEach
    void seedRuntimeApiCatalog() {
        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resources, properties).seedDescriptors();
    }

    @Test
    void composesApprovesRunsZeroEgressBaselineSignsAndPublishes() {
        JsonNode capabilities = invoke("rg.capability.list", Map.of("kind", "API"), "AGENT_TDD_READ");
        JsonNode wallet = java.util.stream.StreamSupport.stream(
                        capabilities.at("/data/capabilities").spliterator(), false)
                .filter(value -> value.path("ref").asText().contains("wallet-service.getBalance"))
                .findFirst().orElseThrow();
        String bindingRef = wallet.path("ref").asText();
        JsonNode contract = invoke("rg.contract.get", Map.of("assetRef", bindingRef), "AGENT_TDD_READ");
        assertThat(contract.at("/data/bindingRef").asText()).isEqualTo(bindingRef);

        JsonNode composed = invoke("rg.tool.compose", Map.of(
                "toolRef", TOOL_REF,
                "graph", Map.of("sourceId", TOOL_REF + ".bloge", "dsl", graph(bindingRef)),
                "libraryRefs", List.of(),
                "idempotencyKey", "compose-wallet-ops-test"), "AGENT_TDD_AUTHORING");
        assertThat(composed.at("/data/revision").asLong()).isEqualTo(1);

        invoke("rg.tool.setInstruction", Map.of(
                "toolRef", TOOL_REF,
                "instruction", Map.of(
                        "name", "codexWalletOps",
                        "title", "Wallet balance lookup",
                        "description", "Reads a governed wallet balance.",
                        "whenToUse", "Use for an operator-requested wallet balance.",
                        "inputs", List.of(Map.of("name", "userId", "type", "string", "required", true)),
                        "outputs", Map.of("amount", "number", "currency", "string"),
                        "errors", List.of(Map.of("code", "WALLET_UNAVAILABLE"))),
                "idempotencyKey", "instruction-wallet-ops-test"), "AGENT_TDD_AUTHORING");
        JsonNode cases = invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", CASE_SET_REF,
                "toolRef", TOOL_REF,
                "rows", List.of(Map.of(
                        "caseId", "wallet-usd",
                        "category", "GOLDEN",
                        "layer", "contract",
                        "given", Map.of("userId", "u-100"),
                        "stubs", Map.of("wallet", Map.of(
                                "payload", Map.of("amount", 100, "currency", "USD"))),
                        "expect", Map.of("amount", 100, "currency", "USD"),
                        "intent", "Return the exact governed wallet balance",
                        "oracleOwner", "wallet-ops")),
                "idempotencyKey", "cases-wallet-ops-test"), "AGENT_TDD_AUTHORING");
        JsonNode oracleReview = reviewGet("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF
                + "/wallet-usd?expectedRevision=" + cases.at("/data/revision").asLong());
        assertThat(oracleReview.path("intent").asText()).contains("governed wallet balance");
        assertAgentCannotApprove("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF
                + "/wallet-usd/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", oracleReview.path("proposalFingerprint").asText()));
        reviewPost("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF + "/wallet-usd/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", oracleReview.path("proposalFingerprint").asText()));

        JsonNode red = invoke("rg.simulate", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(), "side", "RED",
                "cases", Map.of("caseSetRef", CASE_SET_REF)), "AGENT_TDD_EXECUTION");
        assertThat(red.at("/data/cases/0/verdict").asText())
                .as(red.toPrettyString()).isEqualTo("RED_PASS");
        assertThat(red.at("/data/realExternalCalls").asInt()).isZero();

        JsonNode green = invoke("rg.tool.baseline", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(),
                "caseSetRef", CASE_SET_REF, "side", "GREEN", "rounds", 2), "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/businessFingerprintStable").asBoolean()).isTrue();
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();

        reviewPost("/api/agent-tdd/reviews/tools/" + TOOL_REF
                + "/signoffs/ops-review-test/approve", Map.of(
                "draftRevision", green.at("/data/draftRevision").asLong(),
                "goldenSetId", green.at("/data/goldenSetId").asText(),
                "evidenceFingerprint", green.at("/data/evidenceFingerprint").asText()));
        assertThat(invoke("rg.readiness.get", Map.of("toolRef", TOOL_REF))
                .at("/data/publishable").asBoolean()).isTrue();

        JsonNode published = invoke("rg.tool.publish", Map.of(
                "toolRef", TOOL_REF,
                "signoffRef", "ops-review-test",
                "idempotencyKey", "publish-wallet-ops-test"), "AGENT_TDD_GOVERNANCE");
        assertThat(published.at("/data/artifactKind").asText()).isEqualTo("EXECUTABLE");
    }

    private JsonNode invoke(String name, Object arguments) {
        return invoke(name, arguments, "AGENT_TDD_READ");
    }

    private JsonNode invoke(String name, Object arguments, String purpose) {
        JsonNode request = mapper.valueToTree(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
        HttpHeaders headers = headers("bloge-aneke-demo-token", purpose);
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        ResponseEntity<JsonNode> response = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        JsonNode result = response.getBody().at("/result/structuredContent");
        assertThat(result.path("ok").asBoolean()).as(response.getBody().toPrettyString()).isTrue();
        return result;
    }

    private JsonNode reviewGet(String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers("bloge-reviewer-demo-token", "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private void reviewPost(String path, Object body) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(mapper.valueToTree(body),
                        headers("bloge-reviewer-demo-token", "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        assertThat(response.getBody().path("status").asText()).isEqualTo("APPROVED");
    }

    private void assertAgentCannotApprove(String path, Object body) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(mapper.valueToTree(body),
                        headers("bloge-aneke-demo-token", "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("GATE_REJECTED");
    }

    private static HttpHeaders headers(String token, String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Purpose", purpose);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String graph(String bindingRef) {
        return """
                graph codexWalletOps {
                  input { userId: String }
                  node wallet : "%s" {
                    input { params = { userId: ctx.userId } }
                  }
                  transform response {
                    amount = wallet.output.payload.amount
                    currency = wallet.output.payload.currency
                  }
                }
                """.formatted(bindingRef);
    }

}
