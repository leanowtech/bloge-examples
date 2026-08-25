package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Payload-free projection of an authenticated test-control admission.
 *
 * <p>The effective request is the only value passed to the execution service. Fidelity and
 * scope are retained as bounded declarations for later stages; they are deliberately not
 * interpreted as execution policy in stage zero.</p>
 */
record TestExecutionIngress(
        TestExecutionApiRequest request,
        String fidelityToken,
        String scopeToken) {

    TestExecutionIngress {
        request = Objects.requireNonNull(request, "request");
    }

    @Override
    public String toString() {
        return "TestExecutionIngress{requestPresent=true"
                + ", fidelityPresent=" + (fidelityToken != null)
                + ", scopePresent=" + (scopeToken != null) + "}";
    }
}
