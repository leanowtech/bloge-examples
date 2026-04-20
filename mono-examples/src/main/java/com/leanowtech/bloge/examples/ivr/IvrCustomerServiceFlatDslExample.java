package com.leanowtech.bloge.examples.ivr;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.GraphComplexityValidator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive IVR customer service modeled as a single flat DSL graph.
 *
 * <p>Uses the same menu structure as {@link IvrCustomerServiceFlatExample}, but encoded
 * entirely in one `.bloge` file to contrast with SubGraph-based DSL composition.
 *
 * <pre>
 * mainMenu
 * ├─ billing → billingDispute → duplicateCharge
 * ├─ techSupport → networkIssues → cannotConnect
 * ├─ accountInsurance → warrantyClaims → submitClaim
 * ├─ ordersLogistics
 * ├─ complaints
 * └─ liveAgent
 * </pre>
 *
 * @see IvrCustomerServiceExample
 * @see IvrCustomerServiceDslExample
 * @see IvrCustomerServiceFlatExample
 */
@SuppressWarnings({"unchecked", "preview"})
public class IvrCustomerServiceFlatDslExample {

    // ── Flat Graph Trade-offs ──────────────────────────────────────
    // All ~70 nodes in one graph — mirrors traditional IVR "flat config" approach.
    // Compare with IvrCustomerServiceExample which uses SubGraph composition.
    // Flat approach: simpler mental model, but harder to maintain at scale.
    // SubGraph approach: modular, testable, reusable, but requires SubGraphOperator wiring.

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registerPascalCaseOperators(registry);

        GraphComplexityValidator.setLimits(
                GraphComplexityValidator.getLimits()
                        .withHardMaxNodes(120)
                        .withHardMaxDepth(20)
                        .withHardMaxBranchNesting(8)
                        .withRecommendedMaxNodes(100)
                        .withRecommendedMaxDepth(14)
                        .withRecommendedMaxBranchNesting(5));

        var loader = new GraphLoader(registry);
        String dsl = loadResource("/bloge/ivr-customer-service-flat.bloge");
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "callId", "CALL-IVR-FLAT-DSL-001",
                "customerId", "CUST-9001",
                "callerPhone", "+86-139-8888-1001",
                "simulatedKeys", Map.of(
                        "main-menu", "3",
                        "account-insurance-menu", "3",
                        "warranty-claims-menu", "2",
                        "submit-claim-menu", "4"
                )
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  IVR Customer Service Result (DSL/Flat)");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("callSummary") == NodeStatus.COMPLETED) {
            System.out.println("  Call summary : " + result.results().getRaw("callSummary"));
        }
        if (result.getStatus("satisfactionSurvey") == NodeStatus.COMPLETED) {
            System.out.println("  Survey       : " + result.results().getRaw("satisfactionSurvey"));
        }
        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            System.out.println("  Record saved : " + result.results().getRaw("saveCallRecord"));
        }
        System.out.println("═══════════════════════════════════════════════");
    }

    private static void registerPascalCaseOperators(DefaultOperatorRegistry registry) {
        for (var field : IvrCustomerServiceDslExample.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Operator.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                String pascalName = toPascalCase(field.getName());
                registry.register(pascalName, (Operator<?, ?>) field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to register operator field: " + field.getName(), e);
            }
        }
    }

    private static String toPascalCase(String constantName) {
        StringBuilder sb = new StringBuilder();
        for (String part : constantName.toLowerCase().split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private static String loadResource(String resourcePath) {
        try (var stream = IvrCustomerServiceFlatDslExample.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, e);
        }
    }
}
