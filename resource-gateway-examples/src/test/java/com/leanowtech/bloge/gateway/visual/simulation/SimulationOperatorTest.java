package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.CollectionSchema;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.StructuredSchema;
import com.leanowtech.bloge.core.schema.TypedSchema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    void projectsNestedFixtureShapeForCompilerPathValidation() {
        SimulationOperator operator = SimulationOperator.returning("profile", Map.of(
                "payload", Map.of(
                        "score", 728,
                        "tags", List.of("prime", "verified")
                )
        ));

        assertThat(operator.inputSchema()).isSameAs(OpaqueSchema.INSTANCE);
        assertThat(operator.outputSchema()).isInstanceOfSatisfying(StructuredSchema.class, root -> {
            assertThat(root.hasField("payload")).isTrue();
            assertThat(root.fieldSchema("payload")).isInstanceOfSatisfying(
                    StructuredSchema.class,
                    payload -> {
                        assertThat(payload.fieldSchema("score")).isEqualTo(new TypedSchema(Integer.class));
                        assertThat(payload.fieldSchema("tags")).isInstanceOfSatisfying(
                                CollectionSchema.class,
                                tags -> assertThat(tags.elementSchema())
                                        .isEqualTo(new TypedSchema(String.class))
                        );
                    }
            );
        });
    }

    @Test
    void boundsRecursiveFixtureProjectionWithOpaqueCycle() {
        Map<String, Object> recursive = new LinkedHashMap<>();
        recursive.put("name", "root");
        recursive.put("self", recursive);

        SimulationOperator operator = SimulationOperator.returning("recursive", recursive);

        assertThat(operator.outputSchema()).isInstanceOfSatisfying(
                StructuredSchema.class,
                root -> assertThat(root.fieldSchema("self")).isSameAs(OpaqueSchema.INSTANCE)
        );
    }
}
