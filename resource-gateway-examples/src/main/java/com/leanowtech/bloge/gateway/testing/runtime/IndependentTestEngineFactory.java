package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.TimeSource;

import java.util.List;

/**
 * Constructs the short-lived test engine with an explicit zero-production-interceptor contract.
 *
 * <p>Keeping construction in a dedicated type makes isolation structurally testable. Response
 * cache, tenant rate limiter, circuit breaker, production listeners, durable stores, and ambient
 * context carriers are never copied from the application engine.</p>
 */
public class IndependentTestEngineFactory {

    private final OperatorRegistry registry;

    /** @param registry frozen operator bindings shared by identity, not by engine configuration */
    public IndependentTestEngineFactory(OperatorRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    /** @return a new run-scoped engine with only the supplied evidence recorder listener */
    public GraphEngine create(InvocationRecorder recorder) {
        return create(recorder, null);
    }

    /**
     * Creates a new run-scoped engine with an optional deterministic time source.
     * @param recorder run-scoped evidence listener
     * @param timeSource logical time source, or {@code null} for system time
     * @return isolated engine
     */
    public GraphEngine create(InvocationRecorder recorder, TimeSource timeSource) {
        GraphEngine.Builder builder = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(recorder))
                .extensionListeners(List.of())
                .contextCarriers(List.of())
                .requestResponseDefaults();
        if (timeSource != null) {
            builder.timeSource(timeSource);
        }
        return builder.build();
    }

    /** @return immutable construction facts suitable for architecture tests and capability probes */
    public Configuration configuration() {
        return new Configuration(List.of(), List.of(InvocationRecorder.class.getName()),
                false, false, false);
    }

    /**
     * @param interceptorTypes configured interceptor class names
     * @param listenerTypes configured listener class names
     * @param durableStores whether durable production stores are attached
     * @param productionContextCarriers whether production ambient context carriers are attached
     * @param productionExtensionListeners whether production extension listeners are attached
     */
    public record Configuration(List<String> interceptorTypes, List<String> listenerTypes,
                                boolean durableStores, boolean productionContextCarriers,
                                boolean productionExtensionListeners) {
        /** Creates immutable configuration lists. */
        public Configuration {
            interceptorTypes = interceptorTypes == null ? List.of() : List.copyOf(interceptorTypes);
            listenerTypes = listenerTypes == null ? List.of() : List.copyOf(listenerTypes);
        }
    }
}
