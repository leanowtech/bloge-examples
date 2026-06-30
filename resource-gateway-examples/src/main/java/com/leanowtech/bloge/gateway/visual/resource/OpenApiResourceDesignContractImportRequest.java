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
 * @param openApiText OpenAPI document as raw JSON or YAML text
 */
public record OpenApiResourceDesignContractImportRequest(
        String resourceId,
        String operationId,
        String path,
        String method,
        String status,
        Map<String, Object> openApi,
        String openApiText
) {
    /**
     * Backward-compatible constructor for existing JSON-object callers.
     */
    public OpenApiResourceDesignContractImportRequest(String resourceId,
                                                      String operationId,
                                                      String path,
                                                      String method,
                                                      String status,
                                                      Map<String, Object> openApi) {
        this(resourceId, operationId, path, method, status, openApi, "");
    }
}
