package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogicalResourceContractTest {

    @Test
    void projectsAStableDraftWithoutGuessingBusinessSemantics() {
        ResourceDesignContract design = designContract(objectSchema("requestId", "string", true),
                objectSchema("status", "string", true));
        VisualResourceDescriptor descriptor = descriptor("customer.lookup");

        LogicalResourceContractProjection first = LogicalResourceContractProjector.project(design, descriptor);
        LogicalResourceContractProjection second = LogicalResourceContractProjector.project(design, descriptor);

        assertThat(first.reviewStatus())
                .isEqualTo(LogicalResourceContractProjection.ReviewStatus.REQUIRES_CONFIRMATION);
        assertThat(first.contract().contractId()).isEqualTo("contract:customer.lookup");
        assertThat(first.contract().semantics().successCondition().knowledge())
                .isEqualTo(ResponseSemantics.Knowledge.PROJECTED);
        assertThat(first.contract().semantics().successCondition().expression())
                .isEqualTo("http.status in 200..299");
        assertThat(first.contract().semantics().requiresReview()).isTrue();
        assertThat(first.unknownFields()).containsExactly(
                "errorClassification", "idempotency", "retryability");
        assertThat(first.contract().contractFingerprint())
                .isEqualTo(second.contract().contractFingerprint());
        assertThat(first.descriptorFingerprint()).isEqualTo(second.descriptorFingerprint());
    }

    @Test
    void canonicalizesMapAndSetLikeListOrderForFingerprinting() {
        Map<String, Object> leftProperties = new LinkedHashMap<>();
        leftProperties.put("beta", Map.of("type", "integer"));
        leftProperties.put("alpha", Map.of("type", "string", "enum", List.of("B", "A")));
        Map<String, Object> rightProperties = new LinkedHashMap<>();
        rightProperties.put("alpha", Map.of("enum", List.of("A", "B"), "type", "string"));
        rightProperties.put("beta", Map.of("type", "integer"));

        LogicalResourceContract left = new LogicalResourceContract(
                "logical.customer",
                schema(leftProperties, List.of("beta", "alpha"), false),
                objectSchema("result", "string", true),
                confirmed(Map.of("BUSINESS", List.of("B02", "B01"))));
        LogicalResourceContract right = new LogicalResourceContract(
                "logical.customer",
                schema(rightProperties, List.of("alpha", "beta"), false),
                objectSchema("result", "string", true),
                confirmed(Map.of("BUSINESS", List.of("B01", "B02"))));

        assertThat(left.contractFingerprint()).isEqualTo(right.contractFingerprint());
    }

    @Test
    void ownsDefensiveCopiesOfNestedSchemasAndReturnedValues() {
        List<String> required = new ArrayList<>(List.of("requestId"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestId", nested);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "object");
        raw.put("properties", properties);
        raw.put("required", required);
        raw.put("additionalProperties", false);
        LogicalResourceContract contract = new LogicalResourceContract(
                "logical.customer", new SchemaEnvelope("json-schema", "2020-12", raw),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
        String fingerprint = contract.contractFingerprint();

        required.clear();
        nested.put("type", "number");
        @SuppressWarnings("unchecked")
        Map<String, Object> exposedProperty = (Map<String, Object>) contract.inputShape()
                .schema().get("properties");
        exposedProperty.clear();

        assertThat(contract.inputShape().required()).containsExactly("requestId");
        assertThat(contract.inputShape().hasProperty("requestId")).isTrue();
        assertThat(contract.contractFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void bindsProviderVersionOnlyAfterStructuredOutputProof() {
        LogicalResourceContract contract = new LogicalResourceContract(
                "logical.customer", objectSchema("requestId", "string", true),
                objectSchema("status", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));

        LogicalResourceBinding binding = LogicalResourceBinding.bind(
                "mobility", "2026-08", designContract(contract.inputShape(), contract.outputShape()),
                descriptor("customer.lookup"), contract);

        assertThat(binding.provider()).isEqualTo("mobility");
        assertThat(binding.apiVersion()).isEqualTo("2026-08");
        assertThat(binding.resourceId()).isEqualTo("customer.lookup");
        assertThat(binding.contractFingerprint()).isEqualTo(contract.contractFingerprint());
        assertThat(binding.descriptorFingerprint()).startsWith("sha256:");
    }

    @Test
    void keepsProviderAndApiVersionOutsideLogicalContractIdentity() {
        LogicalResourceContract contract = new LogicalResourceContract(
                "logical.customer", objectSchema("requestId", "string", true),
                objectSchema("status", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
        ResourceDesignContract providerDesign = designContract(contract.inputShape(), contract.outputShape());

        LogicalResourceBinding first = LogicalResourceBinding.bind(
                "mobility-a", "v1", providerDesign, descriptor("customer.lookup"), contract);
        LogicalResourceBinding second = LogicalResourceBinding.bind(
                "mobility-b", "v9", providerDesign, descriptor("customer.lookup"), contract);

        assertThat(first.contractFingerprint()).isEqualTo(second.contractFingerprint())
                .isEqualTo(contract.contractFingerprint());
        assertThat(first.provider()).isNotEqualTo(second.provider());
        assertThat(first.apiVersion()).isNotEqualTo(second.apiVersion());
    }

    @Test
    void rejectsBreakingOrUnknownProviderOutputWithoutLeakingPayload() {
        String secret = "token-super-secret";
        LogicalResourceContract contract = new LogicalResourceContract(
                "logical.customer", objectSchema("requestId", "string", true),
                objectSchema("status", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
        ResourceDesignContract breaking = designContract(contract.inputShape(),
                objectSchema("status", "integer", true));
        ResourceDesignContract unknown = designContract(contract.inputShape(),
                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                        "type", "object", "properties", Map.of("status", Map.of("mystery", secret)))));

        assertSanitizedBindingFailure(breaking, contract,
                "RG.LOGICAL_CONTRACT.IMPLEMENTATION_INCOMPATIBLE", secret);
        assertSanitizedBindingFailure(unknown, contract,
                "RG.LOGICAL_CONTRACT.IMPLEMENTATION_COMPATIBILITY_UNKNOWN", secret);
    }

    @Test
    void rejectsBindingAnUnconfirmedProjectedContract() {
        LogicalResourceContract projected = LogicalResourceContractProjector.project(
                designContract(objectSchema("requestId", "string", true),
                        objectSchema("status", "string", true)),
                descriptor("customer.lookup")).contract();

        assertThatThrownBy(() -> LogicalResourceBinding.bind(
                "mobility", "2026-08", designContract(projected.inputShape(), projected.outputShape()),
                descriptor("customer.lookup"), projected))
                .isInstanceOfSatisfying(LogicalResourceContractException.class, error ->
                        assertThat(error.code()).isEqualTo("RG.LOGICAL_CONTRACT.CONFIRMATION_REQUIRED"));
    }

    @Test
    void rejectsProjectionResourceMismatchWithSanitizedError() {
        String secret = "private-resource-secret";
        ResourceDesignContract design = new ResourceDesignContract(
                "contract:" + secret, secret, "x", "x", List.of(),
                objectSchema("id", "string", true), objectSchema("ok", "boolean", true), Map.of(), "ACTIVE");

        assertThatThrownBy(() -> LogicalResourceContractProjector.project(design, descriptor("other")))
                .isInstanceOfSatisfying(LogicalResourceContractException.class, error -> {
                    assertThat(error.code()).isEqualTo("RG.LOGICAL_CONTRACT.PROJECTION_INVALID");
                    assertThat(error.getMessage()).doesNotContain(secret).doesNotContain("other");
                });
    }

    @Test
    void rejectsAConfirmedProjectionWhenSemanticsStillRequireReview() {
        LogicalResourceContract draft = new LogicalResourceContract(
                "logical.customer", objectSchema("requestId", "string", true),
                objectSchema("status", "string", true), ResponseSemantics.unknown());

        assertThatThrownBy(() -> new LogicalResourceContractProjection(
                draft, LogicalResourceContractProjection.ReviewStatus.CONFIRMED,
                "sha256:" + "a".repeat(64), List.of()))
                .isInstanceOfSatisfying(LogicalResourceContractException.class, error ->
                        assertThat(error.code()).isEqualTo("RG.LOGICAL_CONTRACT.PROJECTION_INVALID"));
    }

    private static void assertSanitizedBindingFailure(ResourceDesignContract provider,
                                                      LogicalResourceContract contract,
                                                      String code,
                                                      String secret) {
        assertThatThrownBy(() -> LogicalResourceBinding.bind(
                "provider-" + secret, "v1-" + secret, provider, descriptor("customer.lookup"), contract))
                .isInstanceOfSatisfying(LogicalResourceContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(secret)
                            .doesNotContain("status").doesNotContain("integer").doesNotContain("mystery");
                });
    }

    static ResourceDesignContract designContract(SchemaEnvelope request, SchemaEnvelope response) {
        return new ResourceDesignContract("contract:customer.lookup", "customer.lookup", "Customer lookup", "",
                List.of(), request, response, Map.of(), "ACTIVE");
    }

    static VisualResourceDescriptor descriptor(String resourceId) {
        return new VisualResourceDescriptor(resourceId, "https://example.test/customers/{id}", "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(2),
                new VisualResourceParameterMapping(Map.of("id", "$.requestId"), Map.of(), null),
                new VisualResourceResponseProtocol.HttpStatus(), "data");
    }

    static SchemaEnvelope objectSchema(String property, String type, boolean required) {
        return schema(Map.of(property, Map.of("type", type)), required ? List.of(property) : List.of(), false);
    }

    static SchemaEnvelope schema(Map<String, Object> properties, List<String> required,
                                 boolean additionalProperties) {
        return new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object", "properties", properties, "required", required,
                "additionalProperties", additionalProperties));
    }

    static ResponseSemantics confirmed(Map<String, List<String>> errors) {
        return ResponseSemantics.confirmed("http.status in 200..299", errors,
                ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL);
    }
}
