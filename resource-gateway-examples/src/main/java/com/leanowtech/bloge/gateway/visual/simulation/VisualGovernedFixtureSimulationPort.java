package com.leanowtech.bloge.gateway.visual.simulation;

import org.springframework.http.HttpHeaders;

/**
 * Visual-owned boundary for optional governed Fixture simulation.
 *
 * <p>The visual controller knows only this port and visual DTOs. Authentication, enterprise
 * scope derivation, protected material resolution, and usage accounting belong to the gateway
 * adapter that implements it.</p>
 */
public interface VisualGovernedFixtureSimulationPort {

    /**
     * Simulates a visual request, resolving governed coordinates when present.
     *
     * @param request visual simulation request
     * @param headers transport headers used by the adapter's authenticated boundary
     * @return visual simulation response
     */
    VisualGraphSimulationResponse simulate(VisualGraphSimulationRequest request,
                                            HttpHeaders headers);
}
