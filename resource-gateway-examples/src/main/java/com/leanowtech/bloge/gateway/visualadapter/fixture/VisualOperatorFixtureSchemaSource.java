package com.leanowtech.bloge.gateway.visualadapter.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureSchemaSource;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.util.List;
import java.util.Objects;

/**
 * Resolves governed Fixture schemas from the exact, scope-filtered visual operator catalog.
 *
 * <p>The adapter derives schema references with the same canonical algorithm used during Fixture
 * provision. It never trusts a client-provided schema document or upgrades a stale reference, so
 * an operator-contract change immediately fails Fixture closure.</p>
 */
public final class VisualOperatorFixtureSchemaSource implements FixtureSchemaSource {

    private final VisualOperatorCatalog operators;
    private final ObjectMapper mapper;

    /**
     * Creates an exact schema authority backed by the current operator catalog.
     *
     * @param operators scope-aware operator catalog
     * @param mapper canonical JSON mapper used by schema fingerprints
     */
    public VisualOperatorFixtureSchemaSource(VisualOperatorCatalog operators, ObjectMapper mapper) {
        this.operators = Objects.requireNonNull(operators, "operators");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean schemaIsCurrent(EnterpriseScope scope, ExactSchemaRef schemaRef) {
        if (scope == null || schemaRef == null) return false;
        OperatorCatalogQuery query = new OperatorCatalogQuery(
                "", List.of(), false, true,
                scope.tenantId(), scope.projectId(), scope.environment());
        return operators.list(query).stream().anyMatch(operator -> matches(operator, schemaRef));
    }

    private boolean matches(OperatorDefinition operator, ExactSchemaRef expected) {
        if (operator == null || operator.ports() == null || operator.ports().outputs().isEmpty()) {
            return false;
        }
        try {
            if (operator.ports().outputs().size() == 1
                    && GraphNodeFixturePromotionService.exactOutputSchemaRef(operator, mapper)
                    .equals(expected)) {
                return true;
            }
            return operator.ports().outputs().stream().anyMatch(port ->
                    GraphNodeFixturePromotionService.exactOutputSchemaRef(
                            operator, port.name(), mapper).equals(expected));
        } catch (GraphNodeFixturePromotionException opaqueOrInvalidContract) {
            return false;
        }
    }
}
