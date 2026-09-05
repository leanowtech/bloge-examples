package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Locale;

/**
 * Implementation-independent business identity for one fact collected by a Solution.
 *
 * <p>Every field participates in semantic matching and the Feature contract fingerprint. The
 * contract never carries a URL, runtime binding, credential or business sample.</p>
 *
 * @param schemaVersion semantic contract schema
 * @param semanticKey governed business concept identity
 * @param intent business problem answered by the fact
 * @param domain bounded business domain
 * @param businessObject subject whose fact is evaluated
 * @param requiredContext typed business context required to obtain the fact
 * @param resultDomain closed result shape and meanings
 * @param asOf business time at which the value is true
 * @param unknownPolicy required business response when the fact is unavailable
 * @param acquisitionOwner party responsible for obtaining the value
 * @param authoritySource authoritative source class for platform evaluation
 * @param freshness business validity window
 * @param effect PURE or READ
 * @param lifecycle PROPOSED, ACTIVE or DEPRECATED semantic-key lifecycle
 */
public record BusinessFactSemanticContract(
        String schemaVersion,
        String semanticKey,
        String intent,
        String domain,
        String businessObject,
        JsonNode requiredContext,
        JsonNode resultDomain,
        String asOf,
        String unknownPolicy,
        String acquisitionOwner,
        String authoritySource,
        JsonNode freshness,
        String effect,
        String lifecycle
) {
    public static final String SCHEMA_VERSION = "rg.businessFactSemanticContract.v1";

    /** Normalizes closed labels, freezes JSON values and validates semantic completeness. */
    public BusinessFactSemanticContract {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        semanticKey = normalized(semanticKey);
        intent = normalized(intent);
        domain = normalized(domain);
        businessObject = normalized(businessObject);
        requiredContext = copy(requiredContext, true);
        resultDomain = copy(resultDomain, false);
        asOf = upper(asOf);
        unknownPolicy = upper(unknownPolicy);
        acquisitionOwner = upper(acquisitionOwner);
        authoritySource = normalized(authoritySource);
        freshness = copy(freshness, false);
        effect = upper(effect);
        lifecycle = upper(lifecycle).isBlank() ? "PROPOSED" : upper(lifecycle);
        if (!SCHEMA_VERSION.equals(schemaVersion) || semanticKey.isBlank() || intent.isBlank()
                || domain.isBlank() || businessObject.isBlank() || !requiredContext.isArray()
                || !resultDomain.isObject() || asOf.isBlank() || unknownPolicy.isBlank()
                || acquisitionOwner.isBlank() || !freshness.isObject()
                || !(effect.equals("PURE") || effect.equals("READ"))
                || !java.util.Set.of("PROPOSED", "ACTIVE", "DEPRECATED").contains(lifecycle)) {
            throw new IllegalArgumentException("Business fact semantic contract is incomplete");
        }
        if ("PLATFORM".equals(acquisitionOwner) && authoritySource.isBlank()) {
            throw new IllegalArgumentException("Platform-owned facts require authoritySource");
        }
    }

    /** Creates the explicit UNKNOWN projection used only when reading pre-v2 Features. */
    public static BusinessFactSemanticContract legacy(String featureRef, String summary,
                                                      JsonNode inputs, JsonNode output) {
        var freshness = JsonNodeFactory.instance.objectNode().put("mode", "UNKNOWN");
        return new BusinessFactSemanticContract(SCHEMA_VERSION,
                "legacy:" + normalized(featureRef), normalized(summary), "UNKNOWN", "UNKNOWN",
                JsonNodeFactory.instance.arrayNode(), output, "UNKNOWN", "UNKNOWN", "UNKNOWN", "",
                freshness, "PURE", "PROPOSED");
    }

    /** @return true when this is a compatibility projection that cannot be reused exactly */
    public boolean incompleteLegacyProjection() {
        return semanticKey.startsWith("legacy:") || "UNKNOWN".equals(domain)
                || "UNKNOWN".equals(businessObject) || "UNKNOWN".equals(asOf)
                || "UNKNOWN".equals(unknownPolicy) || "UNKNOWN".equals(acquisitionOwner);
    }

    @Override public JsonNode requiredContext() { return requiredContext.deepCopy(); }
    @Override public JsonNode resultDomain() { return resultDomain.deepCopy(); }
    @Override public JsonNode freshness() { return freshness.deepCopy(); }

    private static JsonNode copy(JsonNode value, boolean array) {
        if (value == null || value.isMissingNode()) {
            return array ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
        }
        return value.deepCopy();
    }

    private static String normalized(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return normalized(value).toUpperCase(Locale.ROOT); }
}
