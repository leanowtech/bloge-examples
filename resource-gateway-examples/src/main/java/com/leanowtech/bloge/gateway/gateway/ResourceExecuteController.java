package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified public API for executing any registered resource by {@code resourceId}.
 *
 * <p>Accepts a JSON body ({@link ResourceExecuteRequest}) containing the resource
 * identifier, call parameters, and optional overrides. Tenant/namespace/auth metadata
 * is conveyed through HTTP headers:
 * <ul>
 *   <li>{@code X-Tenant-Id} — tenant scope for the execution</li>
 *   <li>{@code X-Namespace} — namespace within the tenant</li>
 *   <li>{@code Authorization} — forwarded to the outgoing call when no per-call
 *       {@code authOverride} is specified in the request body</li>
 * </ul>
 *
 * <p>Execution is routed through the {@code resourceDispatch} BLOGE graph, which
 * contains a single {@code httpResource} node. The graph engine handles descriptor
 * resolution, parameter mapping, header merging, and response validation.
 *
 * <h3>Endpoint</h3>
 * {@code POST /api/gateway/resources/execute}
 */
@RestController
@RequestMapping("/api/gateway/resources")
public class ResourceExecuteController {

    private static final Logger log = LoggerFactory.getLogger(ResourceExecuteController.class);

    private static final String DISPATCH_GRAPH = "resourceDispatch";
    private static final String DISPATCH_NODE = "executeResource";

    private final GatewayGraphService graphService;

    /**
     * @param graphService the gateway graph execution service
     */
    public ResourceExecuteController(GatewayGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Executes a registered resource by its {@code resourceId}.
     *
     * <p>Builds a {@link GraphContext} from the request body and HTTP headers, then
     * delegates to the {@code resourceDispatch} graph. The graph result's
     * {@code executeResource} node output is returned to the caller unchanged so the
     * API exposes the full execution envelope ({@code resourceId}, status, payload,
     * raw body, duration, and success flag).
     *
     * @param request     the execution request body
     * @param tenantId    tenant identifier (from {@code X-Tenant-Id} header)
     * @param namespace   namespace (from {@code X-Namespace} header)
     * @param authorization optional forwarded authorization header
     * @return the resource execution result wrapped in a {@link GatewayResponse}
     */
    @PostMapping("/execute")
    public ResponseEntity<GatewayResponse> execute(
            @RequestBody ResourceExecuteRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-Namespace", defaultValue = "default") String namespace,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("Resource execute request: resourceId={}, tenantId={}, namespace={}",
                request.resourceId(), tenantId, namespace);

        // Build header overrides: merge request body overrides with forwarded Authorization
        Map<String, String> headerOverrides = new HashMap<>(request.headerOverrides());
        if (authorization != null && !authorization.isBlank() && !headerOverrides.containsKey("Authorization")) {
            headerOverrides.put("Authorization", authorization);
        }

        // Build graph context with tenant scope and dispatch parameters
        var tenantContext = new TenantContext(tenantId, namespace);
        var ctx = new GraphContext(tenantContext);
        ctx.put("resourceId", request.resourceId());
        ctx.put("params", request.params());
        ctx.put("headerOverrides", headerOverrides);
        if (request.timeoutOverride() != null) {
            ctx.put("timeoutOverride", request.timeoutOverride());
        }
        if (request.authOverride() != null) {
            ctx.put("authOverride", request.authOverride());
        }

        GraphResult result = graphService.execute(DISPATCH_GRAPH, ctx);

        if (result.isSuccess()) {
            GatewayGraphOutput output = graphService.resolveOutput(DISPATCH_GRAPH, result, DISPATCH_NODE);
            if (!output.valid()) {
                return ResponseEntity.status(502).body(new GatewayResponse(
                        false,
                        null,
                        diagnosticSummary(output.diagnostics()),
                        result.elapsed().toMillis()
                ));
            }
            return ResponseEntity.ok(new GatewayResponse(
                    true,
                    output.output(),
                    null,
                    result.elapsed().toMillis()
            ));
        }

        String errorMessage = result.errors().stream()
                .map(e -> e.nodeId() + ": " + e.exception().getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Unknown error");

        // Surface resource-not-found as 404 rather than 502
        boolean isNotFound = result.errors().stream()
                .anyMatch(e -> e.exception() instanceof ResourceNotFoundException);
        int status = isNotFound ? 404 : 502;

        return ResponseEntity.status(status).body(new GatewayResponse(
                false,
                null,
                errorMessage,
                result.elapsed().toMillis()
        ));
    }

    private static String diagnosticSummary(List<VisualDiagnostic> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> "%s: %s".formatted(diagnostic.code(), diagnostic.message()))
                .collect(Collectors.joining("; "));
    }
}
