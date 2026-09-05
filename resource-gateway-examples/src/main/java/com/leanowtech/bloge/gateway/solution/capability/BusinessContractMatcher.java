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
    private static final List<String> COMMON = List.of(
            "schemaVersion", "semanticKey", "intent", "domain", "businessObject");
    private static final List<String> FACT = List.of(
            "requiredContext", "resultDomain", "asOf", "unknownPolicy", "acquisitionOwner",
            "authoritySource", "freshness", "effect");
    private static final List<String> SCENARIO = List.of(
            "inputFactKeys", "decisionPolicy", "outletSemanticKeys", "otherwisePolicy");
    private static final List<String> INSTRUCTION = List.of(
            "requiredFactKeys", "resultDomain", "reasoningPolicy", "effect", "failurePolicy",
            "writeGovernanceClass");
    private static final List<String> SOLUTION = List.of(
            "problemClass", "requiredFactKeys", "scenarioSemanticKey",
            "dispositionSemanticKeys", "runtimeUse");

    /**
     * Compares a normalized search query with one candidate semantic definition.
     *
     * <p>{@code schemaVersion} is part of the common business identity. This prevents a Feature,
     * Scenario, Instruction, or Solution profile from being reused as another profile even when
     * their shared envelope happens to match. A legacy query without an explicit profile can
     * still participate in recall, but is intentionally limited to {@link MatchType#PARTIAL}.</p>
     */
    public Match match(JsonNode query, JsonNode candidate) {
        JsonNode definition = candidate.path("businessDefinition").isObject()
                ? candidate.path("businessDefinition") : candidate;
        List<String> profileFields = profileFields(definition);
        List<String> requiredFields = java.util.stream.Stream.concat(
                COMMON.stream(), profileFields.stream()).distinct().toList();
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (String field : requiredFields) {
            JsonNode queryValue = queryValue(query, field);
            if ("authoritySource".equals(field)
                    && !"PLATFORM".equalsIgnoreCase(queryValue(query, "acquisitionOwner").asText())) {
                continue;
            }
            if (missingRequiredFacet(field, queryValue)) missing.add(field);
        }
        for (String field : requiredFields) {
            if ("requiredContext".equals(field)) continue;
            JsonNode queryValue = queryValue(query, field);
            JsonNode candidateValue = definition.path(field);
            if (empty(queryValue) || empty(candidateValue)) continue;
            if (equivalent(queryValue, candidateValue)) matched.add(field);
            else conflicts.add(field);
        }
        if (profileFields.contains("requiredContext")) {
            compareRequiredContext(queryValue(query, "requiredContext"), definition.path("requiredContext"),
                    matched, missing, conflicts);
        }
        MatchType type = !conflicts.isEmpty() ? MatchType.CONFLICT
                : missing.isEmpty() ? MatchType.EXACT : MatchType.PARTIAL;
        return new Match(type, List.copyOf(new LinkedHashSet<>(matched)),
                List.copyOf(new LinkedHashSet<>(missing)), List.copyOf(new LinkedHashSet<>(conflicts)));
    }

    private static JsonNode queryValue(JsonNode query, String field) {
        if ("resultDomain".equals(field) && !query.has(field)) return query.path("expectedResult");
        return query.path(field);
    }

    /** Selects the closed semantic dimensions owned by the candidate profile schema. */
    private static List<String> profileFields(JsonNode definition) {
        String schema = definition.path("schemaVersion").asText();
        if (schema.contains("Scenario")) return SCENARIO;
        if (schema.contains("Instruction")) return INSTRUCTION;
        if (schema.contains("Solution")) return SOLUTION;
        return FACT;
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
