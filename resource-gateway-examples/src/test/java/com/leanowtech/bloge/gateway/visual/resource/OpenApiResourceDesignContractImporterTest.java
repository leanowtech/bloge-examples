package com.leanowtech.bloge.gateway.visual.resource;

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

    private static String openApiOrderListYaml() {
        return """
                openapi: 3.1.0
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
