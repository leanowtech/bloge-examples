package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Business-readable discovery material for one reusable capability.
 *
 * <p>The display contract is stored and revised independently from the executable entity. It may
 * influence candidate recall and review surfaces, but never business-contract or implementation
 * fingerprints. Lists are closed, bounded sets so a caller cannot turn the capability index into
 * an unbounded payload store.</p>
 *
 * @param schemaVersion display schema version
 * @param businessName concise business name
 * @param description business meaning of the capability
 * @param aliases alternative phrases that should recall the same candidate
 * @param tags bounded business classification labels
 * @param whenToUse business situations in which the capability is relevant
 * @param whenNotToUse business situations that must not select the capability
 * @param legacyProjection whether this value is a read-only projection for a pre-display entity
 */
public record BusinessCapabilityDisplay(
        String schemaVersion,
        String businessName,
        String description,
        List<String> aliases,
        List<String> tags,
        List<String> whenToUse,
        List<String> whenNotToUse,
        @com.fasterxml.jackson.annotation.JsonIgnore boolean legacyProjection
) {
    /** Current closed display schema. */
    public static final String SCHEMA_VERSION = "rg.businessCapabilityDisplay.v1";
    /** Maximum number of values in each display collection. */
    public static final int MAX_VALUES = 64;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_DESCRIPTION_LENGTH = 2_048;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "businessName", "description", "aliases", "tags",
            "whenToUse", "whenNotToUse");

    /** Creates an explicit display supplied by a current authoring request. */
    public BusinessCapabilityDisplay(
            String schemaVersion,
            String businessName,
            String description,
            List<String> aliases,
            List<String> tags,
            List<String> whenToUse,
            List<String> whenNotToUse) {
        this(schemaVersion, businessName, description, aliases, tags, whenToUse, whenNotToUse, false);
    }

    /** Normalizes text, freezes collections and rejects incomplete or unbounded displays. */
    public BusinessCapabilityDisplay {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) schemaVersion = SCHEMA_VERSION;
        businessName = normalized(businessName);
        description = normalized(description);
        aliases = values(aliases, "aliases");
        tags = values(tags, "tags");
        whenToUse = values(whenToUse, "whenToUse");
        whenNotToUse = values(whenNotToUse, "whenNotToUse");
        if (!SCHEMA_VERSION.equals(schemaVersion) || businessName.isBlank()
                || businessName.length() > MAX_NAME_LENGTH || description.isBlank()
                || description.length() > MAX_DESCRIPTION_LENGTH
                || forbidden(businessName) || forbidden(description)
                || aliases.stream().anyMatch(BusinessCapabilityDisplay::forbidden)
                || tags.stream().anyMatch(BusinessCapabilityDisplay::forbidden)
                || whenToUse.stream().anyMatch(BusinessCapabilityDisplay::forbidden)
                || whenNotToUse.stream().anyMatch(BusinessCapabilityDisplay::forbidden)) {
            throw new IllegalArgumentException("Business capability display is incomplete");
        }
    }

    /**
     * Decodes one explicit display while rejecting unknown fields and non-string collection items.
     *
     * @param node untrusted display object
     * @return validated immutable display
     */
    public static BusinessCapabilityDisplay decode(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) throw invalid();
        });
        return new BusinessCapabilityDisplay(
                text(node, "schemaVersion"), text(node, "businessName"),
                text(node, "description"), array(node, "aliases"), array(node, "tags"),
                array(node, "whenToUse"), array(node, "whenNotToUse"));
    }

    /** Creates a safe compatibility projection when an old entity has no display row. */
    public static BusinessCapabilityDisplay legacy(String businessName, String description) {
        String name = normalized(businessName);
        String meaning = normalized(description);
        if (name.isBlank() || forbidden(name)) name = "Legacy business capability";
        if (meaning.isBlank() || forbidden(meaning)) meaning = name;
        return new BusinessCapabilityDisplay(
                SCHEMA_VERSION, truncate(name, MAX_NAME_LENGTH),
                truncate(meaning, MAX_DESCRIPTION_LENGTH), List.of(), List.of(), List.of(), List.of(), true);
    }

    private static List<String> array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > MAX_VALUES) throw invalid();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) throw invalid();
            values.add(item.asText());
        });
        return values;
    }

    private static List<String> values(List<String> values, String field) {
        if (values == null) return List.of();
        if (values.size() > MAX_VALUES) throw invalid();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalized(value);
            if (item.isBlank() || item.length() > MAX_VALUE_LENGTH || !normalized.add(item)) {
                throw new IllegalArgumentException("Business capability display " + field + " is invalid");
            }
        }
        return List.copyOf(normalized);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Business capability display is invalid");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static boolean forbidden(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("bindingref") || normalized.contains("evaluationref")
                || normalized.contains("authorization:") || normalized.contains("password:")
                || normalized.contains("credential:") || normalized.contains("secret:");
    }
}
