package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;

import java.util.List;

/**
 * Supervises one isolated invocation without exposing application services to function code.
 */
public interface AuthoringFunctionTestWorker {

    /** @return the versioned isolation profile placed into run evidence */
    String executionProfile();

    /**
     * Executes one exact runtime function invocation.
     *
     * @param functionName exact callable lookup name
     * @param expectedRuntimeFingerprint fingerprint observed by the gateway
     * @param args bounded, nullable arguments
     * @return payload-bearing ephemeral worker response
     */
    InvocationResponse invoke(String functionName,
                              String expectedRuntimeFingerprint,
                              List<Object> args);
}
