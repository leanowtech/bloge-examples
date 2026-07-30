package com.leanowtech.bloge.gateway.visual.authoring.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded multi-sample inference that emits observed facts instead of declarations.
 */
public final class SampleSchemaInferencer {

    public static final String VERSION = "1.0.0";
    public static final String REDACTION_PROFILE_VERSION = "visual-authoring-redaction-v1";
    public static final int MAXIMUM_REQUEST_BYTES = 2 * 1_048_576;
    public static final int MAXIMUM_SAMPLES = 100;
    public static final int MAXIMUM_TOTAL_NODES = 20_000;
    public static final int MAXIMUM_DEPTH = 32;
    public static final int MAXIMUM_OBJECT_FIELDS = 2_000;
    public static final int MAXIMUM_ARRAY_ITEMS = 2_000;
    public static final int MAXIMUM_STRING_LENGTH = 65_536;
    public static final int MAXIMUM_FIELD_NAME_LENGTH = 256;
    public static final int MAXIMUM_ENUM_CANDIDATES = 8;

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(password|passwd|secret|token|authorization|api[-_]?key|private[-_]?key"
                    + "|ssn|social[-_]?security|credit[-_]?card|card[-_]?number|cvv)");

    private final ObjectMapper objectMapper;

    public SampleSchemaInferencer(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public SampleInferenceResult infer(String draftId,
                                       long authoringRevision,
                                       SampleInferenceRequest request) {
        validate(request);
        String evidenceFingerprint = evidenceFingerprint(request);
        Context context = new Context(evidenceFingerprint, request.options());
        JsonNode candidate = inferNode(
                request.samples(),
                request.samples().size(),
                request.target().authoringPath(),
                "",
                context
        );
        context.observations.sort(Comparator.comparing(
                SampleInferenceResult.FieldObservation::authoringPath));
        context.confirmations.sort(Comparator
                .comparing(SampleInferenceResult.InferenceConfirmation::authoringPath)
                .thenComparing(SampleInferenceResult.InferenceConfirmation::code));
        context.diagnostics.sort(Comparator
                .comparing(AuthoringDiagnostic::authoringPath)
                .thenComparing(AuthoringDiagnostic::code));
        return new SampleInferenceResult(
                SampleInferenceResult.SCHEMA_VERSION,
                draftId,
                authoringRevision,
                request.target(),
                evidenceFingerprint,
                VERSION,
                REDACTION_PROFILE_VERSION,
                request.samples().size(),
                candidate,
                context.observations,
                context.confirmations,
                context.diagnostics,
                false
        );
    }

    private void validate(SampleInferenceRequest request) {
        if (request == null) {
            reject("RG.AUTHORING.INFERENCE_REQUEST_REQUIRED",
                    "Sample inference request is required.", 400, "/");
        }
        if (!SampleInferenceRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            reject("RG.AUTHORING.INFERENCE_SCHEMA_UNSUPPORTED",
                    "schemaVersion must be " + SampleInferenceRequest.SCHEMA_VERSION + ".",
                    400, "/schemaVersion");
        }
        if (request.target() == null) {
            reject("RG.AUTHORING.INFERENCE_TARGET_REQUIRED",
                    "An operator port target is required.", 400, "/target");
        }
        if (!"OPERATOR".equals(request.target().assetKind())) {
            reject("RG.AUTHORING.INFERENCE_TARGET_UNSUPPORTED",
                    "Sample inference currently supports OPERATOR targets only.",
                    422, "/target/assetKind");
        }
        if (request.target().assetRef().isBlank()) {
            reject("RG.AUTHORING.INFERENCE_TARGET_INVALID",
                    "target.assetRef is required.", 400, "/target/assetRef");
        }
        if (!Set.of("INPUT", "OUTPUT").contains(request.target().portDirection())) {
            reject("RG.AUTHORING.INFERENCE_TARGET_INVALID",
                    "target.portDirection must be INPUT or OUTPUT.",
                    400, "/target/portDirection");
        }
        if (request.target().portName().isBlank()) {
            reject("RG.AUTHORING.INFERENCE_TARGET_INVALID",
                    "target.portName is required.", 400, "/target/portName");
        }
        if (request.samples().isEmpty()) {
            reject("RG.AUTHORING.INFERENCE_SAMPLES_REQUIRED",
                    "At least one JSON sample is required.", 400, "/samples");
        }
        if (request.samples().size() > MAXIMUM_SAMPLES) {
            reject("RG.AUTHORING.INFERENCE_SAMPLE_LIMIT_EXCEEDED",
                    "Sample count exceeds the %d item limit.".formatted(MAXIMUM_SAMPLES),
                    413, "/samples");
        }
        if (!IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
            reject("RG.AUTHORING.INFERENCE_IDEMPOTENCY_KEY_INVALID",
                    "idempotencyKey must be 1-128 safe identifier characters.",
                    400, "/idempotencyKey");
        }
        if (request.options() == null) {
            reject("RG.AUTHORING.INFERENCE_OPTIONS_REQUIRED",
                    "Sample inference options are required.", 400, "/options");
        }
        if (request.options().suggestEnums() == null
                || request.options().suggestFormats() == null
                || request.options().persistPayload() == null) {
            reject("RG.AUTHORING.INFERENCE_OPTIONS_INVALID",
                    "suggestEnums, suggestFormats, and persistPayload are required.",
                    400, "/options");
        }
        if (request.options().payloadPersistenceRequested()) {
            reject("RG.AUTHORING.INFERENCE_PAYLOAD_PERSISTENCE_UNSUPPORTED",
                    "Raw sample payload persistence is unavailable; save a governed fixture separately.",
                    422, "/options/persistPayload");
        }
        validateEncodedSize(request);
        validateTreeBounds(request.samples());
    }

    private void validateEncodedSize(SampleInferenceRequest request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length > MAXIMUM_REQUEST_BYTES) {
                reject("RG.AUTHORING.INFERENCE_REQUEST_LIMIT_EXCEEDED",
                        "Sample inference request exceeds the %d byte limit."
                                .formatted(MAXIMUM_REQUEST_BYTES),
                        413, "/samples");
            }
        } catch (JsonProcessingException exception) {
            reject("RG.AUTHORING.INFERENCE_REQUEST_INVALID",
                    "Sample inference request cannot be encoded.", 400, "/");
        }
    }

    private static void validateTreeBounds(List<JsonNode> samples) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        samples.forEach(sample -> pending.addLast(new NodeDepth(sample, 0)));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeFirst();
            nodes += 1;
            if (nodes > MAXIMUM_TOTAL_NODES) {
                reject("RG.AUTHORING.INFERENCE_NODE_LIMIT_EXCEEDED",
                        "Sample tree exceeds the %d node limit.".formatted(MAXIMUM_TOTAL_NODES),
                        413, "/samples");
            }
            if (current.depth() > MAXIMUM_DEPTH) {
                reject("RG.AUTHORING.INFERENCE_DEPTH_LIMIT_EXCEEDED",
                        "Sample tree exceeds the %d level depth limit.".formatted(MAXIMUM_DEPTH),
                        413, "/samples");
            }
            JsonNode node = current.node();
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isTextual() && node.textValue().length() > MAXIMUM_STRING_LENGTH) {
                reject("RG.AUTHORING.INFERENCE_STRING_LIMIT_EXCEEDED",
                        "A sample string exceeds the %d character limit."
                                .formatted(MAXIMUM_STRING_LENGTH),
                        413, "/samples");
            }
            if (node.isObject()) {
                if (node.size() > MAXIMUM_OBJECT_FIELDS) {
                    reject("RG.AUTHORING.INFERENCE_FIELD_LIMIT_EXCEEDED",
                            "A sample object exceeds the %d field limit."
                                    .formatted(MAXIMUM_OBJECT_FIELDS),
                            413, "/samples");
                }
            node.properties().forEach(entry -> {
                    if (entry.getKey().length() > MAXIMUM_FIELD_NAME_LENGTH) {
                        reject("RG.AUTHORING.INFERENCE_FIELD_NAME_LIMIT_EXCEEDED",
                                "A sample field name exceeds the %d character limit."
                                        .formatted(MAXIMUM_FIELD_NAME_LENGTH),
                                413, "/samples");
                    }
                    pending.addLast(new NodeDepth(entry.getValue(), current.depth() + 1));
                });
            } else if (node.isArray()) {
                if (node.size() > MAXIMUM_ARRAY_ITEMS) {
                    reject("RG.AUTHORING.INFERENCE_ARRAY_LIMIT_EXCEEDED",
                            "A sample array exceeds the %d item limit."
                                    .formatted(MAXIMUM_ARRAY_ITEMS),
                            413, "/samples");
                }
                node.forEach(child ->
                        pending.addLast(new NodeDepth(child, current.depth() + 1)));
            }
        }
    }

    private String evidenceFingerprint(SampleInferenceRequest request) {
        List<String> sampleFingerprints = request.samples().stream()
                .map(this::canonicalValue)
                .map(value -> VisualBundleFingerprint.fromCanonicalValue(
                        objectMapper, value, MAXIMUM_REQUEST_BYTES))
                .sorted()
                .toList();
        return VisualBundleFingerprint.fromCanonicalValue(
                objectMapper,
                Map.of(
                        "redactionProfileVersion", REDACTION_PROFILE_VERSION,
                        "inferencerVersion", VERSION,
                        "target", request.target(),
                        "sampleFingerprints", sampleFingerprints,
                        "options", request.options()
                ),
                MAXIMUM_REQUEST_BYTES
        );
    }

    private Object canonicalValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> value = new TreeMap<>();
            node.properties().forEach(entry ->
                    value.put(entry.getKey(), canonicalValue(entry.getValue())));
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            node.forEach(child -> value.add(canonicalValue(child)));
            return value;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        return node.asText();
    }

    private JsonNode inferNode(List<JsonNode> presentValues,
                               int populationCount,
                               String authoringPath,
                               String fieldName,
                               Context context) {
        int presenceCount = presentValues.size();
        int nullCount = (int) presentValues.stream()
                .filter(value -> value == null || value.isNull())
                .count();
        List<JsonNode> nonNull = presentValues.stream()
                .filter(value -> value != null && !value.isNull())
                .toList();
        EnumSet<ValueKind> kinds = EnumSet.noneOf(ValueKind.class);
        nonNull.forEach(value -> kinds.add(kind(value)));
        List<String> widenReasons = new ArrayList<>();
        String suggestedType = suggestedType(kinds, widenReasons);
        boolean sensitive = SENSITIVE_FIELD.matcher(fieldName).find();
        boolean requiredCandidate = presenceCount == populationCount && nullCount == 0;
        boolean nullableCandidate = nullCount > 0 && !nonNull.isEmpty();
        List<String> conflictTypes = conflictTypes(kinds);
        String formatCandidate = inferFormat(
                suggestedType, nonNull, context.options.formatsEnabled());
        List<String> enumCandidates = inferEnum(
                suggestedType, nonNull, sensitive, context.options.enumsEnabled());
        int distinctCount = distinctCount(nonNull);
        String factId = fingerprint(Map.of(
                "evidenceFingerprint", context.evidenceFingerprint,
                "authoringPath", authoringPath,
                "suggestedType", suggestedType
        ));

        context.observations.add(new SampleInferenceResult.FieldObservation(
                factId,
                authoringPath,
                "OBSERVED",
                suggestedType,
                populationCount,
                presenceCount,
                nullCount,
                distinctCount,
                sensitive,
                requiredCandidate,
                nullableCandidate,
                formatCandidate,
                enumCandidates,
                conflictTypes,
                widenReasons
        ));
        addConfirmations(
                factId,
                authoringPath,
                suggestedType,
                requiredCandidate,
                nullableCandidate,
                formatCandidate,
                enumCandidates,
                conflictTypes,
                sensitive,
                kinds.contains(ValueKind.OBJECT),
                context
        );

        if (sensitive) {
            context.diagnostics.add(AuthoringDiagnostic.compiler(
                    "WARNING",
                    "RG.AUTHORING.INFERENCE_SENSITIVE_FIELD",
                    "Sensitive-looking field was inferred without retaining or echoing its values.",
                    authoringPath,
                    -1,
                    Map.of("redactionProfileVersion", REDACTION_PROFILE_VERSION)
            ));
        }
        if (!conflictTypes.isEmpty()) {
            context.diagnostics.add(AuthoringDiagnostic.compiler(
                    "WARNING",
                    "RG.AUTHORING.INFERENCE_TYPE_CONFLICT",
                    "Samples contain incompatible value kinds; the safe candidate is unknown.",
                    authoringPath,
                    -1,
                    Map.of("conflictTypes", conflictTypes)
            ));
        }

        if ("object".equals(suggestedType) && conflictTypes.isEmpty()) {
            return inferObject(nonNull, authoringPath, context);
        }
        if ("array".equals(suggestedType) && conflictTypes.isEmpty()) {
            return inferArray(nonNull, authoringPath, context);
        }
        String compact = suggestedType;
        if (!formatCandidate.isBlank()) {
            compact = "string";
        }
        if (nullableCandidate && supportsNullableCompact(compact)) {
            compact += "?";
        }
        return JsonNodeFactory.instance.textNode(compact);
    }

    private JsonNode inferObject(List<JsonNode> values,
                                 String authoringPath,
                                 Context context) {
        List<JsonNode> objects = values.stream().filter(JsonNode::isObject).toList();
        Set<String> fieldNames = new java.util.TreeSet<>();
        objects.forEach(object -> object.fieldNames().forEachRemaining(fieldNames::add));
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        for (String name : fieldNames) {
            List<JsonNode> present = objects.stream()
                    .filter(object -> object.has(name))
                    .map(object -> object.get(name))
                    .toList();
            boolean requiredCandidate = present.size() == objects.size()
                    && present.stream().noneMatch(value -> value == null || value.isNull());
            String candidateName = requiredCandidate ? name : name + "?";
            fields.set(candidateName, inferNode(
                    present,
                    objects.size(),
                    authoringPath + "/fields/" + pointer(candidateName),
                    name,
                    context
            ));
        }
        ObjectNode candidate = JsonNodeFactory.instance.objectNode();
        candidate.set("fields", fields);
        candidate.put("additionalProperties", true);
        return candidate;
    }

    private JsonNode inferArray(List<JsonNode> values,
                                String authoringPath,
                                Context context) {
        List<JsonNode> elements = new ArrayList<>();
        values.stream().filter(JsonNode::isArray).forEach(array -> array.forEach(elements::add));
        if (elements.isEmpty()) {
            context.diagnostics.add(AuthoringDiagnostic.warning(
                    "RG.AUTHORING.INFERENCE_EMPTY_ARRAY",
                    "Array item type is unknown because every observed array is empty.",
                    authoringPath
            ));
            return JsonNodeFactory.instance.textNode("unknown[]");
        }
        JsonNode elementCandidate = inferNode(
                elements,
                elements.size(),
                authoringPath + "/items",
                "items",
                context
        );
        if (elementCandidate.isTextual()) {
            String itemType = elementCandidate.asText();
            if (!itemType.endsWith("?") && !"unknown".equals(itemType)) {
                return JsonNodeFactory.instance.textNode(itemType + "[]");
            }
        }
        context.diagnostics.add(AuthoringDiagnostic.warning(
                "RG.AUTHORING.INFERENCE_COMPLEX_ARRAY",
                "Complex or nullable array items remain open JSON until a named type is declared.",
                authoringPath
        ));
        return JsonNodeFactory.instance.textNode("json[]");
    }

    private void addConfirmations(String factId,
                                  String authoringPath,
                                  String suggestedType,
                                  boolean requiredCandidate,
                                  boolean nullableCandidate,
                                  String formatCandidate,
                                  List<String> enumCandidates,
                                  List<String> conflictTypes,
                                  boolean sensitive,
                                  boolean object,
                                  Context context) {
        context.confirmations.add(confirmation(
                factId,
                "RG.AUTHORING.INFERENCE_PRESENCE_CONFIRMATION_REQUIRED",
                authoringPath,
                requiredCandidate
                        ? "Is this field required for all valid business inputs?"
                        : "May this field be absent in valid business inputs?",
                requiredCandidate ? "REQUIRED" : "OPTIONAL",
                List.of("REQUIRED", "OPTIONAL"),
                false
        ));
        if (nullableCandidate) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_NULLABILITY_CONFIRMATION_REQUIRED",
                    authoringPath,
                    "May this field explicitly contain null?",
                    "NULLABLE",
                    List.of("NULLABLE", "NON_NULL"),
                    false
            ));
        }
        if (!formatCandidate.isBlank()) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_FORMAT_CONFIRMATION_REQUIRED",
                    authoringPath,
                    "Should this string be constrained to the observed %s format?"
                            .formatted(formatCandidate),
                    formatCandidate.toUpperCase(Locale.ROOT),
                    List.of("STRING", formatCandidate.toUpperCase(Locale.ROOT)),
                    false
            ));
        }
        if (!enumCandidates.isEmpty()) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED",
                    authoringPath,
                    "Do the observed values form a complete business enum?",
                    "KEEP_STRING",
                    List.of("KEEP_STRING", "DECLARE_ENUM"),
                    false
            ));
        }
        if (object) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED",
                    authoringPath,
                    "Can valid payloads contain fields not present in these samples?",
                    "OPEN",
                    List.of("OPEN", "CLOSED"),
                    false
            ));
        }
        if (!conflictTypes.isEmpty()) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_TYPE_CONFLICT_CONFIRMATION_REQUIRED",
                    authoringPath,
                    "Samples disagree on type. Review the source or keep this field unknown.",
                    "REVIEW_SAMPLES",
                    List.of("REVIEW_SAMPLES", "KEEP_UNKNOWN"),
                    true
            ));
        }
        if (sensitive) {
            context.confirmations.add(confirmation(
                    factId,
                    "RG.AUTHORING.INFERENCE_SENSITIVE_HANDLING_REQUIRED",
                    authoringPath,
                    "Confirm the contract without persisting example values for this sensitive field.",
                    "DECLARE_TYPE_ONLY",
                    List.of("DECLARE_TYPE_ONLY", "REMOVE_FIELD"),
                    true
            ));
        }
    }

    private SampleInferenceResult.InferenceConfirmation confirmation(
            String factId,
            String code,
            String path,
            String question,
            String recommended,
            List<String> allowed,
            boolean blocking) {
        return new SampleInferenceResult.InferenceConfirmation(
                fingerprint(Map.of(
                        "factId", factId,
                        "code", code,
                        "recommended", recommended
                )),
                factId,
                code,
                path,
                question,
                recommended,
                allowed,
                blocking
        );
    }

    private String fingerprint(Map<String, ?> value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, value, MAXIMUM_REQUEST_BYTES);
    }

    private static String suggestedType(Set<ValueKind> kinds,
                                        List<String> widenReasons) {
        if (kinds.isEmpty()) {
            return "unknown";
        }
        if (kinds.equals(EnumSet.of(ValueKind.INTEGER, ValueKind.NUMBER))) {
            widenReasons.add("integer widened to number");
            return "number";
        }
        if (kinds.size() > 1) {
            return "unknown";
        }
        return switch (kinds.iterator().next()) {
            case OBJECT -> "object";
            case ARRAY -> "array";
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
        };
    }

    private static List<String> conflictTypes(Set<ValueKind> kinds) {
        if (kinds.size() <= 1
                || kinds.equals(EnumSet.of(ValueKind.INTEGER, ValueKind.NUMBER))) {
            return List.of();
        }
        return kinds.stream().map(kind -> kind.name().toLowerCase(Locale.ROOT)).sorted().toList();
    }

    private int distinctCount(List<JsonNode> values) {
        Set<String> distinct = new LinkedHashSet<>();
        values.forEach(value -> distinct.add(VisualBundleFingerprint.fromCanonicalValue(
                objectMapper,
                canonicalValue(value),
                MAXIMUM_REQUEST_BYTES
        )));
        return distinct.size();
    }

    private static List<String> inferEnum(String suggestedType,
                                          List<JsonNode> values,
                                          boolean sensitive,
                                          boolean enabled) {
        if (!enabled || sensitive || !"string".equals(suggestedType) || values.size() < 3) {
            return List.of();
        }
        Set<String> distinct = new java.util.TreeSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().length() > 128) {
                return List.of();
            }
            distinct.add(value.textValue());
            if (distinct.size() > MAXIMUM_ENUM_CANDIDATES) {
                return List.of();
            }
        }
        return distinct.size() < 2 ? List.of() : List.copyOf(distinct);
    }

    private static String inferFormat(String suggestedType,
                                      List<JsonNode> values,
                                      boolean enabled) {
        if (!enabled || !"string".equals(suggestedType) || values.isEmpty()) {
            return "";
        }
        List<String> strings = values.stream()
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .toList();
        if (strings.size() != values.size()) {
            return "";
        }
        if (strings.stream().allMatch(SampleSchemaInferencer::date)) {
            return "date";
        }
        if (strings.stream().allMatch(SampleSchemaInferencer::dateTime)) {
            return "datetime";
        }
        return "";
    }

    private static boolean date(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean dateTime(String value) {
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            try {
                Instant.parse(value);
                return true;
            } catch (DateTimeParseException alsoIgnored) {
                return false;
            }
        }
    }

    private static boolean supportsNullableCompact(String value) {
        return !Set.of("unknown", "array", "object").contains(value);
    }

    private static ValueKind kind(JsonNode node) {
        if (node.isObject()) {
            return ValueKind.OBJECT;
        }
        if (node.isArray()) {
            return ValueKind.ARRAY;
        }
        if (node.isTextual()) {
            return ValueKind.STRING;
        }
        if (node.isIntegralNumber()) {
            return ValueKind.INTEGER;
        }
        if (node.isNumber()) {
            return ValueKind.NUMBER;
        }
        if (node.isBoolean()) {
            return ValueKind.BOOLEAN;
        }
        return ValueKind.STRING;
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void reject(String code,
                               String message,
                               int status,
                               String path) {
        throw new SampleInferenceRejectedException(code, message, status, path);
    }

    private enum ValueKind {
        OBJECT,
        ARRAY,
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN
    }

    private record NodeDepth(JsonNode node, int depth) {
    }

    private static final class Context {
        private final String evidenceFingerprint;
        private final SampleInferenceRequest.Options options;
        private final List<SampleInferenceResult.FieldObservation> observations =
                new ArrayList<>();
        private final List<SampleInferenceResult.InferenceConfirmation> confirmations =
                new ArrayList<>();
        private final List<AuthoringDiagnostic> diagnostics = new ArrayList<>();

        private Context(String evidenceFingerprint,
                        SampleInferenceRequest.Options options) {
            this.evidenceFingerprint = evidenceFingerprint;
            this.options = options;
        }
    }
}
