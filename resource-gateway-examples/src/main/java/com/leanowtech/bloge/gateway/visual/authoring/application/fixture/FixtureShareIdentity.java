package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Trusted, transport-neutral actor identity required to share protected Fixture material. */
public record FixtureShareIdentity(
        AuthoringScope scope,
        String organizationId,
        String region,
        String actorType,
        String actorId,
        String clearance,
        String correlationId) {
    public FixtureShareIdentity {
        if (scope == null || organizationId == null || organizationId.isBlank()
                || region == null || region.isBlank() || actorType == null || actorType.isBlank()
                || actorId == null || actorId.isBlank() || clearance == null
                || correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Fixture share identity is incomplete");
        }
    }
}
