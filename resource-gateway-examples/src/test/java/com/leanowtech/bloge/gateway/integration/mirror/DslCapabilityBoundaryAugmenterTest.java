package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DslCapabilityBoundaryAugmenterTest {

    @Test
    void addsNestedForeachResourceWithStablePathConditionAndSourceDigest() {
        OperatorDefinition httpResource = httpResource();
        GraphDraft first = DslCapabilityBoundaryAugmenter.augment(draft(httpResource), dsl("shipping.get"),
                catalog(httpResource));
        GraphDraft second = DslCapabilityBoundaryAugmenter.augment(draft(httpResource), dsl("shipping.v2.get"),
                catalog(httpResource));

        assertThat(first.nodes()).extracting(GraphDraft.DraftNode::id)
                .containsExactly("loadOrders", "enrich_fetchShipping");
        GraphDraft.DraftNode nested = first.nodes().getLast();
        assertThat(nested.inputs().get("resourceId").value()).isEqualTo("shipping.get");
        assertThat(first.edges()).filteredOn(edge -> edge.target().nodeId().equals(nested.id()))
                .singleElement().satisfies(edge -> {
                    assertThat(edge.kind()).isEqualTo("route");
                    assertThat(edge.condition()).isEqualTo("foreach:enrich");
                    assertThat(edge.source().nodeId()).isEqualTo("loadOrders");
                });
        assertThat(capabilitySourceFingerprint(first)).isNotEqualTo(capabilitySourceFingerprint(second));
    }

    @Test
    void failsClosedWhenNestedOperatorCannotBeResolved() {
        OperatorDefinition httpResource = httpResource();

        assertThatThrownBy(() -> DslCapabilityBoundaryAugmenter.augment(
                draft(httpResource), dsl("shipping.get"), catalog()))
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.NESTED_OPERATOR_UNRESOLVED"));
    }

    private static Object capabilitySourceFingerprint(GraphDraft draft) {
        return ((Map<?, ?>) draft.visualLayout().get("capabilityProjection")).get("dslSourceFingerprint");
    }

    private static GraphDraft draft(OperatorDefinition operator) {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode("loadOrders", operator.operatorRef(), "Load orders",
                Map.of("resourceId", GraphDraft.Binding.constant("orders.list")), Map.of(),
                new GraphDraft.Position(0, 0));
        return new GraphDraft("", "draft-orders", 1, "orders", "tenant-a", "project-a", "test",
                GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node), List.of(),
                Map.of(), Map.of(), new GraphDraft.OutputSelection("loadOrders", ""),
                Map.of("loadOrders", operator.fingerprint()), Map.of("loadOrders", operator),
                GraphDraft.RevisionMetadata.empty());
    }

    private static VisualOperatorCatalog catalog(OperatorDefinition... operators) {
        Map<String, OperatorDefinition> values = java.util.Arrays.stream(operators)
                .collect(java.util.stream.Collectors.toMap(OperatorDefinition::operatorRef, value -> value));
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return List.copyOf(values.values());
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return Optional.ofNullable(values.get(operatorRef));
            }
        };
    }

    private static OperatorDefinition httpResource() {
        return new OperatorDefinition("", "httpResource", "1.0.0", "",
                new OperatorDefinition.Display("HTTP Resource", "", List.of()),
                OperatorDefinition.Source.builtIn("bloge-operator"),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", false, false, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", "httpResource", Map.of()), List.of());
    }

    private static String dsl(String resourceId) {
        return """
                graph orders {
                  node loadOrders : httpResource {
                    input { resourceId = "orders.list" }
                  }
                  foreach enrich : order in loadOrders.output.payload.orders {
                    node fetchShipping : httpResource {
                      input { resourceId = "%s" }
                    }
                  }
                }
                """.formatted(resourceId);
    }
}
