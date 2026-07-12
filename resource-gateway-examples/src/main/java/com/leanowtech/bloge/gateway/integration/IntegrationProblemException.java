package com.leanowtech.bloge.gateway.integration;

/**
 * Service-layer failure carrying a stable integration problem.
 */
public class IntegrationProblemException extends RuntimeException {

    private final IntegrationProblem problem;

    public IntegrationProblemException(IntegrationProblem problem) {
        super(problem == null ? "Integration request failed" : problem.title());
        this.problem = problem == null
                ? IntegrationProblem.badRequest("RG.INTEGRATION.UNKNOWN", "Integration request failed.", "", null)
                : problem;
    }

    public IntegrationProblem problem() {
        return problem;
    }
}
