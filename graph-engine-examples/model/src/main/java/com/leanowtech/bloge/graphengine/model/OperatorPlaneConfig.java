package com.leanowtech.bloge.graphengine.model;

import java.util.List;
import java.util.Map;

/**
 * Deployment-scoped operator execution-plane configuration.
 *
 * @param builtinEnabled built-in operators remain available when {@code true}
 * @param pluginClasspath plugin JARs visible to the deployment when present
 * @param remoteWorkers logical remote worker bindings keyed by operator reference
 */
public record OperatorPlaneConfig(
        boolean builtinEnabled,
        List<String> pluginClasspath,
        Map<String, RemoteWorkerBinding> remoteWorkers
) {
    public OperatorPlaneConfig {
        pluginClasspath = pluginClasspath == null ? List.of() : List.copyOf(pluginClasspath);
        remoteWorkers = remoteWorkers == null ? Map.of() : Map.copyOf(remoteWorkers);
    }

    /**
     * Returns the default in-process execution-plane configuration.
     *
     * @return default operator-plane configuration
     */
    public static OperatorPlaneConfig defaults() {
        return new OperatorPlaneConfig(true, List.of(), Map.of());
    }
}
