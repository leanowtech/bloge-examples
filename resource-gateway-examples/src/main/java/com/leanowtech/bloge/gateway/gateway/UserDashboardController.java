package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 *   <li>{@code GET /api/gateway/loan-policy/{applicantId}?amount=...} — decision-table loan policy</li>
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
        return toResponse("userDashboard", result);
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
        return toResponse("productDetail", result);
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
        return toResponse("enrichOrderList", result);
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
        return toResponse("creditScore", result);
    }

    /**
     * Evaluates the resource-backed loan decision policy graph.
     *
     * @param applicantId     applicant identifier
     * @param requestedAmount requested loan amount
     * @return a JSON response containing applicant facts and the matched policy row
     */
    @GetMapping("/loan-policy/{applicantId}")
    public ResponseEntity<GatewayResponse> loanDecisionPolicy(@PathVariable String applicantId,
                                                              @RequestParam("amount") double requestedAmount) {
        log.info("Loan policy request for applicantId={}, amount={}", applicantId, requestedAmount);
        GraphContext ctx = new GraphContext(Map.of(
                "applicantId", applicantId,
                "requestedAmount", requestedAmount
        ));
        GraphResult result = graphService.execute("loanDecisionPolicy", ctx);
        return toResponse("loanDecisionPolicy", result);
    }

    /**
     * Converts a {@link GraphResult} into a standardised HTTP response by using the
     * graph contract's output-node order and output schema.
     *
     * @param graphName     resource graph name
     * @param result         the graph execution result
     * @return 200 with the output on success, or 502 with error details on failure
     */
    private ResponseEntity<GatewayResponse> toResponse(String graphName, GraphResult result) {
        if (result.isSuccess()) {
            GatewayGraphOutput output = graphService.resolveOutput(graphName, result);
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
        return ResponseEntity.status(502).body(new GatewayResponse(
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
