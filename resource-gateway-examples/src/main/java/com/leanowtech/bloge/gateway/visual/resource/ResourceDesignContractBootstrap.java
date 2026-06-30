package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds visual authoring contracts for the built-in resource descriptors.
 */
@Component
public class ResourceDesignContractBootstrap {

    private final ResourceDesignContractRegistry registry;

    /**
     * @param registry contract registry
     */
    public ResourceDesignContractBootstrap(ResourceDesignContractRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registers contracts used by the example visual operator catalog.
     */
    @PostConstruct
    public void seedContracts() {
        upsert("user-service.getProfile", "User profile", "Reads profile facts for one user.",
                List.of("user", "profile"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(
                        field("userId", "string", "User id"),
                        field("name", "string", "Display name"),
                        field("tier", "string", "Customer tier"),
                        field("segment", "string", "Customer segment"),
                        field("score", "integer", "Risk or credit score")
                ));
        upsert("loan-applicant-service.getProfile", "Loan applicant profile", "Reads applicant facts for loan policy decisions.",
                List.of("loan", "applicant", "risk"),
                request(List.of("applicantId"), field("applicantId", "string", "Applicant id")),
                response(
                        field("applicantId", "string", "Applicant id"),
                        field("score", "integer", "Credit score"),
                        field("segment", "string", "Applicant segment"),
                        field("income", "number", "Annual income"),
                        field("employmentYears", "number", "Employment years")
                ));
        upsert("order-service.listOrders", "Order list", "Lists orders for a user or order lookup key.",
                List.of("order"),
                request(List.of(), field("userId", "string", "User id"), field("orderId", "string", "Order id")),
                response(arrayField("items", "Orders"), field("total", "integer", "Order count")));
        upsert("recommendation-service.forUser", "Recommendations", "Fetches personalized recommendations.",
                List.of("recommendation"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(arrayField("items", "Recommended items")));
        upsert("wallet-service.getBalance", "Wallet balance", "Reads wallet balance.",
                List.of("wallet", "finance"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(field("amount", "number", "Balance amount"), field("currency", "string", "Currency")));
        upsert("notification-service.unread", "Unread notifications", "Reads unread notifications.",
                List.of("notification"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(field("count", "integer", "Unread count"), arrayField("items", "Notifications")));
        upsert("catalog-service.getProduct", "Product detail", "Reads catalog product details.",
                List.of("catalog", "product"),
                request(List.of("productId"), field("productId", "string", "Product id")),
                response(
                        field("productId", "string", "Product id"),
                        field("name", "string", "Product name"),
                        field("category", "string", "Category"),
                        field("price", "number", "Price")
                ));
        upsert("logistics-service.getShipping", "Shipping quote", "Reads shipping quote or status.",
                List.of("logistics", "shipping"),
                request(List.of(), field("productId", "string", "Product id"), field("orderId", "string", "Order id")),
                response(field("carrier", "string", "Carrier"), field("etaDays", "integer", "ETA days"), field("cost", "number", "Shipping cost")));
        upsert("license-service.getLicense", "License check", "Checks product license validity.",
                List.of("license"),
                request(List.of("productId"), field("productId", "string", "Product id")),
                response(field("valid", "boolean", "Whether license is valid"), field("level", "string", "License level")));
        upsert("invoice-service.getInvoice", "Invoice detail", "Reads invoice details by order.",
                List.of("invoice"),
                request(List.of("orderId"), field("orderId", "string", "Order id")),
                response(field("invoiceId", "string", "Invoice id"), field("amount", "number", "Invoice amount"), field("status", "string", "Invoice status")));
        upsert("credit-provider.primary", "Primary credit score", "Reads primary credit provider score.",
                List.of("credit", "provider"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(field("score", "integer", "Credit score"), field("provider", "string", "Provider name"), field("band", "string", "Credit band")));
        upsert("credit-provider.secondary", "Secondary credit score", "Reads secondary credit provider score.",
                List.of("credit", "provider"),
                request(List.of("userId"), field("userId", "string", "User id")),
                response(field("score", "integer", "Credit score"), field("provider", "string", "Provider name"), field("band", "string", "Credit band")));
    }

    private void upsert(String resourceId,
                        String displayName,
                        String description,
                        List<String> tags,
                        SchemaEnvelope requestSchema,
                        SchemaEnvelope responseSchema) {
        if (registry.findByResourceId(resourceId).isPresent()) {
            return;
        }
        registry.upsert(new ResourceDesignContract(
                "contract:" + resourceId,
                resourceId,
                displayName,
                description,
                tags,
                requestSchema,
                responseSchema,
                Map.of(),
                "ACTIVE"
        ));
    }

    @SafeVarargs
    private static SchemaEnvelope request(List<String> required, Map<String, Object>... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            properties.put(String.valueOf(field.get("name")), withoutName(field));
        }
        return SchemaEnvelope.object(properties, required);
    }

    @SafeVarargs
    private static SchemaEnvelope response(Map<String, Object>... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            properties.put(String.valueOf(field.get("name")), withoutName(field));
        }
        return SchemaEnvelope.object(properties, List.of());
    }

    private static Map<String, Object> field(String name, String type, String description) {
        return new LinkedHashMap<>(Map.of(
                "name", name,
                "type", type,
                "description", description
        ));
    }

    private static Map<String, Object> arrayField(String name, String description) {
        Map<String, Object> field = field(name, "array", description);
        field.put("items", Map.of(
                "type", "object",
                "additionalProperties", true
        ));
        return field;
    }

    private static Map<String, Object> withoutName(Map<String, Object> field) {
        Map<String, Object> copy = new LinkedHashMap<>(field);
        copy.remove("name");
        return copy;
    }
}
