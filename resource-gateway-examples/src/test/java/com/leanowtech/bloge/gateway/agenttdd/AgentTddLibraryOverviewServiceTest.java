package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the payload-free business projection of native and library operator contracts. */
class AgentTddLibraryOverviewServiceTest {

    @Test
    void projectsBusinessBuildingBlocksWorldOperationsAndContractTypes() {
        List<OperatorDefinition> operators = List.of(
                base("httpResource", "EXTERNAL"),
                base("bloge:decisionTable", "PURE"),
                base("bloge:transform", "PURE"),
                libraryOperator("ride:order-lookup", "订单查询", false),
                libraryOperator("ride:policy-lookup", "政策查询", true));
        VisualOperatorCatalog catalog = new FixedCatalog(operators);

        Map<String, Object> overview = new AgentTddLibraryOverviewService(catalog).overview(identity());

        List<?> buildingBlocks = (List<?>) overview.get("buildingBlocks");
        assertThat(buildingBlocks).hasSize(5);
        assertThat(buildingBlocks.toString())
                .contains("调用一个外部服务", "按规则表判定", "整理并输出数据")
                .contains("ride:order-lookup", "ride:policy-lookup");
        Map<?, ?> worldModel = (Map<?, ?>) overview.get("worldModel");
        List<?> operations = (List<?>) worldModel.get("operations");
        assertThat(operations).hasSize(2);
        assertThat(operations.toString())
                .contains("inputs=[orderId]", "outputs=[order]")
                .contains("bound=false", "bound=true");
        assertThat(worldModel.get("types").toString())
                .contains("ride:order-lookup.order", "orderId", "feeCharged");
    }

    @Test
    void projectsPayloadFreeProvidedSampleMetadataForTheSecondAct() {
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset stored = mock(StoredFixtureAsset.class);
        FixtureAssetDescriptor descriptor = mock(FixtureAssetDescriptor.class);
        when(stored.descriptor()).thenReturn(descriptor);
        when(descriptor.fixtureAssetId()).thenReturn("provided-ride-order");
        when(descriptor.lifecycle()).thenReturn(FixtureAssetDescriptor.FixtureLifecycle.DRAFT);
        when(descriptor.source()).thenReturn(new FixtureAssetDescriptor.FixtureSource(
                FixtureAssetDescriptor.SourceKind.SAMPLE, null));
        when(descriptor.variantKey()).thenReturn("order");
        when(fixtures.listHeads(any(), eq(false), eq(100), eq(0))).thenReturn(List.of(stored));

        Map<String, Object> overview = new AgentTddLibraryOverviewService(
                new FixedCatalog(List.of()), fixtures).overview(identity());

        assertThat(overview.get("samples")).asList().singleElement().asString()
                .contains("provided-ride-order", "DRAFT", "SAMPLE", "order")
                .doesNotContain("payload", "sampleValue");
    }

    private static OperatorDefinition base(String ref, String effect) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display(ref, "", List.of()),
                OperatorDefinition.Source.builtIn("bloge-dsl"),
                new OperatorDefinition.Ports(List.of(), List.of()),
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities(effect, "NONE", false, false, false),
                new OperatorDefinition.Lowering("native", ref, Map.of()), List.of());
    }

    private static OperatorDefinition libraryOperator(String ref, String title, boolean bound) {
        SchemaEnvelope order = SchemaEnvelope.object(Map.of(
                "orderId", Map.of("type", "string"),
                "feeCharged", Map.of("type", "number")), List.of("orderId"));
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display(title, "", List.of()),
                new OperatorDefinition.Source("user-library", "", "", "", false, "ride"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("orderId", SchemaEnvelope.opaque(), true, "")),
                        List.of(new OperatorDefinition.Port("order", order, true, ""))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering(bound ? "resource-descriptor" : "design",
                        bound ? "httpResource" : "",
                        bound ? Map.of("bindingRef", "resource:ride-policy") : Map.of()), List.of());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_READ", "corr-1");
    }

    private record FixedCatalog(List<OperatorDefinition> operators) implements VisualOperatorCatalog {
        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            assertThat(query.tenantId()).isEqualTo("tenant-a");
            assertThat(query.namespace()).isEqualTo("project-a");
            assertThat(query.environment()).isEqualTo("test");
            return operators;
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return operators.stream().filter(operator -> operator.operatorRef().equals(operatorRef)).findFirst();
        }
    }
}
