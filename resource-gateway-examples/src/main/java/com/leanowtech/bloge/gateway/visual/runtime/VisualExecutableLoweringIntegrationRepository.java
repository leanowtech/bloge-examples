package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for executable lowering integration facts.
 */
public interface VisualExecutableLoweringIntegrationRepository {

    /**
     * @return all stored executable lowering integrations
     */
    Collection<VisualExecutableLoweringIntegration> all();

    /**
     * Finds one integration.
     *
     * @param integrationId integration id
     * @return integration when present
     */
    Optional<VisualExecutableLoweringIntegration> find(String integrationId);

    /**
     * Persists a new integration.
     *
     * @param integration integration to create
     * @return stored integration with repository identity
     */
    VisualExecutableLoweringIntegration create(VisualExecutableLoweringIntegration integration);

    /**
     * Updates an existing integration.
     *
     * @param integration integration record to replace
     * @return stored integration record
     */
    VisualExecutableLoweringIntegration update(VisualExecutableLoweringIntegration integration);

    /**
     * Finds the active executable lowering integration for one adapter activation.
     *
     * @param activationId adapter activation id
     * @return active executable lowering integration when present
     */
    default Optional<VisualExecutableLoweringIntegration> findActiveByActivationId(String activationId) {
        String normalized = activationId == null ? "" : activationId.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return all().stream()
                .filter(integration -> normalized.equals(integration.activationId()))
                .filter(VisualExecutableLoweringIntegration::active)
                .findFirst();
    }
}
