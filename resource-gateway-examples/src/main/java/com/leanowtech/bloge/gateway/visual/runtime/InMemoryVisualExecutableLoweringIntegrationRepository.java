package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory executable lowering integration repository for tests and lightweight examples.
 */
public class InMemoryVisualExecutableLoweringIntegrationRepository
        implements VisualExecutableLoweringIntegrationRepository {

    private final ConcurrentHashMap<String, VisualExecutableLoweringIntegration> integrations =
            new ConcurrentHashMap<>();

    @Override
    public Collection<VisualExecutableLoweringIntegration> all() {
        return integrations.values().stream()
                .sorted(Comparator.comparing(VisualExecutableLoweringIntegration::createdAt)
                        .reversed()
                        .thenComparing(VisualExecutableLoweringIntegration::integrationId))
                .toList();
    }

    @Override
    public Optional<VisualExecutableLoweringIntegration> find(String integrationId) {
        return Optional.ofNullable(integrations.get(integrationId));
    }

    @Override
    public VisualExecutableLoweringIntegration create(VisualExecutableLoweringIntegration integration) {
        if (integration == null) {
            throw new IllegalArgumentException("Executable lowering integration is required.");
        }
        String integrationId = integration.integrationId().isBlank()
                ? UUID.randomUUID().toString()
                : integration.integrationId();
        if (integrations.containsKey(integrationId)) {
            throw new IllegalArgumentException("Executable lowering integration already exists: " + integrationId);
        }
        if (findActiveByActivationId(integration.activationId()).isPresent()) {
            throw new IllegalArgumentException(
                    "Executable lowering integration already exists for activation: "
                            + integration.activationId());
        }
        Instant now = Instant.now();
        VisualExecutableLoweringIntegration stored = integration.withIdentity(integrationId, 1, now, now);
        VisualExecutableLoweringIntegration previous = integrations.putIfAbsent(integrationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Executable lowering integration already exists: " + integrationId);
        }
        return stored;
    }

    @Override
    public VisualExecutableLoweringIntegration update(VisualExecutableLoweringIntegration integration) {
        if (integration == null || integration.integrationId().isBlank()) {
            throw new IllegalArgumentException("Executable lowering integration id is required for update.");
        }
        if (!integrations.containsKey(integration.integrationId())) {
            throw new IllegalArgumentException(
                    "Executable lowering integration does not exist: " + integration.integrationId());
        }
        VisualExecutableLoweringIntegration stored = integration.withIdentity(
                integration.integrationId(),
                integration.revision() + 1,
                integration.createdAt(),
                Instant.now()
        );
        integrations.put(integration.integrationId(), stored);
        return stored;
    }
}
