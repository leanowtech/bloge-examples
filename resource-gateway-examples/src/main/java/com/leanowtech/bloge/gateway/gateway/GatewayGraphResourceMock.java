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
 * @param responseHeaders transport response headers used by protocol-derived fixtures
 * @param fixtureMode explicit fidelity boundary; legacy rows default to {@link FixtureMode#OUTPUT_LEVEL}
 */
public record GatewayGraphResourceMock(
        String resourceId,
        Map<String, Object> expectedParams,
        Object payload,
        int statusCode,
        String rawBody,
        long durationMs,
        boolean success,
        boolean required,
        Map<String, String> responseHeaders,
        FixtureMode fixtureMode
) {
    /**
     * Controls whether a fixture supplies an already-interpreted operator output or a raw upstream
     * response that must pass through the production resource protocol pipeline.
     */
    public enum FixtureMode {
        /** Legacy node-output replacement; {@code payload} and {@code success} are trusted as supplied. */
        OUTPUT_LEVEL,
        /** Raw response interpreted by the real descriptor response protocol and payload extractor. */
        PROTOCOL_DERIVED,
        /** Raw response injected at the HTTP transport boundary after real request mapping. */
        TRANSPORT_LEVEL
    }

    /**
     * Creates a mock with standard successful HTTP envelope defaults.
     */
    public GatewayGraphResourceMock {
        resourceId = resourceId == null ? "" : resourceId.trim();
        expectedParams = expectedParams == null ? Map.of() : new LinkedHashMap<>(expectedParams);
        fixtureMode = fixtureMode == null ? FixtureMode.OUTPUT_LEVEL : fixtureMode;
        if (fixtureMode != FixtureMode.OUTPUT_LEVEL && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException(
                    "Protocol-derived resource mocks require statusCode between 100 and 599.");
        }
        statusCode = statusCode <= 0 ? 200 : statusCode;
        rawBody = rawBody == null ? "" : rawBody;
        durationMs = Math.max(durationMs, 0);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }

    /**
     * Preserves the pre-F2 canonical constructor as an explicit output-level fixture.
     *
     * @param resourceId logical resource id
     * @param expectedParams exact expected resource parameters
     * @param payload trusted operator payload
     * @param statusCode trusted operator status code
     * @param rawBody informational raw body retained in the output
     * @param durationMs trusted operator duration
     * @param success trusted operator success flag
     * @param required whether the fixture must be consumed
     */
    public GatewayGraphResourceMock(String resourceId,
                                    Map<String, Object> expectedParams,
                                    Object payload,
                                    int statusCode,
                                    String rawBody,
                                    long durationMs,
                                    boolean success,
                                    boolean required) {
        this(resourceId, expectedParams, payload, statusCode, rawBody, durationMs, success, required,
                Map.of(), FixtureMode.OUTPUT_LEVEL);
    }

    /**
     * Convenience constructor for the common table-test case.
     */
    public GatewayGraphResourceMock(String resourceId,
                                    Map<String, Object> expectedParams,
                                    Object payload) {
        this(resourceId, expectedParams, payload, 200, "", 0, true, true,
                Map.of(), FixtureMode.OUTPUT_LEVEL);
    }

    /**
     * Creates an F2 fixture whose success and payload are derived from the frozen resource
     * descriptor instead of trusted from the test row.
     *
     * @param resourceId logical resource id
     * @param expectedParams exact expected resource parameters
     * @param rawBody raw upstream response body
     * @param statusCode upstream HTTP status
     * @param responseHeaders upstream response headers
     * @param required whether the fixture must be consumed
     * @return explicit protocol-derived fixture
     */
    public static GatewayGraphResourceMock protocolDerived(String resourceId,
                                                           Map<String, Object> expectedParams,
                                                           String rawBody,
                                                           int statusCode,
                                                           Map<String, String> responseHeaders,
                                                           boolean required) {
        return rawResponse(resourceId, expectedParams, rawBody, statusCode, responseHeaders, required,
                FixtureMode.PROTOCOL_DERIVED);
    }

    /**
     * Creates an F3 fixture that preserves real parameter mapping, URL rendering, response protocol,
     * and payload extraction while replacing only the HTTP transport.
     *
     * @param resourceId logical resource id
     * @param expectedParams exact expected resource parameters
     * @param rawBody raw upstream response body
     * @param statusCode upstream HTTP status
     * @param responseHeaders upstream response headers
     * @param required whether the fixture must be consumed
     * @return explicit transport-level fixture
     */
    public static GatewayGraphResourceMock transportResponse(String resourceId,
                                                             Map<String, Object> expectedParams,
                                                             String rawBody,
                                                             int statusCode,
                                                             Map<String, String> responseHeaders,
                                                             boolean required) {
        return rawResponse(resourceId, expectedParams, rawBody, statusCode, responseHeaders, required,
                FixtureMode.TRANSPORT_LEVEL);
    }

    private static GatewayGraphResourceMock rawResponse(String resourceId,
                                                        Map<String, Object> expectedParams,
                                                        String rawBody,
                                                        int statusCode,
                                                        Map<String, String> responseHeaders,
                                                        boolean required,
                                                        FixtureMode mode) {
        return new GatewayGraphResourceMock(resourceId, expectedParams, null, statusCode, rawBody,
                0, false, required, responseHeaders, mode);
    }
}
