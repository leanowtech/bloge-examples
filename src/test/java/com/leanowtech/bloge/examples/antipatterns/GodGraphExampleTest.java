package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodGraphExampleTest {

    @Test
    void refactoredGraph_preservesBusinessOutcome() {
        var request = new GodGraphExample.TicketRequest("T-100", "vip-1", "Urgent outage in the refund flow", "apac");

        GraphResult bad = GodGraphExample.executeBadScenario(request);
        GraphResult refactored = GodGraphExample.executeComposedScenario(request);

        assertTrue(bad.isSuccess());
        assertTrue(refactored.isSuccess());
        assertEquals(
                bad.getOutput("publishPlan", GodGraphExample.RoutingPlan.class).queue(),
                refactored.getOutput("publishPlan", GodGraphExample.RoutingPlan.class).queue()
        );
        assertEquals(
                bad.getOutput("publishPlan", GodGraphExample.RoutingPlan.class).skills(),
                refactored.getOutput("publishPlan", GodGraphExample.RoutingPlan.class).skills()
        );
        assertTrue(GodGraphExample.lintDslExample().stream()
                .anyMatch(diagnostic -> diagnostic.ruleId().equals("excessive-fan-out")));
    }
}
