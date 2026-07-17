package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.BoundaryCase;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.BoundaryKind;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.CoverageGap;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.ExpectedOutcome;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.GapCode;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.GenerationPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.Status;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates a bounded boundary-input plan and independently proves every candidate with the
 * shared visual schema validator.
 *
 * <p>The planner never equates successful synthesis with validity. A candidate is published only
 * when validator diagnostics agree with the expected outcome and, for rejected inputs, contain
 * the diagnostic family corresponding to the applied boundary. Unsupported constraint families
 * are disclosed as gaps instead of silently inflating coverage.</p>
 */
public final class TestBoundaryCasePlanner {

    /** Current deterministic algorithm generation. */
    public static final String GENERATOR_VERSION = "bloge.testBoundaryCaseGenerator.v1";
    /** Maximum number of published cases including the baseline. */
    public static final int MAX_CASES = 64;
    /** Maximum traversed property/item depth. */
    public static final int MAX_DEPTH = 8;
    /** Maximum generated collection size or string length. */
    public static final int MAX_COLLECTION_ITEMS = 32;

    private static final GenerationPolicy POLICY = new GenerationPolicy(
            GENERATOR_VERSION, MAX_CASES, MAX_DEPTH, MAX_COLLECTION_ITEMS,
            "VISUAL_SCHEMA_VALIDATOR_PROOF");
    private static final Set<String> UNEXPANDED_CONSTRAINTS = Set.of(
            "pattern", "format", "multipleOf", "uniqueItems", "contains", "minContains",
            "maxContains", "minProperties", "maxProperties", "dependentRequired",
            "dependentSchemas", "propertyNames", "patternProperties", "oneOf", "anyOf",
            "allOf", "if", "then", "else", "not", "unevaluatedProperties",
            "unevaluatedItems");

    private final ObjectMapper objectMapper;
    private final JsonSchemaSampleGenerator samples;

    /**
     * @param objectMapper canonical fingerprint mapper
     * @param samples deterministic baseline sample generator
     */
    public TestBoundaryCasePlanner(ObjectMapper objectMapper,
                                   JsonSchemaSampleGenerator samples) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.samples = Objects.requireNonNull(samples, "samples");
    }

    /**
     * Generates a plan for one exact target and projected input schema.
     *
     * @param target exact target identity and fingerprint
     * @param inputSchema supported visual input schema
     * @param initialGaps projection losses discovered before planning
     * @return immutable content-addressed plan
     */
    public TestBoundaryCasePlan plan(
            TestExecutionApiRequest.Target target,
            SchemaEnvelope inputSchema,
            List<CoverageGap> initialGaps) {
        TestExecutionApiRequest.Target safeTarget = Objects.requireNonNull(target, "target");
        SchemaEnvelope safeSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        State state = new State(safeSchema, initialGaps);
        String schemaFingerprint = ProtocolFingerprint.of(objectMapper, safeSchema);

        List<VisualDiagnostic> schemaDiagnostics = VisualSchemaValidator.validateEnvelope(
                safeSchema, "/inputSchema");
        schemaDiagnostics.stream().filter(VisualDiagnostic::error).forEach(diagnostic ->
                state.gap(GapCode.INVALID_INPUT_SCHEMA, diagnostic.target(), diagnostic.code()));
        if (schemaDiagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return result(safeTarget, schemaFingerprint, Status.UNAVAILABLE, state);
        }
        if (isOpaque(safeSchema.schema())) {
            state.gap(GapCode.OPAQUE_INPUT_SCHEMA, "/inputSchema/schema", "opaque");
            return result(safeTarget, schemaFingerprint, Status.UNAVAILABLE, state);
        }

        Object baseline = samples.generate(safeSchema);
        List<VisualDiagnostic> baselineDiagnostics = errors(safeSchema, baseline);
        if (!baselineDiagnostics.isEmpty()) {
            baselineDiagnostics.forEach(diagnostic -> state.gap(
                    GapCode.BASELINE_NOT_PROVEN, diagnostic.target(), diagnostic.code()));
            return result(safeTarget, schemaFingerprint, Status.UNAVAILABLE, state);
        }
        state.add(BoundaryKind.BASELINE, List.of(), "/inputSchema/schema",
                ExpectedOutcome.ACCEPTED, baseline, "");
        visit(safeSchema.schema(), baseline, baseline, List.of(),
                "/inputSchema/schema", 0, state);
        Status status = state.gaps.isEmpty() ? Status.GENERATED : Status.PARTIAL;
        return result(safeTarget, schemaFingerprint, status, state);
    }

    private void visit(Map<String, Object> schema,
                       Object value,
                       Object baseline,
                       List<Object> path,
                       String schemaPath,
                       int depth,
                       State state) {
        if (depth > MAX_DEPTH) {
            state.gap(GapCode.DEPTH_LIMIT_REACHED, schemaPath, "depth");
            return;
        }
        discloseUnexpanded(schema, schemaPath, state);
        String type = type(schema, value);
        addTypeMismatch(type, baseline, path, schemaPath, state);
        addEnumAndConst(schema, baseline, path, schemaPath, state);
        switch (type) {
            case "integer", "number", "decimal" ->
                    addNumericBoundaries(schema, "integer".equals(type), baseline,
                            path, schemaPath, state);
            case "string", "duration", "datetime" ->
                    addStringBoundaries(schema, baseline, path, schemaPath, state);
            case "array" -> addArrayBoundaries(schema, value, baseline, path,
                    schemaPath, depth, state);
            case "object" -> addObjectBoundaries(schema, value, baseline, path,
                    schemaPath, depth, state);
            default -> {
                // Opaque and union-only nodes are disclosed by constraint gaps above.
            }
        }
    }

    private void addObjectBoundaries(Map<String, Object> schema,
                                     Object value,
                                     Object baseline,
                                     List<Object> path,
                                     String schemaPath,
                                     int depth,
                                     State state) {
        if (!(value instanceof Map<?, ?> rawValue)) {
            return;
        }
        Map<String, Object> object = stringMap(rawValue);
        for (String required : strings(schema.get("required"))) {
            if (object.containsKey(required)) {
                state.add(BoundaryKind.REQUIRED_PROPERTY_MISSING,
                        append(path, required), schemaPath + "/required",
                        ExpectedOutcome.SCHEMA_REJECTED,
                        withoutProperty(baseline, path, required),
                        "visual.context.requiredMissing");
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            String unknown = uniquePropertyName(object);
            state.add(BoundaryKind.UNKNOWN_PROPERTY, append(path, unknown),
                    schemaPath + "/additionalProperties",
                    ExpectedOutcome.SCHEMA_REJECTED,
                    withProperty(baseline, path, unknown, "unexpected"),
                    "visual.context.unknownProperty");
        } else if (schema.get("additionalProperties") instanceof Map<?, ?>) {
            state.gap(GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                    schemaPath + "/additionalProperties", "additionalProperties");
        }
        Map<String, Object> properties = map(schema.get("properties"));
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (object.containsKey(entry.getKey())) {
                visit(map(entry.getValue()), object.get(entry.getKey()), baseline,
                        append(path, entry.getKey()),
                        schemaPath + "/properties/" + pointer(entry.getKey()), depth + 1, state);
            }
        });
    }

    private void addArrayBoundaries(Map<String, Object> schema,
                                    Object value,
                                    Object baseline,
                                    List<Object> path,
                                    String schemaPath,
                                    int depth,
                                    State state) {
        List<?> values = value instanceof List<?> list ? list : List.of();
        Map<String, Object> itemSchema = map(schema.get("items"));
        Object item = values.isEmpty() ? samples.generate(itemSchema) : values.getFirst();
        integer(schema.get("minItems")).ifPresent(minimum -> {
            if (minimum <= MAX_COLLECTION_ITEMS) {
                state.add(BoundaryKind.MIN_ITEMS, path, schemaPath + "/minItems",
                        ExpectedOutcome.ACCEPTED,
                        replace(baseline, path, repeated(item, minimum)), "");
                if (minimum > 0) {
                    state.add(BoundaryKind.BELOW_MIN_ITEMS, path, schemaPath + "/minItems",
                            ExpectedOutcome.SCHEMA_REJECTED,
                            replace(baseline, path, repeated(item, minimum - 1)),
                            "visual.context.arrayConstraintMismatch");
                }
            } else {
                state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/minItems",
                        "minItems");
            }
        });
        integer(schema.get("maxItems")).ifPresent(maximum -> {
            if (maximum < MAX_COLLECTION_ITEMS) {
                state.add(BoundaryKind.MAX_ITEMS, path, schemaPath + "/maxItems",
                        ExpectedOutcome.ACCEPTED,
                        replace(baseline, path, repeated(item, maximum)), "");
                state.add(BoundaryKind.ABOVE_MAX_ITEMS, path, schemaPath + "/maxItems",
                        ExpectedOutcome.SCHEMA_REJECTED,
                        replace(baseline, path, repeated(item, maximum + 1)),
                        "visual.context.arrayConstraintMismatch");
            } else {
                state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/maxItems",
                        "maxItems");
            }
        });
        if (!itemSchema.isEmpty() && item != null) {
            visit(itemSchema, item, baseline, append(path, 0),
                    schemaPath + "/items", depth + 1, state);
        }
    }

    private void addNumericBoundaries(Map<String, Object> schema,
                                      boolean integral,
                                      Object baseline,
                                      List<Object> path,
                                      String schemaPath,
                                      State state) {
        decimal(schema.get("minimum")).ifPresent(minimum -> {
            state.add(BoundaryKind.MINIMUM, path, schemaPath + "/minimum",
                    ExpectedOutcome.ACCEPTED,
                    replace(baseline, path, number(minimum, integral)), "");
            state.add(BoundaryKind.BELOW_MINIMUM, path, schemaPath + "/minimum",
                    ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, number(minimum.subtract(step(integral)), integral)),
                    "visual.context.numericConstraintMismatch");
        });
        decimal(schema.get("exclusiveMinimum")).ifPresent(minimum -> {
            state.add(BoundaryKind.EXCLUSIVE_MINIMUM, path,
                    schemaPath + "/exclusiveMinimum", ExpectedOutcome.ACCEPTED,
                    replace(baseline, path, number(minimum.add(step(integral)), integral)), "");
            state.add(BoundaryKind.AT_EXCLUSIVE_MINIMUM, path,
                    schemaPath + "/exclusiveMinimum", ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, number(minimum, integral)),
                    "visual.context.numericConstraintMismatch");
        });
        decimal(schema.get("maximum")).ifPresent(maximum -> {
            state.add(BoundaryKind.MAXIMUM, path, schemaPath + "/maximum",
                    ExpectedOutcome.ACCEPTED,
                    replace(baseline, path, number(maximum, integral)), "");
            state.add(BoundaryKind.ABOVE_MAXIMUM, path, schemaPath + "/maximum",
                    ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, number(maximum.add(step(integral)), integral)),
                    "visual.context.numericConstraintMismatch");
        });
        decimal(schema.get("exclusiveMaximum")).ifPresent(maximum -> {
            state.add(BoundaryKind.EXCLUSIVE_MAXIMUM, path,
                    schemaPath + "/exclusiveMaximum", ExpectedOutcome.ACCEPTED,
                    replace(baseline, path, number(maximum.subtract(step(integral)), integral)), "");
            state.add(BoundaryKind.AT_EXCLUSIVE_MAXIMUM, path,
                    schemaPath + "/exclusiveMaximum", ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, number(maximum, integral)),
                    "visual.context.numericConstraintMismatch");
        });
    }

    private void addStringBoundaries(Map<String, Object> schema,
                                     Object baseline,
                                     List<Object> path,
                                     String schemaPath,
                                     State state) {
        integer(schema.get("minLength")).ifPresent(minimum -> {
            if (minimum <= MAX_COLLECTION_ITEMS) {
                state.add(BoundaryKind.MIN_LENGTH, path, schemaPath + "/minLength",
                        ExpectedOutcome.ACCEPTED,
                        replace(baseline, path, "x".repeat(minimum)), "");
                if (minimum > 0) {
                    state.add(BoundaryKind.BELOW_MIN_LENGTH, path,
                            schemaPath + "/minLength", ExpectedOutcome.SCHEMA_REJECTED,
                            replace(baseline, path, "x".repeat(minimum - 1)),
                            "visual.context.stringConstraintMismatch");
                }
            } else {
                state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/minLength",
                        "minLength");
            }
        });
        integer(schema.get("maxLength")).ifPresent(maximum -> {
            if (maximum < MAX_COLLECTION_ITEMS) {
                state.add(BoundaryKind.MAX_LENGTH, path, schemaPath + "/maxLength",
                        ExpectedOutcome.ACCEPTED,
                        replace(baseline, path, "x".repeat(maximum)), "");
                state.add(BoundaryKind.ABOVE_MAX_LENGTH, path,
                        schemaPath + "/maxLength", ExpectedOutcome.SCHEMA_REJECTED,
                        replace(baseline, path, "x".repeat(maximum + 1)),
                        "visual.context.stringConstraintMismatch");
            } else {
                state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/maxLength",
                        "maxLength");
            }
        });
    }

    private void addEnumAndConst(Map<String, Object> schema,
                                 Object baseline,
                                 List<Object> path,
                                 String schemaPath,
                                 State state) {
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !values.isEmpty()) {
            values.stream().limit(8).forEach(value -> state.add(BoundaryKind.ENUM_MEMBER,
                    path, schemaPath + "/enum", ExpectedOutcome.ACCEPTED,
                    replace(baseline, path, value), ""));
            if (values.size() > 8) {
                state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/enum", "enum");
            }
            state.add(BoundaryKind.OUTSIDE_ENUM, path, schemaPath + "/enum",
                    ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, outside(values.getFirst())),
                    "visual.context.enumMismatch");
        }
        if (schema.containsKey("const")) {
            Object constant = schema.get("const");
            state.add(BoundaryKind.CONST_VALUE, path, schemaPath + "/const",
                    ExpectedOutcome.ACCEPTED, replace(baseline, path, constant), "");
            state.add(BoundaryKind.OUTSIDE_CONST, path, schemaPath + "/const",
                    ExpectedOutcome.SCHEMA_REJECTED,
                    replace(baseline, path, outside(constant)),
                    "visual.context.constMismatch");
        }
    }

    private void addTypeMismatch(String type,
                                 Object baseline,
                                 List<Object> path,
                                 String schemaPath,
                                 State state) {
        Object mismatch = switch (type) {
            case "object" -> List.of();
            case "array" -> Map.of();
            case "string", "duration", "datetime" -> 7;
            case "integer", "number", "decimal" -> "not-a-number";
            case "boolean" -> "not-a-boolean";
            case "null" -> "not-null";
            default -> null;
        };
        if (mismatch != null) {
            state.add(BoundaryKind.TYPE_MISMATCH, path, schemaPath + "/type",
                    ExpectedOutcome.SCHEMA_REJECTED, replace(baseline, path, mismatch),
                    "visual.context.typeMismatch");
        }
    }

    private void discloseUnexpanded(Map<String, Object> schema,
                                    String schemaPath,
                                    State state) {
        if (schema.get("type") instanceof List<?>) {
            state.gap(GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                    schemaPath + "/type", "type");
        }
        schema.keySet().stream().filter(UNEXPANDED_CONSTRAINTS::contains).sorted()
                .forEach(keyword -> state.gap(GapCode.CONSTRAINT_NOT_BOUNDARY_EXPANDED,
                        schemaPath + "/" + pointer(keyword), keyword));
    }

    private TestBoundaryCasePlan result(TestExecutionApiRequest.Target target,
                                        String schemaFingerprint,
                                        Status status,
                                        State state) {
        List<CoverageGap> gaps = state.sortedGaps();
        List<BoundaryCase> cases = List.copyOf(state.cases);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", TestBoundaryCasePlan.SCHEMA_VERSION);
        material.put("target", target);
        material.put("inputSchemaFingerprint", schemaFingerprint);
        material.put("status", status.name());
        material.put("policy", POLICY);
        material.put("cases", cases);
        material.put("gaps", gaps);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestBoundaryCasePlan("", target, schemaFingerprint, fingerprint,
                status, POLICY, cases, gaps);
    }

    private final class State {
        private final SchemaEnvelope schema;
        private final List<BoundaryCase> cases = new ArrayList<>();
        private final Set<CoverageGap> gaps = new LinkedHashSet<>();
        private boolean limitDisclosed;

        private State(SchemaEnvelope schema, List<CoverageGap> initialGaps) {
            this.schema = schema;
            if (initialGaps != null) {
                gaps.addAll(initialGaps);
            }
        }

        private void add(BoundaryKind kind,
                         List<Object> path,
                         String schemaPath,
                         ExpectedOutcome expected,
                         Object input,
                         String requiredDiagnostic) {
            if (cases.size() >= MAX_CASES) {
                if (!limitDisclosed) {
                    gap(GapCode.CASE_LIMIT_REACHED, "/inputSchema/schema", "maxCases");
                    limitDisclosed = true;
                }
                return;
            }
            List<VisualDiagnostic> diagnostics = errors(schema, input);
            boolean proven = expected == ExpectedOutcome.ACCEPTED
                    ? diagnostics.isEmpty()
                    : !diagnostics.isEmpty() && diagnostics.stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals(requiredDiagnostic));
            if (!proven) {
                gap(GapCode.CANDIDATE_NOT_PROVEN, schemaPath, kind.name());
                return;
            }
            String instancePath = jsonPointer(path);
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("kind", kind.name());
            identity.put("instancePath", instancePath);
            identity.put("schemaPath", schemaPath);
            identity.put("input", input);
            String digest = ProtocolFingerprint.of(objectMapper, identity);
            String caseId = kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                    + "-" + digest.substring("sha256:".length(), "sha256:".length() + 12);
            List<String> codes = diagnostics.stream().map(VisualDiagnostic::code)
                    .distinct().sorted().toList();
            cases.add(new BoundaryCase(caseId, kind, instancePath, schemaPath,
                    expected, input, codes));
        }

        private void gap(GapCode code, String schemaPath, String keyword) {
            gaps.add(new CoverageGap(code, schemaPath, keyword));
        }

        private List<CoverageGap> sortedGaps() {
            return gaps.stream().sorted(Comparator
                    .comparing((CoverageGap gap) -> gap.code().name())
                    .thenComparing(CoverageGap::schemaPath)
                    .thenComparing(CoverageGap::keyword)).toList();
        }
    }

    private static List<VisualDiagnostic> errors(SchemaEnvelope schema, Object value) {
        return VisualSchemaValidator.validateValue(schema, value, "/input").stream()
                .filter(VisualDiagnostic::error).toList();
    }

    private static boolean isOpaque(Map<String, Object> schema) {
        if (schema.isEmpty() || "any".equals(schema.get("type"))
                || "opaque".equals(schema.get("type"))
                || "any".equals(schema.get("kind"))
                || "opaque".equals(schema.get("kind"))) {
            return true;
        }
        return "object".equals(schema.get("type"))
                && Boolean.TRUE.equals(schema.get("additionalProperties"))
                && !schema.containsKey("properties") && !schema.containsKey("required");
    }

    private static String type(Map<String, Object> schema, Object value) {
        Object declared = schema.get("type");
        if (declared instanceof String text) {
            return text;
        }
        Object kind = schema.get("kind");
        if (kind instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) return "integer";
        if (value instanceof Number) return "number";
        return value == null ? "null" : "";
    }

    private static java.util.Optional<BigDecimal> decimal(Object value) {
        if (!(value instanceof Number)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new BigDecimal(String.valueOf(value)));
        } catch (NumberFormatException invalid) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Integer> integer(Object value) {
        if (!(value instanceof Number)) {
            return java.util.Optional.empty();
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            if (decimal.signum() >= 0 && decimal.stripTrailingZeros().scale() <= 0
                    && decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                return java.util.Optional.of(Integer.MAX_VALUE);
            }
            int result = decimal.intValueExact();
            return result < 0 ? java.util.Optional.empty() : java.util.Optional.of(result);
        } catch (ArithmeticException | NumberFormatException outsideSupportedRange) {
            return java.util.Optional.empty();
        }
    }

    private static BigDecimal step(boolean integral) {
        return integral ? BigDecimal.ONE : new BigDecimal("0.000001");
    }

    private static Number number(BigDecimal value, boolean integral) {
        if (!integral) {
            return value;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException outsideLong) {
            return value;
        }
    }

    private static Object outside(Object value) {
        if (value instanceof String text) return text + "__outside__";
        if (value instanceof BigDecimal decimal) return decimal.add(BigDecimal.ONE);
        if (value instanceof Number number) return new BigDecimal(String.valueOf(number)).add(BigDecimal.ONE);
        if (value instanceof Boolean bool) return !bool;
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.add("__outside__");
            return copy;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = stringMap(raw);
            copy.put("__outside__", true);
            return copy;
        }
        return "__outside__";
    }

    private static List<Object> repeated(Object value, int count) {
        List<Object> result = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            result.add(deepCopy(value));
        }
        return result;
    }

    private static Object replace(Object baseline, List<Object> path, Object replacement) {
        if (path.isEmpty()) {
            return deepCopy(replacement);
        }
        Object copy = deepCopy(baseline);
        Object parent = navigate(copy, path.subList(0, path.size() - 1));
        Object leaf = path.getLast();
        if (parent instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) rawMap;
            map.put(String.valueOf(leaf), deepCopy(replacement));
        } else if (parent instanceof List<?> rawList && leaf instanceof Integer index) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) rawList;
            if (index >= 0 && index < list.size()) {
                list.set(index, deepCopy(replacement));
            }
        }
        return copy;
    }

    private static Object withoutProperty(Object baseline, List<Object> objectPath, String property) {
        Object copy = deepCopy(baseline);
        Object target = navigate(copy, objectPath);
        if (target instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) rawMap;
            map.remove(property);
        }
        return copy;
    }

    private static Object withProperty(Object baseline, List<Object> objectPath,
                                       String property, Object value) {
        Object copy = deepCopy(baseline);
        Object target = navigate(copy, objectPath);
        if (target instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) rawMap;
            map.put(property, deepCopy(value));
        }
        return copy;
    }

    private static Object navigate(Object root, List<Object> path) {
        Object current = root;
        for (Object segment : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(String.valueOf(segment));
            } else if (current instanceof List<?> list && segment instanceof Integer index
                    && index >= 0 && index < list.size()) {
                current = list.get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> copy = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> copy.put(String.valueOf(key), deepCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(TestBoundaryCasePlanner::deepCopy).toList());
        }
        return value;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? stringMap(raw) : Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                map.put(String.valueOf(key), stringMap(nested));
            } else {
                map.put(String.valueOf(key), value);
            }
        });
        return map;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf).sorted().toList();
    }

    private static List<Object> append(List<Object> path, Object segment) {
        List<Object> appended = new ArrayList<>(path);
        appended.add(segment);
        return List.copyOf(appended);
    }

    private static String jsonPointer(List<Object> path) {
        if (path.isEmpty()) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        for (Object segment : path) {
            pointer.append('/').append(pointer(String.valueOf(segment)));
        }
        return pointer.toString();
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String uniquePropertyName(Map<String, Object> value) {
        String candidate = "__unexpected__";
        int suffix = 1;
        while (value.containsKey(candidate)) {
            candidate = "__unexpected__" + suffix++;
        }
        return candidate;
    }
}
