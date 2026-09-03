package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streamable-HTTP JSON-RPC boundary for the RG Agent TDD tool surface.
 *
 * <p>The boundary supports both the sessionless 2026-07-28 protocol and Codex clients using the
 * negotiated 2025-06-18 or 2025-11-25 lifecycle. Stateless requests mirror method and tool name in
 * routing headers; negotiated legacy requests use their JSON-RPC body and protocol-version header.
 * The legacy {@code notifications/initialized} message is acknowledged without manufacturing a
 * JSON-RPC response. Application identity is always derived from the existing RG integration
 * authority.</p>
 */
@RestController
public final class McpProtocolController {
    public static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    public static final String LEGACY_PROTOCOL_VERSION = "2025-11-25";
    public static final String CODEX_PROTOCOL_VERSION = "2025-06-18";
    private static final String AGENT_INSTRUCTIONS = "Use the Agent TDD tools in order: READ, "
            + "AUTHORING, EXECUTION, then GOVERNANCE. Never invent runtime bindings or approval "
            + "evidence. RED and GREEN must report realExternalCalls=0. Human Oracle approval and "
            + "executable signoff happen through the review boundary; pause and ask the operator. "
            + "Call rg.readiness.get before publish and publish only when publishable=true.";

    private final ObjectMapper mapper;
    private final McpToolCatalog catalog;
    private final IntegrationRequestAuthenticator authenticator;
    private final McpToolInvoker invoker;

    /** Creates the authenticated MCP transport. */
    public McpProtocolController(ObjectMapper mapper,
                                 McpToolCatalog catalog,
                                 IntegrationRequestAuthenticator authenticator,
                                 McpToolInvoker invoker) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.invoker = Objects.requireNonNull(invoker, "invoker");
    }

    /**
     * Exchanges one MCP request or legacy lifecycle notification.
     *
     * @param request JSON-RPC request object
     * @param headers protocol routing and integration authentication headers
     * @return JSON-RPC response with no-store policy
     */
    @PostMapping(value = "/mcp", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> exchange(@RequestBody JsonNode request,
                                             @RequestHeader HttpHeaders headers) {
        JsonNode id = request != null && request.isObject() ? request.get("id") : null;
        try {
            if (isInitializedNotification(request)) {
                validateLegacyNotification(headers);
                return ResponseEntity.accepted()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build();
            }
            requireRequest(request);
            String method = text(request, "method");
            validateRouting(headers, method, request.path("params"));
            Object result = switch (method) {
                case "server/discover" -> discover();
                case "initialize" -> initialize(request.path("params"));
                case "tools/list" -> listTools(authenticate(headers, McpToolImpact.READ));
                case "tools/call" -> callTool(request.path("params"), headers);
                default -> throw new McpProtocolException(-32601, "Unsupported MCP method");
            };
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id == null ? mapper.nullNode() : id);
            response.set("result", mapper.valueToTree(result));
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
        } catch (McpProtocolException failure) {
            return protocolFailure(failure, id);
        } catch (IntegrationProblemException failure) {
            return authenticationFailure(failure.problem(), id);
        } catch (RuntimeException failure) {
            return protocolFailure(new McpProtocolException(
                    -32603, "MCP request failed inside the governed boundary"), id);
        }
    }

    /** Maps safe protocol failures to JSON-RPC errors without exposing application payloads. */
    @ExceptionHandler(McpProtocolException.class)
    public ResponseEntity<JsonNode> protocolFailure(McpProtocolException failure) {
        return protocolFailure(failure, null);
    }

    private ResponseEntity<JsonNode> protocolFailure(McpProtocolException failure, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        response.set("error", mapper.valueToTree(Map.of(
                "code", failure.code(),
                "message", failure.getMessage()
        )));
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    /**
     * Maps integration authentication failures to a payload-free JSON-RPC boundary.
     *
     * <p>The integration authority retains its HTTP status and audit record, while callers see only
     * the Agent TDD stable code. Provider messages, correlation material and authorization details
     * never cross the MCP response boundary.</p>
     */
    private ResponseEntity<JsonNode> authenticationFailure(IntegrationProblem problem, JsonNode id) {
        String stableCode = stableAuthenticationCode(problem);
        String safeMessage = switch (stableCode) {
            case "UNAUTHENTICATED" -> "Authentication is required.";
            case "FORBIDDEN_PURPOSE" -> "The authenticated purpose does not authorize this operation.";
            default -> "Authentication could not be completed inside the governed boundary.";
        };
        int rpcCode = switch (stableCode) {
            case "UNAUTHENTICATED" -> -32001;
            case "FORBIDDEN_PURPOSE" -> -32003;
            default -> -32603;
        };
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        response.set("error", mapper.valueToTree(Map.of(
                "code", rpcCode,
                "message", safeMessage,
                "data", Map.of("code", stableCode, "retryable", problem.retryable()))));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.status())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (problem.status() == 401) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"resource-gateway-integration\"");
        }
        return builder.body(response);
    }

    private static String stableAuthenticationCode(IntegrationProblem problem) {
        if (problem.status() == 401) return "UNAUTHENTICATED";
        if (problem.status() == 403 || problem.code().contains("PURPOSE")) return "FORBIDDEN_PURPOSE";
        return "GATE_REJECTED";
    }

    private Object callTool(JsonNode params, HttpHeaders headers) {
        String name = text(params, "name");
        McpToolDefinition definition = catalog.require(name);
        JsonNode arguments = params.path("arguments");
        if (!arguments.isObject()) {
            throw new McpProtocolException(-32602, "Tool arguments must be an object");
        }
        requireSchemaMatch(definition.inputSchema(), arguments, -32602,
                "Tool arguments do not match the declared input schema");
        IntegrationRequestContext identity = authenticate(headers, definition.impact());
        JsonNode structured;
        try {
            structured = mapper.valueToTree(invoker.invoke(name, arguments, identity));
        } catch (McpProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new McpProtocolException(-32603, "Tool execution failed inside the governed boundary");
        }
        requireSchemaMatch(definition.outputSchema(), structured, -32603,
                "Tool response does not match the declared output schema");
        boolean isError = structured.has("ok") && !structured.path("ok").asBoolean();
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", structured.toString())),
                "structuredContent", structured,
                "isError", isError
        );
    }

    private Map<String, Object> listTools(IntegrationRequestContext ignored) {
        return Map.of("tools", catalog.all().stream().map(McpToolDefinition::protocolView).toList());
    }

    private Map<String, Object> discover() {
        return Map.of(
                "protocolVersion", MODERN_PROTOCOL_VERSION,
                "serverInfo", Map.of("name", "bloge-resource-gateway", "version", "1.4.0"),
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "instructions", AGENT_INSTRUCTIONS
        );
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = text(params, "protocolVersion");
        if (!requested.isBlank() && !MODERN_PROTOCOL_VERSION.equals(requested)
                && !LEGACY_PROTOCOL_VERSION.equals(requested)
                && !CODEX_PROTOCOL_VERSION.equals(requested)) {
            throw new McpProtocolException(-32602, "Unsupported MCP protocol version");
        }
        return Map.of(
                "protocolVersion", requested.isBlank() ? LEGACY_PROTOCOL_VERSION : requested,
                "serverInfo", Map.of("name", "bloge-resource-gateway", "version", "1.4.0"),
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "instructions", AGENT_INSTRUCTIONS
        );
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, McpToolImpact impact) {
        IntegrationOperation operation = impact.operation();
        return authenticator.authenticate(headers, operation);
    }

    private static void requireRequest(JsonNode request) {
        if (request == null || !request.isObject() || !"2.0".equals(text(request, "jsonrpc"))
                || !request.has("id")) {
            throw new McpProtocolException(-32600, "A JSON-RPC 2.0 request with id is required");
        }
    }

    private static boolean isInitializedNotification(JsonNode request) {
        return request != null && request.isObject()
                && "2.0".equals(text(request, "jsonrpc"))
                && !request.has("id")
                && "notifications/initialized".equals(text(request, "method"));
    }

    /**
     * Validates the only lifecycle notification consumed by this synchronous server.
     *
     * <p>Codex sends the negotiated protocol version after initialization. No custom routing
     * headers are required by the 2025-11-25 transport, and the server intentionally does not
     * create a session because all Agent TDD state is carried by authenticated durable assets.</p>
     */
    private static void validateLegacyNotification(HttpHeaders headers) {
        String version = header(headers, "MCP-Protocol-Version");
        if (!version.isBlank() && !LEGACY_PROTOCOL_VERSION.equals(version)
                && !CODEX_PROTOCOL_VERSION.equals(version)) {
            throw new McpProtocolException(-32022, "Unsupported MCP lifecycle notification version");
        }
    }

    private static void validateRouting(HttpHeaders headers, String method, JsonNode params) {
        String version = header(headers, "MCP-Protocol-Version");
        if (version.isBlank()) {
            return;
        }
        if (LEGACY_PROTOCOL_VERSION.equals(version) || CODEX_PROTOCOL_VERSION.equals(version)) {
            return;
        }
        if (!MODERN_PROTOCOL_VERSION.equals(version)) {
            throw new McpProtocolException(-32022, "Unsupported MCP protocol version");
        }
        if (!method.equals(header(headers, "Mcp-Method"))) {
            throw new McpProtocolException(-32020, "Mcp-Method must match the JSON-RPC method");
        }
        if ("tools/call".equals(method)
                && !text(params, "name").equals(header(headers, "Mcp-Name"))) {
            throw new McpProtocolException(-32020, "Mcp-Name must match params.name");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) {
            return "";
        }
        return node.path(field).asText().trim();
    }

    private static String header(HttpHeaders headers, String name) {
        String value = headers == null ? null : headers.getFirst(name);
        return value == null ? "" : value.trim();
    }

    /**
     * Enforces the exact schema advertised by {@code tools/list} without reflecting rejected data.
     *
     * <p>Only a fixed protocol error is returned. Validator diagnostics are deliberately not copied
     * because they may contain rejected property names or values supplied by the caller.</p>
     */
    private void requireSchemaMatch(Map<String, Object> schema,
                                    JsonNode value,
                                    int errorCode,
                                    String safeMessage) {
        Object schemaVisible = mapper.convertValue(value, Object.class);
        SchemaEnvelope envelope = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        if (!VisualSchemaValidator.validateValue(envelope, schemaVisible, "/mcp").isEmpty()) {
            throw new McpProtocolException(errorCode, safeMessage);
        }
    }
}
