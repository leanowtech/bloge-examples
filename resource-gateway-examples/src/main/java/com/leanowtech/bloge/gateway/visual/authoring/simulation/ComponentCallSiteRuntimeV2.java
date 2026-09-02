package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/**
 * Executes one exact local Operator while exposing only its compiler-owned function call sites.
 *
 * <p>The adapter owns real Operator semantics. Immediately before each built-in invocation it must
 * call {@link CallInterceptor#invoke}; the interceptor decides whether to run the supplied local
 * function or return a Fixture result. It must not cache that decision because repeated calls at the
 * same static site can carry different inputs and always require distinct Invocation Keys.</p>
 */
public interface ComponentCallSiteRuntimeV2 {
    /** Runs one exact Operator and returns its normal output projection. */
    JsonNode execute(AuthoringScope scope, ExactFixtureSubjectRefV2.OperatorVersion operator,
                     JsonNode input, CallInterceptor interceptor);

    /** Per-dynamic-call boundary used by the Fixture runtime. */
    @FunctionalInterface
    interface CallInterceptor {
        JsonNode invoke(ComponentSimulationAuthorityV2.CallSite callSite, JsonNode input,
                        RealFunctionCall realCall);
    }

    /** Local, credential-free built-in invocation supplied by the Operator runtime. */
    @FunctionalInterface
    interface RealFunctionCall {
        JsonNode invoke();
    }
}
