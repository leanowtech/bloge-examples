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
        Set<String> suppliedTargets = new LinkedHashSet<>();
        fixtureCase.path("givenFacts").forEach(fact -> {
            String featureRef = validateFact(closure, fact, vector);
            if (!suppliedTargets.add("FEATURE:" + featureRef)) throw duplicateAssumption();
        });
        fixtureCase.path("dependencyAssumptions")
                .forEach(assumption -> validateDependency(
                        assumption, closure, suppliedTargets, vector));
        validateExpectedOutcome(fixtureCase, closure);
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
                        assumption, closure, given, dependencies, businessVector));
        if (fixtureCase.path("expectedOutcome").isObject()) {
            validateExpectedOutcome(fixtureCase, closure);
        }
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
                List.copyOf(scenarios.values()), List.copyOf(coordinates));
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
        if (given.has(match.alias())) throw duplicateAssumption();
        given.set(match.alias(), fact.path("value").deepCopy());
        vector.add(businessCoordinate("FEATURE", match.feature(),
                match.feature().contract().businessDefinition().semanticKey()));
    }

    private String validateFact(FrozenClosure closure, JsonNode fact, List<JsonNode> vector) {
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
        return match.feature().ref();
    }

    private void compileDependency(JsonNode assumption, FrozenClosure closure, ObjectNode given,
                                   ObjectNode dependencies, List<JsonNode> vector) {
        DependencyTarget target = resolveDependency(closure, assumption);
        String outcome = validateDependencyOutcome(assumption, target);
        if (dependencies.has(target.ref())) throw duplicateAssumption();
        ObjectNode compiled = dependencies.putObject(target.ref());
        compiled.put("assetKind", target.kind());
        compiled.put("semanticKey", target.semanticKey());
        compiled.put("outcome", outcome);
        if (target.feature() != null) compiled.put("inputAlias", target.inputAlias());
        if (assumption.has("value")) compiled.set("value", assumption.path("value").deepCopy());
        if (target.feature() != null && given.has(target.inputAlias())) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_DUPLICATE",
                    "A business fact cannot be supplied by two assumptions in one case.");
        }
        if (target.feature() != null && "RETURNS".equals(outcome)) {
            given.set(target.inputAlias(), assumption.path("value").deepCopy());
        }
        FrozenEntity<?> entity = target.feature() == null ? target.instruction() : target.feature();
        vector.add(businessCoordinate(target.kind(), entity, target.semanticKey()));
    }

    private void validateDependency(
            JsonNode assumption, FrozenClosure closure, Set<String> suppliedTargets,
            List<JsonNode> vector) {
        DependencyTarget target = resolveDependency(closure, assumption);
        validateDependencyOutcome(assumption, target);
        if (!suppliedTargets.add(target.kind() + ':' + target.ref())) throw duplicateAssumption();
        FrozenEntity<?> entity = target.feature() == null ? target.instruction() : target.feature();
        vector.add(stableBusinessCoordinate(target.semanticKey(), entity.contractFingerprint()));
    }

    private DependencyTarget resolveDependency(FrozenClosure closure, JsonNode assumption) {
        String name = requiredText(assumption, "capabilityName");
        List<DependencyTarget> matches = new ArrayList<>();
        closure.solution().contract().inputs().entrySet().stream()
                .map(entry -> new InputFeature(entry.getKey(), closure.features().get(entry.getValue())))
                .filter(input -> input.feature() != null && featureMatches(input.feature().contract(),
                        name, input.alias(), input.feature().ref()))
                .map(input -> DependencyTarget.feature(input.alias(), input.feature()))
                .forEach(matches::add);
        closure.instructions().stream()
                .filter(instruction -> instructionMatches(instruction.contract(), name))
                .map(DependencyTarget::instruction).forEach(matches::add);
        if (matches.size() != 1) throw ambiguous("dependency");
        return matches.getFirst();
    }

    private static String validateDependencyOutcome(
            JsonNode assumption, DependencyTarget target) {
        String outcome = requiredText(assumption, "outcome").toUpperCase(Locale.ROOT);
        if (!OUTCOMES.contains(outcome)) throw schema();
        if (!"RETURNS".equals(outcome) && assumption.has("value")) throw assumptionSchema();
        if (target.feature() != null && "SUCCEEDS_WITHOUT_EFFECT".equals(outcome)) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_EFFECT_INVALID",
                    "A fact capability cannot be represented as a no-effect action.");
        }
        if (target.instruction() != null && "RETURNS".equals(outcome)
                && target.instruction().contract().effect() == InstructionContract.Effect.WRITE) {
            throw new AgentTddToolException("BUSINESS_ASSUMPTION_EFFECT_INVALID",
                    "A write capability cannot be represented as a returned fact.");
        }
        if ("RETURNS".equals(outcome)) {
            if (!assumption.has("value")) throw assumptionSchema();
            if (target.feature() != null) {
                FeatureContract feature = target.feature().contract();
                if (!SolutionValueSchemaValidator.featureValueMatches(
                        feature.output(), assumption.path("value"))
                        || !matchesDeclaredValue(feature.businessDefinition().resultDomain(),
                        assumption.path("value"))) throw assumptionSchema();
            } else {
                JsonNode value = assumption.path("value");
                InstructionContract instruction = target.instruction().contract();
                if (!value.isObject() || !value.path("reasoning").isTextual()
                        || value.path("reasoning").asText().isBlank()
                        || !matchesDeclaredValue(instruction.output().path("result"),
                        value.path("result"))
                        || !matchesBusinessResultDomain(
                        instruction.businessDefinition().resultDomain(), value.path("result"))) {
                    throw assumptionSchema();
                }
            }
        }
        return outcome;
    }

    private static void validateExpectedOutcome(JsonNode fixtureCase, FrozenClosure closure) {
        JsonNode expected = fixtureCase.path("expectedOutcome");
        if (!expected.isObject() || !expected.has("result")
                || !expected.path("reasoningClass").isTextual()
                || expected.path("reasoningClass").asText().isBlank()) throw expectedOutcomeInvalid();
        JsonNode result = expected.path("result");
        boolean instructionDisposition = closure.instructions().stream().anyMatch(instruction ->
                matchesDeclaredValue(instruction.contract().output().path("result"), result)
                        && matchesBusinessResultDomain(
                        instruction.contract().businessDefinition().resultDomain(), result));
        boolean terminalDisposition = result.isObject() && result.path("terminalKind").isTextual()
                && closure.scenarios().stream().map(FrozenEntity::contract)
                .flatMap(scenario -> scenarioOutlets(scenario).stream())
                .filter(outlet -> outlet.kind() == ScenarioContract.OutletKind.TERMINAL)
                .anyMatch(outlet -> outlet.terminalKind().equals(result.path("terminalKind").asText()));
        Set<String> controlledFailureStatuses = new LinkedHashSet<>();
        fixtureCase.path("dependencyAssumptions").forEach(assumption -> {
            switch (assumption.path("outcome").asText().toUpperCase(Locale.ROOT)) {
                case "UNAVAILABLE" -> controlledFailureStatuses.add("UNAVAILABLE");
                case "FAILS_WITHOUT_EFFECT" -> controlledFailureStatuses.add("FAILED_WITHOUT_EFFECT");
                default -> { }
            }
        });
        boolean controlledFailure = result.isObject()
                && controlledFailureStatuses.contains(result.path("dependencyStatus").asText());
        if (controlledFailure && (result.size() != 1
                || !("UNAVAILABLE".equals(result.path("dependencyStatus").asText())
                ? "CONTROLLED_DEPENDENCY_UNAVAILABLE"
                : "CONTROLLED_DEPENDENCY_FAILED_WITHOUT_EFFECT")
                .equals(expected.path("reasoningClass").asText()))) {
            throw expectedOutcomeInvalid();
        }
        if (!instructionDisposition && !terminalDisposition && !controlledFailure) {
            throw expectedOutcomeInvalid();
        }
    }

    private static boolean matchesBusinessResultDomain(JsonNode domain, JsonNode value) {
        if (domain == null || !domain.isObject() || domain.isEmpty()
                || "UNKNOWN".equalsIgnoreCase(domain.path("type").asText())) return true;
        JsonNode resultDomain = domain.has("result") ? domain.path("result") : domain;
        return matchesDeclaredValue(resultDomain, value);
    }

    private static boolean matchesDeclaredValue(JsonNode declaration, JsonNode value) {
        if (declaration == null || declaration.isMissingNode() || declaration.isNull()
                || value == null || value.isMissingNode()) return false;
        if (declaration.path("enum").isArray()) {
            for (JsonNode candidate : declaration.path("enum")) if (candidate.equals(value)) return true;
            return false;
        }
        JsonNode type = declaration.has("type") ? declaration.path("type") : declaration;
        if (type.isTextual()) {
            if ("enum".equalsIgnoreCase(type.asText()) && declaration.path("values").isArray()) {
                for (JsonNode candidate : declaration.path("values")) {
                    if (candidate.equals(value)) return true;
                }
                return false;
            }
            return switch (type.asText().trim().toLowerCase(Locale.ROOT)) {
                case "string" -> value.isTextual();
                case "boolean" -> value.isBoolean();
                case "number", "decimal" -> value.isNumber();
                case "integer" -> value.isIntegralNumber();
                case "array" -> value.isArray() && (!declaration.has("items")
                        || iterable(value).stream().allMatch(item ->
                        matchesDeclaredValue(declaration.path("items"), item)));
                case "object" -> objectMatches(declaration, value);
                default -> false;
            };
        }
        if (!type.isObject()) return false;
        if (type.path("enum").isArray()) {
            for (JsonNode candidate : type.path("enum")) if (candidate.equals(value)) return true;
            return false;
        }
        return objectMatches(type, value);
    }

    private static boolean objectMatches(JsonNode declaration, JsonNode value) {
        if (!value.isObject()) return false;
        JsonNode fields = declaration.has("fields")
                ? declaration.path("fields") : declaration.path("properties");
        if (!fields.isObject()) return true;
        Set<String> required = new LinkedHashSet<>();
        if (declaration.has("fields") || !declaration.path("required").isArray()) {
            fields.fieldNames().forEachRemaining(required::add);
        } else {
            declaration.path("required").forEach(node -> {
                if (node.isTextual()) required.add(node.asText());
            });
        }
        if (required.stream().anyMatch(field -> !value.has(field))) return false;
        var iterator = fields.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> field = iterator.next();
            if (value.has(field.getKey())
                    && !matchesDeclaredValue(field.getValue(), value.path(field.getKey()))) return false;
        }
        return true;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        ArrayList<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return List.copyOf(values);
    }

    private static List<ScenarioContract.Outlet> scenarioOutlets(ScenarioContract scenario) {
        ArrayList<ScenarioContract.Outlet> outlets = new ArrayList<>();
        scenario.rules().forEach(rule -> outlets.add(rule.outlet()));
        outlets.add(scenario.otherwise());
        return List.copyOf(outlets);
    }

    private static AgentTddToolException assumptionSchema() {
        return new AgentTddToolException("BUSINESS_ASSUMPTION_SCHEMA_INVALID",
                "A controlled dependency result does not match its current business contract.");
    }

    private static AgentTddToolException duplicateAssumption() {
        return new AgentTddToolException("BUSINESS_ASSUMPTION_DUPLICATE",
                "A business capability can be supplied only once in one case.");
    }

    private static AgentTddToolException expectedOutcomeInvalid() {
        return new AgentTddToolException("BUSINESS_EXPECTED_OUTCOME_INVALID",
                "The expected outcome does not match a reachable Solution disposition.");
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
                                 List<FrozenEntity<ScenarioContract>> scenarios,
                                 List<JsonNode> coordinates) { }

    private record InputFeature(String alias, FrozenEntity<FeatureContract> feature) { }

    private record DependencyTarget(String kind, String ref, String inputAlias,
                                    FrozenEntity<FeatureContract> feature,
                                    FrozenEntity<InstructionContract> instruction,
                                    String semanticKey) {
        private static DependencyTarget feature(
                String inputAlias, FrozenEntity<FeatureContract> feature) {
            return new DependencyTarget("FEATURE", feature.ref(), inputAlias, feature, null,
                    feature.contract().businessDefinition().semanticKey());
        }

        private static DependencyTarget instruction(FrozenEntity<InstructionContract> instruction) {
            return new DependencyTarget("INSTRUCTION", instruction.ref(), "", null, instruction,
                    instruction.contract().businessDefinition().semanticKey());
        }
    }

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
