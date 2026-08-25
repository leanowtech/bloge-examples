package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.TimeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return create(recorder, timeSource, Map.of());
    }

    /**
     * Creates a new run-scoped engine with frozen bindings that exist only in that engine.
     *
     * @param recorder run-scoped evidence listener
     * @param timeSource logical time source, or {@code null} for system time
     * @param runScopedBindings bindings added to a private registry overlay
     * @return isolated engine
     */
    public GraphEngine create(InvocationRecorder recorder, TimeSource timeSource,
                              Map<String, ?> runScopedBindings) {
        OperatorRegistry effectiveRegistry = runScopedBindings == null || runScopedBindings.isEmpty()
                ? registry
                : new RunScopedOperatorRegistry(registry, runScopedBindings);
        GraphEngine.Builder builder = GraphEngine.builder()
                .registry(effectiveRegistry)
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

    private static final class RunScopedOperatorRegistry implements OperatorRegistry {
        private final OperatorRegistry delegate;
        private final DefaultOperatorRegistry overlay = new DefaultOperatorRegistry();

        private RunScopedOperatorRegistry(OperatorRegistry delegate, Map<String, ?> bindings) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
            new LinkedHashMap<>(bindings).forEach(overlay::registerRaw);
        }

        @Override
        public void register(String name, com.leanowtech.bloge.core.operator.Operator<?, ?> operator) {
            overlay.register(name, operator);
        }

        @Override
        public void registerRaw(String name, Object operator) {
            overlay.registerRaw(name, operator);
        }

        @Override
        public Object lookup(String name) {
            return overlay.contains(name) ? overlay.lookup(name) : delegate.lookup(name);
        }

        @Override
        public OperatorMetadata metadata(String name) {
            return overlay.contains(name) ? overlay.metadata(name) : delegate.metadata(name);
        }

        @Override
        public boolean contains(String name) {
            return overlay.contains(name) || delegate.contains(name);
        }

        @Override
        public List<String> discover(String pattern) {
            List<String> names = new ArrayList<>(delegate.discover(pattern));
            names.addAll(overlay.discover(pattern));
            return names.stream().distinct().sorted().toList();
        }

        @Override
        public void addRegistrationListener(RegistrationListener listener) {
            overlay.addRegistrationListener(listener);
        }
    }
}
