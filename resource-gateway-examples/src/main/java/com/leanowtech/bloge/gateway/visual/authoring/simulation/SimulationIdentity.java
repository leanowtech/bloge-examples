package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Trusted, transport-neutral identity used only for governed Fixture material reads. */
public record SimulationIdentity(
        AuthoringScope scope,
        String organizationId,
        String region,
        String actorType,
        String actorId,
        String clearance,
        String correlationId) {
    public SimulationIdentity {
        if (scope == null || blank(organizationId) || blank(region) || blank(actorType)
                || blank(actorId) || blank(clearance) || blank(correlationId)) {
            throw new IllegalArgumentException("Simulation identity is incomplete");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
