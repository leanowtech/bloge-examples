package com.leanowtech.bloge.examples.approval;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.ext.builder.PhaseBuilder;
import com.leanowtech.bloge.ext.engine.SessionOperator;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.state.builder.StateMachineBuilder;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java fluent example showing a state machine state that delegates to a nested session.
 */
@SuppressWarnings({"preview", "unchecked"})
public final class ReviewStateMachineWithSessionExample {

    static final Operator<Map<String, Object>, Map<String, Object>> COLLECT_REVIEW_CONTEXT = (input, ctx) -> Map.of(
            "applicantId", input.get("applicantId"),
            "riskLevel", input.get("riskLevel")
    );

    static final Operator<Map<String, Object>, Map<String, Object>> DECIDE_REVIEW = (input, ctx) -> {
        Number riskLevel = (Number) input.get("riskLevel");
        String decision = riskLevel != null && riskLevel.intValue() > 50 ? "rejected" : "approved";
        return Map.of(
                "applicantId", input.get("applicantId"),
                "riskLevel", riskLevel,
                "decision", decision
        );
    };

    private ReviewStateMachineWithSessionExample() {
    }

    /**
     * Builds the nested review session executed in the initial state.
     *
     * @return immutable session graph
     */
    public static SessionGraph buildReviewSession() {
        Graph collectGraph = Graph.builder("reviewCollect")
                .node("collectReview", COLLECT_REVIEW_CONTEXT)
                .input((results, ctx) -> Map.of(
                        "applicantId", ctx.get("applicantId", String.class),
                        "riskLevel", ctx.get("riskLevel")
                ))
                .build();

        Graph finalizeGraph = Graph.builder("reviewFinalize")
                .node("decideReview", DECIDE_REVIEW)
                .input((results, ctx) -> {
                    Map<String, Object> collect = asMap(ctx.get("collect"));
                    Map<String, Object> collectOutput = asMap(collect.get("output"));
                    Map<String, Object> review = asMap(collectOutput.get("collectReview"));
                    return Map.of(
                            "applicantId", review.get("applicantId"),
                            "riskLevel", review.get("riskLevel")
                    );
                })
                .build();

        return SessionGraph.builder("reviewSession")
                .idleTimeout(Duration.ofMinutes(5))
                .maxTotalRounds(5)
                .phase(PhaseBuilder.once("collect").graph(collectGraph).then("finalize").build())
                .phase(PhaseBuilder.once("finalize").graph(finalizeGraph).build())
                .build();
    }

    /**
     * Builds the enclosing state machine that embeds {@link #buildReviewSession()}.
     *
     * @return immutable state-machine definition
     */
    public static StateMachineDef buildStateMachine() {
        Graph reviewGraph = Graph.builder("reviewState")
                .node("reviewSession", new SessionOperator("__session__:reviewSession", buildReviewSession()))
                .build();

        return StateMachineBuilder.create("reviewWorkflow")
                .state("review").initial()
                    .graph(reviewGraph)
                    .on("*").when(ReviewStateMachineWithSessionExample::isApproved,
                            "ctx.review.output.reviewSession.finalize.output.decideReview.decision == \"approved\"")
                    .goTo("approved")
                    .on("*").goTo("rejected")
                    .done()
                .state("approved").terminal().done()
                .state("rejected").terminal().done()
                .build();
    }

    /**
     * Executes the sample flow using the builder-based state machine.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registerOperators(registry);
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        GraphEngine.builder().registry(registry).build())
                .build();

        StateMachineResult result = executor.execute(buildStateMachine(), Map.of(
                "applicantId", "APP-1001",
                "riskLevel", 15
        ));
        System.out.println("Review workflow: " + result.status() + " @ " + result.instance().currentStateId());
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }

    /**
     * Registers the example's leaf operators into the supplied registry.
     *
     * @param registry operator registry to update
     */
    public static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("CollectReviewContextOperator", COLLECT_REVIEW_CONTEXT);
        registry.register("DecideReviewOperator", DECIDE_REVIEW);
    }

    private static boolean isApproved(Map<String, Object> evalContext) {
        Map<String, Object> review = asMap(evalContext.get("review"));
        Map<String, Object> output = asMap(review.get("output"));
        Map<String, Object> reviewSession = asMap(output.get("reviewSession"));
        Map<String, Object> finalizePhase = asMap(reviewSession.get("finalize"));
        Map<String, Object> finalizeOutput = asMap(finalizePhase.get("output"));
        Map<String, Object> decideReview = asMap(finalizeOutput.get("decideReview"));
        return "approved".equals(decideReview.get("decision"));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            return normalized;
        }
        return Map.of();
    }
}
