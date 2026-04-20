package com.leanowtech.bloge.examples.approval;

import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateDef;
import com.leanowtech.bloge.state.model.StateMachineDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewStateMachineWithSessionExampleTest {

    @Test
    void dslCompile_embedsSyntheticSessionOperatorRef() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef definition = ReviewStateMachineWithSessionDslExample.compile(registry);

        StateDef review = definition.states().get("review");
        assertEquals(1, review.graph().nodes().size());
        assertEquals(
                ReservedKeys.extensionOperatorRef("session", "reviewSession"),
                review.graph().nodes().get("reviewSession").operatorRef()
        );
    }

    @Test
    @Timeout(10)
    void javaApi_nestedSessionCompletesAndDrivesApprovedTransition() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        ReviewStateMachineWithSessionExample.registerOperators(registry);
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        com.leanowtech.bloge.core.engine.GraphEngine.builder().registry(registry).build())
                .build();

        StateMachineResult result = executor.execute(
                ReviewStateMachineWithSessionExample.buildStateMachine(),
                Map.of("applicantId", "APP-1001", "riskLevel", 15)
        );

        assertTrue(result.isCompleted());
        assertEquals("approved", result.instance().currentStateId());
        assertEquals("approved", decision(result));
        assertEquals("APP-1001", collectedApplicantId(result));
    }

    @Test
    @Timeout(10)
    void dsl_nestedSessionCompletesAndDrivesRejectedTransition() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef definition = ReviewStateMachineWithSessionDslExample.compile(registry);
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        com.leanowtech.bloge.core.engine.GraphEngine.builder().registry(registry).build())
                .build();

        StateMachineResult result = executor.execute(
                definition,
                Map.of("applicantId", "APP-2001", "riskLevel", 90)
        );

        assertTrue(result.isCompleted());
        assertEquals("rejected", result.instance().currentStateId());
        assertEquals("rejected", decision(result));
        assertEquals("APP-2001", collectedApplicantId(result));
    }

    private static String decision(StateMachineResult result) {
        Map<String, Object> review = asMap(result.instance().getStateOutputs("review"));
        Map<String, Object> reviewSession = asMap(review.get("reviewSession"));
        Map<String, Object> finalizePhase = asMap(reviewSession.get("finalize"));
        Map<String, Object> finalizeOutput = asMap(finalizePhase.get("output"));
        Map<String, Object> decideReview = asMap(finalizeOutput.get("decideReview"));
        return String.valueOf(decideReview.get("decision"));
    }

    private static String collectedApplicantId(StateMachineResult result) {
        Map<String, Object> review = asMap(result.instance().getStateOutputs("review"));
        Map<String, Object> reviewSession = asMap(review.get("reviewSession"));
        Map<String, Object> collectPhase = asMap(reviewSession.get("collect"));
        Map<String, Object> collectOutput = asMap(collectPhase.get("output"));
        Map<String, Object> collectReview = asMap(collectOutput.get("collectReview"));
        return String.valueOf(collectReview.get("applicantId"));
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
