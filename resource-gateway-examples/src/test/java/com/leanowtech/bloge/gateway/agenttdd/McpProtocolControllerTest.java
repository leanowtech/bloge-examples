package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the MCP Streamable HTTP boundary for legacy and stateless Codex clients. */
class McpProtocolControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listsToolsUsingModernStatelessRoutingAndReadPurpose() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("called", name));
        HttpHeaders headers = modernHeaders("tools/list", null);

        JsonNode response = controller.exchange(request(7, "tools/list", Map.of()), headers).getBody();

        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("result").path("tools")).hasSize(24);
        assertThat(response.path("result").path("ttlMs").asInt()).isPositive();
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
    }

    @Test
    void listsToolsForNegotiatedLegacyClientWithoutDraftRoutingHeaders() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("called", name));
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.LEGACY_PROTOCOL_VERSION);

        ResponseEntity<JsonNode> response = controller.exchange(
                request(8, "tools/list", Map.of()), headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("result").path("tools")).hasSize(24);
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
    }

    @Test
    void acceptsLegacyInitializedNotificationWithoutJsonRpcResponse() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.LEGACY_PROTOCOL_VERSION);
        JsonNode notification = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

        ResponseEntity<JsonNode> response = controller.exchange(notification, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void callsToolWithImpactSpecificAuthenticationAndStructuredEnvelope() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_EXECUTE)))
                .thenReturn(identity());
        McpToolInvoker invoker = (name, arguments, identity) -> Map.of(
                "ok", true,
                "data", Map.of("goldenSetId", "sha256:golden", "side", "RED",
                        "realExternalCalls", 0),
                "diagnostics", java.util.List.of());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);
        HttpHeaders headers = modernHeaders("tools/call", "rg.simulate");
        JsonNode request = request(9, "tools/call", Map.of(
                "name", "rg.simulate",
                "arguments", Map.of("toolRef", "ride:cancel", "libraryRefs", java.util.List.of("ride"),
                        "cases", Map.of("caseSetRef", "cancel-golden"))));

        JsonNode response = controller.exchange(request, headers).getBody();

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent").path("ok").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("data")
                .path("realExternalCalls").asInt()).isZero();
        assertThat(response.path("result").path("content").get(0).path("type").asText()).isEqualTo("text");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_EXECUTE);
    }

    @Test
    void rejectsArgumentsThatDoNotMatchTheAdvertisedSchemaBeforeInvocation() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        McpToolControllerProbe invoker = new McpToolControllerProbe();
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);

        JsonNode response = controller.exchange(request(13, "tools/call", Map.of(
                "name", "rg.simulate",
                "arguments", Map.of("toolRef", "ride:cancel", "libraryRefs", java.util.List.of("ride"),
                        "cases", Map.of("caseSetRef", "golden", "rows", java.util.List.of())))),
                modernHeaders("tools/call", "rg.simulate")).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText())
                .isEqualTo("Tool arguments do not match the declared input schema");
        assertThat(invoker.called).isFalse();
    }

    @Test
    void rejectsImplementationResponseThatViolatesOutputSchemaWithoutReflectingPayload() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true, "data", Map.of("undeclared", "customer-secret"),
                        "diagnostics", java.util.List.of()));

        JsonNode response = controller.exchange(request(14, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.toString()).doesNotContain("customer-secret", "undeclared");
    }

    @Test
    void requiresSuccessDataAndHidesUnexpectedApplicationFailureMessages() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController missingData = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("ok", true, "diagnostics", java.util.List.of()));
        JsonNode invalidEnvelope = missingData.exchange(request(15, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        McpProtocolController failing = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> {
                    throw new IllegalStateException("provider customer-secret response");
                });
        JsonNode hiddenFailure = failing.exchange(request(16, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        assertThat(invalidEnvelope.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(hiddenFailure.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(hiddenFailure.toString()).doesNotContain("customer-secret", "provider");
    }

    @Test
    void acceptsRowsProducedByTheRealDecisionScenarioEnumerator() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_DRAFT_WRITE)))
                .thenReturn(identity());
        GraphDraft draft = decisionDraft();
        List<com.fasterxml.jackson.databind.node.ObjectNode> rows =
                new AgentTddDecisionScenarioEnumerator(mapper).enumerate(draft, mapper.valueToTree(Map.of(
                        "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 3)));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true,
                        "data", Map.of("caseSetRef", "cancel-boundaries", "revision", 1,
                                "rows", rows, "enumeratedCount", rows.size()),
                        "diagnostics", List.of()));

        JsonNode response = controller.exchange(request(17, "tools/call", Map.of(
                        "name", "rg.scenario.upsertCases", "arguments", Map.of(
                                "caseSetRef", "cancel-boundaries", "toolRef", "cancel-tool",
                                "rows", List.of(), "enumerateFrom", Map.of(
                                        "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 3),
                                "idempotencyKey", "enum-1"))),
                modernHeaders("tools/call", "rg.scenario.upsertCases")).getBody();

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.at("/result/structuredContent/data/rows/0/enumeration/enumerationMode").asText())
                .isEqualTo("combinatorial");
    }

    @Test
    void mapsAuthenticationAndPurposeFailuresToStablePayloadFreeRpcErrors() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_FAILED", "credential customer-secret rejected",
                        "corr-secret", Map.of("token", "customer-secret"))))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.forbidden(
                        "RG.INTEGRATION.PURPOSE_FORBIDDEN", "purpose provider-secret rejected",
                        "corr-secret", Map.of("operation", "provider-secret"))));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of());

        ResponseEntity<JsonNode> unauthenticated = controller.exchange(
                request(18, "tools/list", Map.of()), modernHeaders("tools/list", null));
        ResponseEntity<JsonNode> forbidden = controller.exchange(
                request(19, "tools/list", Map.of()), modernHeaders("tools/list", null));

        assertThat(unauthenticated.getStatusCode().value()).isEqualTo(401);
        assertThat(unauthenticated.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNotBlank();
        assertThat(unauthenticated.getBody().at("/error/data/code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getBody().at("/error/data/code").asText()).isEqualTo("FORBIDDEN_PURPOSE");
        assertThat(unauthenticated.getBody().toString()).doesNotContain("customer-secret", "corr-secret", "token");
        assertThat(forbidden.getBody().toString()).doesNotContain(
                "provider-secret", "corr-secret", "\"operation\":");
    }

    @Test
    void foldsUnexpectedPreDispatchFailuresWithoutReflectingTheirMaterial() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IllegalStateException("identity-provider customer-secret"));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of());

        JsonNode response = controller.exchange(
                request(20, "tools/list", Map.of()), modernHeaders("tools/list", null)).getBody();

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32603);
        assertThat(response.at("/error/message").asText())
                .isEqualTo("MCP request failed inside the governed boundary");
        assertThat(response.toString()).doesNotContain("identity-provider", "customer-secret");
    }

    @Test
    void rejectsModernRequestWhenRoutingHeadersDisagreeWithBody() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());
        HttpHeaders headers = modernHeaders("tools/list", null);

        JsonNode response = controller.exchange(
                request(3, "tools/call", Map.of("name", "rg.capability.list", "arguments", Map.of())),
                headers).getBody();

        assertThat(response.path("id").asInt()).isEqualTo(3);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32020);
    }

    @Test
    void initializeNegotiatesOnlyKnownVersionsAndPreservesRequestIdOnFailure() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());

        JsonNode modern = controller.exchange(request(11, "initialize", Map.of(
                "protocolVersion", McpProtocolController.MODERN_PROTOCOL_VERSION)), new HttpHeaders()).getBody();
        JsonNode unknown = controller.exchange(request(12, "initialize", Map.of(
                "protocolVersion", "2099-01-01")), new HttpHeaders()).getBody();

        assertThat(modern.path("result").path("protocolVersion").asText())
                .isEqualTo(McpProtocolController.MODERN_PROTOCOL_VERSION);
        assertThat(modern.path("result").path("instructions").asText())
                .startsWith("Use the Agent TDD tools in order");
        assertThat(unknown.path("id").asInt()).isEqualTo(12);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    private JsonNode request(int id, String method, Map<String, ?> params) {
        return mapper.valueToTree(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static HttpHeaders modernHeaders(String method, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.MODERN_PROTOCOL_VERSION);
        headers.set("Mcp-Method", method);
        if (name != null) {
            headers.set("Mcp-Name", name);
        }
        return headers;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_EXECUTE", "corr-1");
    }

    private static GraphDraft decisionDraft() {
        return new GraphDraft(GraphDraft.SCHEMA_VERSION, "cancel-tool", 1, "cancelTool",
                "tenant-a", "project-a", "test", GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(new GraphDraft.DraftNode(
                "policy", "bloge:decisionTable", "Policy", Map.of(), Map.of(
                "rules", List.of(Map.of("id", "R4", "conditions", Map.of(
                        "seconds", "seconds <= 120"), "output", Map.of("decision", "WAIVE_FULL")))), null)),
                List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection("policy", ""),
                Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }

    private static final class McpToolControllerProbe implements McpToolInvoker {
        private boolean called;

        @Override
        public Object invoke(String name, JsonNode arguments, IntegrationRequestContext identity) {
            called = true;
            return Map.of("ok", true, "diagnostics", java.util.List.of());
        }
    }
}
