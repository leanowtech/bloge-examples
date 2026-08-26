package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict consumer projection of the Tool Studio capability probe.
 * @param schemaVersion capability object schema version
 * @param protocol protocol name
 * @param protocolVersion protocol version
 * @param supportedObjects advertised object schema versions
 * @param features advertised feature flags
 * @param limits authoritative payload-free limits
 */
public record ResourceGatewayCapabilities(
        String schemaVersion,
        String protocol,
        String protocolVersion,
        Map<String, List<String>> supportedObjects,
        Map<String, Boolean> features,
        Map<String, Integer> limits
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.capabilities.v1";
    public static final String FUNCTION_ASSET_SCHEMA = "bloge.functionControlAsset.v1";

    /** Freezes all capability collections. */
    public ResourceGatewayCapabilities {
        supportedObjects = immutableLists(supportedObjects);
        features = features == null ? Map.of() : Map.copyOf(features);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    /** Decodes either the integration envelope or its payload.
     * @param response integration response or capability payload
     * @return strict capability projection
     */
    public static ResourceGatewayCapabilities from(JsonNode response) {
        JsonNode payload = response != null && response.has("payload")
                ? response.path("payload") : response;
        if (payload == null || !payload.isObject()) throw invalid();
        String schema = text(payload, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schema)) throw invalid();
        Map<String, List<String>> objects = new LinkedHashMap<>();
        JsonNode objectNode = payload.path("supportedObjects");
        if (!objectNode.isObject()) throw invalid();
        objectNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) throw invalid();
            List<String> versions = new ArrayList<>();
            entry.getValue().forEach(version -> versions.add(text(version)));
            objects.put(entry.getKey(), List.copyOf(versions));
        });
        Map<String, Boolean> features = new LinkedHashMap<>();
        JsonNode featureNode = payload.path("features");
        if (!featureNode.isObject()) throw invalid();
        featureNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isBoolean()) throw invalid();
            features.put(entry.getKey(), entry.getValue().booleanValue());
        });
        Map<String, Integer> limits = new LinkedHashMap<>();
        JsonNode limitNode = payload.path("limits");
        if (!limitNode.isObject()) throw invalid();
        limitNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToInt()
                    || entry.getValue().intValue() < 1) throw invalid();
            limits.put(entry.getKey(), entry.getValue().intValue());
        });
        ResourceGatewayCapabilities result = new ResourceGatewayCapabilities(schema,
                text(payload, "protocol"), text(payload, "protocolVersion"), objects, features, limits);
        result.validateFunctionAdvertisement();
        return result;
    }

    /**
     * Returns whether the deployment advertises a usable function-control provider.
     * @return whether function-control assets and payload-free evidence are available
     */
    public boolean functionControlAvailable() {
        return features.getOrDefault("functionControlAssetReference", false)
                && features.getOrDefault("functionControlGovernedCatalog", false)
                && features.getOrDefault("functionControlStateComposition", false)
                && features.getOrDefault("functionControlPayloadFreeEvidence", false);
    }

    /**
     * Returns the advertised function-control asset schema version.
     * @return the schema version, or an empty string when the provider is unavailable
     */
    public String functionControlAssetSchemaVersion() {
        List<String> versions = supportedObjects.get("functionControlAsset");
        return versions == null || versions.isEmpty() ? "" : versions.getFirst();
    }

    /**
     * Looks up one authoritative server-advertised limit.
     * @param name authoritative limit key
     * @return positive server-advertised limit
     */
    public int limit(String name) {
        Integer value = limits.get(name);
        if (value == null) throw new IllegalArgumentException("Capability limit is unavailable");
        return value;
    }

    private void validateFunctionAdvertisement() {
        boolean advertised = functionControlAvailable();
        boolean objectAdvertised = supportedObjects.containsKey("functionControlAsset");
        if (advertised != objectAdvertised
                || advertised && !supportedObjects.get("functionControlAsset").contains(FUNCTION_ASSET_SCHEMA)) {
            throw invalid();
        }
        if (advertised) {
            for (String required : List.of("testControlEnvelopeDecodedBytes", "functionNameChars",
                    "functionDeclarations", "functionRules", "functionDurationMillis",
                    "functionConsumption", "functionJsonValueBytes", "functionJsonValueDepth",
                    "functionSchemaBytes", "functionSchemaDepth")) {
                if (!limits.containsKey(required)) throw invalid();
            }
        }
    }

    private static Map<String, List<String>> immutableLists(Map<String, List<String>> values) {
        if (values == null) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private static String text(JsonNode object, String field) {
        return text(object.get(field));
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue().trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Resource Gateway capability projection");
    }
}
