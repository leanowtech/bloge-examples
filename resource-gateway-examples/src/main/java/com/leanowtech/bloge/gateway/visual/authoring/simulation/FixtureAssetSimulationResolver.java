package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;

/** Resolves one exact ACTIVE protected Fixture material inside an authenticated simulation. */
@FunctionalInterface
public interface FixtureAssetSimulationResolver {
    /** Returns the protected output without weakening its exact asset coordinate. */
    JsonNode resolve(SimulationIdentity identity, FixtureSetCommand.Material.FixtureAsset material);
}
