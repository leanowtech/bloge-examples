package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.NestedGraphProvider;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationInventoryBuilderTest {

    private static final String TARGET = "sha256:" + "c".repeat(64);
    private final DefaultOperatorRegistry rootRegistry = new DefaultOperatorRegistry();
    private final InvocationInventoryBuilder builder =
            new InvocationInventoryBuilder(rootRegistry);

    @Test
    void freezesNestedAndCompensationSitesWithRuntimeCompatibleCoordinates() {
        DefaultOperatorRegistry nestedRegistry = new DefaultOperatorRegistry();
        Operator<Object, Object> lookup = (input, context) -> "real";
        nestedRegistry.register("customer.lookup", lookup);
        Graph child = registryGraph("item/body", "lookup/id", "customer.lookup");
        ForEachOperator foreach = new ForEachOperator(child, nestedRegistry, false);
        Operator<Object, Object> compensation = (input, context) -> "undone";
        Graph root = new GraphBuilder("root")
                .node("enrich/orders", foreach)
                .compensate(compensation)
                .build();

        InvocationInventory inventory = builder.build(root, TARGET);

        assertThat(inventory.entries()).hasSize(3);
        assertThat(inventory.byEngineStructuralId()).containsKeys(
                "/root/enrich~1orders#PRIMARY",
                "/root/enrich~1orders#COMPENSATION",
                "/root/enrich~1orders/item~1body/lookup~1id#PRIMARY");
        assertThat(inventory.byInvocationSiteId())
                .containsKey("/root/enrich~1orders/item~1body/lookup~1id#PRIMARY");
        assertThat(inventory.byEngineStructuralId()
                .get("/root/enrich~1orders/item~1body/lookup~1id#PRIMARY").frozenOperator())
                .isSameAs(lookup);
        assertThat(inventory.entries().stream().map(entry -> entry.site().invocationKind()))
                .contains(InvocationSite.InvocationKind.COMPENSATION);
        InvocationInventory.Entry primary = inventory.byInvocationSiteId()
                .get("/root/enrich~1orders#PRIMARY");
        InvocationInventory.Entry undo = inventory.byInvocationSiteId()
                .get("/root/enrich~1orders#COMPENSATION");
        assertThat(undo.site().runtimeBindingFingerprint())
                .isNotEqualTo(primary.site().runtimeBindingFingerprint());
    }

    @Test
    void mapsEnginePrimaryResourceSiteToGovernanceResourceKind() {
        Operator<Object, Object> resource = (input, context) -> input;
        rootRegistry.register("httpResource", resource);
        Graph graph = registryGraph("resource", "fetch", "httpResource");

        InvocationInventory inventory = builder.build(graph, TARGET);

        assertThat(inventory.byEngineStructuralId()).containsKey("/root/fetch#PRIMARY");
        assertThat(inventory.byInvocationSiteId()).containsKey("/root/fetch#RESOURCE");
        assertThat(inventory.entries().getFirst().frozenOperator()).isSameAs(resource);
    }

    @Test
    void freezesMissingHttpResourceAsStableFailClosedExternalBinding() {
        Graph graph = registryGraph("resource", "fetch", "httpResource");

        InvocationInventory first = builder.build(graph, TARGET);
        InvocationInventory second = builder.build(graph, TARGET);
        EphemeralHttpResourceOperator frozen = (EphemeralHttpResourceOperator)
                first.entries().getFirst().frozenOperator();

        assertThat(frozen).isInstanceOf(Operator.class);
        assertThat(frozen.sideEffectType()).isEqualTo(SideEffectType.EXTERNAL_CALL);
        assertThat(frozen.idempotency()).isEqualTo(Idempotency.UNKNOWN);
        assertThatThrownBy(() -> frozen.execute(Map.of(), null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(first.entries().getFirst().site().runtimeBindingFingerprint())
                .isEqualTo(second.entries().getFirst().site().runtimeBindingFingerprint());
    }

    @Test
    void missingNonHttpResourceStillRejectsUnresolvedOperator() {
        Graph graph = registryGraph("missing", "fetch", "notRegistered");

        assertThatThrownBy(() -> builder.build(graph, TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_OPERATOR_UNRESOLVED"));
    }

    @Test
    void rejectsCyclesBeforeAnyGraphNodeCanRun() {
        MutableNestedOperator nested = new MutableNestedOperator();
        Graph graph = new GraphBuilder("cycle").node("self", nested).build();
        nested.binding = new NestedGraphProvider.NestedGraphBinding("again", graph, rootRegistry);

        assertThatThrownBy(() -> builder.build(graph, TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTROL_PLAN_INVENTORY_CYCLE"));
    }

    private static Graph registryGraph(String name, String nodeId, String operatorRef) {
        NodeSpec node = new NodeSpec(nodeId, operatorRef, null, ResilienceConfig.DEFAULT,
                Map.of(), OpaqueSchema.INSTANCE, OpaqueSchema.INSTANCE);
        return new Graph(name, Map.of(nodeId, node), List.of(), Set.of(nodeId), Set.of(nodeId),
                SchemaValidationLevel.OFF);
    }

    private static final class MutableNestedOperator
            implements Operator<Object, Object>, NestedGraphProvider {
        private NestedGraphBinding binding;

        @Override
        public Object execute(Object input, OperatorContext context) {
            throw new AssertionError("preflight must reject before execution");
        }

        @Override
        public List<NestedGraphBinding> nestedGraphBindings() {
            return List.of(binding);
        }
    }
}
