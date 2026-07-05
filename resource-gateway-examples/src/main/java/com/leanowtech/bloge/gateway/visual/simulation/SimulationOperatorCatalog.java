package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link VisualOperatorCatalog} decorator that adds synthetic native operator definitions for the
 * per-node simulation stand-ins ({@code __sim_<nodeId>}) while delegating every other lookup to the
 * real catalog.
 *
 * <p>This lets the existing {@code GraphDraftDslGenerator} lower mocked nodes as ordinary native
 * operator nodes with no change to the generator: the generator resolves {@code __sim_<nodeId>} to the
 * synthetic definition and emits {@code node <id> : __sim_<id> { ... }}, which the simulation registry
 * then resolves to a {@link SimulationOperator}.</p>
 */
final class SimulationOperatorCatalog implements VisualOperatorCatalog {

    private final VisualOperatorCatalog delegate;
    private final Map<String, OperatorDefinition> synthetic;

    SimulationOperatorCatalog(VisualOperatorCatalog delegate, Map<String, OperatorDefinition> synthetic) {
        this.delegate = delegate;
        this.synthetic = synthetic;
    }

    @Override
    public List<OperatorDefinition> list(OperatorCatalogQuery query) {
        return delegate.list(query);
    }

    @Override
    public Optional<OperatorDefinition> find(String operatorRef) {
        OperatorDefinition syntheticDefinition = synthetic.get(operatorRef);
        return syntheticDefinition != null ? Optional.of(syntheticDefinition) : delegate.find(operatorRef);
    }
}
