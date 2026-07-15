package com.leanowtech.bloge.gateway.integration;

/** Profile-owned capability marker; absent from production application contexts. */
public record TestabilityAvailability(boolean executionEndpointEnabled) {
}
