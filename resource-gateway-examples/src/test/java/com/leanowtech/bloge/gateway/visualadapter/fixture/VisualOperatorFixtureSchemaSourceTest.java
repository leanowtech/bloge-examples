package com.leanowtech.bloge.gateway.visualadapter.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies exact and scope-filtered Fixture schema resolution from visual operators. */
class VisualOperatorFixtureSchemaSourceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyTheCurrentExactSchemaFromTheRequestedEnterpriseScope() {
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        OperatorDefinition operator = operator();
        when(catalog.list(any(OperatorCatalogQuery.class))).thenReturn(List.of(operator));
        VisualOperatorFixtureSchemaSource source = new VisualOperatorFixtureSchemaSource(catalog, mapper);
        EnterpriseScope scope = new EnterpriseScope("tenant-a", "org-a", "project-a", "sandbox", "sg");
        ExactSchemaRef current = GraphNodeFixturePromotionService.exactOutputSchemaRef(operator, mapper);

        assertThat(source.schemaIsCurrent(scope, current)).isTrue();
        assertThat(source.schemaIsCurrent(scope,
                new ExactSchemaRef(current.id(), current.revision(), "sha256:" + "0".repeat(64))))
                .isFalse();

        ArgumentCaptor<OperatorCatalogQuery> query = ArgumentCaptor.forClass(OperatorCatalogQuery.class);
        verify(catalog, org.mockito.Mockito.times(2)).list(query.capture());
        assertThat(query.getAllValues()).allSatisfy(value -> {
            assertThat(value.tenantId()).isEqualTo("tenant-a");
            assertThat(value.namespace()).isEqualTo("project-a");
            assertThat(value.environment()).isEqualTo("sandbox");
        });
    }

    @Test
    void failsClosedForMissingScopeSchemaOrExactOutputContract() {
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        when(catalog.list(any(OperatorCatalogQuery.class))).thenReturn(List.of(operatorWithOpaqueOutput()));
        VisualOperatorFixtureSchemaSource source = new VisualOperatorFixtureSchemaSource(catalog, mapper);
        EnterpriseScope scope = new EnterpriseScope("tenant-a", "org-a", "project-a", "sandbox", "sg");
        ExactSchemaRef arbitrary = new ExactSchemaRef("profile", 1, "sha256:" + "1".repeat(64));

        assertThat(source.schemaIsCurrent(null, arbitrary)).isFalse();
        assertThat(source.schemaIsCurrent(scope, null)).isFalse();
        assertThat(source.schemaIsCurrent(scope, arbitrary)).isFalse();
    }

    private static OperatorDefinition operator() {
        return operatorWithSchema(SchemaEnvelope.object(
                Map.of("tier", Map.of("type", "string")), List.of("tier")));
    }

    private static OperatorDefinition operatorWithOpaqueOutput() {
        return operatorWithSchema(SchemaEnvelope.opaque());
    }

    private static OperatorDefinition operatorWithSchema(SchemaEnvelope schema) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", "profile:read", "1.0.0", "",
                new OperatorDefinition.Display("Customer profile", "", List.of("resource-read")),
                new OperatorDefinition.Source("user-library", "", "", "", false, "profile"),
                new OperatorDefinition.Ports(List.of(), List.of(
                        new OperatorDefinition.Port("payload", schema, true, "Profile payload"))),
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", false, false, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", "profile:read", Map.of()), List.of());
    }
}
