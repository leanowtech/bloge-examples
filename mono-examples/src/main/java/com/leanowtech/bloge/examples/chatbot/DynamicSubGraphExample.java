package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.operators.spi.CommonOperators;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates the supported "graph factory" pattern: one node emits BLOGE DSL and a
 * downstream {@code dynamicSubGraph} node compiles and executes it immediately.
 */
@SuppressWarnings("preview")
public final class DynamicSubGraphExample {

    public record PlanRequest(String task, List<String> history) {
    }

    public record PlanOutput(String dslSource, String planKind) {
    }

    public record DynamicReplyInput(String task, List<String> history, String planKind) {
    }

    public record DynamicReply(String summary, int historySize, String planKind) {
    }

    private static final String DSL_RESOURCE = "/bloge/dynamic-agent.bloge";

    static final Operator<Map<String, Object>, PlanOutput> PLAN_GENERATOR = new PlanGeneratorOperator();

    static final Operator<Map<String, Object>, DynamicReply> COMPOSE_DYNAMIC_REPLY =
            new ComposeDynamicReplyOperator();

    private DynamicSubGraphExample() {
    }

    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registerOperators(registry);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    public static GraphResult execute(String task, List<String> history) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .listeners(List.of())
                .interceptors(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(
                "task", task,
                "history", history
        )));
    }

    public static void main(String[] args) {
        String task = args.length > 0 ? args[0] : "Draft a refund follow-up for order #42";
        List<String> history = List.of("Customer opened a refund request", "Support asked for order details");

        GraphResult result = execute(task, history);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedOutputs = result.getOutput("executePlan", Map.class);
        DynamicReply reply = (DynamicReply) nestedOutputs.get("draftReply");

        System.out.println("Success: " + result.isSuccess());
        System.out.println("Nested outputs: " + nestedOutputs);
        System.out.println("Reply summary: " + reply.summary());
    }

    private static void registerOperators(DefaultOperatorRegistry registry) {
        CommonOperators.builder()
                .dynamic()
                .build()
                .registerAll(registry);
        registry.register("generateDynamicDsl", PLAN_GENERATOR);
        registry.register("composeDynamicReply", COMPOSE_DYNAMIC_REPLY);
    }

    private static final class PlanGeneratorOperator implements Operator<Map<String, Object>, PlanOutput> {
        @Override
        @SuppressWarnings("unchecked")
        public PlanOutput execute(Map<String, Object> input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            PlanRequest request = new PlanRequest(
                    String.valueOf(input.get("task")),
                    (List<String>) input.getOrDefault("history", List.of())
            );
            return new PlanOutput(
                    """
                            graph generatedPlan {
                              node draftReply : composeDynamicReply {
                                input {
                                  task = ctx.task
                                  history = ctx.history
                                  planKind = ctx.planKind
                                }
                              }
                            }
                            """,
                    request.task().toLowerCase().contains("refund") ? "refund" : "general"
            );
        }
    }

    private static final class ComposeDynamicReplyOperator implements Operator<Map<String, Object>, DynamicReply> {
        @Override
        @SuppressWarnings("unchecked")
        public DynamicReply execute(Map<String, Object> input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            DynamicReplyInput replyInput = new DynamicReplyInput(
                    String.valueOf(input.get("task")),
                    (List<String>) input.getOrDefault("history", List.of()),
                    String.valueOf(input.get("planKind"))
            );
            return new DynamicReply(
                    "Dynamic plan '%s' prepared a response for: %s".formatted(replyInput.planKind(), replyInput.task()),
                    replyInput.history().size(),
                    replyInput.planKind()
            );
        }
    }
}
