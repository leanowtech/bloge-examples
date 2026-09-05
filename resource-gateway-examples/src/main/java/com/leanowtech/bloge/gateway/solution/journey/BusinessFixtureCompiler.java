package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionValueSchemaValidator;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles business-language facts and dependency outcomes into a case-scoped controlled test
 * plan. The compiler resolves only capabilities reachable from the selected Solution and uses
 * structured semantic keys as authority; runtime node ids, bindings and graph shapes never enter
 * the business input or response.
 */
public final class BusinessFixtureCompiler {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Set<String> OUTCOMES = Set.of(
            "RETURNS", "UNAVAILABLE", "SUCCEEDS_WITHOUT_EFFECT",
            "FAILS_WITHOUT_EFFECT", "MUST_NOT_BE_USED");

    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;

    /** Creates a deterministic compiler over the canonical four-entity registry. */
    public BusinessFixtureCompiler(SolutionEntityRegistry registry, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves one complete FixtureCase and returns its immutable controlled-assumption plan.
     * Ambiguous business names fail the whole case before any protected material is written.
     */
    public ControlledAssumptionPlan compile(String scope, SolutionContract solution, JsonNode fixtureCase) {
        if (!fixtureCase.path("givenFacts").isArray()
                || !fixtureCase.path("dependencyAssumptions").isArray()) throw schema();
        ObjectNode given = mapper.createObjectNode();
        ArrayNode vector = mapper.createArrayNode();
        fixtureCase.path("givenFacts").forEach(fact -> compileFact(scope, solution, fact, given, vector));
        ObjectNode dependencies = mapper.createObjectNode();
        List<InstructionContract> reachable = reachableInstructions(scope, solution);
        fixtureCase.path("dependencyAssumptions")
                .forEach(assumption -> compileDependency(assumption, reachable, dependencies, vector));
        List<JsonNode> sortedVector = new ArrayList<>();
        vector.forEach(value -> sortedVector.add(value.deepCopy()));
        sortedVector.sort(java.util.Comparator.comparing(value -> value.path("semanticKey").asText()));
        String featureValuesFingerprint = fingerprint(given);
        String dependencyPlanFingerprint = fingerprint(dependencies);
        String planFingerprint = fingerprint(Map.of(
                "featureValuesFingerprint", featureValuesFingerprint,
                "dependencyPlanFingerprint", dependencyPlanFingerprint,
                "referencedBusinessContractVector", sortedVector));
        return new ControlledAssumptionPlan(given, dependencies, sortedVector,
                featureValuesFingerprint, dependencyPlanFingerprint, planFingerprint);
    }

    private void compileFact(String scope, SolutionContract solution, JsonNode fact,
                             ObjectNode given, ArrayNode vector) {
        String name = requiredText(fact, "factName");
        List<Map.Entry<String, String>> matches = solution.inputs().entrySet().stream()
                .filter(entry -> featureMatches(registry.requireFeature(scope, entry.getValue()), name, entry))
                .toList();
        if (matches.size() != 1 || !fact.has("value")) throw ambiguous("fact");
        Map.Entry<String, String> match = matches.getFirst();
        FeatureContract feature = registry.requireFeature(scope, match.getValue());
        if (!SolutionValueSchemaValidator.featureValueMatches(feature.output(), fact.path("value"))) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_SCHEMA_INVALID",
                    "A supplied business fact value does not match its current contract.");
        }
        given.set(match.getKey(), fact.path("value").deepCopy());
        addCoordinate(vector, "FEATURE", match.getValue(),
                feature.businessDefinition().semanticKey(), feature.contractIdentity());
    }

    private void compileDependency(JsonNode assumption, List<InstructionContract> reachable,
                                   ObjectNode dependencies, ArrayNode vector) {
        String name = requiredText(assumption, "capabilityName");
        List<InstructionContract> matches = reachable.stream()
                .filter(instruction -> instructionMatches(instruction, name)).toList();
        if (matches.size() != 1) throw ambiguous("dependency");
        InstructionContract instruction = matches.getFirst();
        String outcome = requiredText(assumption, "outcome").toUpperCase(java.util.Locale.ROOT);
        if (!OUTCOMES.contains(outcome)) throw schema();
        if ("RETURNS".equals(outcome) && instruction.effect() == InstructionContract.Effect.WRITE) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_EFFECT_INVALID",
                    "A write capability cannot be represented as a returned fact.");
        }
        if ("RETURNS".equals(outcome) && (!assumption.path("value").isObject()
                || !assumption.path("value").has("result")
                || !assumption.path("value").has("reasoning"))) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_SCHEMA_INVALID",
                    "A returned dependency result must contain result and reasoning.");
        }
        ObjectNode compiled = dependencies.putObject(instruction.instructionRef());
        compiled.put("outcome", outcome);
        if (assumption.has("value")) compiled.set("value", assumption.path("value").deepCopy());
        addCoordinate(vector, "INSTRUCTION", instruction.instructionRef(),
                instruction.businessDefinition().semanticKey(), instruction.contractIdentity());
    }

    private List<InstructionContract> reachableInstructions(String scope, SolutionContract solution) {
        Set<String> instructionRefs = new LinkedHashSet<>(solution.instructions());
        Set<String> scenarios = new LinkedHashSet<>();
        collectScenario(scope, solution.rootScenarioRef(), scenarios, instructionRefs);
        return instructionRefs.stream().sorted().map(ref -> registry.requireInstruction(scope, ref)).toList();
    }

    private void collectScenario(String scope, String ref, Set<String> visited, Set<String> instructions) {
        if (!visited.add(ref)) return;
        ScenarioContract scenario = registry.requireScenario(scope, ref);
        instructions.addAll(scenario.referencedInstructions());
        scenario.referencedScenarios().forEach(child -> collectScenario(scope, child, visited, instructions));
    }

    private boolean featureMatches(FeatureContract feature, String name, Map.Entry<String, String> input) {
        return input.getKey().equalsIgnoreCase(name) || input.getValue().equalsIgnoreCase(name)
                || feature.businessSemantics().equalsIgnoreCase(name)
                || feature.businessDefinition().semanticKey().equalsIgnoreCase(name);
    }

    private static boolean instructionMatches(InstructionContract instruction, String name) {
        if (instruction.businessDefinition().semanticKey().equalsIgnoreCase(name)
                || instruction.businessSemantics().equalsIgnoreCase(name)) return true;
        return instruction.businessDefinition().incompleteLegacyProjection()
                && instruction.instructionRef().equalsIgnoreCase(name);
    }

    private void addCoordinate(ArrayNode vector, String kind, String ref,
                               String semanticKey, Object contractIdentity) {
        ObjectNode coordinate = vector.addObject();
        coordinate.put("assetKind", kind);
        coordinate.put("assetRef", ref);
        coordinate.put("semanticKey", semanticKey);
        coordinate.put("contractFingerprint", fingerprint(contractIdentity));
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static AgentTddToolException ambiguous(String kind) {
        return new AgentTddToolException("BUSINESS_ASSUMPTION_AMBIGUOUS",
                "A business " + kind + " did not resolve to exactly one current capability.");
    }

    private static AgentTddToolException schema() {
        return new AgentTddToolException("SCHEMA_NONCONFORMANT", "Business fixture is incomplete.");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText().trim() : "";
        if (value.isBlank()) throw schema();
        return value;
    }

    /**
     * Immutable, payload-bearing plan kept inside the protected material boundary.
     *
     * @param given canonical Feature values keyed by Solution input aliases
     * @param dependencyAssumptions case-scoped Instruction outcomes keyed by internal reference
     * @param businessContractVector frozen semantic contract coordinates
     * @param featureValuesFingerprint fingerprint of canonical Feature values
     * @param dependencyPlanFingerprint fingerprint of controlled Instruction outcomes
     * @param planFingerprint combined plan and contract-vector fingerprint
     */
    public record ControlledAssumptionPlan(
            JsonNode given,
            JsonNode dependencyAssumptions,
            List<JsonNode> businessContractVector,
            String featureValuesFingerprint,
            String dependencyPlanFingerprint,
            String planFingerprint
    ) {
        /** Freezes payloads and coordinates so later caller mutation cannot change the plan. */
        public ControlledAssumptionPlan {
            given = Objects.requireNonNull(given, "given").deepCopy();
            dependencyAssumptions = Objects.requireNonNull(
                    dependencyAssumptions, "dependencyAssumptions").deepCopy();
            businessContractVector = businessContractVector == null ? List.of()
                    : businessContractVector.stream().map(value -> (JsonNode) value.deepCopy()).toList();
            featureValuesFingerprint = requiredFingerprint(featureValuesFingerprint);
            dependencyPlanFingerprint = requiredFingerprint(dependencyPlanFingerprint);
            planFingerprint = requiredFingerprint(planFingerprint);
        }

        private static String requiredFingerprint(String value) {
            if (value == null || !value.startsWith("sha256:")) {
                throw new IllegalArgumentException("Controlled plan fingerprint is invalid");
            }
            return value;
        }
    }
}
