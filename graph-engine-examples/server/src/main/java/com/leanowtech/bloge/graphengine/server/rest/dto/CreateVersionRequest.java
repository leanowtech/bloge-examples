package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that creates a new immutable graph version draft.
 *
 * @param version semantic version string
 * @param dslSource authoritative `.bloge` source
 * @param visualLayout optional visual layout JSON
 * @param migrationPolicy resume-time migration policy
 */
public record CreateVersionRequest(
        @NotBlank String version,
        @NotBlank String dslSource,
        String visualLayout,
        GraphMigrationPolicy migrationPolicy
) {
}
