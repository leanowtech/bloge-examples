package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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
    private ResourceGatewayAgentTddTools tools;

    @Autowired
    private AgentTddReviewService reviews;

    @Autowired
    private WritableResourceRegistry resources;

    @Autowired
    private ObjectMapper mapper;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedRuntimeApiCatalog() {
        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resources, properties).seedDescriptors();
    }

    @Test
    void composesApprovesRunsZeroEgressBaselineSignsAndPublishes() {
        JsonNode composed = invoke("rg.tool.compose", Map.of(
                "toolRef", TOOL_REF,
                "graph", Map.of("sourceId", TOOL_REF + ".bloge", "dsl", graph()),
                "libraryRefs", List.of(),
                "idempotencyKey", "compose-wallet-ops-test"));
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
                "idempotencyKey", "instruction-wallet-ops-test"));
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
                "idempotencyKey", "cases-wallet-ops-test"));
        reviews.approveOracle(CASE_SET_REF, "wallet-usd",
                cases.at("/data/revision").asLong(),
                cases.at("/data/rows/0/proposedOracle/proposalFingerprint").asText(), reviewerIdentity());

        JsonNode red = invoke("rg.simulate", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(), "side", "RED",
                "cases", Map.of("caseSetRef", CASE_SET_REF)));
        assertThat(red.at("/data/cases/0/verdict").asText())
                .as(red.toPrettyString()).isEqualTo("RED_PASS");
        assertThat(red.at("/data/realExternalCalls").asInt()).isZero();

        JsonNode green = invoke("rg.tool.baseline", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(),
                "caseSetRef", CASE_SET_REF, "side", "GREEN", "rounds", 2));
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/businessFingerprintStable").asBoolean()).isTrue();
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();

        reviews.approveToolSignoff(TOOL_REF, "ops-review-test",
                green.at("/data/draftRevision").asLong(),
                green.at("/data/goldenSetId").asText(),
                green.at("/data/evidenceFingerprint").asText(), reviewerIdentity());
        assertThat(invoke("rg.readiness.get", Map.of("toolRef", TOOL_REF))
                .at("/data/publishable").asBoolean()).isTrue();

        JsonNode published = invoke("rg.tool.publish", Map.of(
                "toolRef", TOOL_REF,
                "signoffRef", "ops-review-test",
                "idempotencyKey", "publish-wallet-ops-test"));
        assertThat(published.at("/data/artifactKind").asText()).isEqualTo("EXECUTABLE");
    }

    private JsonNode invoke(String name, Object arguments) {
        JsonNode result = mapper.valueToTree(tools.invoke(name, mapper.valueToTree(arguments), identity()));
        assertThat(result.path("ok").asBoolean()).as(result.toPrettyString()).isTrue();
        return result;
    }

    private static String graph() {
        return """
                graph codexWalletOps {
                  input { userId: String }
                  node wallet : "resource:wallet-service.getBalance" {
                    input { params = { userId: ctx.userId } }
                  }
                  transform response {
                    amount = wallet.output.payload.amount
                    currency = wallet.output.payload.currency
                  }
                }
                """;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "local",
                "WORKLOAD", "codex-agent", "", "AGENT_TDD_AUTHORING", "ops-test");
    }

    private static IntegrationRequestContext reviewerIdentity() {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "local",
                "HUMAN", "ops-reviewer", "", "AGENT_TDD_GOVERNANCE", "ops-review");
    }

}
