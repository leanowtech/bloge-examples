package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionControlPreparationTest {

    private static final String SHA = "sha256:" + "a".repeat(64);

    @Test
    void exposesOnlyBindingsAndConsumesProvidersOnce() {
        InvocationInventory inventory = new InvocationInventory(List.of(), Map.of(), Map.of());
        FixtureBundle fixture = new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION,
                "fixture-a",
                1,
                SHA,
                "INTERNAL",
                null,
                7L,
                List.of(),
                List.of(),
                Map.of());
        ExecutionControlPreparation preparation = ExecutionControlPreparation.prepare(
                new ObjectMapper(), fixture, inventory);
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION,
                "plan-a",
                SHA,
                "CAPABILITY_CONFORMANCE",
                SHA,
                SHA,
                List.of(),
                List.of(),
                preparation.bindings(),
                Map.of(),
                List.of());

        CompiledExecutionControl compiled = preparation.assemble(
                SHA, plan, Map.of(), List.of(), inventory,
                null, null);

        assertThat(compiled.executionServices()).isNotNull();
        assertThat(compiled.effectivePlan().executionServiceBindings())
                .isEqualTo(preparation.bindings());
        assertThatThrownBy(() -> preparation.assemble(
                SHA, plan, Map.of(), List.of(), inventory, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }
}
