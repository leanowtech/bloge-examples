package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, one-request-per-process protocol for isolated authoring function tests.
 */
public final class AuthoringFunctionWorkerProtocol {

    public static final String EXECUTION_PROFILE = "bloge-core-isolated-process.v1";
    public static final int INVOCATION_TIMEOUT_MILLIS = 250;
    public static final int SUPERVISOR_TIMEOUT_MILLIS = 5_000;
    public static final int SUITE_TIMEOUT_MILLIS = 15_000;
    public static final int MAXIMUM_REQUEST_BYTES = 256 * 1024;
    public static final int MAXIMUM_RESPONSE_BYTES = 512 * 1024;
    public static final int MAXIMUM_STDERR_BYTES = 16 * 1024;
    public static final int WORKER_HEAP_MIB = 64;
    public static final int WORKER_METASPACE_MIB = 96;
    public static final int MAXIMUM_CONCURRENT_WORKERS = 2;

    private AuthoringFunctionWorkerProtocol() {
    }

    public record InvocationRequest(
            String schemaVersion,
            String requestId,
            String functionName,
            String expectedRuntimeFingerprint,
            List<Object> args
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringFunctionWorkerInvocationRequest.v1";

        public InvocationRequest {
            schemaVersion = normalized(schemaVersion, "");
            requestId = normalized(requestId, "");
            functionName = normalized(functionName, "");
            expectedRuntimeFingerprint = normalized(expectedRuntimeFingerprint, "");
            args = immutableAllowingNull(args);
        }
    }

    public record InvocationResponse(
            String schemaVersion,
            String requestId,
            String executionProfile,
            String runtimeFingerprint,
            InvocationOutcome outcome,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Object actual,
            String errorCode,
            long durationMicros
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringFunctionWorkerInvocationResponse.v1";

        public InvocationResponse {
            schemaVersion = normalized(schemaVersion, "");
            requestId = normalized(requestId, "");
            executionProfile = normalized(executionProfile, "");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
            outcome = outcome == null ? InvocationOutcome.WORKER_FAILED : outcome;
            errorCode = normalized(errorCode, "");
            durationMicros = Math.max(0, durationMicros);
        }
    }

    public enum InvocationOutcome {
        SUCCESS,
        INVOCATION_FAILED,
        TIMEOUT,
        RESOURCE_EXHAUSTED,
        WORKER_UNAVAILABLE,
        WORKER_FAILED
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<Object> immutableAllowingNull(List<Object> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
