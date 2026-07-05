package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.SideEffectType;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SimulationOperator}.
 */
class SimulationOperatorTest {

    @Test
    void returnsConfiguredOutputOnEveryInvocation() throws Exception {
        Object output = Map.of("eligible", true);
        SimulationOperator operator = SimulationOperator.returning("node1", output);

        assertThat(operator.execute(Map.of("in", 1), null)).isSameAs(output);
        assertThat(operator.execute(Map.of("in", 2), null)).isSameAs(output);
    }

    @Test
    void recordsInvocationCountAndLastInput() throws Exception {
        SimulationOperator operator = SimulationOperator.returning("node1", "out");

        assertThat(operator.invocationCount()).isZero();
        assertThat(operator.lastInput()).isNull();

        operator.execute("first", null);
        operator.execute("second", null);

        assertThat(operator.invocationCount()).isEqualTo(2);
        assertThat(operator.lastInput()).isEqualTo("second");
    }

    @Test
    void isDeclaredSideEffectFreeAndIdempotent() {
        SimulationOperator operator = SimulationOperator.returning("node1", "out");

        assertThat(operator.sideEffectType()).isEqualTo(SideEffectType.READ_ONLY);
        assertThat(operator.idempotency()).isEqualTo(Idempotency.IDEMPOTENT);
    }

    @Test
    void allowsNullOutput() throws Exception {
        SimulationOperator operator = SimulationOperator.returning("node1", null);
        assertThat(operator.execute("in", null)).isNull();
        assertThat(operator.label()).isEqualTo("node1");
    }
}
