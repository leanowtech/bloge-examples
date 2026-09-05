package com.leanowtech.bloge.gateway.solution.capability;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares structured business contracts without using implementation bindings or fuzzy text as
 * authority. Missing query dimensions produce PARTIAL; incompatible closed dimensions produce
 * CONFLICT; only a complete field-for-field match can produce EXACT.
 */
@Component
public final class BusinessContractMatcher {
    private static final List<String> REQUIRED = List.of(
            "semanticKey", "domain", "businessObject", "requiredContext", "resultDomain",
            "asOf", "unknownPolicy", "acquisitionOwner", "effect");
    private static final List<String> CLOSED = List.of(
            "semanticKey", "domain", "businessObject", "resultDomain", "asOf",
            "unknownPolicy", "acquisitionOwner", "authoritySource", "freshness", "effect");

    /** Compares a normalized search query with one candidate semantic definition. */
    public Match match(JsonNode query, JsonNode candidate) {
        JsonNode definition = candidate.path("businessDefinition").isObject()
                ? candidate.path("businessDefinition") : candidate;
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (String field : REQUIRED) {
            JsonNode queryValue = queryValue(query, field);
            if (missingRequiredFacet(field, queryValue)) missing.add(field);
        }
        for (String field : CLOSED) {
            JsonNode queryValue = queryValue(query, field);
            JsonNode candidateValue = definition.path(field);
            if (empty(queryValue) || empty(candidateValue)) continue;
            if (equivalent(queryValue, candidateValue)) matched.add(field);
            else conflicts.add(field);
        }
        compareRequiredContext(queryValue(query, "requiredContext"), definition.path("requiredContext"),
                matched, missing, conflicts);
        MatchType type = !conflicts.isEmpty() ? MatchType.CONFLICT
                : missing.isEmpty() ? MatchType.EXACT : MatchType.PARTIAL;
        return new Match(type, List.copyOf(new LinkedHashSet<>(matched)),
                List.copyOf(new LinkedHashSet<>(missing)), List.copyOf(new LinkedHashSet<>(conflicts)));
    }

    private static JsonNode queryValue(JsonNode query, String field) {
        if ("resultDomain".equals(field) && !query.has(field)) return query.path("expectedResult");
        return query.path(field);
    }

    private static void compareRequiredContext(JsonNode query, JsonNode candidate,
                                               List<String> matched, List<String> missing,
                                               List<String> conflicts) {
        if (empty(query)) return;
        if (!query.isArray() || !candidate.isArray()) {
            conflicts.add("requiredContext");
            return;
        }
        Set<String> queryKeys = new LinkedHashSet<>();
        query.forEach(value -> queryKeys.add(contextIdentity(value)));
        Set<String> candidateKeys = new LinkedHashSet<>();
        candidate.forEach(value -> candidateKeys.add(contextIdentity(value)));
        boolean typeConflict = false;
        for (JsonNode value : query) {
            String identity = contextIdentity(value);
            if (!candidateKeys.contains(identity)) {
                String key = value.path("semanticKey").asText(value.path("name").asText());
                boolean sameKey = hasContextKey(candidate, key);
                typeConflict |= sameKey;
                if (!sameKey) missing.add("requiredContext");
            }
        }
        for (JsonNode value : candidate) {
            if (value.path("required").asBoolean(true) && !queryKeys.contains(contextIdentity(value))) {
                String key = value.path("semanticKey").asText(value.path("name").asText());
                if (hasContextKey(query, key)) typeConflict = true;
                else missing.add("requiredContext");
            }
        }
        if (typeConflict) conflicts.add("requiredContext");
        else if (!missing.contains("requiredContext")) matched.add("requiredContext");
    }

    /** Returns whether one context vector contains the same business key, regardless of type. */
    private static boolean hasContextKey(JsonNode contexts, String key) {
        for (JsonNode item : contexts) {
            if (item.path("semanticKey").asText(item.path("name").asText()).equals(key)) return true;
        }
        return false;
    }

    /** Treats an explicit empty context vector as a complete statement that no context is needed. */
    private static boolean missingRequiredFacet(String field, JsonNode value) {
        return !"requiredContext".equals(field) ? empty(value)
                : value == null || value.isMissingNode() || value.isNull() || !value.isArray();
    }

    private static String contextIdentity(JsonNode value) {
        return value.path("semanticKey").asText(value.path("name").asText()) + "|"
                + value.path("type").asText() + "|" + value.path("required").asBoolean(true);
    }

    private static boolean equivalent(JsonNode left, JsonNode right) {
        if (left.isTextual() && right.isTextual()) {
            return left.asText().trim().equalsIgnoreCase(right.asText().trim());
        }
        return left.equals(right);
    }

    private static boolean empty(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || (value.isTextual() && value.asText().isBlank())
                || (value.isContainerNode() && value.isEmpty());
    }

    /** Stable candidate classification. */
    public enum MatchType { EXACT, PARTIAL, CONFLICT }

    /** Explainable field-level comparison result. */
    public record Match(MatchType type,
                        List<String> matchedFacets,
                        List<String> missingFacets,
                        List<String> conflicts) {
        /** @return true only for a complete, conflict-free semantic identity */
        public boolean exact() { return type == MatchType.EXACT; }
    }
}
