package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.CoverageGap;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.GapCode;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.GenerationPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.PropertyTrial;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.ShrinkCandidate;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Deterministic bounded property-input generator and validator-proven shrink planner.
 *
 * <p>The algorithm never trusts its own construction rules. Every root and shrink input is checked
 * by the shared visual schema validator before publication. The caller-selected seed, all resource
 * bounds, generated values, and disclosed gaps enter the content address.</p>
 */
public final class TestPropertyCasePlanner {
    /** Stable algorithm generation bound into plans and later suite evidence. */
    public static final String GENERATOR_VERSION = "property-cases-v1";
    /** Maximum unique root trials in one plan. */
    public static final int MAX_TRIALS = 16;
    /** Maximum precomputed shrink steps for one root trial. */
    public static final int MAX_SHRINK_STEPS = 5;
    /** Maximum root plus shrink cases in one plan. */
    public static final int MAX_CASES = MAX_TRIALS * (MAX_SHRINK_STEPS + 1);
    /** Maximum candidate attempts made for each unique root trial. */
    public static final int MAX_GENERATION_ATTEMPTS = 32;
    /** Maximum recursive schema depth. */
    public static final int MAX_DEPTH = 8;
    /** Maximum generated string or collection size. */
    public static final int MAX_COLLECTION_ITEMS = 32;
    /** Exact shared-validator proof mode. */
    public static final String VERIFICATION_MODE = "VISUAL_SCHEMA_VALIDATOR_PROOF";

    private static final int MAX_SIMPLIFICATIONS = 128;
    private static final Set<String> UNGENERATED_CONSTRAINTS = Set.of(
            "pattern", "format", "multipleOf", "uniqueItems", "contains", "minContains",
            "maxContains", "minProperties", "maxProperties", "dependentRequired",
            "dependentSchemas", "propertyNames", "patternProperties", "oneOf", "anyOf",
            "allOf", "if", "then", "else", "not", "unevaluatedProperties",
            "unevaluatedItems");

    private final ObjectMapper objectMapper;
    private final JsonSchemaSampleGenerator samples;

    /**
     * @param objectMapper canonical fingerprint mapper
     * @param samples deterministic last-resort valid-sample generator
     */
    public TestPropertyCasePlanner(ObjectMapper objectMapper,
                                   JsonSchemaSampleGenerator samples) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.samples = Objects.requireNonNull(samples, "samples");
    }

    /**
     * Generates one reproducible bounded property plan.
     *
     * @param target exact target identity
     * @param inputSchema projected target input schema
     * @param seed caller-selected reproducibility seed
     * @param trialCount requested unique root trials, between 1 and 16
     * @param maxShrinkSteps maximum linear shrink steps per root, between 0 and 5
     * @param initialGaps projection limitations discovered before planning
     * @return content-addressed validator-proven property plan
     */
    public TestPropertyCasePlan plan(
            TestExecutionApiRequest.Target target,
            SchemaEnvelope inputSchema,
            long seed,
            int trialCount,
            int maxShrinkSteps,
            List<CoverageGap> initialGaps) {
        if (trialCount < 1 || trialCount > MAX_TRIALS) {
            throw new IllegalArgumentException("trialCount must be between 1 and " + MAX_TRIALS);
        }
        if (maxShrinkSteps < 0 || maxShrinkSteps > MAX_SHRINK_STEPS) {
            throw new IllegalArgumentException(
                    "maxShrinkSteps must be between 0 and " + MAX_SHRINK_STEPS);
        }
        TestExecutionApiRequest.Target safeTarget = Objects.requireNonNull(target, "target");
        SchemaEnvelope safeSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        GenerationPolicy policy = new GenerationPolicy(GENERATOR_VERSION, seed, trialCount,
                maxShrinkSteps, trialCount * (maxShrinkSteps + 1),
                MAX_GENERATION_ATTEMPTS, MAX_DEPTH, MAX_COLLECTION_ITEMS, VERIFICATION_MODE);
        State state = new State(safeSchema, initialGaps);
        String schemaFingerprint = ProtocolFingerprint.of(objectMapper, safeSchema);

        List<VisualDiagnostic> schemaDiagnostics = VisualSchemaValidator.validateEnvelope(
                safeSchema, "/inputSchema");
        schemaDiagnostics.stream().filter(VisualDiagnostic::error).forEach(diagnostic ->
                state.gap(GapCode.INVALID_INPUT_SCHEMA, diagnostic.target(), diagnostic.code()));
        if (schemaDiagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return result(safeTarget, schemaFingerprint, TestPropertyCasePlan.Status.UNAVAILABLE,
                    policy, state);
        }
        if (!domainConsistent(safeSchema.schema(), "/inputSchema/schema", state)) {
            return result(safeTarget, schemaFingerprint, TestPropertyCasePlan.Status.UNAVAILABLE,
                    policy, state);
        }
        if (isOpaque(safeSchema.schema())) {
            state.gap(GapCode.OPAQUE_INPUT_SCHEMA, "/inputSchema/schema", "opaque");
            return result(safeTarget, schemaFingerprint, TestPropertyCasePlan.Status.UNAVAILABLE,
                    policy, state);
        }
        discloseUngenerated(safeSchema.schema(), "/inputSchema/schema", 0, state);

        SplittableRandom random = new SplittableRandom(seed);
        Set<String> rootFingerprints = new LinkedHashSet<>();
        for (int trialIndex = 0; trialIndex < trialCount; trialIndex++) {
            Candidate root = uniqueRoot(safeSchema, random.split(), rootFingerprints, state);
            if (root == null) {
                state.gap(GapCode.UNIQUE_TRIAL_LIMIT_REACHED, "/inputSchema/schema",
                        "requestedTrials");
                break;
            }
            rootFingerprints.add(root.fingerprint());
            String trialId = "property-%03d".formatted(trialIndex + 1);
            List<ShrinkCandidate> shrinkPath = shrink(safeSchema, trialId, root,
                    maxShrinkSteps, state);
            state.trials.add(new PropertyTrial(trialId, root.input(), root.fingerprint(),
                    root.complexity(), shrinkPath));
        }

        TestPropertyCasePlan.Status status;
        if (state.trials.isEmpty()) {
            state.gap(GapCode.CANDIDATE_NOT_PROVEN, "/inputSchema/schema", "rootTrial");
            status = TestPropertyCasePlan.Status.UNAVAILABLE;
        } else if (state.trials.size() == trialCount && state.gaps.isEmpty()) {
            status = TestPropertyCasePlan.Status.GENERATED;
        } else {
            status = TestPropertyCasePlan.Status.PARTIAL;
        }
        return result(safeTarget, schemaFingerprint, status, policy, state);
    }

    private Candidate uniqueRoot(SchemaEnvelope schema,
                                 SplittableRandom random,
                                 Set<String> existing,
                                 State state) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            Object input;
            try {
                input = generate(map(schema.schema()), random.split(), 0,
                        "/inputSchema/schema", state);
            } catch (RuntimeException unsupported) {
                continue;
            }
            Candidate candidate = proven(schema, input);
            if (candidate != null && !existing.contains(candidate.fingerprint())) {
                return candidate;
            }
        }
        try {
            Candidate fallback = proven(schema, samples.generate(schema));
            return fallback != null && !existing.contains(fallback.fingerprint()) ? fallback : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private List<ShrinkCandidate> shrink(SchemaEnvelope schema,
                                         String trialId,
                                         Candidate root,
                                         int maximum,
                                         State state) {
        List<ShrinkCandidate> result = new ArrayList<>();
        Candidate current = root;
        String parent = trialId;
        Set<String> seen = new LinkedHashSet<>();
        seen.add(root.fingerprint());
        for (int step = 1; step <= maximum; step++) {
            List<Candidate> candidates = new ArrayList<>();
            for (Object value : simplifications(map(schema.schema()), current.input(),
                    0, "/inputSchema/schema", state)) {
                Candidate candidate = proven(schema, value);
                if (candidate != null && candidate.complexity() < current.complexity()
                        && seen.add(candidate.fingerprint())) {
                    candidates.add(candidate);
                }
            }
            Candidate next = candidates.stream()
                    .min(Comparator.comparingInt(Candidate::complexity)
                            .thenComparing(Candidate::fingerprint))
                    .orElse(null);
            if (next == null) {
                break;
            }
            String caseId = trialId + "-shrink-%03d".formatted(step);
            result.add(new ShrinkCandidate(caseId, parent, step, next.input(),
                    next.fingerprint(), next.complexity()));
            current = next;
            parent = caseId;
        }
        return List.copyOf(result);
    }

    private Candidate proven(SchemaEnvelope schema, Object input) {
        List<VisualDiagnostic> diagnostics = VisualSchemaValidator.validateValue(
                schema, input, "/input");
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return null;
        }
        Object frozen = deepCopy(input);
        return new Candidate(frozen, ProtocolFingerprint.of(objectMapper, frozen),
                complexity(frozen));
    }

    private Object generate(Map<String, Object> schema,
                            SplittableRandom random,
                            int depth,
                            String schemaPath,
                            State state) {
        if (depth > MAX_DEPTH) {
            state.gap(GapCode.DEPTH_LIMIT_REACHED, schemaPath, "depth");
            return null;
        }
        if (schema.containsKey("const")) {
            return deepCopy(schema.get("const"));
        }
        if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
            return deepCopy(values.get(random.nextInt(values.size())));
        }
        String type = generatedType(schema, random, schemaPath, state);
        return switch (type) {
            case "object" -> generateObject(schema, random, depth, schemaPath, state);
            case "array" -> generateArray(schema, random, depth, schemaPath, state);
            case "integer" -> generateInteger(schema, random);
            case "number", "decimal" -> generateDecimal(schema, random);
            case "string", "duration", "datetime" -> generateString(schema, random, schemaPath, state);
            case "boolean" -> random.nextBoolean();
            case "null" -> null;
            default -> random.nextBoolean() ? random.nextInt(-1000, 1001) : randomString(random, 8);
        };
    }

    private Map<String, Object> generateObject(Map<String, Object> schema,
                                               SplittableRandom random,
                                               int depth,
                                               String schemaPath,
                                               State state) {
        Set<String> required = new LinkedHashSet<>(strings(schema.get("required")));
        Map<String, Object> result = new LinkedHashMap<>();
        map(schema.get("properties")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (required.contains(entry.getKey()) || random.nextBoolean()) {
                        result.put(entry.getKey(), generate(map(entry.getValue()), random.split(),
                                depth + 1, schemaPath + "/properties/" + pointer(entry.getKey()), state));
                    }
                });
        return result;
    }

    private List<Object> generateArray(Map<String, Object> schema,
                                       SplittableRandom random,
                                       int depth,
                                       String schemaPath,
                                       State state) {
        int minimum = boundedInteger(schema.get("minItems"), 0);
        int configuredMaximum = boundedInteger(schema.get("maxItems"), minimum + 4);
        if (minimum > MAX_COLLECTION_ITEMS) {
            state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/minItems", "minItems");
            throw new IllegalArgumentException("minItems exceeds generation limit");
        }
        int maximum = Math.min(MAX_COLLECTION_ITEMS, Math.max(minimum, configuredMaximum));
        if (configuredMaximum > MAX_COLLECTION_ITEMS) {
            state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/maxItems", "maxItems");
        }
        int size = minimum == maximum ? minimum : random.nextInt(minimum, maximum + 1);
        Map<String, Object> itemSchema = map(schema.get("items"));
        List<Object> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(generate(itemSchema, random.split(), depth + 1,
                    schemaPath + "/items", state));
        }
        return result;
    }

    private Object generateInteger(Map<String, Object> schema, SplittableRandom random) {
        long minimum = integerLower(schema).orElse(-1_000_000L);
        long maximum = integerUpper(schema).orElse(1_000_000L);
        if (minimum > maximum) {
            throw new IllegalArgumentException("Empty integer domain");
        }
        long span = maximum - minimum;
        if (span >= 0 && span < Integer.MAX_VALUE) {
            return minimum + random.nextLong(span + 1);
        }
        long candidate = random.nextLong(-1_000_000L, 1_000_001L);
        return Math.max(minimum, Math.min(maximum, candidate));
    }

    private Object generateDecimal(Map<String, Object> schema, SplittableRandom random) {
        BigDecimal minimum = decimalLower(schema).orElse(BigDecimal.valueOf(-1_000_000));
        BigDecimal maximum = decimalUpper(schema).orElse(BigDecimal.valueOf(1_000_000));
        if (minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Empty decimal domain");
        }
        BigDecimal ratio = BigDecimal.valueOf(random.nextLong(0, 1_000_001), 6);
        BigDecimal value = minimum.add(maximum.subtract(minimum).multiply(ratio));
        return value.setScale(Math.min(6, Math.max(0, value.scale())), RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private String generateString(Map<String, Object> schema,
                                  SplittableRandom random,
                                  String schemaPath,
                                  State state) {
        int minimum = boundedInteger(schema.get("minLength"), 0);
        int configuredMaximum = boundedInteger(schema.get("maxLength"), Math.max(minimum, 16));
        if (minimum > MAX_COLLECTION_ITEMS) {
            state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/minLength", "minLength");
            throw new IllegalArgumentException("minLength exceeds generation limit");
        }
        int maximum = Math.min(MAX_COLLECTION_ITEMS, Math.max(minimum, configuredMaximum));
        if (configuredMaximum > MAX_COLLECTION_ITEMS) {
            state.gap(GapCode.COLLECTION_LIMIT_REACHED, schemaPath + "/maxLength", "maxLength");
        }
        int length = minimum == maximum ? minimum : random.nextInt(minimum, maximum + 1);
        return randomString(random, length);
    }

    private List<Object> simplifications(Map<String, Object> schema,
                                         Object value,
                                         int depth,
                                         String schemaPath,
                                         State state) {
        if (depth > MAX_DEPTH) {
            state.gap(GapCode.DEPTH_LIMIT_REACHED, schemaPath, "shrinkDepth");
            return List.of();
        }
        List<Object> result = new ArrayList<>();
        if (schema.containsKey("const")) {
            add(result, schema.get("const"));
            return bounded(result);
        }
        if (schema.get("enum") instanceof List<?> values) {
            values.forEach(candidate -> add(result, candidate));
        }
        String type = simpleType(schema, value);
        switch (type) {
            case "object" -> simplifyObject(schema, value, depth, schemaPath, state, result);
            case "array" -> simplifyArray(schema, value, depth, schemaPath, state, result);
            case "integer", "number", "decimal" -> simplifyNumber(schema, value, result);
            case "string", "duration", "datetime" -> simplifyString(schema, value, result);
            case "boolean" -> add(result, false);
            default -> {
                // No safe generic simplification exists for this node.
            }
        }
        return bounded(result);
    }

    private void simplifyObject(Map<String, Object> schema,
                                Object value,
                                int depth,
                                String schemaPath,
                                State state,
                                List<Object> result) {
        if (!(value instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> object = stringMap(raw);
        Set<String> required = new LinkedHashSet<>(strings(schema.get("required")));
        object.keySet().stream().filter(key -> !required.contains(key)).sorted().forEach(key -> {
            Map<String, Object> copy = new LinkedHashMap<>(object);
            copy.remove(key);
            add(result, copy);
        });
        Map<String, Object> properties = map(schema.get("properties"));
        object.keySet().stream().sorted().forEach(key -> {
            if (!properties.containsKey(key)) {
                return;
            }
            List<Object> nested = simplifications(map(properties.get(key)), object.get(key),
                    depth + 1, schemaPath + "/properties/" + pointer(key), state);
            nested.stream().limit(8).forEach(candidate -> {
                Map<String, Object> copy = new LinkedHashMap<>(object);
                copy.put(key, candidate);
                add(result, copy);
            });
        });
    }

    private void simplifyArray(Map<String, Object> schema,
                               Object value,
                               int depth,
                               String schemaPath,
                               State state,
                               List<Object> result) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        int minimum = boundedInteger(schema.get("minItems"), 0);
        if (list.size() > minimum) {
            add(result, new ArrayList<>(list.subList(0, minimum)));
            add(result, new ArrayList<>(list.subList(0, list.size() - 1)));
        }
        Map<String, Object> itemSchema = map(schema.get("items"));
        for (int index = 0; index < list.size(); index++) {
            int itemIndex = index;
            simplifications(itemSchema, list.get(index), depth + 1,
                    schemaPath + "/items", state).stream().limit(4).forEach(candidate -> {
                List<Object> copy = new ArrayList<>(list);
                copy.set(itemIndex, candidate);
                add(result, copy);
            });
        }
    }

    private static void simplifyNumber(Map<String, Object> schema,
                                       Object value,
                                       List<Object> result) {
        BigDecimal current = decimal(value).orElse(null);
        if (current == null) {
            return;
        }
        boolean integral = "integer".equals(simpleType(schema, value));
        BigDecimal minimum = integral
                ? integerLower(schema).map(BigDecimal::valueOf).orElse(null)
                : decimalLower(schema).orElse(null);
        BigDecimal maximum = integral
                ? integerUpper(schema).map(BigDecimal::valueOf).orElse(null)
                : decimalUpper(schema).orElse(null);
        BigDecimal zero = BigDecimal.ZERO;
        if (minimum != null && zero.compareTo(minimum) < 0) {
            zero = minimum;
        }
        if (maximum != null && zero.compareTo(maximum) > 0) {
            zero = maximum;
        }
        add(result, number(zero, integral));
        add(result, number(current.divide(BigDecimal.valueOf(2), 6, RoundingMode.DOWN),
                integral));
        if (minimum != null) {
            add(result, number(minimum, integral));
        }
        if (maximum != null) {
            add(result, number(maximum, integral));
        }
    }

    private static void simplifyString(Map<String, Object> schema,
                                       Object value,
                                       List<Object> result) {
        if (!(value instanceof String text)) {
            return;
        }
        int minimum = boundedInteger(schema.get("minLength"), 0);
        add(result, "a".repeat(Math.min(minimum, MAX_COLLECTION_ITEMS)));
        if (text.length() > minimum) {
            int half = Math.max(minimum, text.length() / 2);
            add(result, text.substring(0, half));
            add(result, "a".repeat(half));
        }
    }

    private TestPropertyCasePlan result(
            TestExecutionApiRequest.Target target,
            String schemaFingerprint,
            TestPropertyCasePlan.Status status,
            GenerationPolicy policy,
            State state) {
        List<CoverageGap> gaps = state.sortedGaps();
        List<PropertyTrial> trials = List.copyOf(state.trials);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", TestPropertyCasePlan.SCHEMA_VERSION);
        material.put("target", target);
        material.put("inputSchemaFingerprint", schemaFingerprint);
        material.put("status", status.name());
        material.put("quantification", TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED.name());
        material.put("exhaustive", false);
        material.put("policy", policy);
        material.put("trials", trials);
        material.put("gaps", gaps);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestPropertyCasePlan("", target, schemaFingerprint, fingerprint, status,
                TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED, false,
                policy, trials, gaps);
    }

    private void discloseUngenerated(Map<String, Object> schema,
                                     String path,
                                     int depth,
                                     State state) {
        if (depth > MAX_DEPTH) {
            state.gap(GapCode.DEPTH_LIMIT_REACHED, path, "depth");
            return;
        }
        if (schema.get("type") instanceof List<?>) {
            state.gap(GapCode.CONSTRAINT_NOT_GENERATED, path + "/type", "type");
        }
        schema.keySet().stream().filter(UNGENERATED_CONSTRAINTS::contains).sorted()
                .forEach(keyword -> state.gap(GapCode.CONSTRAINT_NOT_GENERATED,
                        path + "/" + pointer(keyword), keyword));
        map(schema.get("properties")).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> discloseUngenerated(map(entry.getValue()),
                        path + "/properties/" + pointer(entry.getKey()), depth + 1, state));
        if (schema.get("items") instanceof Map<?, ?>) {
            discloseUngenerated(map(schema.get("items")), path + "/items", depth + 1, state);
        }
    }

    private static String generatedType(Map<String, Object> schema,
                                        SplittableRandom random,
                                        String path,
                                        State state) {
        Object raw = schema.get("type");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<String> types = list.stream().map(String::valueOf).sorted().toList();
            state.gap(GapCode.CONSTRAINT_NOT_GENERATED, path + "/type", "type");
            return types.get(random.nextInt(types.size()));
        }
        return simpleType(schema, null);
    }

    private static String simpleType(Map<String, Object> schema, Object value) {
        Object raw = schema.get("type");
        if (raw instanceof String type && !type.isBlank()) {
            return type;
        }
        if (schema.containsKey("properties")) {
            return "object";
        }
        if (schema.containsKey("items")) {
            return "array";
        }
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return "integer";
        if (value instanceof Number) return "number";
        if (value == null) return "null";
        return "";
    }

    private static Optional<Long> integerLower(Map<String, Object> schema) {
        Optional<BigDecimal> exclusive = decimal(schema.get("exclusiveMinimum"));
        if (exclusive.isPresent()) {
            return Optional.of(Math.addExact(
                    exclusive.get().setScale(0, RoundingMode.FLOOR).longValueExact(), 1L));
        }
        return decimal(schema.get("minimum"))
                .map(value -> value.setScale(0, RoundingMode.CEILING).longValueExact());
    }

    private static Optional<Long> integerUpper(Map<String, Object> schema) {
        Optional<BigDecimal> exclusive = decimal(schema.get("exclusiveMaximum"));
        if (exclusive.isPresent()) {
            return Optional.of(Math.subtractExact(
                    exclusive.get().setScale(0, RoundingMode.CEILING).longValueExact(), 1L));
        }
        return decimal(schema.get("maximum"))
                .map(value -> value.setScale(0, RoundingMode.FLOOR).longValueExact());
    }

    private static Optional<BigDecimal> decimalLower(Map<String, Object> schema) {
        return decimal(schema.get("exclusiveMinimum"))
                .map(value -> value.add(BigDecimal.valueOf(0.000001)))
                .or(() -> decimal(schema.get("minimum")));
    }

    private static Optional<BigDecimal> decimalUpper(Map<String, Object> schema) {
        return decimal(schema.get("exclusiveMaximum"))
                .map(value -> value.subtract(BigDecimal.valueOf(0.000001)))
                .or(() -> decimal(schema.get("maximum")));
    }

    private static Optional<BigDecimal> decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(String.valueOf(value)));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static Object number(BigDecimal value, boolean integral) {
        if (integral) {
            return value.setScale(0, RoundingMode.DOWN).longValue();
        }
        return value.stripTrailingZeros();
    }

    private static int complexity(Object value) {
        long score;
        if (value == null) {
            score = 0;
        } else if (value instanceof Boolean bool) {
            score = bool ? 2 : 1;
        } else if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(String.valueOf(number)).abs();
            score = 1 + Math.min(1_000_000L,
                    decimal.multiply(BigDecimal.TEN).setScale(0, RoundingMode.CEILING).longValue());
        } else if (value instanceof String text) {
            score = 1L + text.length();
        } else if (value instanceof Collection<?> collection) {
            score = 1;
            for (Object nested : collection) {
                score += 1L + complexity(nested);
            }
        } else if (value instanceof Map<?, ?> map) {
            score = 1;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                score += 1L + String.valueOf(entry.getKey()).length() + complexity(entry.getValue());
            }
        } else {
            score = 1L + String.valueOf(value).length();
        }
        return (int) Math.min(Integer.MAX_VALUE, score);
    }

    private static String randomString(SplittableRandom random, int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private static boolean isOpaque(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()
                || "any".equals(schema.get("type"))
                || "opaque".equals(schema.get("type"))
                || "any".equals(schema.get("kind"))
                || "opaque".equals(schema.get("kind"))) {
            return true;
        }
        return "object".equals(schema.get("type"))
                && Boolean.TRUE.equals(schema.get("additionalProperties"))
                && !schema.containsKey("properties") && !schema.containsKey("required");
    }

    private static boolean domainConsistent(Map<String, Object> schema,
                                            String path,
                                            State state) {
        boolean valid = true;
        Optional<BigDecimal> minimum = decimal(schema.get("minimum"));
        Optional<BigDecimal> exclusiveMinimum = decimal(schema.get("exclusiveMinimum"));
        Optional<BigDecimal> maximum = decimal(schema.get("maximum"));
        Optional<BigDecimal> exclusiveMaximum = decimal(schema.get("exclusiveMaximum"));
        BigDecimal lower = exclusiveMinimum.orElseGet(() -> minimum.orElse(null));
        BigDecimal upper = exclusiveMaximum.orElseGet(() -> maximum.orElse(null));
        if (lower != null && upper != null
                && (lower.compareTo(upper) > 0
                || lower.compareTo(upper) == 0
                && (exclusiveMinimum.isPresent() || exclusiveMaximum.isPresent()))) {
            state.gap(GapCode.INVALID_INPUT_SCHEMA, path, "numericRange");
            valid = false;
        }
        valid &= orderedBounds(schema, "minLength", "maxLength", path, state);
        valid &= orderedBounds(schema, "minItems", "maxItems", path, state);
        for (Map.Entry<String, Object> entry : map(schema.get("properties")).entrySet()) {
            valid &= domainConsistent(map(entry.getValue()),
                    path + "/properties/" + pointer(entry.getKey()), state);
        }
        if (schema.get("items") instanceof Map<?, ?>) {
            valid &= domainConsistent(map(schema.get("items")), path + "/items", state);
        }
        return valid;
    }

    private static boolean orderedBounds(Map<String, Object> schema,
                                         String minimumName,
                                         String maximumName,
                                         String path,
                                         State state) {
        Optional<BigDecimal> minimum = decimal(schema.get(minimumName));
        Optional<BigDecimal> maximum = decimal(schema.get(maximumName));
        if (minimum.isPresent() && maximum.isPresent()
                && minimum.get().compareTo(maximum.get()) > 0) {
            state.gap(GapCode.INVALID_INPUT_SCHEMA, path,
                    minimumName + "/" + maximumName);
            return false;
        }
        return true;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static int boundedInteger(Object value, int fallback) {
        Optional<BigDecimal> decimal = decimal(value);
        if (decimal.isEmpty()) {
            return fallback;
        }
        try {
            long result = decimal.get().longValueExact();
            if (result < 0 || result > Integer.MAX_VALUE) {
                return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : fallback;
            }
            return (int) result;
        } catch (ArithmeticException invalid) {
            return fallback;
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return stringMap(raw);
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), deepCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(TestPropertyCasePlanner::deepCopy).toList());
        }
        return value;
    }

    private static void add(List<Object> values, Object value) {
        if (values.size() < MAX_SIMPLIFICATIONS) {
            values.add(deepCopy(value));
        }
    }

    private static List<Object> bounded(List<Object> values) {
        int size = Math.min(values.size(), MAX_SIMPLIFICATIONS);
        return Collections.unmodifiableList(new ArrayList<>(values.subList(0, size)));
    }

    private record Candidate(Object input, String fingerprint, int complexity) {
    }

    private static final class State {
        private final List<PropertyTrial> trials = new ArrayList<>();
        private final Set<CoverageGap> gaps = new LinkedHashSet<>();

        private State(SchemaEnvelope schema, List<CoverageGap> initialGaps) {
            Objects.requireNonNull(schema, "schema");
            if (initialGaps != null) {
                gaps.addAll(initialGaps);
            }
        }

        private void gap(GapCode code, String schemaPath, String keyword) {
            gaps.add(new CoverageGap(code, schemaPath, keyword));
        }

        private List<CoverageGap> sortedGaps() {
            List<CoverageGap> result = new ArrayList<>(gaps);
            result.sort(Comparator.comparing((CoverageGap gap) -> gap.code().name())
                    .thenComparing(CoverageGap::schemaPath)
                    .thenComparing(CoverageGap::keyword));
            return List.copyOf(result);
        }
    }
}
