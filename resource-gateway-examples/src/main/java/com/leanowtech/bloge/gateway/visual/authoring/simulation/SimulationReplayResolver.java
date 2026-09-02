package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Resolves one exact, authorized replay output without exposing replay payload through evidence. */
@FunctionalInterface
public interface SimulationReplayResolver {
    /** Returns the output bound to the exact replay id and fingerprint in the trusted scope. */
    JsonNode resolve(AuthoringScope scope, String replayId, String fingerprint);
}
