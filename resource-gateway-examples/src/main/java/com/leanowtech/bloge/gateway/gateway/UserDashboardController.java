package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing non-streaming gateway graph endpoints.
 *
 * <p>Each endpoint builds a {@link GraphContext} from request parameters, delegates
 * execution to {@link GatewayGraphService}, and returns the graph output as a
 * JSON-friendly response. The controller itself contains no business logic — all
 * orchestration is defined in the corresponding {@code .bloge} graphs.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/gateway/dashboard/{userId}} — user dashboard aggregation</li>
 *   <li>{@code GET /api/gateway/products/{productId}} — product detail with conditional routing</li>
 *   <li>{@code GET /api/gateway/orders/{userId}/enriched} — enriched order list via foreach</li>
 *   <li>{@code GET /api/gateway/credit-score/{userId}} — multi-provider credit score</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gateway")
public class UserDashboardController {

    private static final Logger log = LoggerFactory.getLogger(UserDashboardController.class);

    private final GatewayGraphService graphService;

    /**
     * @param graphService the gateway graph execution service
     */
    public UserDashboardController(GatewayGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Assembles a unified dashboard payload for the given user by executing the
     * {@code userDashboard} graph in parallel-aggregation mode.
     *
     * @param userId the authenticated user identifier
     * @return a JSON response containing profile, orders, recommendations, wallet, and notifications
     */
    @GetMapping("/dashboard/{userId}")
    public ResponseEntity<GatewayResponse> dashboard(@PathVariable String userId) {
        log.info("Dashboard request for userId={}", userId);
        GraphContext ctx = new GraphContext(Map.of("userId", userId));
        GraphResult result = graphService.execute("userDashboard", ctx);
        return toResponse(result, "assembleDashboard");
    }

    /**
     * Fetches product detail with conditional enrichment based on product type by
     * executing the {@code productDetail} graph.
     *
     * @param productId the product identifier
     * @return a JSON response containing base product data plus type-specific enrichment
     */
    @GetMapping("/products/{productId}")
    public ResponseEntity<GatewayResponse> productDetail(@PathVariable String productId) {
        log.info("Product detail request for productId={}", productId);
        GraphContext ctx = new GraphContext(Map.of("productId", productId));
        GraphResult result = graphService.execute("productDetail", ctx);
        // unifyDetail may be cancelled when branch routing leaves un-taken paths
        // un-resolved; fall back to the individual branch assemble nodes.
        return toResponse(result, "unifyDetail", "assemblePhysical", "assembleDigital", "assembleGeneric");
    }

    /**
     * Enriches a user's order list with shipping and invoice data by executing the
     * {@code enrichOrderList} graph with foreach parallelism.
     *
     * @param userId the user whose orders should be enriched
     * @return a JSON response containing the enriched order list
     */
    @GetMapping("/orders/{userId}/enriched")
    public ResponseEntity<GatewayResponse> enrichedOrders(@PathVariable String userId) {
        log.info("Enriched orders request for userId={}", userId);
        GraphContext ctx = new GraphContext(Map.of("userId", userId));
        GraphResult result = graphService.execute("enrichOrderList", ctx);
        return toResponse(result, "collectEnriched");
    }

    /**
     * Obtains a credit score from multiple providers with fallback degradation by
     * executing the {@code creditScore} graph.
     *
     * @param userId the user whose credit score is requested
     * @return a JSON response containing the score and the provider that produced it
     */
    @GetMapping("/credit-score/{userId}")
    public ResponseEntity<GatewayResponse> creditScore(@PathVariable String userId) {
        log.info("Credit score request for userId={}", userId);
        GraphContext ctx = new GraphContext(Map.of("userId", userId));
        GraphResult result = graphService.execute("creditScore", ctx);
        // assembleResult may be cancelled when the primary/secondary branch leaves
        // the other path un-resolved; fall back to individual branch nodes.
        return toResponse(result, "assembleResult", "assemblePrimary", "assembleSecondary");
    }

    /**
     * Converts a {@link GraphResult} into a standardised HTTP response.
     *
     * <p>When a convergence transform (e.g. {@code unifyDetail}) is cancelled because
     * branch routing leaves un-taken paths un-resolved, the method tries each candidate
     * node in order until it finds one with output.
     *
     * @param result         the graph execution result
     * @param outputNodes    candidate terminal nodes whose output should be returned (tried in order)
     * @return 200 with the output on success, or 502 with error details on failure
     */
    private static ResponseEntity<GatewayResponse> toResponse(GraphResult result, String... outputNodes) {
        if (result.isSuccess()) {
            Object output = null;
            for (String node : outputNodes) {
                output = result.findOutput(node, Object.class).orElse(null);
                if (output != null) break;
            }
            return ResponseEntity.ok(new GatewayResponse(
                    true,
                    output,
                    null,
                    result.elapsed().toMillis()
            ));
        }
        String errorMessage = result.errors().stream()
                .map(e -> e.nodeId() + ": " + e.exception().getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Unknown error");
        return ResponseEntity.status(502).body(new GatewayResponse(
                false,
                null,
                errorMessage,
                result.elapsed().toMillis()
        ));
    }
}
