package com.leanowtech.bloge.gateway.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observed mocked resource invocation during a graph contract-test run.
 *
 * @param resourceId logical resource id
 * @param params runtime parameters observed by the mock operator
 * @param matched whether the invocation matched a declared mock row
 */
public record GatewayGraphResourceInvocation(
        String resourceId,
        Map<String, Object> params,
        boolean matched
) {
    /**
     * Creates an invocation record.
     */
    public GatewayGraphResourceInvocation {
        resourceId = resourceId == null ? "" : resourceId;
        params = params == null ? Map.of() : new LinkedHashMap<>(params);
    }
}
