package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.model.CompiledGraph;
import com.leanowtech.bloge.core.runtime.registry.CompiledGraphCatalog;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.util.Objects;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import java.util.Map;

/** Resolves the compiler-owned artifact; it never recompiles or accepts a caller artifact. */
public final class CompiledFunctionInventoryProvider {
    private final CompiledGraphCatalog catalog;

    public CompiledFunctionInventoryProvider(CompiledGraphCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public FunctionInvocationInventory build(Graph graph, InvocationInventory inventory) {
        if (graph == null || inventory == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        CompiledGraph artifact;
        try {
            artifact = catalog.require(graph.name());
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
        }
        if (artifact.graph() != graph) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
        }
        return new FunctionInvocationInventoryBuilder().build(artifact, inventory);
    }

    public Map<String, ExpressionFunction> runtimeFunctions() {
        return catalog.functionRegistry();
    }
}
