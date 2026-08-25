package com.leanowtech.bloge.gateway.visual.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualSimulationPlanTest {

    @Test
    void normalizesStringsAndDefensivelyCopiesCollections() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customerId", "C-1");
        List<VisualSimulationPlan.Standin> standins = new ArrayList<>();
        standins.add(new VisualSimulationPlan.Standin(
                " node ", " operator.customer ", Map.of("tier", "gold"), null));

        VisualSimulationPlan plan = new VisualSimulationPlan(
                "  graph source  ", context, " output ", standins);
        context.put("mutated", true);
        standins.clear();

        assertThat(plan.generatedDsl()).isEqualTo("graph source");
        assertThat(plan.businessContext()).containsExactly(Map.entry("customerId", "C-1"));
        assertThat(plan.selectedOutputNode()).isEqualTo("output");
        assertThat(plan.standins()).singleElement().satisfies(standin -> {
            assertThat(standin.originalNodeId()).isEqualTo("node");
            assertThat(standin.rewrittenOperatorRef()).isEqualTo("operator.customer");
        });
        assertThatThrownBy(() -> plan.businessContext().put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.standins().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullsUseStableDefaultsAndExpectedInputIsOptional() {
        VisualSimulationPlan plan = new VisualSimulationPlan(null, null, null,
                List.of(new VisualSimulationPlan.Standin(null, "  ", null, null)));

        assertThat(plan.generatedDsl()).isEmpty();
        assertThat(plan.businessContext()).isEmpty();
        assertThat(plan.selectedOutputNode()).isEmpty();
        assertThat(plan.standins()).singleElement().satisfies(standin -> {
            assertThat(standin.originalNodeId()).isEmpty();
            assertThat(standin.rewrittenOperatorRef()).isEmpty();
            assertThat(standin.output()).isNull();
            assertThat(standin.expectedInputOptional()).isEmpty();
        });
    }

    @Test
    void executorIsAVisualOwnedFunctionalPort() {
        VisualSimulationExecutor executor = plan -> null;
        assertThat(executor).isNotNull();
    }
}
