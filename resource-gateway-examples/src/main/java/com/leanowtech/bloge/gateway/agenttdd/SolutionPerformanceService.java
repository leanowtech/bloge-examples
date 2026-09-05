package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Map;
import java.util.Objects;

/**
 * Compatibility facade for callers compiled against the v1.4.4 performance service.
 *
 * @deprecated use {@link com.leanowtech.bloge.gateway.solution.ops.OperationsInsightService};
 * runtime counts no longer come from test evidence
 */
@Deprecated(forRemoval = false)
public final class SolutionPerformanceService {
    private final AgentTddStateRepository states;

    /** Creates a read model over the shared evidence repository. */
    public SolutionPerformanceService(AgentTddStateRepository states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    /**
     * Returns rule and disposition counts, escalation rate and currently red GOLDEN identifiers.
     *
     * <p>No input, expected value, runtime result or diagnostic prose enters this projection.</p>
     */
    public Map<String, Object> performance(String solutionRef, IntegrationRequestContext identity) {
        return new com.leanowtech.bloge.gateway.solution.ops.OperationsInsightService(
                states, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())
                .performance(solutionRef, identity);
    }
}
