package com.leanowtech.bloge.gateway.visual.resource;

import java.util.Map;

/**
 * Request for projecting one OpenAPI operation into a resource design contract draft.
 *
 * @param resourceId resource descriptor id that the generated contract describes
 * @param operationId OpenAPI operationId selector
 * @param path OpenAPI path selector
 * @param method HTTP method selector for {@code path}
 * @param status optional lifecycle status for the generated draft
 * @param openApi OpenAPI document as a parsed JSON object
 */
public record OpenApiResourceDesignContractImportRequest(
        String resourceId,
        String operationId,
        String path,
        String method,
        String status,
        Map<String, Object> openApi
) {
}
