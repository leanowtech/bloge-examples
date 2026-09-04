package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationBackend;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationService;
import com.leanowtech.bloge.gateway.solution.FeatureTokenKeyProvider;
import com.leanowtech.bloge.gateway.solution.FeatureValueTokenService;
import com.leanowtech.bloge.gateway.solution.InMemoryFeatureTokenKeyProvider;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.SolutionAuthoringDecoder;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.ScenarioTreeValidator;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionLowering;
import com.leanowtech.bloge.gateway.solution.SolutionExecutionService;
import com.leanowtech.bloge.gateway.solution.SolutionInvocationService;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-facing application boundary for the Feature, Scenario, Instruction and Solution model.
 *
 * <p>Every mutation is scoped from the authenticated identity and committed with the existing
 * exact-response idempotency authority. Individual entity operations are added here as vertical
 * slices so MCP transport code remains a name-to-method dispatcher.</p>
 */
public final class SolutionAgentTools {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AgentTddStateRepository states;
    private final ObjectMapper mapper;
    private final SolutionAuthoringDecoder decoder;
    private final SolutionEntityRegistry registry;
    private final SolutionLowering lowering;
    private final FeatureEvaluationService featureEvaluation;
    private final SolutionInvocationService invocation;
    private final SolutionLiveInvocationService liveInvocation;
    private final SolutionTestingService testing;
    private final SolutionPerformanceService performance;
    private final EngineeringHandoffService handoffs;
    private final SolutionGovernanceService governance;

    /** Creates the four-entity authoring boundary over the durable Agent TDD store. */
    public SolutionAgentTools(AgentTddStateRepository states, ObjectMapper mapper) {
        this(states, mapper, null);
    }

    /** Creates the production boundary with server BLOGE precompilation enabled. */
    public SolutionAgentTools(
            AgentTddStateRepository states, ObjectMapper mapper, DslImportService importer) {
        this(states, mapper, importer, null, null, null);
    }

    /** Creates a fully wired authoring and runtime boundary with explicit governed adapters. */
    public SolutionAgentTools(
            AgentTddStateRepository states,
            ObjectMapper mapper,
            DslImportService importer,
            FeatureEvaluationBackend featureBackend,
            InstructionDispatchChannel instructionChannel,
            FeatureTokenKeyProvider tokenKeys) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.decoder = new SolutionAuthoringDecoder();
        this.registry = new SolutionEntityRegistry(states, mapper);
        this.lowering = importer == null ? null : new SolutionLowering(registry, importer);
        FeatureTokenKeyProvider safeKeys = tokenKeys == null
                ? InMemoryFeatureTokenKeyProvider.ephemeral() : tokenKeys;
        FeatureValueTokenService tokens = new FeatureValueTokenService(
                mapper, safeKeys, java.time.Clock.systemUTC(), new java.security.SecureRandom());
        FeatureEvaluationBackend safeBackend = featureBackend == null
                ? (feature, inputs, identity) -> {
                    throw new SolutionContractException(
                            "FEATURE_EVALUATOR_UNAVAILABLE", "Feature evaluation is unavailable.");
                } : featureBackend;
        InstructionDispatchChannel safeChannel = instructionChannel == null
                ? (instruction, values, context) -> {
                    throw new SolutionContractException(
                            "INSTRUCTION_BINDING_UNAVAILABLE", "Instruction execution is unavailable.");
                } : instructionChannel;
        this.featureEvaluation = new FeatureEvaluationService(registry, safeBackend, tokens);
        this.invocation = new SolutionInvocationService(registry, tokens,
                new SolutionExecutionService(registry, mapper, safeChannel), mapper);
        this.testing = new SolutionTestingService(states, registry, mapper, safeChannel);
        this.performance = new SolutionPerformanceService(states);
        this.handoffs = new EngineeringHandoffService(states, registry, mapper);
        this.governance = new SolutionGovernanceService(states, registry, mapper);
        this.liveInvocation = new SolutionLiveInvocationService(
                states, invocation, governance, mapper);
    }

    /** Submits the exact current Solution and DSL receipt for independent business review. */
    public Map<String, Object> commitSolution(JsonNode arguments, IntegrationRequestContext identity) {
        return executeOnce("rg.solution.commit", arguments, identity,
                () -> governance.commit(requiredText(arguments, "solutionRef"),
                        requiredText(arguments, "authoringReceiptFingerprint"), identity));
    }

    /** Produces a design-only engineering handoff without granting WRITE execution authority. */
    public Map<String, Object> handoffSolution(JsonNode arguments, IntegrationRequestContext identity) {
        return executeOnce("rg.engineering.handoff", arguments, identity,
                () -> handoffs.submit(requiredText(arguments, "solutionRef"), identity));
    }

    /** Returns live Solution publication gates bound to the current evidence coordinates. */
    public Map<String, Object> readinessSolution(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        return governance.readiness(requiredText(arguments, "solutionRef"), identity);
    }

    /** Returns payload-free rule, disposition, escalation and red-GOLDEN operating signals. */
    public Map<String, Object> performanceSolution(JsonNode arguments, IntegrationRequestContext identity) {
        return performance.performance(requiredText(arguments, "solutionRef"), identity);
    }

    /** Publishes an immutable Solution only through the separately governed purpose. */
    public Map<String, Object> publishSolution(JsonNode arguments, IntegrationRequestContext identity) {
        return executeOnce("rg.solution.publish", arguments, identity,
                () -> governance.publish(requiredText(arguments, "solutionRef"),
                        requiredText(arguments, "signoffRef"), identity));
    }

    /** Evaluates a platform-owned Feature and returns a short-lived proof for Solution invocation. */
    public Map<String, Object> evaluateFeature(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String featureRef = requiredText(arguments, "featureRef");
        JsonNode inputs = requiredObject(arguments, "inputs");
        try {
            FeatureEvaluationService.EvaluationResult result = featureEvaluation.evaluate(
                    AgentTddMutationService.scopeKey(identity), featureRef, inputs, identity);
            return Map.of("featureRef", featureRef, "value", result.value(),
                    "evaluationToken", result.evaluationToken(),
                    "evaluationKind", result.evaluationKind());
        } catch (SolutionContractException failure) {
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
    }

    /** Returns the collection plan for a Solution without executing or collecting any Feature. */
    public Map<String, Object> getSolutionContract(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        try {
            return mapper.convertValue(invocation.contract(
                    AgentTddMutationService.scopeKey(identity), requiredText(arguments, "solutionRef")),
                    OBJECT_MAP);
        } catch (SolutionContractException failure) {
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
    }

    /**
     * Verifies Feature proofs and invokes only a current published Solution.
     *
     * <p>The caller supplies an idempotency key but never WRITE_EXEC authority. The live boundary
     * reserves the exact published implementation before deriving an internal platform identity
     * for the selected Instruction.</p>
     */
    public Map<String, Object> invokeSolution(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        return liveInvocation.invoke(requiredText(arguments, "solutionRef"),
                requiredObject(arguments, "inputs"), requiredText(arguments, "idempotencyKey"), identity);
    }

    /** Runs pure Scenario outlet contract cases using caller-pinned Feature values. */
    public Map<String, Object> testScenario(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        return testing.testScenario(AgentTddMutationService.scopeKey(identity),
                requiredText(arguments, "scenarioRef"), arguments.path("cases"));
    }

    /** Runs the approved Solution GOLDEN line with WRITE effects stubbed and zero real calls. */
    public Map<String, Object> baselineSolution(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        return testing.baseline(AgentTddMutationService.scopeKey(identity),
                requiredText(arguments, "solutionRef"), requiredText(arguments, "caseSetRef"),
                requiredText(arguments, "side"));
    }

    /**
     * Strictly decodes and stores one Feature contract.
     *
     * @param arguments {@code featureYaml} plus a stable {@code idempotencyKey}
     * @param identity authenticated scope and actor
     * @return payload-free canonical registry projection
     */
    public Map<String, Object> defineFeature(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String source = requiredText(arguments, "featureYaml");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode response = states.executeOnce(
                AgentTddMutationService.scopeKey(identity), "rg.feature.define", idempotencyKey, fingerprint,
                () -> mapper.valueToTree(defineFeature(source, identity)));
        return mapper.convertValue(response, OBJECT_MAP);
    }

    /**
     * Strictly decodes and stores one complete Scenario decision contract.
     *
     * @param arguments {@code scenarioYaml}, explicit {@code libraryRefs}, and an idempotency key
     * @param identity authenticated scope and actor
     * @return rule matrix and local tree summary for business review
     */
    public Map<String, Object> defineScenario(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String source = requiredText(arguments, "scenarioYaml");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        if (!arguments.path("libraryRefs").isArray()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "libraryRefs is required.");
        }
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode response = states.executeOnce(
                AgentTddMutationService.scopeKey(identity), "rg.scenario.define", idempotencyKey, fingerprint,
                () -> mapper.valueToTree(defineScenario(source, identity)));
        return mapper.convertValue(response, OBJECT_MAP);
    }

    /**
     * Strictly decodes and stores one result-plus-reasoning Instruction contract.
     *
     * @param arguments {@code instructionYaml} plus a stable {@code idempotencyKey}
     * @param identity authenticated scope and actor
     * @return effect, reconciliation contract and design-state projection
     */
    public Map<String, Object> defineInstruction(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String source = requiredText(arguments, "instructionYaml");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode response = states.executeOnce(
                AgentTddMutationService.scopeKey(identity), "rg.instruction.define",
                idempotencyKey, fingerprint,
                () -> mapper.valueToTree(defineInstruction(source, identity)));
        return mapper.convertValue(response, OBJECT_MAP);
    }

    /**
     * Strictly decodes, resolves and stores one pure Solution contract.
     *
     * <p>All direct Feature, Scenario and Instruction references are resolved within the exact
     * authenticated scope before the revision is stored. The supplied authoring-context
     * fingerprint is returned as a receipt coordinate and later phases can reject its drift.</p>
     *
     * @param arguments solution YAML, context fingerprint, and stable idempotency key
     * @param identity authenticated scope and actor
     * @return canonical input contract and pure-function projection
     */
    public Map<String, Object> composeSolution(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String source = requiredText(arguments, "solutionYaml");
        String contextFingerprint = requiredText(arguments, "authoringContextFingerprint");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode response = states.executeOnce(
                AgentTddMutationService.scopeKey(identity), "rg.solution.compose",
                idempotencyKey, fingerprint,
                () -> mapper.valueToTree(composeSolution(source, contextFingerprint, identity)));
        return mapper.convertValue(response, OBJECT_MAP);
    }

    private Map<String, Object> composeSolution(
            String source, String contextFingerprint, IntegrationRequestContext identity) {
        SolutionAuthoringDecoder.DecodeResult<SolutionContract> decoded =
                decoder.decodeSolution(source.getBytes(StandardCharsets.UTF_8));
        if (!decoded.successful()) {
            throw new AgentTddToolException("COMPILE_ERROR", "Solution contract is invalid.",
                    Map.of("diagnosticCode", decoded.diagnosticCode()));
        }
        String scopeKey = AgentTddMutationService.scopeKey(identity);
        SolutionContract contract = decoded.value();
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        boolean speccing = false;
        ScenarioTreeValidator.ValidationResult tree;
        try {
            contract.inputs().forEach((name, featureRef) -> {
                FeatureContract feature = registry.requireFeature(scopeKey, featureRef);
                inputs.put(name, feature.output().path("type"));
            });
            for (String instructionRef : contract.instructions()) {
                InstructionContract instruction = registry.requireInstruction(scopeKey, instructionRef);
                speccing |= instruction.speccing();
            }
            tree = new ScenarioTreeValidator(registry, 8)
                    .validate(scopeKey, contract.rootScenarioRef());
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException(
                    "REFERENCE_UNRESOLVED", "Referenced solution entity is unavailable.");
        } catch (SolutionContractException failure) {
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
        if (!contract.instructions().containsAll(tree.referencedInstructions())) {
            throw new AgentTddToolException(
                    "SCENARIO_OUTLET_UNRESOLVED", "Scenario instruction outlet is unresolved.");
        }
        SolutionLowering.LoweredSolution lowered;
        try {
            lowered = lowering == null ? null : lowering.lower(scopeKey, contract);
        } catch (SolutionContractException failure) {
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
        String authoringReceiptFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, Map.of(
                        "solutionSourceFingerprint", VisualBundleFingerprint.fromCanonicalValue(
                                mapper, source, MAX_BYTES),
                        "authoringContextFingerprint", contextFingerprint,
                        "contractIdentity", contract.contractIdentity()), MAX_BYTES);
        SolutionEntityRegistry.RegisteredEntity stored =
                registry.upsertSolution(scopeKey, contract, speccing,
                        lowered == null ? null : lowered.draft(), authoringReceiptFingerprint);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("solutionRef", stored.ref());
        result.put("inputContract", Map.copyOf(inputs));
        result.put("scenarioTreeValid", true);
        result.put("precompiled", lowered != null && lowered.precompiled());
        result.put("graphNodeCount", lowered == null ? 0 : lowered.draft().nodes().size());
        result.put("pureFunctionProjection", Map.of(
                "pure", true,
                "rootScenarioRef", contract.rootScenarioRef(),
                "operators", List.of("bloge:scenarioCall", "bloge:instructionCall")));
        result.put("speccing", stored.speccing());
        result.put("authoringContextFingerprint", contextFingerprint);
        result.put("authoringReceiptFingerprint", authoringReceiptFingerprint);
        result.put("revision", stored.revision());
        result.put("contractFingerprint", stored.contractFingerprint());
        result.put("honestVerdict", draftVerdict("Solution"));
        return Map.copyOf(result);
    }

    private Map<String, Object> defineInstruction(String source, IntegrationRequestContext identity) {
        SolutionAuthoringDecoder.DecodeResult<InstructionContract> decoded =
                decoder.decodeInstruction(source.getBytes(StandardCharsets.UTF_8));
        if (!decoded.successful()) {
            throw new AgentTddToolException("COMPILE_ERROR", "Instruction contract is invalid.",
                    Map.of("diagnosticCode", decoded.diagnosticCode()));
        }
        InstructionContract contract = decoded.value();
        SolutionEntityRegistry.RegisteredEntity stored = registry.upsertInstruction(
                AgentTddMutationService.scopeKey(identity), contract);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("instructionId", stored.ref());
        result.put("effect", contract.effect().name());
        result.put("reasoningRequired", contract.reasoningRequired());
        if (contract.writeGovernance() != null) {
            result.put("writeGovernance", contract.writeGovernance());
        }
        result.put("speccing", stored.speccing());
        result.put("revision", stored.revision());
        result.put("contractFingerprint", stored.contractFingerprint());
        result.put("honestVerdict", draftVerdict("Instruction"));
        return Map.copyOf(result);
    }

    private Map<String, Object> defineScenario(String source, IntegrationRequestContext identity) {
        SolutionAuthoringDecoder.DecodeResult<ScenarioContract> decoded =
                decoder.decodeScenario(source.getBytes(StandardCharsets.UTF_8));
        if (!decoded.successful()) {
            throw new AgentTddToolException("COMPILE_ERROR", "Scenario contract is invalid.",
                    Map.of("diagnosticCode", decoded.diagnosticCode()));
        }
        ScenarioContract contract = decoded.value();
        SolutionEntityRegistry.RegisteredEntity stored = registry.upsertScenario(
                AgentTddMutationService.scopeKey(identity), contract);
        Map<String, Object> ruleMatrix = Map.of(
                "conditions", contract.inputs(),
                "rules", contract.rules(),
                "otherwise", contract.otherwise());
        Map<String, Object> tree = Map.of(
                "acyclic", !contract.referencedScenarios().contains(contract.scenarioRef()),
                "maxDepth", contract.referencedScenarios().isEmpty() ? 1 : 2,
                "referencedScenarios", contract.referencedScenarios(),
                "referencedInstructions", contract.referencedInstructions());
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioId", stored.ref());
        result.put("ruleMatrix", ruleMatrix);
        result.put("tree", tree);
        result.put("speccing", false);
        result.put("revision", stored.revision());
        result.put("contractFingerprint", stored.contractFingerprint());
        result.put("honestVerdict", draftVerdict("Scenario"));
        return Map.copyOf(result);
    }

    private Map<String, Object> defineFeature(String source, IntegrationRequestContext identity) {
        SolutionAuthoringDecoder.DecodeResult<FeatureContract> decoded =
                decoder.decodeFeature(source.getBytes(StandardCharsets.UTF_8));
        if (!decoded.successful()) {
            throw new AgentTddToolException("COMPILE_ERROR", "Feature contract is invalid.",
                    Map.of("diagnosticCode", decoded.diagnosticCode()));
        }
        SolutionEntityRegistry.RegisteredEntity stored = registry.upsertFeature(
                AgentTddMutationService.scopeKey(identity), decoded.value());
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("featureId", stored.ref());
        result.put("evaluationKind", decoded.value().evaluationKind().name());
        result.put("determinism", decoded.value().determinism().name());
        result.put("speccing", stored.speccing());
        result.put("revision", stored.revision());
        result.put("contractFingerprint", stored.contractFingerprint());
        result.put("honestVerdict", draftVerdict("Feature"));
        return Map.copyOf(result);
    }

    private static Map<String, Object> draftVerdict(String entityKind) {
        return Map.of("dimensions", List.of(
                dimension("contract-syntax", "PASS",
                        "The server accepted and stored the canonical " + entityKind + " contract."),
                dimension("business-correctness", "NOT_PROVEN",
                        "Approved solution GOLDEN cases have not established the intended outcome."),
                dimension("dependency-isolation", "NOT_PROVEN",
                        "No zero-egress feature evaluation evidence was produced by this write."),
                dimension("runtime-governance", "NOT_PROVEN",
                        "No governed solution publication was produced by this write.")));
    }

    private static Map<String, Object> dimension(String name, String status, String limitation) {
        return Map.of("name", name, "status", status, "limitation", limitation);
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.path(field).isTextual()
                || arguments.path(field).asText().trim().isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return arguments.path(field).asText().trim();
    }

    private static JsonNode requiredObject(JsonNode arguments, String field) {
        if (arguments == null || !arguments.path(field).isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return arguments.path(field).deepCopy();
    }

    private Map<String, Object> executeOnce(
            String operation,
            JsonNode arguments,
            IntegrationRequestContext identity,
            java.util.function.Supplier<Map<String, Object>> action) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode response = states.executeOnce(AgentTddMutationService.scopeKey(identity), operation,
                idempotencyKey, fingerprint, () -> mapper.valueToTree(action.get()));
        return mapper.convertValue(response, OBJECT_MAP);
    }
}
