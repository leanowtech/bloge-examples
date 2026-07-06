package com.leanowtech.bloge.gateway.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock response for one descriptor-backed resource call during a graph contract-test run.
 *
 * @param resourceId logical resource id expected in the httpResource input
 * @param expectedParams optional exact parameter map used to match and assert the call
 * @param payload payload exposed through {@code HttpResourceOutput.payload}
 * @param statusCode mocked HTTP status code
 * @param rawBody mocked raw response body
 * @param durationMs mocked call duration in milliseconds
 * @param success whether the resource call should be considered successful
 * @param required whether this mock must be consumed by the graph execution
 */
public record GatewayGraphResourceMock(
        String resourceId,
        Map<String, Object> expectedParams,
        Object payload,
        int statusCode,
        String rawBody,
        long durationMs,
        boolean success,
        boolean required
) {
    /**
     * Creates a mock with standard successful HTTP envelope defaults.
     */
    public GatewayGraphResourceMock {
        resourceId = resourceId == null ? "" : resourceId.trim();
        expectedParams = expectedParams == null ? Map.of() : new LinkedHashMap<>(expectedParams);
        statusCode = statusCode <= 0 ? 200 : statusCode;
        rawBody = rawBody == null ? "" : rawBody;
        durationMs = Math.max(durationMs, 0);
    }

    /**
     * Convenience constructor for the common table-test case.
     */
    public GatewayGraphResourceMock(String resourceId,
                                    Map<String, Object> expectedParams,
                                    Object payload) {
        this(resourceId, expectedParams, payload, 200, "", 0, true, true);
    }
}
