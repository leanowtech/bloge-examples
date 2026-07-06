package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in graph-level integration contracts for resource gateway graphs.
 */
@Component
public class GatewayGraphContractCatalog {

    private final Map<String, GatewayGraphContract> contractsByGraphName;

    public GatewayGraphContractCatalog() {
        this(builtInContracts());
    }

    private GatewayGraphContractCatalog(Map<String, GatewayGraphContract> contractsByGraphName) {
        this.contractsByGraphName = Map.copyOf(contractsByGraphName);
    }

    /**
     * @return catalog populated with built-in resource gateway graph contracts
     */
    public static GatewayGraphContractCatalog builtIn() {
        return new GatewayGraphContractCatalog(builtInContracts());
    }

    /**
     * @return graph names covered by this catalog
     */
    public Collection<String> graphNames() {
        return contractsByGraphName.keySet();
    }

    /**
     * @return all contracts in stable graph-name order
     */
    public List<GatewayGraphContract> all() {
        return contractsByGraphName.values().stream()
                .sorted((left, right) -> left.graphName().compareTo(right.graphName()))
                .toList();
    }

    /**
     * @param graphName graph name
     * @return matching contract
     */
    public Optional<GatewayGraphContract> find(String graphName) {
        return Optional.ofNullable(contractsByGraphName.get(graphName));
    }

    /**
     * @param graphName graph name
     * @return matching contract
     * @throws IllegalArgumentException when no contract exists
     */
    public GatewayGraphContract require(String graphName) {
        return find(graphName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Gateway graph contract for '%s' not found. Available contracts: %s"
                                .formatted(graphName, contractsByGraphName.keySet())));
    }

    /**
     * @param graphName graph name
     * @return true when a contract exists
     */
    public boolean contains(String graphName) {
        return contractsByGraphName.containsKey(graphName);
    }

    private static Map<String, GatewayGraphContract> builtInContracts() {
        Map<String, GatewayGraphContract> contracts = new LinkedHashMap<>();
        put(contracts, new GatewayGraphContract(
                "aiEnrichedSearch",
                contextSchema(List.of("query"), field("query", string())),
                envelope(object(List.of("meta", "tokens", "citations"), false,
                        field("meta", arrayOf(object(Map.of(), List.of(), true))),
                        field("tokens", arrayOf(object(Map.of(), List.of(), true))),
                        field("citations", arrayOf(object(Map.of(), List.of(), true))))),
                List.of("assembleResult")));
        put(contracts, new GatewayGraphContract(
                "creditScore",
                contextSchema(List.of("userId"), field("userId", string())),
                envelope(object(List.of("provider", "score"), false,
                        field("provider", string()),
                        field("score", object(Map.of(), List.of(), true)))),
                List.of("assembleResult", "assemblePrimary", "assembleSecondary")));
        put(contracts, new GatewayGraphContract(
                "enrichOrderList",
                contextSchema(List.of("userId"), field("userId", string())),
                envelope(object(List.of("orders"), false,
                        field("orders", arrayOf(object(Map.of(), List.of(), true))))),
                List.of("collectEnriched")));
        put(contracts, new GatewayGraphContract(
                "loanDecisionPolicy",
                contextSchema(List.of("applicantId", "requestedAmount"),
                        field("applicantId", string()),
                        field("requestedAmount", number())),
                envelope(object(List.of("applicant", "requestedAmount", "policy", "explanation"), false,
                        field("applicant", object(Map.of(), List.of(), true)),
                        field("requestedAmount", number()),
                        field("policy", object(List.of("decision", "rate", "maxTerm", "reviewLane", "ruleId"), false,
                                field("decision", string()),
                                field("rate", number()),
                                field("maxTerm", integer()),
                                field("reviewLane", string()),
                                field("ruleId", string()))),
                        field("explanation", string()))),
                List.of("assembleLoanDecision")));
        put(contracts, new GatewayGraphContract(
                "productDetail",
                contextSchema(List.of("productId"), field("productId", string())),
                envelope(object(List.of("product", "productType"), true,
                        field("product", object(Map.of(), List.of(), true)),
                        field("productType", string()),
                        field("shipping", object(Map.of(), List.of(), true)),
                        field("license", object(Map.of(), List.of(), true)))),
                List.of("unifyDetail", "assemblePhysical", "assembleDigital", "assembleGeneric")));
        put(contracts, new GatewayGraphContract(
                "resourceDispatch",
                contextSchema(List.of("resourceId", "params"),
                        field("resourceId", string()),
                        field("params", object(Map.of(), List.of(), true)),
                        field("headerOverrides", object(Map.of(), List.of(), string())),
                        field("authOverride", any()),
                        field("timeoutOverride", any())),
                envelope(httpResourceOutputSchema()),
                List.of("executeResource")));
        put(contracts, new GatewayGraphContract(
                "userDashboard",
                contextSchema(List.of("userId"), field("userId", string())),
                envelope(object(List.of("profile", "orders", "recommendations", "wallet", "notifications"), false,
                        field("profile", object(Map.of(), List.of(), true)),
                        field("orders", object(Map.of(), List.of(), true)),
                        field("recommendations", object(Map.of(), List.of(), true)),
                        field("wallet", object(Map.of(), List.of(), true)),
                        field("notifications", object(Map.of(), List.of(), true)))),
                List.of("assembleDashboard")));
        return contracts;
    }

    private static void put(Map<String, GatewayGraphContract> contracts, GatewayGraphContract contract) {
        contracts.put(contract.graphName(), contract);
    }

    @SafeVarargs
    private static SchemaEnvelope contextSchema(List<String> required, Map.Entry<String, Object>... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bloge.tenantId", string());
        properties.put("bloge.namespace", string());
        for (Map.Entry<String, Object> field : fields) {
            properties.put(field.getKey(), field.getValue());
        }
        return envelope(object(properties, required, false));
    }

    private static SchemaEnvelope envelope(Map<String, Object> schema) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
    }

    @SafeVarargs
    private static Map<String, Object> object(List<String> required,
                                              boolean additionalProperties,
                                              Map.Entry<String, Object>... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : fields) {
            properties.put(field.getKey(), field.getValue());
        }
        return object(properties, required, additionalProperties);
    }

    private static Map<String, Object> object(Map<String, Object> properties,
                                              List<String> required,
                                              Object additionalProperties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", required == null ? List.of() : List.copyOf(required));
        schema.put("additionalProperties", additionalProperties);
        return schema;
    }

    private static Map<String, Object> httpResourceOutputSchema() {
        return object(List.of("resourceId", "statusCode", "payload", "rawBody", "duration", "success"), false,
                field("resourceId", string()),
                field("statusCode", integer()),
                field("payload", any()),
                field("rawBody", string()),
                field("duration", any()),
                field("success", bool()));
    }

    private static Map.Entry<String, Object> field(String name, Object schema) {
        return Map.entry(name, schema);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> any() {
        return Map.of("kind", "any");
    }

    private static Map<String, Object> arrayOf(Map<String, Object> itemSchema) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", itemSchema);
        return schema;
    }
}
