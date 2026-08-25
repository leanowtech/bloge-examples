package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative compatibility analysis for revisions of one logical resource contract. */
public final class LogicalResourceContractCompatibility {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "null", "boolean", "object", "array", "number", "integer", "string");
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum", "const", "format",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
            "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly", "$comment");

    private LogicalResourceContractCompatibility() {
    }

    /**
     * Analyzes one logical contract revision against another. Only proven incompatibilities are
     * {@link Status#BREAKING}; unsupported structure and semantic changes remain review-required.
     */
    public static Report analyze(LogicalResourceContract baseline, LogicalResourceContract candidate) {
        if (baseline == null || candidate == null) {
            throw LogicalResourceContractException.invalid();
        }
        List<Finding> findings = new ArrayList<>();
        if (!baseline.contractId().equals(candidate.contractId())) {
            findings.add(Finding.review("/contractId", "CONTRACT_ID_CHANGED",
                    "Different logical contract identities require manual review."));
        }
        compareSchema("input", baseline.internalInputShape(), candidate.internalInputShape(), findings);
        compareSchema("output", candidate.internalOutputShape(), baseline.internalOutputShape(), findings);
        compareSemantics(baseline.semantics(), candidate.semantics(), findings);
        findings.sort(Comparator.comparing(Finding::path).thenComparing(Finding::code));
        Status status = findings.stream().anyMatch(finding -> finding.classification() == Classification.BREAKING)
                ? Status.BREAKING
                : findings.isEmpty() ? Status.COMPATIBLE : Status.REVIEW_REQUIRED;
        return new Report(status, status != Status.BREAKING, findings);
    }

    static Knowledge schemaKnowledge(SchemaEnvelope envelope) {
        if (envelope == null || !SchemaEnvelope.JSON_SCHEMA.equals(envelope.format())
                || !"2020-12".equals(envelope.version())) {
            return Knowledge.UNKNOWN;
        }
        return schemaMapKnown(envelope.schema()) ? Knowledge.KNOWN : Knowledge.UNKNOWN;
    }

    private static void compareSchema(String role,
                                      SchemaEnvelope source,
                                      SchemaEnvelope target,
                                      List<Finding> findings) {
        String upperRole = role.toUpperCase();
        if (!source.format().equals(target.format()) || !source.version().equals(target.version())
                || schemaKnowledge(source) == Knowledge.UNKNOWN || schemaKnowledge(target) == Knowledge.UNKNOWN) {
            findings.add(Finding.review("/" + role + "Shape", upperRole + "_SCHEMA_UNKNOWN",
                    "Schema compatibility requires manual review."));
            return;
        }
        VisualSchemaCompatibility.schemaCompatibilityIssueDetail(source.schema(), target.schema())
                .ifPresent(issue -> findings.add(Finding.breaking(
                        issue.path().isBlank() ? "/" + role + "Shape" : "/" + role + "Shape/" + issue.path(),
                        upperRole + "_SCHEMA_INCOMPATIBLE", "Schema revision is not backward compatible.")));
    }

    private static void compareSemantics(ResponseSemantics baseline,
                                         ResponseSemantics candidate,
                                         List<Finding> findings) {
        if (baseline.requiresReview() || candidate.requiresReview()) {
            findings.add(Finding.review("/semantics", "RESPONSE_SEMANTICS_UNKNOWN",
                    "Response semantics require manual confirmation."));
        } else if (!baseline.equals(candidate)) {
            findings.add(Finding.review("/semantics", "RESPONSE_SEMANTICS_CHANGED",
                    "Confirmed response semantics changed and require semantic review."));
        }
    }

    private static boolean schemaMapKnown(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty() || !SUPPORTED_KEYWORDS.containsAll(schema.keySet())) {
            return false;
        }
        if (!knownType(schema.get("type"))) {
            return false;
        }
        Object properties = schema.get("properties");
        if (properties != null) {
            if (!(properties instanceof Map<?, ?> propertyMap)) {
                return false;
            }
            for (Object property : propertyMap.values()) {
                if (!(property instanceof Map<?, ?> nested) || !schemaMapKnown(stringMap(nested))) {
                    return false;
                }
            }
        }
        Object required = schema.get("required");
        if (required != null && (!(required instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String)))) {
            return false;
        }
        Object additional = schema.get("additionalProperties");
        if (additional != null && !(additional instanceof Boolean)) {
            return false;
        }
        Object items = schema.get("items");
        return items == null || items instanceof Map<?, ?> map && schemaMapKnown(stringMap(map));
    }

    private static boolean knownType(Object raw) {
        if (raw instanceof String type) {
            return SUPPORTED_TYPES.contains(type);
        }
        return raw instanceof List<?> types && !types.isEmpty()
                && types.stream().allMatch(type -> type instanceof String value && SUPPORTED_TYPES.contains(value));
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    enum Knowledge { KNOWN, UNKNOWN }

    public enum Status { COMPATIBLE, BREAKING, REVIEW_REQUIRED }

    public enum Classification { BREAKING, REVIEW_REQUIRED }

    public record Finding(String path, String code, Classification classification, String message) {
        public Finding {
            if (path == null || code == null || classification == null || message == null) {
                throw LogicalResourceContractException.invalid();
            }
        }

        static Finding breaking(String path, String code, String message) {
            return new Finding(path, code, Classification.BREAKING, message);
        }

        static Finding review(String path, String code, String message) {
            return new Finding(path, code, Classification.REVIEW_REQUIRED, message);
        }
    }

    public record Report(Status status, boolean retainsBinding, List<Finding> findings) {
        public Report {
            if (status == null || findings == null) {
                throw LogicalResourceContractException.invalid();
            }
            findings = List.copyOf(findings);
            if (retainsBinding != (status != Status.BREAKING)) {
                throw LogicalResourceContractException.invalid();
            }
        }

        /** @return true only when no breaking or unresolved finding remains */
        public boolean automaticUseAllowed() {
            return status == Status.COMPATIBLE;
        }
    }
}
