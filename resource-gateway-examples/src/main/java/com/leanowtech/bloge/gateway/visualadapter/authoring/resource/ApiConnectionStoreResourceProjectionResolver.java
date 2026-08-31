package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Adapts committed payload-free Connection heads to Resource projection input. */
public final class ApiConnectionStoreResourceProjectionResolver
        implements ApiResourceConnectionProjectionResolver {
    private final ApiConnectionAuthoringStore connections;

    /** @param connections exact committed Connection authority */
    public ApiConnectionStoreResourceProjectionResolver(ApiConnectionAuthoringStore connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Resolves Auth.NONE only. Credential routing remains owned by the secret
     * runtime and is intentionally not projected through this first slice.
     */
    @Override
    public Optional<ResolvedConnection> resolve(AuthoringScope scope, String connectionId) {
        return connections.findHead(scope, connectionId)
                .filter(stored -> "NONE".equals(stored.view().auth().kind())
                        && !stored.view().auth().configured())
                .map(stored -> {
                    ApiConnectionCommand.Defaults defaults = stored.view().defaults();
                    Map<String, String> headers = defaults == null ? Map.of() : defaults.headers();
                    Duration timeout = defaults == null || defaults.timeoutMs() == null
                            ? Duration.ofSeconds(30) : Duration.ofMillis(defaults.timeoutMs());
                    return new ResolvedConnection(new ApiResourceConnectionSnapshot(
                            stored.view().connectionId(), stored.view().revision(),
                            stored.metadataFingerprint()), new ConnectionMetadata(
                            stored.view().baseUrl(), headers, timeout));
                });
    }
}
