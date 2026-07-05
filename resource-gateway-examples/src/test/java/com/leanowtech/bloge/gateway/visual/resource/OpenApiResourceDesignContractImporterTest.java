package com.leanowtech.bloge.gateway.visual.resource;

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
        VisualResourceDescriptor descriptor = result.descriptorSuggestion();
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
    void discoversOperationsFromYamlTextBeforeProjection() {
        OpenApiOperationDiscoveryResult result = importer.discoverOperations(
                new OpenApiResourceDesignContractImportRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        openApiDiscoveryYaml()
                )
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.operations())
                .extracting(operation -> operation.method() + " " + operation.path())
                .containsExactly("GET /orders", "POST /orders/{orderId}");
        assertThat(result.operations().get(0))
                .satisfies(operation -> {
                    assertThat(operation.operationId()).isEqualTo("listOrders");
                    assertThat(operation.summary()).isEqualTo("List orders");
                    assertThat(operation.tags()).containsExactly("order");
                    assertThat(operation.hasRequestBody()).isFalse();
                    assertThat(operation.responseMediaTypes()).containsExactly("application/json");
                    assertThat(operation.projectionLevel()).isEqualTo("READY");
                });
        assertThat(result.operations().get(1))
                .satisfies(operation -> {
                    assertThat(operation.operationId()).isEqualTo("submitOrder");
                    assertThat(operation.hasRequestBody()).isTrue();
                    assertThat(operation.requestMediaTypes())
                            .containsExactly("application/x-www-form-urlencoded");
                    assertThat(operation.responseMediaTypes())
                            .containsExactly("application/json");
                    assertThat(operation.projectionLevel()).isEqualTo("READY");
                });
    }

    @Test
    void discoversProjectionWarningsAndBlockers() {
        OpenApiOperationDiscoveryResult result = importer.discoverOperations(
                new OpenApiResourceDesignContractImportRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        openApiProjectionReadinessYaml()
                )
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.operations())
                .extracting(OpenApiOperationSummary::projectionLevel)
                .containsExactly("READY", "BLOCKED", "WARNING");
        assertThat(result.operations().get(0).projectionMessage())
                .contains("Ready to project");
        assertThat(result.operations().get(1).projectionMessage())
                .contains("selected 2xx response");
        assertThat(result.operations().get(2).projectionMessage())
                .contains("omit the body mapping");
    }

    @Test
    void discoveryReadinessMatchesTheResponseChosenByProjection() {
        OpenApiOperationDiscoveryResult result = importer.discoverOperations(
                new OpenApiResourceDesignContractImportRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        openApiFirstSuccessResponseIsNotJsonYaml()
                )
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.operations()).singleElement()
                .satisfies(operation -> {
                    assertThat(operation.responseMediaTypes())
                            .containsExactly("application/json", "text/plain");
                    assertThat(operation.projectionLevel()).isEqualTo("BLOCKED");
                    assertThat(operation.projectionMessage()).contains("selected 2xx response");
                });
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
    void discoversUnresolvedSchemaReferenceAsBlockedBeforeProjection() {
        OpenApiOperationDiscoveryResult result = importer.discoverOperations(
                new OpenApiResourceDesignContractImportRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        openApiWithUnresolvedResponseSchemaRef()
                )
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.operations()).singleElement()
                .satisfies(operation -> {
                    assertThat(operation.operationId()).isEqualTo("listOrders");
                    assertThat(operation.projectionLevel()).isEqualTo("BLOCKED");
                    assertThat(operation.projectionMessage())
                            .contains("Projection cannot safely import OpenAPI schemas")
                            .contains("MissingOrderList");
                });
    }

    @Test
    void rejectsProjectionWhenSchemaReferenceCannotBeResolved() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithUnresolvedResponseSchemaRef())
        );

        assertThat(result.contract()).isNull();
        assertThat(result.descriptorSuggestion()).isNull();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.refUnresolved"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message()).contains("MissingOrderList");
                    assertThat(diagnostic.target()).contains("$defs/MissingOrderList");
                });
    }

    @Test
    void suggestsDescriptorForPathQueryAndJsonBody() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.updateOrder", "updateOrder", null, null, openApiUpdateOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        VisualResourceDescriptor descriptor = result.descriptorSuggestion();
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
    void preservesSelectedJsonCompatibleMediaTypesInDescriptorHeaders() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.updateOrder", "updateOrder", null, null, openApiVendorJsonUpdateOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().requestSchema().properties()).containsKey("body");
        assertThat(result.contract().responseSchema().properties()).containsKey("id");
        assertThat(result.descriptorSuggestion().defaultHeaders())
                .containsEntry("Accept", "application/vnd.orders.result+json")
                .containsEntry("Content-Type", "application/vnd.orders.update+json");
        assertThat(result.descriptorSuggestion().parameterMapping().bodyExpression())
                .isEqualTo("ctx.params.body");
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.jsonCompatibleMediaTypeSelected"
                        .equals(diagnostic.code()))
                .hasSize(2)
                .extracting(diagnostic -> diagnostic.message())
                .anySatisfy(message -> assertThat(message)
                        .contains("application/vnd.orders.update+json"))
                .anySatisfy(message -> assertThat(message)
                        .contains("application/vnd.orders.result+json"));
    }

    @Test
    void projectsFormUrlEncodedRequestBodyIntoDescriptorHeadersAndBodyMapping() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.submitOrder", "submitOrder", null, null, openApiFormUrlEncodedSubmitOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().requestSchema().properties()).containsKeys("orderId", "body");
        assertThat(result.contract().requestSchema().required()).contains("orderId", "body");
        @SuppressWarnings("unchecked")
        Map<String, Object> bodySchema =
                (Map<String, Object>) result.contract().requestSchema().properties().get("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyProperties = (Map<String, Object>) bodySchema.get("properties");
        assertThat(bodyProperties).containsKey("priority");
        assertThat(result.descriptorSuggestion().defaultHeaders())
                .containsEntry("Accept", "application/json")
                .containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(result.descriptorSuggestion().parameterMapping().bodyExpression()).isEqualTo("ctx.params.body");
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.requestBodyContentUnsupported"
                        .equals(diagnostic.code()))
                .isEmpty();
    }

    @Test
    void projectsMultipartRequestBodyIntoDescriptorHeadersAndBodyMapping() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.submitOrder", "submitOrder", null, null, openApiMultipartSubmitOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().requestSchema().properties())
                .containsKeys("orderId", "body");
        assertThat(result.contract().requestSchema().required()).contains("orderId", "body");
        @SuppressWarnings("unchecked")
        Map<String, Object> bodySchema =
                (Map<String, Object>) result.contract().requestSchema().properties().get("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyProperties = (Map<String, Object>) bodySchema.get("properties");
        assertThat(bodyProperties).containsKey("priority");
        assertThat(result.descriptorSuggestion().defaultHeaders())
                .containsEntry("Accept", "application/json")
                .containsEntry("Content-Type", "multipart/form-data");
        assertThat(result.descriptorSuggestion().parameterMapping().bodyExpression()).isEqualTo("ctx.params.body");
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.requestBodyContentUnsupported"
                        .equals(diagnostic.code()))
                .isEmpty();
    }

    @Test
    void warnsAndOmitsMultipartRequestBodyWhenSchemaContainsBinaryFile() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.submitOrder", "submitOrder", null, null, openApiMultipartBinarySubmitOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().requestSchema().properties())
                .containsKey("orderId")
                .doesNotContainKey("body");
        assertThat(result.descriptorSuggestion().defaultHeaders())
                .containsEntry("Accept", "application/json")
                .doesNotContainKey("Content-Type");
        assertThat(result.descriptorSuggestion().parameterMapping().bodyExpression()).isNull();
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.multipartBinaryUnsupported"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message())
                            .contains("binary/base64")
                            .contains("file uploads");
                    assertThat(diagnostic.target())
                            .isEqualTo("/openApi/paths/~1orders~1{orderId}/post/requestBody/content/multipart~1form-data/schema");
                });
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.requestBodyContentUnsupported"
                        .equals(diagnostic.code()))
                .isEmpty();
    }

    @Test
    void warnsAndOmitsRequestBodyWhenOpenApiRequestBodyMediaTypeIsUnsupported() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.submitOrder", "submitOrder", null, null, openApiBinarySubmitOrder())
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contract()).isNotNull();
        assertThat(result.contract().requestSchema().properties())
                .containsKey("orderId")
                .doesNotContainKey("body");
        assertThat(result.descriptorSuggestion().defaultHeaders())
                .containsEntry("Accept", "application/json")
                .doesNotContainKey("Content-Type");
        assertThat(result.descriptorSuggestion().parameterMapping().bodyExpression()).isNull();
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.requestBodyContentUnsupported"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message())
                            .contains("application/octet-stream")
                            .contains("body input will be omitted");
                    assertThat(diagnostic.target())
                            .isEqualTo("/openApi/paths/~1orders~1{orderId}/post/requestBody/content");
                });
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
                .isEqualTo(new VisualResourceAuth.Bearer("CHANGE_ME_BEARER_TOKEN"));
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
                .isEqualTo(new VisualResourceAuth.ApiKey("X-Api-Key", "CHANGE_ME_API_KEY"));
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
                .isEqualTo(new VisualResourceAuth.Basic("CHANGE_ME_USERNAME", "CHANGE_ME_PASSWORD"));
    }

    @Test
    void warnsWhenOauth2SecurityCannotBeMappedToDescriptorAuth() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("OAuth", List.of("orders:read"))),
                                null,
                                Map.of("OAuth", Map.of(
                                        "type", "oauth2",
                                        "flows", Map.of(
                                                "authorizationCode", Map.of(
                                                        "authorizationUrl", "https://idp.example.test/auth",
                                                        "tokenUrl", "https://idp.example.test/token",
                                                        "scopes", Map.of(
                                                                "orders:read", "Read orders",
                                                                "orders:write", "Write orders"
                                                        )
                                                )
                                        )
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy()).isNull();
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.resourceContract.openapi.oauth2DescriptorUnsupported");
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.oauth2DescriptorUnsupported"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message())
                            .contains("authorizationCode")
                            .contains("orders:read")
                            .contains("orders:write");
                    assertThat(diagnostic.target())
                            .isEqualTo("/openApi/components/securitySchemes/OAuth/flows");
                });
    }

    @Test
    void warnsWhenOpenIdConnectSecurityCannotBeMappedToDescriptorAuth() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("Oidc", List.of("orders:read"))),
                                null,
                                Map.of("Oidc", Map.of(
                                        "type", "openIdConnect",
                                        "openIdConnectUrl", "https://idp.example.test/.well-known/openid-configuration"
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy()).isNull();
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.openIdConnectDescriptorUnsupported"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message())
                            .contains("discovery URL")
                            .contains("https://idp.example.test/.well-known/openid-configuration");
                    assertThat(diagnostic.target())
                            .isEqualTo("/openApi/components/securitySchemes/Oidc/openIdConnectUrl");
                });
    }

    @Test
    void warnsWhenMutualTlsSecurityCannotBeMappedToDescriptorAuth() {
        OpenApiResourceDesignContractImportResult result = importer.project(
                request("order-service.listOrders", "listOrders", null, null,
                        openApiWithSecurity(
                                List.of(Map.of("ClientCertificate", List.of())),
                                null,
                                Map.of("ClientCertificate", Map.of(
                                        "type", "mutualTLS"
                                ))
                        ))
        );

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.descriptorSuggestion().authStrategy()).isNull();
        assertThat(result.validation().diagnostics())
                .filteredOn(diagnostic -> "visual.resourceContract.openapi.mutualTlsDescriptorUnsupported"
                        .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.message())
                            .contains("client certificate")
                            .contains("request-level bearer, basic, or header apiKey");
                    assertThat(diagnostic.target())
                            .isEqualTo("/openApi/components/securitySchemes/ClientCertificate/type");
                });
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

    private static Map<String, Object> openApiWithUnresolvedResponseSchemaRef() {
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://api.example.test/v1")),
                "paths", Map.of(
                        "/orders", Map.of(
                                "get", Map.of(
                                        "operationId", "listOrders",
                                        "summary", "List orders",
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "ok",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "$ref",
                                                                                "#/components/schemas/MissingOrderList"
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "components", Map.of(
                        "schemas", Map.of(
                                "Order", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string")
                                        )
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

    private static Map<String, Object> openApiVendorJsonUpdateOrder() {
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
                                                )
                                        ),
                                        "requestBody", Map.of(
                                                "required", true,
                                                "content", Map.of(
                                                        "application/vnd.orders.update+json", Map.of(
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
                                                                "application/vnd.orders.result+json", Map.of(
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

    private static Map<String, Object> openApiFormUrlEncodedSubmitOrder() {
        return openApiSubmitOrderWithRequestBodyMediaType("application/x-www-form-urlencoded");
    }

    private static Map<String, Object> openApiMultipartSubmitOrder() {
        return openApiSubmitOrderWithRequestBodyMediaType("multipart/form-data");
    }

    private static Map<String, Object> openApiMultipartBinarySubmitOrder() {
        return openApiSubmitOrderWithRequestBodyMediaTypeAndSchema("multipart/form-data", Map.of(
                "type", "object",
                "properties", Map.of(
                        "file", Map.of(
                                "type", "string",
                                "format", "binary"
                        )
                ),
                "required", List.of("file")
        ));
    }

    private static Map<String, Object> openApiBinarySubmitOrder() {
        return openApiSubmitOrderWithRequestBodyMediaType("application/octet-stream");
    }

    private static Map<String, Object> openApiSubmitOrderWithRequestBodyMediaType(String mediaType) {
        return openApiSubmitOrderWithRequestBodyMediaTypeAndSchema(mediaType, Map.of(
                "type", "object",
                "properties", Map.of(
                        "priority", Map.of("type", "string")
                ),
                "required", List.of("priority")
        ));
    }

    private static Map<String, Object> openApiSubmitOrderWithRequestBodyMediaTypeAndSchema(
            String mediaType,
            Map<String, Object> bodySchema) {
        return Map.of(
                "openapi", "3.1.0",
                "servers", List.of(Map.of("url", "https://orders.example.test")),
                "paths", Map.of(
                        "/orders/{orderId}", Map.of(
                                "post", Map.of(
                                        "operationId", "submitOrder",
                                        "parameters", List.of(
                                                Map.of(
                                                        "name", "orderId",
                                                        "in", "path",
                                                        "required", true,
                                                        "schema", Map.of("type", "string")
                                                )
                                        ),
                                        "requestBody", Map.of(
                                                "required", true,
                                                "content", Map.of(
                                                        mediaType, Map.of(
                                                                "schema", bodySchema
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

    private static String openApiDiscoveryYaml() {
        return """
                openapi: 3.1.0
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                      summary: List orders
                      tags:
                        - order
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
                                      type: string
                  /orders/{orderId}:
                    post:
                      operationId: submitOrder
                      summary: Submit order
                      tags:
                        - order
                      requestBody:
                        required: true
                        content:
                          application/x-www-form-urlencoded:
                            schema:
                              type: object
                              properties:
                                priority:
                                  type: string
                      responses:
                        '201':
                          description: created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                """;
    }

    private static String openApiProjectionReadinessYaml() {
        return """
                openapi: 3.1.0
                paths:
                  /forms:
                    post:
                      operationId: submitForm
                      requestBody:
                        required: true
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                note:
                                  type: string
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                  /health:
                    get:
                      operationId: healthText
                      responses:
                        '200':
                          description: ok
                          content:
                            text/plain:
                              schema:
                                type: string
                  /uploads:
                    post:
                      operationId: uploadDocument
                      requestBody:
                        required: true
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                file:
                                  type: string
                                  format: binary
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                """;
    }

    private static String openApiFirstSuccessResponseIsNotJsonYaml() {
        return """
                openapi: 3.1.0
                paths:
                  /mixed-success:
                    get:
                      operationId: mixedSuccess
                      responses:
                        '200':
                          description: plain text status
                          content:
                            text/plain:
                              schema:
                                type: string
                        '201':
                          description: json body
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                """;
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
