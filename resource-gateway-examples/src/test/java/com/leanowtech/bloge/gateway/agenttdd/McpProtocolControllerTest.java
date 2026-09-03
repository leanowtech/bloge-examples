package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the stateless MCP 2026-07-28 HTTP/JSON-RPC boundary. */
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
    void callsToolWithImpactSpecificAuthenticationAndStructuredEnvelope() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_EXECUTE)))
                .thenReturn(identity());
        McpToolInvoker invoker = (name, arguments, identity) -> Map.of(
                "ok", true,
                "data", Map.of("side", "RED", "realExternalCalls", 0),
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
}
