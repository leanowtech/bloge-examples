package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.core.spi.OperatorRegistry;

/**
 * Factory for DSL runners that need a request-scoped operator registry.
 *
 * <p>Simulation uses this to execute a graph with synthetic mock operators without making the visual
 * simulation service know which concrete gateway composer is hosting the registry.</p>
 */
public interface VisualDslRunnerFactory {

    /**
     * Creates a runner backed by the supplied operator registry.
     *
     * @param registry operator registry used for this execution context
     * @return DSL runner bound to the supplied registry
     */
    VisualDslRunner forRegistry(OperatorRegistry registry);
}
