package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable catalog adapter backed only by one already-authorized DSL authoring context. */
final class DslAuthoringContextCatalog implements VisualOperatorCatalog {
    private final Map<String, OperatorDefinition> operators;
    private final List<OperatorLibrary.BuiltInFunction> functions;

    DslAuthoringContextCatalog(DslAuthoringContext context) {
        this.operators = Map.copyOf(context.operators());
        this.functions = context.functions().values().stream()
                .sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name)).toList();
    }

    /** Lists only frozen definitions; no registry or network access is possible. */
    @Override
    public List<OperatorDefinition> list(OperatorCatalogQuery query) {
        return operators.values().stream().sorted(Comparator.comparing(OperatorDefinition::operatorRef)).toList();
    }

    /** Finds one exact frozen definition. */
    @Override
    public Optional<OperatorDefinition> find(String operatorRef) {
        return Optional.ofNullable(operators.get(operatorRef));
    }

    /** Resolves all refs from the same immutable map. */
    @Override
    public Map<String, OperatorDefinition> findAll(Iterable<String> operatorRefs) {
        if (operatorRefs == null) return Map.of();
        java.util.LinkedHashMap<String, OperatorDefinition> result = new java.util.LinkedHashMap<>();
        for (String ref : operatorRefs) {
            OperatorDefinition value = operators.get(ref);
            if (value != null) result.putIfAbsent(ref, value);
        }
        return Map.copyOf(result);
    }

    /** Returns only the functions frozen into the context. */
    @Override
    public List<OperatorLibrary.BuiltInFunction> builtInFunctions(OperatorCatalogQuery query) {
        return functions;
    }
}
