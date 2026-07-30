package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;

/**
 * Structured lifecycle failure translated directly by the authoring transport.
 */
public final class AuthoringLifecycleException extends RuntimeException {

    private final AuthoringProblem problem;

    public AuthoringLifecycleException(AuthoringProblem problem) {
        super(problem == null ? "Visual library authoring failed." : problem.message());
        this.problem = problem;
    }

    public AuthoringProblem problem() {
        return problem;
    }
}
