package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for resource design contract validation.
 */
class ResourceDesignContractValidatorTest {

    private final ResourceDesignContractValidator validator = new ResourceDesignContractValidator();

    @Test
    void acceptsValidContractWithArrayItems() {
        VisualValidationResult result = validator.validate(validContract(Map.of()));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsNumericBoundsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "minScore", Map.of("type", "integer", "minimum", 0, "maximum", 850)
                ), List.of("minScore")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer", "minimum", 0, "maximum", 850)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsNumericMultipleOfInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "amountCents", Map.of("type", "integer", "multipleOf", 5)
                ), List.of("amountCents")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer", "multipleOf", 10)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsStringLengthConstraintsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "customerId", Map.of("type", "string", "minLength", 8, "maxLength", 16)
                ), List.of("customerId")),
                SchemaEnvelope.object(Map.of(
                        "trackingCode", Map.of("type", "string", "minLength", 10, "maxLength", 24)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsArrayItemBoundsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "ids", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "minItems", 1,
                                "maxItems", 50)
                ), List.of("ids")),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true),
                                "maxItems", 50)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsArrayUniqueItemsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "ids", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "uniqueItems", true)
                ), List.of("ids")),
                SchemaEnvelope.object(Map.of(
                        "segments", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "uniqueItems", true)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsArrayPrefixItemsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "tuple", Map.of(
                                "type", "array",
                                "prefixItems", List.of(
                                        Map.of("type", "integer"),
                                        Map.of("type", "string")),
                                "items", Map.of("type", "string"),
                                "minItems", 2)
                ), List.of("tuple")),
                SchemaEnvelope.object(Map.of(
                        "audit", Map.of(
                                "type", "array",
                                "prefixItems", List.of(Map.of("type", "string")),
                                "items", Map.of("type", "string"))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsArrayContainsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "channels", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "contains", Map.of("type", "string", "const", "primary"),
                                "minContains", 1,
                                "maxContains", 1)
                ), List.of("channels")),
                SchemaEnvelope.object(Map.of(
                        "flags", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "contains", Map.of("type", "string", "pattern", "^critical\\."),
                                "minContains", 1)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectPropertyBoundsInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "filters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", "string"),
                                        "region", Map.of("type", "string")
                                ),
                                "minProperties", 1,
                                "maxProperties", 2)
                ), List.of("filters")),
                SchemaEnvelope.object(Map.of(
                        "summary", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "minProperties", 1,
                                "maxProperties", 4)
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectPropertyNamesInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "filters", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "propertyNames", Map.of("pattern", "^filter\\.[a-z]+$"))
                ), List.of("filters")),
                SchemaEnvelope.object(Map.of(
                        "facets", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "integer"),
                                "propertyNames", Map.of("type", "string", "enum", List.of("tier", "segment")))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectPatternPropertiesInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "metrics", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "patternProperties", Map.of(
                                        "^metric\\.[a-z]+$", Map.of("type", "integer")))
                ), List.of("metrics")),
                SchemaEnvelope.object(Map.of(
                        "labels", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "patternProperties", Map.of(
                                        "^label\\.[a-z]+$", Map.of("type", "string")))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectUnevaluatedPropertiesInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "filters", Map.of(
                                "type", "object",
                                "properties", Map.of("status", Map.of("type", "string")),
                                "patternProperties", Map.of(
                                        "^filter\\.[a-z]+$", Map.of("type", "string")),
                                "unevaluatedProperties", false)
                ), List.of("filters")),
                SchemaEnvelope.object(Map.of(
                        "labels", Map.of(
                                "type", "object",
                                "properties", Map.of("status", Map.of("type", "string")),
                                "unevaluatedProperties", Map.of("type", "string"))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsNullableTypeArraysInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "filters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", List.of("string", "null")),
                                        "limit", Map.of("type", List.of("integer", "null"))
                                ))
                ), List.of("filters")),
                SchemaEnvelope.object(Map.of(
                        "cursor", Map.of("type", List.of("string", "null")),
                        "scores", Map.of(
                                "type", "array",
                                "items", Map.of("type", List.of("integer", "null")))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsLocalDefinitionsReferencesInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "filters", Map.of("$ref", "#/$defs/OrderFilters")
                        ),
                        "required", List.of("filters"),
                        "$defs", Map.of(
                                "OrderFilters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "status", Map.of("type", "string"),
                                                "limit", Map.of("type", "integer")
                                        ),
                                        "required", List.of("status"),
                                        "additionalProperties", false)
                        )
                )),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of("$ref", "#/$defs/OrderSummary")
                        ),
                        "$defs", Map.of(
                                "OrderSummary", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "count", Map.of("type", "integer"),
                                                "cursor", Map.of("type", List.of("string", "null"))
                                        ),
                                        "additionalProperties", false)
                        )
                )),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsLocalDefinitionObjectAllOfInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "allOf", List.of(
                                Map.of("$ref", "#/$defs/BaseFilters"),
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "limit", Map.of("type", "integer", "minimum", 1)
                                        ),
                                        "required", List.of("limit"),
                                        "additionalProperties", false)
                        ),
                        "$defs", Map.of(
                                "BaseFilters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "status", Map.of("type", "string")
                                        ),
                                        "required", List.of("status"))
                        )
                )),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of(
                                        "allOf", List.of(
                                                Map.of("$ref", "#/$defs/BaseSummary"),
                                                Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "cursor", Map.of("type", List.of("string", "null"))
                                                        ),
                                                        "additionalProperties", false)
                                        ))
                        ),
                        "$defs", Map.of(
                                "BaseSummary", Map.of(
                                        "type", "object",
                                        "properties", Map.of("count", Map.of("type", "integer")),
                                        "required", List.of("count"))
                        )
                )),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectDependentRequiredInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "payment", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardNumber", Map.of("type", "string"),
                                        "billingZip", Map.of("type", "string"),
                                        "method", Map.of("type", "string")
                                ),
                                "additionalProperties", false,
                                "dependentRequired", Map.of("cardNumber", List.of("billingZip")))
                ), List.of("payment")),
                SchemaEnvelope.object(Map.of(
                        "quote", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "discountCode", Map.of("type", "string"),
                                        "discountReason", Map.of("type", "string")
                                ),
                                "additionalProperties", false,
                                "dependentRequired", Map.of("discountCode", List.of("discountReason")))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsObjectDependentSchemasInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "payment", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cardNumber", Map.of("type", "string"),
                                        "billingZip", Map.of("type", "string"),
                                        "method", Map.of("type", "string")
                                ),
                                "additionalProperties", false,
                                "dependentSchemas", Map.of(
                                        "cardNumber", Map.of(
                                                "properties", Map.of(
                                                        "billingZip", Map.of("type", "string")),
                                                "required", List.of("billingZip"))))
                ), List.of("payment")),
                SchemaEnvelope.object(Map.of(
                        "quote", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "discountCode", Map.of("type", "string"),
                                        "discountReason", Map.of("type", "string")
                                ),
                                "additionalProperties", false,
                                "dependentSchemas", Map.of(
                                        "discountCode", Map.of(
                                                "properties", Map.of(
                                                        "discountReason", Map.of("type", "string")),
                                                "required", List.of("discountReason"))))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsObjectEnumAndConstValuesOutsideDeclaredShapeInResourceSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "status", Map.of(
                                "type", "object",
                                "required", List.of("code"),
                                "additionalProperties", false,
                                "properties", Map.of(
                                        "code", Map.of("type", "string", "pattern", "^[A-Z]+$"),
                                        "score", Map.of("type", "integer", "minimum", 0)
                                ),
                                "enum", List.of(
                                        Map.of("score", 10),
                                        Map.of("code", "ok"),
                                        Map.of("code", "OK", "extra", true)
                                ))
                ), List.of("status")),
                SchemaEnvelope.object(Map.of(
                        "fixed", Map.of(
                                "type", "object",
                                "required", List.of("mode"),
                                "additionalProperties", false,
                                "properties", Map.of("mode", Map.of("type", "string")),
                                "const", Map.of("mode", 7))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.enumConstraintMismatch",
                        "visual.schema.constConstraintMismatch"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/requestSchema/schema/properties/status/enum/0",
                        "/requestSchema/schema/properties/status/enum/1",
                        "/requestSchema/schema/properties/status/enum/2",
                        "/responseSchema/schema/properties/fixed/const"
                );
    }

    @Test
    void acceptsStringPatternInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "customerCode", Map.of("type", "string", "pattern", "^[A-Z]{2}\\d{4}$")
                ), List.of("customerCode")),
                SchemaEnvelope.object(Map.of(
                        "trackingCode", Map.of("type", "string", "pattern", "^TRK-[A-Z0-9]{8}$")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsStringFormatInResourceContractSchemas() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "customerEmail", Map.of("type", "string", "format", "email")
                ), List.of("customerEmail")),
                SchemaEnvelope.object(Map.of(
                        "traceId", Map.of("type", "string", "format", "uuid")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsArraySchemaWithoutItems() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of("type", "array")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.arrayItemsMissing");
                    assertThat(diagnostic.target()).isEqualTo("/responseSchema/schema/properties/items/items");
                });
    }

    @Test
    void rejectsUnsupportedJsonSchemaKeywords() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                SchemaEnvelope.object(Map.of(
                        "userId", Map.of("$ref", "#/$defs/UserId")
                ), List.of()),
                SchemaEnvelope.object(Map.of(
                        "customerCode", Map.of("type", "string", "pattern", "^[A-Z]+$"),
                        "decision", Map.of("oneOf", List.of(
                                Map.of("type", "string"),
                                Map.of("type", "integer")
                        ))
                ), List.of()),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.refUnsupported",
                        "visual.schema.compositionUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/requestSchema/schema/properties/userId/$ref",
                        "/responseSchema/schema/properties/decision/oneOf"
                );
    }

    @Test
    void rejectsUnsupportedSchemaEnvelope() {
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                new SchemaEnvelope("json-schema", "draft-07", Map.of("type", "object")),
                new SchemaEnvelope("avro", "2020-12", Map.of("type", "object")),
                Map.of(),
                "ACTIVE"
        );

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.versionUnsupported",
                        "visual.schema.formatUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/requestSchema/version",
                        "/responseSchema/format"
                );
    }

    @Test
    void rejectsRawSecretExamples() {
        ResourceDesignContract contract = validContract(Map.of(
                "request", Map.of("apiKey", "sk-123456789012")
        ));

        VisualValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.secret.raw");
                    assertThat(diagnostic.target()).isEqualTo("/examples/request/apiKey");
                });
    }

    private static ResourceDesignContract validContract(Map<String, Object> examples) {
        return new ResourceDesignContract(
                "contract:orders",
                "order-service.listOrders",
                "Order list",
                "Lists orders.",
                List.of("order"),
                requestSchema(),
                SchemaEnvelope.object(Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object", "additionalProperties", true)
                        )
                ), List.of()),
                examples,
                "ACTIVE"
        );
    }

    private static SchemaEnvelope requestSchema() {
        return SchemaEnvelope.object(Map.of(
                "userId", Map.of("type", "string")
        ), List.of("userId"));
    }
}
