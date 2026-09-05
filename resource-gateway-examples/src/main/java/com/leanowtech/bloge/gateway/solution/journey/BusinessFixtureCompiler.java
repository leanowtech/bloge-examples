package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionValueSchemaValidator;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles business-language facts and dependency outcomes against one revision-locked Solution
 * closure. Every Feature, Scenario, Instruction and Solution is decoded exactly once from its
 * locked persisted revision. The compiler never performs a second mutable-registry lookup after
 * name resolution, so one proposal cannot combine contracts from different revisions.
 *
 * <p>The compiler opens an atomic read boundary. {@link BusinessGoldenService} invokes it inside
 * the wider idempotent proposal transaction, so the locks remain held through protected-material
 * and case-set persistence. Runtime node ids, bindings and graph shapes never enter the business
 * input or response.</p>
 */
public final class BusinessFixtureCompiler {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Set<String> OUTCOMES = Set.of(
            "RETURNS", "UNAVAILABLE", "SUCCEEDS_WITHOUT_EFFECT",
            "FAILS_WITHOUT_EFFECT", "MUST_NOT_BE_USED");

    private final AgentTddStateRepository states;
    private final ObjectMapper mapper;

    /** Creates a compiler over the authoritative revision-locking state repository. */
    public BusinessFixtureCompiler(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves one complete fixture case against a single frozen Solution closure.
     *
     * @param scope server-derived integration scope
     * @param solutionRef current Solution selected by the journey
     * @param fixtureCase complete business-language fixture case
     * @return immutable controlled-assumption plan bound to the locked closure
     */
    public ControlledAssumptionPlan compile(String scope, String solutionRef, JsonNode fixtureCase) {
        if (!fixtureCase.path("givenFacts").isArray()
                || !fixtureCase.path("dependencyAssumptions").isArray()) throw schema();
        return states.executeAtomically(() -> compileLocked(scope, solutionRef, fixtureCase));
    }

    /**
     * Resolves and validates a proposed business case without creating an execution plan.
     *
     * <p>This operation exists for the pre-approval boundary. It checks unique business-name
     * resolution, value schemas and dependency outcomes, but returns only stable semantic keys and
     * business contract fingerprints. Solution aliases, entity references, revisions and compiled
     * dependency maps are not part of the approval subject.</p>
     *
     * @param scope server-derived integration scope
     * @param solutionRef current Solution selected by the journey
     * @param fixtureCase complete original business case
     * @return validated business contract identities plus an internal material target coordinate
     */
    public BusinessCaseValidation validateBusinessCase(
            String scope, String solutionRef, JsonNode fixtureCase) {
        if (!fixtureCase.path("givenFacts").isArray()
                || !fixtureCase.path("dependencyAssumptions").isArray()) throw schema();
        return states.executeAtomically(() -> validateBusinessCaseLocked(
                scope, solutionRef, fixtureCase));
    }

    private BusinessCaseValidation validateBusinessCaseLocked(
            String scope, String solutionRef, JsonNode fixtureCase) {
        FrozenClosure closure = freeze(scope, solutionRef);
        List<JsonNode> vector = new ArrayList<>();
        fixtureCase.path("givenFacts").forEach(fact -> validateFact(closure, fact, vector));
        fixtureCase.path("dependencyAssumptions")
                .forEach(assumption -> validateDependency(
                        assumption, closure.instructions(), vector));
        List<JsonNode> stableVector = vector.stream()
                .map(value -> (JsonNode) value.deepCopy())
                .sorted(Comparator.comparing(BusinessFixtureCompiler::semanticCoordinateOrder))
                .toList();
        return new BusinessCaseValidation(stableVector, closure.solution().contract().solutionRef(),
                closure.solution().revision(), closure.solution().contractFingerprint());
    }

    private ControlledAssumptionPlan compileLocked(
            String scope, String solutionRef, JsonNode fixtureCase) {
        FrozenClosure closure = freeze(scope, solutionRef);
        ObjectNode given = mapper.createObjectNode();
        List<JsonNode> businessVector = new ArrayList<>();
        fixtureCase.path("givenFacts")
                .forEach(fact -> compileFact(closure, fact, given, businessVector));
        ObjectNode dependencies = mapper.createObjectNode();
        fixtureCase.path("dependencyAssumptions")
                .forEach(assumption -> compileDependency(
                        assumption, closure.instructions(), dependencies, businessVector));
        List<JsonNode> sortedBusinessVector = businessVector.stream()
                .map(value -> (JsonNode) value.deepCopy())
                .sorted(Comparator.comparing(BusinessFixtureCompiler::coordinateOrder))
                .toList();
        String featureValuesFingerprint = fingerprint(given);
        String dependencyPlanFingerprint = fingerprint(dependencies);
        String frozenContextFingerprint = fingerprint(closure.coordinates());
        String planFingerprint = fingerprint(Map.of(
                "featureValuesFingerprint", featureValuesFingerprint,
                "dependencyPlanFingerprint", dependencyPlanFingerprint,
                "frozenContextFingerprint", frozenContextFingerprint,
                "referencedBusinessContractVector", sortedBusinessVector));
        return new ControlledAssumptionPlan(given, dependencies, sortedBusinessVector,
                closure.solution().contract().solutionRef(), closure.solution().revision(),
                closure.solution().contractFingerprint(), featureValuesFingerprint,
                dependencyPlanFingerprint, frozenContextFingerprint, planFingerprint);
    }

    private FrozenClosure freeze(String scope, String solutionRef) {
        FrozenEntity<SolutionContract> solution = lockAndDecode(
                scope, SolutionEntityRegistry.SOLUTION, solutionRef, SolutionContract.class);
        Map<String, FrozenEntity<ScenarioContract>> scenarios = new LinkedHashMap<>();
        Set<String> instructionRefs = new LinkedHashSet<>(solution.contract().instructions());
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(solution.contract().rootScenarioRef());
        while (!pending.isEmpty()) {
            String scenarioRef = pending.removeFirst();
            if (scenarios.containsKey(scenarioRef)) continue;
            FrozenEntity<ScenarioContract> scenario = lockAndDecode(
                    scope, SolutionEntityRegistry.SCENARIO, scenarioRef, ScenarioContract.class);
            scenarios.put(scenarioRef, scenario);
            instructionRefs.addAll(scenario.contract().referencedInstructions());
            scenario.contract().referencedScenarios().stream().sorted().forEach(pending::addLast);
        }
        Map<String, FrozenEntity<FeatureContract>> features = new LinkedHashMap<>();
        solution.contract().inputs().values().stream().distinct().sorted().forEach(ref ->
                features.put(ref, lockAndDecode(
                        scope, SolutionEntityRegistry.FEATURE, ref, FeatureContract.class)));
        List<FrozenEntity<InstructionContract>> instructions = instructionRefs.stream().sorted()
                .map(ref -> lockAndDecode(
                        scope, SolutionEntityRegistry.INSTRUCTION, ref, InstructionContract.class))
                .toList();
        List<JsonNode> coordinates = new ArrayList<>();
        coordinates.add(closureCoordinate("SOLUTION", solution));
        scenarios.values().stream().sorted(Comparator.comparing(FrozenEntity::ref))
                .map(value -> closureCoordinate("SCENARIO", value)).forEach(coordinates::add);
        features.values().stream().sorted(Comparator.comparing(FrozenEntity::ref))
                .map(value -> closureCoordinate("FEATURE", value)).forEach(coordinates::add);
        instructions.stream().map(value -> closureCoordinate("INSTRUCTION", value))
                .forEach(coordinates::add);
        return new FrozenClosure(solution, Map.copyOf(features), List.copyOf(instructions),
                List.copyOf(coordinates));
    }

    private <T> FrozenEntity<T> lockAndDecode(
            String scope, String kind, String ref, Class<T> contractType) {
        AgentTddStoredAsset observed = states.find(scope, kind, ref)
                .orElseThrow(BusinessFixtureCompiler::unresolved);
        AgentTddStoredAsset locked;
        try {
            locked = states.lockRevision(scope, kind, ref, observed.revision());
        } catch (AgentTddToolException failure) {
            if (!"GATE_REJECTED".equals(failure.code())) throw failure;
            throw new AgentTddToolException("CAPABILITY_CONTEXT_STALE",
                    "A business capability changed while the fixture context was being frozen.");
        }
        JsonNode data = locked.data();
        if (!data.path("contract").isObject()
                || data.path("contractFingerprint").asText().isBlank()) throw unresolved();
        try {
            T contract = mapper.treeToValue(data.path("contract"), contractType);
            return new FrozenEntity<>(ref, locked.revision(),
                    data.path("contractFingerprint").asText(), contract);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw unresolved();
        }
    }

    private void compileFact(FrozenClosure closure, JsonNode fact,
                             ObjectNode given, List<JsonNode> vector) {
        String name = requiredText(fact, "factName");
        List<InputFeature> matches = closure.solution().contract().inputs().entrySet().stream()
                .map(entry -> new InputFeature(
                        entry.getKey(), closure.features().get(entry.getValue())))
                .filter(input -> input.feature() != null
                        && featureMatches(input.feature().contract(), name,
                        input.alias(), input.feature().ref()))
                .toList();
        if (matches.size() != 1 || !fact.has("value")) throw ambiguous("fact");
        InputFeature match = matches.getFirst();
        if (!SolutionValueSchemaValidator.featureValueMatches(
                match.feature().contract().output(), fact.path("value"))) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_SCHEMA_INVALID",
                    "A supplied business fact value does not match its current contract.");
        }
        given.set(match.alias(), fact.path("value").deepCopy());
        vector.add(businessCoordinate("FEATURE", match.feature(),
                match.feature().contract().businessDefinition().semanticKey()));
    }

    private void validateFact(FrozenClosure closure, JsonNode fact, List<JsonNode> vector) {
        String name = requiredText(fact, "factName");
        List<InputFeature> matches = closure.solution().contract().inputs().entrySet().stream()
                .map(entry -> new InputFeature(
                        entry.getKey(), closure.features().get(entry.getValue())))
                .filter(input -> input.feature() != null
                        && featureMatches(input.feature().contract(), name,
                        input.alias(), input.feature().ref()))
                .toList();
        if (matches.size() != 1 || !fact.has("value")) throw ambiguous("fact");
        InputFeature match = matches.getFirst();
        if (!SolutionValueSchemaValidator.featureValueMatches(
                match.feature().contract().output(), fact.path("value"))) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_SCHEMA_INVALID",
                    "A supplied business fact value does not match its current contract.");
        }
        vector.add(stableBusinessCoordinate(match.feature().contract()
                .businessDefinition().semanticKey(), match.feature().contractFingerprint()));
    }

    private void compileDependency(JsonNode assumption,
                                   List<FrozenEntity<InstructionContract>> reachable,
                                   ObjectNode dependencies,
                                   List<JsonNode> vector) {
        String name = requiredText(assumption, "capabilityName");
        List<FrozenEntity<InstructionContract>> matches = reachable.stream()
                .filter(instruction -> instructionMatches(instruction.contract(), name)).toList();
        if (matches.size() != 1) throw ambiguous("dependency");
        FrozenEntity<InstructionContract> frozen = matches.getFirst();
        InstructionContract instruction = frozen.contract();
        String outcome = requiredText(assumption, "outcome").toUpperCase(Locale.ROOT);
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
        vector.add(businessCoordinate("INSTRUCTION", frozen,
                instruction.businessDefinition().semanticKey()));
    }

    private void validateDependency(JsonNode assumption,
                                    List<FrozenEntity<InstructionContract>> reachable,
                                    List<JsonNode> vector) {
        String name = requiredText(assumption, "capabilityName");
        List<FrozenEntity<InstructionContract>> matches = reachable.stream()
                .filter(instruction -> instructionMatches(instruction.contract(), name)).toList();
        if (matches.size() != 1) throw ambiguous("dependency");
        FrozenEntity<InstructionContract> frozen = matches.getFirst();
        validateDependencyOutcome(assumption, frozen.contract());
        vector.add(stableBusinessCoordinate(
                frozen.contract().businessDefinition().semanticKey(), frozen.contractFingerprint()));
    }

    private static void validateDependencyOutcome(
            JsonNode assumption, InstructionContract instruction) {
        String outcome = requiredText(assumption, "outcome").toUpperCase(Locale.ROOT);
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
    }

    private boolean featureMatches(FeatureContract feature, String name,
                                   String inputAlias, String featureRef) {
        return inputAlias.equalsIgnoreCase(name) || featureRef.equalsIgnoreCase(name)
                || feature.businessSemantics().equalsIgnoreCase(name)
                || feature.businessDefinition().semanticKey().equalsIgnoreCase(name);
    }

    private static boolean instructionMatches(InstructionContract instruction, String name) {
        if (instruction.businessDefinition().semanticKey().equalsIgnoreCase(name)
                || instruction.businessSemantics().equalsIgnoreCase(name)) return true;
        return instruction.businessDefinition().incompleteLegacyProjection()
                && instruction.instructionRef().equalsIgnoreCase(name);
    }

    private JsonNode businessCoordinate(
            String kind, FrozenEntity<?> entity, String semanticKey) {
        ObjectNode coordinate = mapper.createObjectNode();
        coordinate.put("assetKind", kind);
        coordinate.put("assetRef", entity.ref());
        coordinate.put("revision", entity.revision());
        coordinate.put("semanticKey", semanticKey);
        coordinate.put("contractFingerprint", entity.contractFingerprint());
        return coordinate;
    }

    private JsonNode stableBusinessCoordinate(String semanticKey, String contractFingerprint) {
        ObjectNode coordinate = mapper.createObjectNode();
        coordinate.put("semanticKey", semanticKey);
        coordinate.put("contractFingerprint", contractFingerprint);
        return coordinate;
    }

    private JsonNode closureCoordinate(String kind, FrozenEntity<?> entity) {
        ObjectNode coordinate = mapper.createObjectNode();
        coordinate.put("assetKind", kind);
        coordinate.put("assetRef", entity.ref());
        coordinate.put("revision", entity.revision());
        coordinate.put("contractFingerprint", entity.contractFingerprint());
        return coordinate;
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static String coordinateOrder(JsonNode coordinate) {
        return coordinate.path("semanticKey").asText() + '\u001f'
                + coordinate.path("assetKind").asText() + '\u001f'
                + coordinate.path("assetRef").asText();
    }

    private static String semanticCoordinateOrder(JsonNode coordinate) {
        return coordinate.path("semanticKey").asText() + '\u001f'
                + coordinate.path("contractFingerprint").asText();
    }

    private static AgentTddToolException ambiguous(String kind) {
        return new AgentTddToolException("BUSINESS_ASSUMPTION_AMBIGUOUS",
                "A business " + kind + " did not resolve to exactly one current capability.");
    }

    private static AgentTddToolException unresolved() {
        return new AgentTddToolException("REFERENCE_UNRESOLVED",
                "A referenced Solution capability is unavailable.");
    }

    private static AgentTddToolException schema() {
        return new AgentTddToolException("SCHEMA_NONCONFORMANT", "Business fixture is incomplete.");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText().trim() : "";
        if (value.isBlank()) throw schema();
        return value;
    }

    private record FrozenEntity<T>(String ref, long revision,
                                   String contractFingerprint, T contract) { }

    private record FrozenClosure(FrozenEntity<SolutionContract> solution,
                                 Map<String, FrozenEntity<FeatureContract>> features,
                                 List<FrozenEntity<InstructionContract>> instructions,
                                 List<JsonNode> coordinates) { }

    private record InputFeature(String alias, FrozenEntity<FeatureContract> feature) { }

    /**
     * Immutable pre-approval validation result without any controlled execution plan.
     *
     * @param businessContractVector stable semantic keys and business contract fingerprints
     * @param solutionRef internal protected-material target
     * @param solutionRevision internal protected-material target revision
     * @param solutionContractFingerprint internal protected-material target fingerprint
     */
    public record BusinessCaseValidation(
            List<JsonNode> businessContractVector,
            String solutionRef,
            long solutionRevision,
            String solutionContractFingerprint
    ) {
        /** Freezes validation coordinates and rejects incomplete material targets. */
        public BusinessCaseValidation {
            businessContractVector = businessContractVector == null ? List.of()
                    : businessContractVector.stream()
                    .map(value -> (JsonNode) value.deepCopy()).toList();
            solutionRef = ControlledAssumptionPlan.requiredText(solutionRef, "solutionRef");
            if (solutionRevision < 1) throw new IllegalArgumentException("Solution revision is invalid");
            solutionContractFingerprint = ControlledAssumptionPlan.requiredFingerprint(
                    solutionContractFingerprint);
        }

        /** Returns copies so callers cannot mutate the approved semantic vector. */
        @Override
        public List<JsonNode> businessContractVector() {
            return businessContractVector.stream()
                    .map(value -> (JsonNode) value.deepCopy()).toList();
        }
    }

    /**
     * Immutable payload-bearing plan kept inside the protected material boundary.
     *
     * @param given canonical Feature values keyed by Solution input aliases
     * @param dependencyAssumptions case-scoped Instruction outcomes keyed by internal reference
     * @param businessContractVector frozen semantic contract coordinates and locked revisions
     * @param solutionRef Solution whose locked closure was compiled
     * @param solutionRevision locked Solution revision
     * @param solutionContractFingerprint locked Solution contract fingerprint
     * @param featureValuesFingerprint fingerprint of canonical Feature values
     * @param dependencyPlanFingerprint fingerprint of controlled Instruction outcomes
     * @param frozenContextFingerprint fingerprint of all locked entity revisions
     * @param planFingerprint combined plan and frozen-context fingerprint
     */
    public record ControlledAssumptionPlan(
            JsonNode given,
            JsonNode dependencyAssumptions,
            List<JsonNode> businessContractVector,
            String solutionRef,
            long solutionRevision,
            String solutionContractFingerprint,
            String featureValuesFingerprint,
            String dependencyPlanFingerprint,
            String frozenContextFingerprint,
            String planFingerprint
    ) {
        /** Freezes payloads and coordinates so later caller mutation cannot change the plan. */
        public ControlledAssumptionPlan {
            given = Objects.requireNonNull(given, "given").deepCopy();
            dependencyAssumptions = Objects.requireNonNull(
                    dependencyAssumptions, "dependencyAssumptions").deepCopy();
            businessContractVector = businessContractVector == null ? List.of()
                    : businessContractVector.stream()
                    .map(value -> (JsonNode) value.deepCopy()).toList();
            solutionRef = requiredText(solutionRef, "solutionRef");
            if (solutionRevision < 1) throw new IllegalArgumentException("Solution revision is invalid");
            solutionContractFingerprint = requiredFingerprint(solutionContractFingerprint);
            featureValuesFingerprint = requiredFingerprint(featureValuesFingerprint);
            dependencyPlanFingerprint = requiredFingerprint(dependencyPlanFingerprint);
            frozenContextFingerprint = requiredFingerprint(frozenContextFingerprint);
            planFingerprint = requiredFingerprint(planFingerprint);
        }

        /** Returns a copy so callers cannot mutate the plan's canonical Feature values. */
        @Override
        public JsonNode given() {
            return given.deepCopy();
        }

        /** Returns a copy so callers cannot mutate controlled Instruction outcomes. */
        @Override
        public JsonNode dependencyAssumptions() {
            return dependencyAssumptions.deepCopy();
        }

        /** Returns copies so callers cannot mutate frozen business contract coordinates. */
        @Override
        public List<JsonNode> businessContractVector() {
            return businessContractVector.stream()
                    .map(value -> (JsonNode) value.deepCopy()).toList();
        }

        private static String requiredText(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }

        private static String requiredFingerprint(String value) {
            if (value == null || !value.startsWith("sha256:")) {
                throw new IllegalArgumentException("Controlled plan fingerprint is invalid");
            }
            return value;
        }
    }
}
