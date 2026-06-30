package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.operators.http.HttpRequestInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OpenAPI operation projection into resource design contracts.
 */
class OpenApiResourceDesignContractImporterTest {

    private final OpenApiResourceDesignContractImporter importer = new OpenApiResourceDesignContractImporter();
    private final ResourceDesignContractValidator validator = new ResourceDesignContractValidator();

    @Test
    void projectsOperationByOperationIdIntoValidContract() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null, openApiOrderList(false))
        );

        assertThat(result.validation().valid()).isTrue();
        ResourceDesignContract contract = result.contract();
        assertThat(contract).isNotNull();
        assertThat(contract.resourceId()).isEqualTo("order-service.listOrders");
        assertThat(contract.displayName()).isEqualTo("List orders");
        assertThat(contract.tags()).containsExactly("order");
        assertThat(contract.requestSchema().required()).containsExactly("userId");
        assertThat(contract.requestSchema().properties()).containsKey("userId");
        assertThat(validator.validate(contract).valid()).isTrue();
        ResourceDescriptor descriptor = result.descriptorSuggestion();
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.resourceId()).isEqualTo("order-service.listOrders");
        assertThat(descriptor.urlTemplate()).isEqualTo("https://api.example.test/v1/orders");
        assertThat(descriptor.method()).isEqualTo("GET");
        assertThat(descriptor.defaultHeaders()).containsEntry("Accept", "application/json");
        assertThat(descriptor.parameterMapping().queryExpressions())
                .containsEntry("userId", "ctx.params.userId");
        assertThat(descriptor.parameterMapping().bodyExpression()).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) contract.responseSchema().properties().get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemSchema = (Map<String, Object>) items.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProperties = (Map<String, Object>) itemSchema.get("properties");
        assertThat(itemProperties).containsKey("id");
    }

    @Test
    void rewritesTopLevelComponentRefIntoDefinitionsAcceptedByValidator() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null, openApiOrderList(true))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().responseSchema().properties()).containsKey("items");
        assertThat(result.contract().responseSchema().schema()).containsKey("$defs");
        assertThat(validator.validate(result.contract()).valid()).isTrue();
    }

    @Test
    void suggestsDescriptorForPathQueryAndJsonBody() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.updateOrder", "updateOrder", null, null, openApiUpdateOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        ResourceDescriptor descriptor = result.descriptorSuggestion();
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.urlTemplate()).isEqualTo("https://orders.example.test/orders/{orderId}");
        assertThat(descriptor.method()).isEqualTo("PUT");
        assertThat(descriptor.defaultHeaders())
                .containsEntry("Accept", "application/json")
                .containsEntry("Content-Type", "application/json");
        assertThat(descriptor.parameterMapping().pathExpressions())
                .containsEntry("orderId", "ctx.params.orderId");
        assertThat(descriptor.parameterMapping().queryExpressions())
                .containsEntry("expand", "ctx.params.expand");
        assertThat(descriptor.parameterMapping().headerExpressions())
                .containsEntry("X-Request-Id", "ctx.params[\"X-Request-Id\"]");
        assertThat(descriptor.parameterMapping().cookieExpressions())
                .containsEntry("SESSION", "ctx.params.SESSION");
        assertThat(descriptor.parameterMapping().bodyExpression()).isEqualTo("ctx.params.body");
    }

    @Test
    void suggestsBearerAuthFromRootSecurityScheme() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("BearerAuth", List.of())),
                                null,
                                Map.of("BearerAuth", Map.of(
                                        "type", "http",
                                        "scheme", "bearer",
                                        "bearerFormat", "JWT"
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy())
                .isEqualTo(new HttpRequestInput.BearerAuth("CHANGE_ME_BEARER_TOKEN"));
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.resourceContract.openapi.authPlaceholder");
    }

    @Test
    void suggestsHeaderApiKeyAuthFromOperationSecurityScheme() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                null,
                                List.of(Map.of("ApiKeyAuth", List.of())),
                                Map.of("ApiKeyAuth", Map.of(
                                        "type", "apiKey",
                                        "in", "header",
                                        "name", "X-Api-Key"
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy())
                .isEqualTo(new HttpRequestInput.ApiKeyAuth("X-Api-Key", "CHANGE_ME_API_KEY"));
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.resourceContract.openapi.authPlaceholder");
    }

    @Test
    void operationSecurityOverridesRootSecurityWhenSuggestingAuth() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("BearerAuth", List.of())),
                                List.of(Map.of("BasicAuth", List.of())),
                                Map.of(
                                        "BearerAuth", Map.of(
                                                "type", "http",
                                                "scheme", "bearer"
                                        ),
                                        "BasicAuth", Map.of(
                                                "type", "http",
                                                "scheme", "basic"
                                        )
                                )
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy())
                .isEqualTo(new HttpRequestInput.BasicAuth("CHANGE_ME_USERNAME", "CHANGE_ME_PASSWORD"));
    }

    @Test
    void warnsWhenOpenApiSecurityCannotBeMappedToDescriptorAuth() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("OAuth", List.of("orders:read"))),
                                null,
                                Map.of("OAuth", Map.of(
                                        "type", "oauth2",
                                        "flows", Map.of()
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy()).isNull();
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.resourceContract.openapi.securitySchemeUnsupported");
    }

    @Test
    void warnsWhenDescriptorSuggestionUsesFallbackServer() {
        Map<String, Object> openApi = new java.util.LinkedHashMap<>(openApiOrderList(false));
        openApi.remove("servers");

        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null, openApi)
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().urlTemplate()).isEqualTo("https://api.example.com/orders");
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.resourceContract.openapi.serverMissing");
    }

    @Test
    void rejectsMissingOperationWithDiagnostic() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "missing", null, null, openApiOrderList(false))
        );

        assertThat(result.contract()).isNull();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .containsExactly("visual.resourceContract.openapi.operationMissing");
    }

    @Test
    void rejectsMissingSuccessResponseWithDiagnostic() {
        Map<String, Object> openApi = Map.of(
                "openapi", "3.1.0",
                "paths", Map.of(
                        "/orders", Map.of(
                                "get", Map.of(
                                        "operationId", "listOrders",
                                        "summary", "List orders"
                                )
                        )
                )
        );

        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null, openApi)
        );

        assertThat(result.contract()).isNull();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .containsExactly("visual.resourceContract.openapi.responsesMissing");
    }

    @Test
    void selectsOperationByPathAndMethod() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", null, "/orders", "GET", openApiOrderList(false))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().displayName()).isEqualTo("List orders");
    }

    @Test
    void projectsYamlOpenApiTextIntoValidContract() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                new OpenApiResourceDesignContractImportRequest(
                        "order-service.listOrders",
                        null,
                        "/orders",
                        "GET",
                        null,
                        null,
                        openApiOrderListYaml()
                )
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().resourceId()).isEqualTo("order-service.listOrders");
        assertThat(result.contract().requestSchema().properties()).containsKey("userId");
        assertThat(validator.validate(result.contract()).valid()).isTrue();
    }

    @Test
    void rejectsMalformedOpenApiTextWithDiagnostic() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                new OpenApiResourceDesignContractImportRequest(
                        "order-service.listOrders",
                        null,
                        "/orders",
                        "GET",
                        null,
                        null,
                        "openapi: \"3.1.0\"\npaths: ["
                )
        );

        assertThat(result.contract()).isNull();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .containsExactly("visual.resourceContract.openapi.documentMalformed");
    }

    private static OpenApiResourceDesignContractImportRequest request(String resourceId,
                                                                      String operationId,
                                                                      String path,
                                                                      String method,
                                                                      Map<String, Object> openApi) {
        return new OpenApiResourceDesignContractImportRequest(
                resourceId,
                operationId,
                path,
                method,
                null,
                openApi
        );
    }

    private static Map<String, Object> openApiOrderList(boolean topLevelResponseRef) {
        Map<String, Object> responseSchema = topLevelResponseRef
                ? Map.of("$ref", "#/components/schemas/OrderList")
                : Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "items", Map.of(
                                        "type", "array",
                                        "items", Map.of("$ref", "#/components/schemas/Order")
                                )
                        ),
                        "required", List.of("items")
                );
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://api.example.test/v1")),
                "paths", Map.of(
                        "/orders", Map.of(
                                "get", Map.of(
                                        "operationId", "listOrders",
                                        "summary", "List orders",
                                        "description", "Lists orders for a user.",
                                        "tags", List.of("order"),
                                        "parameters", List.of(
                                                Map.of(
                                                        "name", "userId",
                                                        "in", "query",
                                                        "required", true,
                                                        "schema", Map.of("type", "string")
                                                )
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "ok",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", responseSchema
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "components", Map.of(
                        "schemas", Map.of(
                                "OrderList", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "items", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("$ref", "#/components/schemas/Order")
                                                )
                                        ),
                                        "required", List.of("items")
                                ),
                                "Order", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "total", Map.of("type", "number", "format", "double")
                                        ),
                                        "required", List.of("id"),
                                        "additionalProperties", false
                                )
                        )
                )
        );
    }

    private static Map<String, Object> openApiUpdateOrder() {
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://orders.example.test")),
                "paths", Map.of(
                        "/orders/{orderId}", Map.of(
                                "put", Map.of(
                                        "operationId", "updateOrder",
                                        "parameters", List.of(
                                                Map.of(
                                                        "name", "orderId",
                                                        "in", "path",
                                                        "required", true,
                                                        "schema", Map.of("type", "string")
                                                ),
                                                Map.of(
                                                        "name", "expand",
                                                        "in", "query",
                                                        "schema", Map.of("type", "string")
                                                ),
                                                Map.of(
                                                        "name", "X-Request-Id",
                                                        "in", "header",
                                                        "schema", Map.of("type", "string")
                                                ),
                                                Map.of(
                                                        "name", "SESSION",
                                                        "in", "cookie",
                                                        "schema", Map.of("type", "string")
                                                )
                                        ),
                                        "requestBody", Map.of(
                                                "required", true,
                                                "content", Map.of(
                                                        "application/json", Map.of(
                                                                "schema", Map.of(
                                                                        "type", "object",
                                                                        "properties", Map.of(
                                                                                "status", Map.of("type", "string")
                                                                        ),
                                                                        "required", List.of("status")
                                                                )
                                                        )
                                                )
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "ok",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "type", "object",
                                                                                "properties", Map.of(
                                                                                        "id", Map.of("type", "string")
                                                                                ),
                                                                                "required", List.of("id")
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static Map<String, Object> openApiWithSecurity(Object rootSecurity,
                                                           Object operationSecurity,
                                                           Map<String, Object> securitySchemes) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "listOrders");
        operation.put("summary", "List orders");
        operation.put("parameters", List.of(
                Map.of(
                        "name", "userId",
                        "in", "query",
                        "required", true,
                        "schema", Map.of("type", "string")
                )
        ));
        operation.put("responses", Map.of(
                "200", Map.of(
                        "description", "ok",
                        "content", Map.of(
                                "application/json", Map.of(
                                        "schema", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "items", Map.of(
                                                                "type", "array",
                                                                "items", Map.of("type", "string")
                                                        )
                                                ),
                                                "required", List.of("items")
                                        )
                                )
                        )
                )
        ));
        if (operationSecurity != null) {
            operation.put("security", operationSecurity);
        }

        Map<String, Object> openApi = new LinkedHashMap<>();
        openApi.put("openapi", "3.1.0");
        openApi.put("servers", List.of(Map.of("url", "https://api.example.test/v1")));
        if (rootSecurity != null) {
            openApi.put("security", rootSecurity);
        }
        openApi.put("paths", Map.of("/orders", Map.of("get", operation)));
        openApi.put("components", Map.of("securitySchemes", securitySchemes));
        return openApi;
    }

    private static String openApiOrderListYaml() {
        return """
                openapi: 3.1.0
                servers:
                  - url: https://api.example.test/v1
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                      summary: List orders
                      description: Lists orders for a user.
                      tags:
                        - order
                      parameters:
                        - name: userId
                          in: query
                          required: true
                          schema:
                            type: string
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  items:
                                    type: array
                                    items:
                                      $ref: '#/components/schemas/Order'
                                required:
                                  - items
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        id:
                          type: string
                        total:
                          type: number
                          format: double
                      required:
                        - id
                      additionalProperties: false
                """;
    }
}
