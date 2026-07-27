package com.leanowtech.bloge.gateway.visual.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned non-schema semantics embedded under
 * {@code GraphDraft.visualLayout.graphContract.contractSemantics}.
 *
 * <p>The graph keeps input/output schemas as first-class fields. This companion protocol preserves
 * errors, effects, idempotency, invariants, compatibility policy, and field governance metadata
 * without making the otherwise opaque visual layout an untyped source of truth.</p>
 *
 * @param schemaVersion embedded semantics protocol version
 * @param errorContract declared stable errors
 * @param executionSemantics target effect and execution guarantees
 * @param invariants target preconditions and postconditions
 * @param compatibilityPolicy authoring compatibility policy
 * @param fieldMetadata JSON-Pointer keyed field governance metadata
 */
public record GraphContractSemantics(
        String schemaVersion,
        List<ContractDraft.ErrorVariant> errorContract,
        ContractDraft.ExecutionSemantics executionSemantics,
        List<ContractDraft.ContractInvariant> invariants,
        ContractDraft.CompatibilityPolicy compatibilityPolicy,
        Map<String, ContractDraft.FieldMetadata> fieldMetadata
) {
    /** Current embedded semantics protocol. */
    public static final String SCHEMA_VERSION = "bloge.graphContractSemantics.v1";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "errorContract", "executionSemantics", "invariants",
            "compatibilityPolicy", "fieldMetadata");

    /** Freezes authored collections and preserves UNKNOWN defaults. */
    public GraphContractSemantics {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        errorContract = errorContract == null ? List.of() : List.copyOf(errorContract);
        executionSemantics = executionSemantics == null
                ? ContractDraft.ExecutionSemantics.unknown() : executionSemantics;
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
        compatibilityPolicy = compatibilityPolicy == null
                ? ContractDraft.CompatibilityPolicy.strict() : compatibilityPolicy;
        fieldMetadata = fieldMetadata == null ? Map.of() : Map.copyOf(fieldMetadata);
    }

    /**
     * Reads and strictly validates the optional embedded protocol.
     *
     * @param visualLayout graph visual layout
     * @return empty when no semantics are declared
     */
    public static Optional<GraphContractSemantics> fromVisualLayout(
            Map<String, Object> visualLayout) {
        Map<String, Object> graphContract = objectMap(
                visualLayout == null ? null : visualLayout.get("graphContract"));
        if (graphContract.isEmpty() || !graphContract.containsKey("contractSemantics")) {
            return Optional.empty();
        }
        Map<String, Object> source = objectMap(graphContract.get("contractSemantics"));
        if (source.isEmpty()) {
            throw invalid("contractSemantics must be an object");
        }
        requireExactFields(source, ROOT_FIELDS, "contractSemantics");
        String schemaVersion = requiredString(source.get("schemaVersion"), "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("unsupported contractSemantics schemaVersion");
        }
        return Optional.of(new GraphContractSemantics(
                schemaVersion,
                list(source.get("errorContract")).stream()
                        .map(GraphContractSemantics::errorVariant).toList(),
                executionSemantics(objectMapRequired(
                        source.get("executionSemantics"), "executionSemantics")),
                list(source.get("invariants")).stream()
                        .map(GraphContractSemantics::invariant).toList(),
                compatibilityPolicy(objectMapRequired(
                        source.get("compatibilityPolicy"), "compatibilityPolicy")),
                fieldMetadata(objectMapRequired(source.get("fieldMetadata"), "fieldMetadata"))
        ));
    }

    private static ContractDraft.ErrorVariant errorVariant(Object value) {
        Map<String, Object> map = objectMapRequired(value, "errorContract item");
        requireExactFields(map, Set.of("code", "type", "description", "retryable"),
                "errorContract item");
        return new ContractDraft.ErrorVariant(
                requiredString(map.get("code"), "error code"),
                requiredString(map.get("type"), "error type"),
                optionalString(map.get("description"), "error description"),
                requiredBoolean(map.get("retryable"), "error retryable"));
    }

    private static ContractDraft.ExecutionSemantics executionSemantics(
            Map<String, Object> map) {
        requireFields(map, Set.of("effect", "idempotency", "streaming", "durable",
                "sideEffectProtocol"), "executionSemantics");
        requireFieldsPresent(map, Set.of("effect", "idempotency", "streaming", "durable"),
                "executionSemantics");
        ContractDraft.SideEffectProtocol sideEffectProtocol = map.get("sideEffectProtocol") == null
                ? null : sideEffectProtocol(objectMapRequired(
                        map.get("sideEffectProtocol"), "sideEffectProtocol"));
        return new ContractDraft.ExecutionSemantics(
                effect(map.get("effect")),
                requiredString(map.get("idempotency"), "idempotency"),
                nullableBoolean(map.get("streaming"), "streaming"),
                nullableBoolean(map.get("durable"), "durable"),
                sideEffectProtocol);
    }

    private static ContractDraft.SideEffectProtocol sideEffectProtocol(Map<String, Object> map) {
        requireExactFields(map, Set.of("protocol", "reconcilerRef", "reversible", "metadata"),
                "sideEffectProtocol");
        return new ContractDraft.SideEffectProtocol(
                requiredString(map.get("protocol"), "sideEffectProtocol.protocol"),
                optionalString(map.get("reconcilerRef"), "sideEffectProtocol.reconcilerRef"),
                requiredBoolean(map.get("reversible"), "sideEffectProtocol.reversible"),
                objectMapRequired(map.get("metadata"), "sideEffectProtocol.metadata"));
    }

    private static ContractDraft.ContractInvariant invariant(Object value) {
        Map<String, Object> map = objectMapRequired(value, "invariant");
        requireExactFields(map, Set.of("invariantId", "phase", "expression", "description", "severity"),
                "invariant");
        String phase = enumString(map.get("phase"), Set.of("PRECONDITION", "POSTCONDITION"),
                "invariant phase");
        String severity = enumString(map.get("severity"), Set.of("ERROR", "WARNING"),
                "invariant severity");
        return new ContractDraft.ContractInvariant(
                requiredString(map.get("invariantId"), "invariant id"),
                phase,
                requiredString(map.get("expression"), "invariant expression"),
                optionalString(map.get("description"), "invariant description"),
                severity);
    }

    private static ContractDraft.CompatibilityPolicy compatibilityPolicy(
            Map<String, Object> map) {
        requireExactFields(map, Set.of("mode", "unknownBlocksAutomaticMigration"),
                "compatibilityPolicy");
        return new ContractDraft.CompatibilityPolicy(
                enumString(map.get("mode"), Set.of("STRICT", "BACKWARD", "FORWARD", "NONE"),
                        "compatibility mode"),
                requiredBoolean(map.get("unknownBlocksAutomaticMigration"),
                        "unknownBlocksAutomaticMigration"));
    }

    private static Map<String, ContractDraft.FieldMetadata> fieldMetadata(
            Map<String, Object> source) {
        Map<String, ContractDraft.FieldMetadata> result = new LinkedHashMap<>();
        source.forEach((path, value) -> {
            if (!validJsonPointer(path)) {
                throw invalid("fieldMetadata key must be a JSON Pointer");
            }
            Map<String, Object> map = objectMapRequired(value, "fieldMetadata");
            requireExactFields(map, Set.of("displayName", "description", "classification", "source",
                    "confidence", "extensions"), "fieldMetadata");
            result.put(path, new ContractDraft.FieldMetadata(
                    optionalString(map.get("displayName"), "field displayName"),
                    optionalString(map.get("description"), "field description"),
                    enumString(map.get("classification"),
                            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"),
                            "field classification"),
                    enumValue(ContractDraft.Source.class, map.get("source")),
                    enumValue(ContractDraft.Confidence.class, map.get("confidence")),
                    objectMapRequired(map.get("extensions"), "field extensions")));
        });
        return result;
    }

    private static ContractDraft.Effect effect(Object value) {
        return enumValue(ContractDraft.Effect.class, value);
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            Object value) {
        String candidate = requiredString(value, type.getSimpleName());
        try {
            return Enum.valueOf(type, candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("unsupported " + type.getSimpleName());
        }
    }

    private static void requireFields(
            Map<String, Object> map,
            Set<String> allowed,
            String target) {
        if (!allowed.containsAll(map.keySet())) {
            throw invalid(target + " contains unsupported fields");
        }
    }

    private static void requireExactFields(
            Map<String, Object> map,
            Set<String> fields,
            String target) {
        requireFields(map, fields, target);
        requireFieldsPresent(map, fields, target);
    }

    private static void requireFieldsPresent(
            Map<String, Object> map,
            Set<String> required,
            String target) {
        if (!map.keySet().containsAll(required)) {
            throw invalid(target + " is missing required fields");
        }
    }

    private static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> result) {
            return result;
        }
        throw invalid("expected an array");
    }

    private static Map<String, Object> objectMapRequired(Object value, String target) {
        Map<String, Object> result = objectMap(value);
        if (result.isEmpty() && !(value instanceof Map<?, ?>)) {
            throw invalid(target + " must be an object");
        }
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Boolean nullableBoolean(Object value, String target) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean result) {
            return result;
        }
        throw invalid(target + " must be boolean or null");
    }

    private static boolean requiredBoolean(Object value, String target) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw invalid(target + " must be a boolean");
    }

    private static String requiredString(Object value, String target) {
        String result = optionalString(value, target);
        if (result.isEmpty()) {
            throw invalid(target + " must not be empty");
        }
        return result;
    }

    private static String optionalString(Object value, String target) {
        if (!(value instanceof String result)) {
            throw invalid(target + " must be a string");
        }
        return result.trim();
    }

    private static String enumString(Object value, Set<String> allowed, String target) {
        String result = requiredString(value, target).toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) {
            throw invalid("unsupported " + target);
        }
        return result;
    }

    private static boolean validJsonPointer(String value) {
        if (value == null || (!value.isEmpty() && !value.startsWith("/"))) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '~'
                    && (index + 1 >= value.length()
                    || (value.charAt(index + 1) != '0' && value.charAt(index + 1) != '1'))) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid graph Contract semantics: " + detail + ".");
    }
}
