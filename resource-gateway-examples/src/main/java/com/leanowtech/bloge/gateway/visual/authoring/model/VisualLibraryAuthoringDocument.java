package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Human-oriented source contract compiled into {@code bloge.visualOperatorLibrary.v1}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record VisualLibraryAuthoringDocument(
        String schemaVersion,
        LibraryMetadata library,
        Defaults defaults,
        Map<String, JsonNode> types,
        Map<String, OperatorAuthoring> operators,
        Map<String, FunctionAuthoring> functions,
        List<ImportReference> imports,
        Map<String, JsonNode> examples
) {
    public static final String SCHEMA_VERSION = "bloge.visualLibraryAuthoring.v1";

    public VisualLibraryAuthoringDocument {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        library = library == null ? new LibraryMetadata("", "", "", "", "") : library;
        defaults = defaults == null ? new Defaults("", "") : defaults;
        types = immutableMap(types);
        operators = immutableMap(operators);
        functions = immutableMap(functions);
        imports = immutableList(imports);
        examples = immutableMap(examples);
    }

    /**
     * Human-facing library identity and ownership.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record LibraryMetadata(
            String id,
            String name,
            String version,
            String owner,
            String status
    ) {
        public LibraryMetadata {
            id = normalized(id, "");
            name = normalized(name, "");
            version = normalized(version, "1.0.0");
            owner = normalized(owner, "");
            status = normalized(status, "ACTIVE").toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Safe defaults shared by Quick authoring entries.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Defaults(
            String operatorVersion,
            String namespace
    ) {
        public Defaults {
            operatorVersion = normalized(operatorVersion, "1.0.0");
            namespace = normalized(namespace, "");
        }
    }

    /**
     * Quick operator definition. Input and output values are compact type expressions or structured type nodes.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record OperatorAuthoring(
            String name,
            String description,
            String archetype,
            String version,
            List<String> tags,
            Map<String, JsonNode> input,
            Map<String, JsonNode> output,
            JsonNode config,
            String effect,
            String idempotency,
            Boolean streaming,
            Boolean durable,
            Boolean requiresSecrets,
            JsonNode runtime,
            List<TestReference> tests
    ) {
        public OperatorAuthoring {
            name = normalized(name, "");
            description = normalized(description, "");
            archetype = normalized(archetype, "pure").toLowerCase(Locale.ROOT);
            version = normalized(version, "");
            tags = immutableStrings(tags);
            input = immutableMap(input);
            output = immutableMap(output);
            effect = normalized(effect, "").toUpperCase(Locale.ROOT);
            idempotency = normalized(idempotency, "").toUpperCase(Locale.ROOT);
            tests = immutableList(tests);
        }
    }

    /**
     * Quick built-in function definition.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FunctionAuthoring(
            String name,
            String namespace,
            String description,
            String category,
            String signature,
            List<String> signatures,
            List<String> examples
    ) {
        public FunctionAuthoring {
            name = normalized(name, "");
            namespace = normalized(namespace, "");
            description = normalized(description, "");
            category = normalized(category, "");
            signature = normalized(signature, "");
            signatures = immutableStrings(signatures);
            examples = immutableStrings(examples);
        }

        public List<String> allSignatures() {
            List<String> values = new ArrayList<>();
            if (!signature.isBlank()) {
                values.add(signature);
            }
            values.addAll(signatures);
            return List.copyOf(values);
        }
    }

    /**
     * Reference to a separately governed fixture or test asset.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestReference(String ref) {
        public TestReference {
            ref = normalized(ref, "");
        }
    }

    /**
     * Locked cross-library type dependency. Stage 0 rejects non-empty imports rather than resolving implicitly.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ImportReference(
            String libraryId,
            String version,
            String fingerprint
    ) {
        public ImportReference {
            libraryId = normalized(libraryId, "");
            version = normalized(version, "");
            fingerprint = normalized(fingerprint, "");
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
