package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.inspector.UnTrustedTagInspector;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, bounded decoder for untrusted {@code bloge.solutionAuthoring.v1} entity fragments.
 *
 * <p>The decoder never returns parser exception text. Callers receive a stable diagnostic code and
 * can safely expose that code through MCP without leaking rejected source or business values.</p>
 */
public final class SolutionAuthoringDecoder {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> FEATURE_FIELDS = Set.of(
            "output", "evaluationKind", "determinism", "inputs", "evaluationRef",
            "componentRef", "promptRef", "businessSemantics", "businessDefinition");
    private static final Set<String> SCENARIO_FIELDS = Set.of(
            "inputs", "hitPolicy", "rules", "otherwise", "businessDefinition");
    private static final Set<String> RULE_FIELDS = Set.of("ruleId", "when", "outlet");
    private static final Set<String> OUTLET_FIELDS = Set.of("kind", "ref", "bind", "terminalKind");
    private static final Set<String> INSTRUCTION_FIELDS = Set.of(
            "inputs", "output", "effect", "bindingRef", "writeGovernance", "businessSemantics",
            "businessDefinition");
    private static final Set<String> WRITE_GOVERNANCE_FIELDS = Set.of(
            "downstreamSystem", "reconciliationKey", "reconciliationAdapterRef");
    private static final Set<String> SOLUTION_FIELDS = Set.of(
            "problem", "inputs", "scenarioTree", "instructions", "golden", "businessDefinition");
    private static final Set<String> SCENARIO_TREE_FIELDS = Set.of("root");
    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "schemaVersion", "features", "scenarios", "instructions", "solutions");

    private final ObjectMapper mapper;

    /** Creates a YAML/JSON decoder with duplicate-key, depth, token, alias and size limits. */
    public SolutionAuthoringDecoder() {
        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setAllowRecursiveKeys(false);
        loader.setMaxAliasesForCollections(20);
        loader.setNestingDepthLimit(64);
        loader.setCodePointLimit(MAX_BYTES);
        loader.setMergeOnCompose(false);
        loader.setTagInspector(new UnTrustedTagInspector());
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAX_BYTES)
                .maxNestingDepth(64)
                .maxTokenCount(250_000)
                .maxNameLength(1_024)
                .maxStringLength(MAX_BYTES)
                .build();
        YAMLFactory factory = YAMLFactory.builder()
                .loaderOptions(loader)
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = new ObjectMapper(factory).findAndRegisterModules();
    }

    /**
     * Decodes one mapping whose sole top-level key is the feature reference.
     *
     * @param source UTF-8 YAML or JSON source
     * @return decoded feature or one payload-free stable failure
     */
    public DecodeResult<FeatureContract> decodeFeature(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_BYTES
                || new String(source, StandardCharsets.UTF_8).isBlank()) {
            return DecodeResult.failed("SOLUTION_DOCUMENT_INVALID");
        }
        try {
            JsonNode root = mapper.readTree(source);
            if (root == null || !root.isObject() || root.size() != 1) {
                return DecodeResult.failed("FEATURE_ROOT_INVALID");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode body = entry.getValue();
            if (!body.isObject() || hasUnknownField(body, FEATURE_FIELDS)) {
                return DecodeResult.failed("FEATURE_CONTRACT_UNKNOWN_FIELD");
            }
            FeatureContract contract = new FeatureContract(
                    entry.getKey(), required(body, "output"),
                    FeatureContract.enumValue(FeatureContract.EvaluationKind.class,
                            requiredText(body, "evaluationKind")),
                    FeatureContract.enumValue(FeatureContract.Determinism.class,
                            requiredText(body, "determinism")),
                    body.path("inputs"), text(body, "evaluationRef"),
                    text(body, "componentRef"), text(body, "promptRef"),
                    body.has("businessSemantics")
                            ? requiredText(body, "businessSemantics") : entry.getKey(),
                    body.has("businessDefinition")
                            ? mapper.treeToValue(body.path("businessDefinition"),
                                    BusinessFactSemanticContract.class)
                            : null);
            return DecodeResult.decoded(contract);
        } catch (RuntimeException | java.io.IOException failure) {
            return DecodeResult.failed("FEATURE_CONTRACT_INVALID");
        }
    }

    /**
     * Decodes one complete versioned four-entity authoring document.
     *
     * <p>Every entity is passed through the same strict fragment decoder used by public MCP
     * mutations. The aggregate path therefore cannot accept fields or shapes that individual
     * operations would reject.</p>
     *
     * @param source UTF-8 YAML or JSON document
     * @return immutable document or one payload-free stable failure
     */
    public DecodeResult<SolutionDocument> decode(byte[] source) {
        try {
            if (source == null || source.length == 0 || source.length > MAX_BYTES
                    || new String(source, StandardCharsets.UTF_8).isBlank()) {
                return DecodeResult.failed("SOLUTION_DOCUMENT_INVALID");
            }
            JsonNode root = mapper.readTree(source);
            if (root == null || !root.isObject() || hasUnknownField(root, DOCUMENT_FIELDS)
                    || !"bloge.solutionAuthoring.v1".equals(requiredText(root, "schemaVersion"))) {
                return DecodeResult.failed("SOLUTION_DOCUMENT_INVALID");
            }
            Map<String, FeatureContract> features = decodeSection(
                    required(root, "features"), this::decodeFeature);
            Map<String, ScenarioContract> scenarios = decodeSection(
                    required(root, "scenarios"), this::decodeScenario);
            Map<String, InstructionContract> instructions = decodeSection(
                    required(root, "instructions"), this::decodeInstruction);
            Map<String, SolutionContract> solutions = decodeSection(
                    required(root, "solutions"), this::decodeSolution);
            return DecodeResult.decoded(new SolutionDocument(
                    features, scenarios, instructions, solutions));
        } catch (RuntimeException | java.io.IOException failure) {
            return DecodeResult.failed("SOLUTION_DOCUMENT_INVALID");
        }
    }

    private <T> Map<String, T> decodeSection(
            JsonNode section, java.util.function.Function<byte[], DecodeResult<T>> entityDecoder)
            throws java.io.IOException {
        if (!section.isObject() || section.isEmpty()) {
            throw new IllegalArgumentException("non-empty entity section required");
        }
        LinkedHashMap<String, T> decoded = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = section.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            com.fasterxml.jackson.databind.node.ObjectNode wrapper = mapper.createObjectNode();
            wrapper.set(entry.getKey(), entry.getValue());
            DecodeResult<T> result = entityDecoder.apply(mapper.writeValueAsBytes(wrapper));
            if (!result.successful()) {
                throw new IllegalArgumentException("entity contract invalid");
            }
            decoded.put(entry.getKey(), result.value());
        }
        return Map.copyOf(decoded);
    }

    /**
     * Decodes one complete unique-hit Scenario contract with a mandatory fallback outlet.
     *
     * @param source UTF-8 YAML or JSON source
     * @return decoded scenario or one payload-free stable failure
     */
    public DecodeResult<ScenarioContract> decodeScenario(byte[] source) {
        try {
            Map.Entry<String, JsonNode> entry = singleEntity(source, "SCENARIO_ROOT_INVALID");
            JsonNode body = entry.getValue();
            if (!body.isObject() || hasUnknownField(body, SCENARIO_FIELDS)) {
                return DecodeResult.failed("SCENARIO_CONTRACT_UNKNOWN_FIELD");
            }
            List<ScenarioContract.Rule> rules = new ArrayList<>();
            JsonNode rows = required(body, "rules");
            if (!rows.isArray()) return DecodeResult.failed("SCENARIO_RULES_INVALID");
            for (JsonNode row : rows) {
                if (!row.isObject() || hasUnknownField(row, RULE_FIELDS)) {
                    return DecodeResult.failed("SCENARIO_RULE_INVALID");
                }
                rules.add(new ScenarioContract.Rule(
                        requiredText(row, "ruleId"), required(row, "when"),
                        outlet(required(row, "outlet"))));
            }
            ScenarioContract contract = new ScenarioContract(
                    entry.getKey(), stringList(required(body, "inputs")),
                    ScenarioContract.enumValue(ScenarioContract.HitPolicy.class,
                            requiredText(body, "hitPolicy")),
                    rules, outlet(required(body, "otherwise")),
                    body.has("businessDefinition")
                            ? mapper.treeToValue(body.path("businessDefinition"),
                                    BusinessScenarioSemanticContract.class) : null);
            return DecodeResult.decoded(contract);
        } catch (RuntimeException | java.io.IOException failure) {
            return DecodeResult.failed("SCENARIO_CONTRACT_INVALID");
        }
    }

    /**
     * Decodes one Instruction contract and rejects effect-inconsistent write governance.
     *
     * @param source UTF-8 YAML or JSON source
     * @return decoded instruction or one payload-free stable failure
     */
    public DecodeResult<InstructionContract> decodeInstruction(byte[] source) {
        try {
            Map.Entry<String, JsonNode> entry = singleEntity(source, "INSTRUCTION_ROOT_INVALID");
            JsonNode body = entry.getValue();
            if (!body.isObject() || hasUnknownField(body, INSTRUCTION_FIELDS)) {
                return DecodeResult.failed("INSTRUCTION_CONTRACT_UNKNOWN_FIELD");
            }
            JsonNode governanceNode = body.path("writeGovernance");
            InstructionContract.WriteGovernance governance = null;
            if (!governanceNode.isMissingNode() && !governanceNode.isNull()) {
                if (!governanceNode.isObject()
                        || hasUnknownField(governanceNode, WRITE_GOVERNANCE_FIELDS)) {
                    return DecodeResult.failed("INSTRUCTION_WRITE_GOVERNANCE_INVALID");
                }
                governance = new InstructionContract.WriteGovernance(
                        requiredText(governanceNode, "downstreamSystem"),
                        requiredText(governanceNode, "reconciliationKey"),
                        requiredText(governanceNode, "reconciliationAdapterRef"));
            }
            InstructionContract contract = new InstructionContract(
                    entry.getKey(), required(body, "inputs"), required(body, "output"),
                    InstructionContract.enumValue(InstructionContract.Effect.class,
                            requiredText(body, "effect")),
                    text(body, "bindingRef"), governance,
                    body.has("businessSemantics")
                            ? requiredText(body, "businessSemantics") : entry.getKey(),
                    body.has("businessDefinition")
                            ? mapper.treeToValue(body.path("businessDefinition"),
                                    BusinessInstructionSemanticContract.class) : null);
            return DecodeResult.decoded(contract);
        } catch (RuntimeException | java.io.IOException failure) {
            return DecodeResult.failed("INSTRUCTION_CONTRACT_INVALID");
        }
    }

    /**
     * Decodes one pure Solution contract whose inputs reference Feature contracts.
     *
     * @param source UTF-8 YAML or JSON source
     * @return decoded solution or one payload-free stable failure
     */
    public DecodeResult<SolutionContract> decodeSolution(byte[] source) {
        try {
            Map.Entry<String, JsonNode> entry = singleEntity(source, "SOLUTION_ROOT_INVALID");
            JsonNode body = entry.getValue();
            if (!body.isObject() || hasUnknownField(body, SOLUTION_FIELDS)) {
                return DecodeResult.failed("SOLUTION_CONTRACT_UNKNOWN_FIELD");
            }
            JsonNode scenarioTree = required(body, "scenarioTree");
            if (!scenarioTree.isObject() || hasUnknownField(scenarioTree, SCENARIO_TREE_FIELDS)) {
                return DecodeResult.failed("SOLUTION_SCENARIO_TREE_INVALID");
            }
            SolutionContract contract = new SolutionContract(
                    entry.getKey(), requiredText(body, "problem"),
                    stringMap(required(body, "inputs")),
                    requiredText(scenarioTree, "root"),
                    stringList(required(body, "instructions")),
                    requiredText(body, "golden"),
                    body.has("businessDefinition")
                            ? mapper.treeToValue(body.path("businessDefinition"),
                                    BusinessSolutionSemanticContract.class) : null);
            return DecodeResult.decoded(contract);
        } catch (RuntimeException | java.io.IOException failure) {
            return DecodeResult.failed("SOLUTION_CONTRACT_INVALID");
        }
    }

    private Map.Entry<String, JsonNode> singleEntity(byte[] source, String failureCode)
            throws java.io.IOException {
        if (source == null || source.length == 0 || source.length > MAX_BYTES
                || new String(source, StandardCharsets.UTF_8).isBlank()) {
            throw new IllegalArgumentException(failureCode);
        }
        JsonNode root = mapper.readTree(source);
        if (root == null || !root.isObject() || root.size() != 1) {
            throw new IllegalArgumentException(failureCode);
        }
        return root.fields().next();
    }

    private static ScenarioContract.Outlet outlet(JsonNode value) {
        if (!value.isObject() || hasUnknownField(value, OUTLET_FIELDS)) {
            throw new IllegalArgumentException("invalid outlet");
        }
        return new ScenarioContract.Outlet(
                ScenarioContract.enumValue(ScenarioContract.OutletKind.class,
                        requiredText(value, "kind")),
                text(value, "ref"), stringMap(value.path("bind")), text(value, "terminalKind"));
    }

    private static List<String> stringList(JsonNode value) {
        if (!value.isArray()) throw new IllegalArgumentException("array required");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().trim().isBlank()) {
                throw new IllegalArgumentException("text item required");
            }
            result.add(item.asText().trim());
        });
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return Map.of();
        if (!value.isObject()) throw new IllegalArgumentException("object required");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().asText().trim().isBlank()) {
                throw new IllegalArgumentException("text binding required");
            }
            result.put(entry.getKey(), entry.getValue().asText().trim());
        });
        return Map.copyOf(result);
    }

    private static JsonNode required(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("required field absent");
        }
        return value;
    }

    private static String requiredText(JsonNode body, String field) {
        String value = text(body, field);
        if (value.isBlank()) throw new IllegalArgumentException("required text absent");
        return value;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static boolean hasUnknownField(JsonNode body, Set<String> allowed) {
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) return true;
        }
        return false;
    }

    /** One decoded value or one stable payload-free diagnostic code. */
    public record DecodeResult<T>(T value, String diagnosticCode) {
        /** Creates a successful immutable result. */
        public static <T> DecodeResult<T> decoded(T value) {
            return new DecodeResult<>(value, "");
        }

        /** Creates a failed result without retaining rejected source or exception text. */
        public static <T> DecodeResult<T> failed(String diagnosticCode) {
            return new DecodeResult<>(null, diagnosticCode);
        }

        /** @return whether a value was decoded */
        public boolean successful() {
            return value != null && diagnosticCode.isBlank();
        }
    }
}
