package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapter;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapterRegistry;
import com.leanowtech.bloge.gateway.solution.PublishedSolutionSnapshot;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import com.leanowtech.bloge.gateway.solution.ScenarioTreeEvaluator;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutionService;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenContractGuard;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Platform-only crash-safe WRITE execution and downstream reconciliation boundary. */
@Service
public final class SolutionWriteExecutionRunner {
    /** Durable reconciliation asset kind consumed by Solution readiness. */
    public static final String RECONCILIATION = "SOLUTION_WRITE_RECONCILIATION";
    private static final String OPERATION = "SOLUTION_WRITE_EXEC";
    private static final Set<String> SANDBOX_ENVIRONMENTS = Set.of("local", "test", "sandbox");
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ReconciliationAdapterRegistry adapters;
    private final ObjectMapper mapper;
    private final SolutionExecutionService execution;
    private final EngineeringHandoffService handoffs;
    private final BusinessGoldenMaterialStore goldenMaterials;

    /** Creates the non-MCP execution boundary; a missing Instruction channel fails closed. */
    @Autowired
    public SolutionWriteExecutionRunner(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ReconciliationAdapterRegistry adapters,
            ObjectMapper mapper,
            ObjectProvider<InstructionDispatchChannel> channels,
            EngineeringHandoffService handoffs,
            BusinessGoldenMaterialStore goldenMaterials) {
        this(states, registry, adapters, mapper, channels.getIfUnique(() ->
                (instruction, values, context) -> {
                    throw new SolutionContractException(
                            "INSTRUCTION_BINDING_UNAVAILABLE", "Instruction execution is unavailable.");
                }), handoffs, goldenMaterials);
    }

    /** Focused constructor used by certification tests with explicit controlled adapters. */
    SolutionWriteExecutionRunner(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ReconciliationAdapterRegistry adapters,
            ObjectMapper mapper,
            InstructionDispatchChannel channel) {
        this(states, registry, adapters, mapper, channel,
                new EngineeringHandoffService(states, registry, mapper), null);
    }

    /** Focused constructor with an explicit handoff lifecycle collaborator. */
    SolutionWriteExecutionRunner(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ReconciliationAdapterRegistry adapters,
            ObjectMapper mapper,
            InstructionDispatchChannel channel,
            EngineeringHandoffService handoffs) {
        this(states, registry, adapters, mapper, channel, handoffs, null);
    }

    /** Focused constructor that resolves protected GOLDEN material before real WRITE execution. */
    SolutionWriteExecutionRunner(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ReconciliationAdapterRegistry adapters,
            ObjectMapper mapper,
            InstructionDispatchChannel channel,
            EngineeringHandoffService handoffs,
            BusinessGoldenMaterialStore goldenMaterials) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.execution = new SolutionExecutionService(registry, mapper, channel);
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
        this.goldenMaterials = goldenMaterials;
    }

    /**
     * Executes the exact current GREEN line once, then reads each selected WRITE effect back.
     *
     * <p>The durable reservation commits before the first write. Any process loss leaves an
     * ambiguous reservation and returns RECOVERY_REQUIRED on subsequent calls; it is never
     * reclaimed automatically.</p>
     */
    public Map<String, Object> execute(String solutionRef, IntegrationRequestContext platformIdentity) {
        requireAuthority(platformIdentity);
        String scope = AgentTddMutationService.scopeKey(platformIdentity);
        AgentTddStoredAsset evidence = states.find(
                        scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef)
                .filter(asset -> "GREEN".equals(asset.data().path("side").asText()))
                .filter(asset -> asset.data().path("businessBacklog").isEmpty())
                .orElseThrow(() -> new AgentTddToolException(
                        "GREEN_BASELINE_ABSENT", "A current GREEN Solution baseline is required."));
        SolutionExecutableSnapshot frozen;
        try {
            frozen = registry.freezeExecutable(scope, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        SolutionEntityRegistry.RegisteredEntity solution = frozen.solutionIdentity();
        AgentTddStoredAsset caseSet = states.find(scope, AgentTddMutationService.CASE_SET,
                        evidence.data().path("caseSetRef").asText())
                .orElseThrow(() -> new AgentTddToolException(
                        "GATE_REJECTED", "The GREEN case set is stale."));
        PublishedSolutionSnapshot executable = frozen.contracts();
        String implementationFingerprint = SolutionImplementationIdentity.fingerprint(
                mapper, executable);
        if (!SolutionEvidenceCurrentness.isCurrent(
                states, mapper, scope, solutionRef, evidence, solution, executable)
                || !frozen.isCurrent(states, scope)) {
            throw new AgentTddToolException("GATE_REJECTED", "The GREEN Solution line is stale.");
        }
        String requestFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "solutionRevision", solution.revision(),
                "solutionContractFingerprint", solution.contractFingerprint(),
                "evidenceFingerprint", evidence.fingerprint(),
                "implementationFingerprint", implementationFingerprint,
                "caseSetRevision", caseSet.revision(),
                "environment", platformIdentity.environmentId()), MAX_BYTES);
        AgentTddStateRepository.ExternalExecutionReservation reservation =
                states.reserveExternalExecution(scope, OPERATION, requestFingerprint, requestFingerprint);
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.IN_PROGRESS) {
            return recovery(solutionRef, evidence, solution, implementationFingerprint,
                    platformIdentity.environmentId());
        }
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.COMPLETED) {
            return persistAndConvert(scope, solutionRef, reservation.response());
        }
        JsonNode completed = states.completeExternalExecution(scope, OPERATION,
                requestFingerprint, requestFingerprint,
                run(scope, solutionRef, caseSet, evidence, solution, executable,
                        implementationFingerprint, platformIdentity));
        return persistAndConvert(scope, solutionRef, completed);
    }

    private JsonNode run(
            String scope,
            String solutionRef,
            AgentTddStoredAsset caseSet,
            AgentTddStoredAsset evidence,
            SolutionEntityRegistry.RegisteredEntity registered,
            PublishedSolutionSnapshot executable,
            String implementationFingerprint,
            IntegrationRequestContext identity) {
        SolutionContract solution = executable.solution();
        List<Map<String, Object>> results = new ArrayList<>();
        int writeCount = 0;
        for (JsonNode metadata : iterable(caseSet.data().path("rows"))) {
            if (!"GOLDEN".equals(metadata.path("category").asText())
                    || !"ACTIVE".equals(metadata.path("lifecycle").asText())) continue;
            JsonNode row = approvedMaterial(scope, metadata, identity);
            ScenarioTreeEvaluator.Outcome outcome = new ScenarioTreeEvaluator(executable.scenarios(), 8)
                    .evaluate(scope, solution.rootScenarioRef(), row.path("given"));
            if (!"INSTRUCTION".equals(outcome.outletKind())) continue;
            InstructionContract instruction = executable.instructions().get(outcome.ref());
            if (instruction == null) {
                throw new AgentTddToolException("REFERENCE_UNRESOLVED", "An Instruction is unavailable.");
            }
            if (instruction.effect() != InstructionContract.Effect.WRITE) continue;
            if (instruction.speccing()) throw new AgentTddToolException(
                    "SPECCING_NOT_EXECUTABLE", "A WRITE Instruction still lacks a binding.");
            execution.executePublished(scope, executable, row.path("given"), identity);
            String keyName = instruction.writeGovernance().reconciliationKey();
            String keyValue = row.path("given").path(keyName).asText();
            if (keyValue.isBlank()) throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "A reconciliation key value is missing.");
            ReconciliationAdapter adapter = adapters.require(
                    instruction.writeGovernance().reconciliationAdapterRef(),
                    instruction.writeGovernance().downstreamSystem());
            ReconciliationAdapter.ObservedEffect observed = adapter.observe(keyValue, row.path("given"));
            JsonNode expected = row.path("expect").path("result");
            JsonNode actual = mapper.valueToTree(observed.effect());
            boolean match = expected.isObject() && contains(actual, expected);
            results.add(Map.of(
                    "caseId", row.path("caseId").asText(),
                    "instructionRef", instruction.instructionRef(),
                    "expectedFingerprint", fingerprint(expected),
                    "observedFingerprint", fingerprint(actual),
                    "match", match));
            writeCount++;
        }
        boolean reconciled = results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("match")));
        ObjectNode response = mapper.createObjectNode();
        response.put("solutionRef", solutionRef);
        response.put("goldenSetId", evidence.data().path("goldenSetId").asText());
        response.put("evidenceFingerprint", evidence.fingerprint());
        response.put("solutionRevision", registered.revision());
        response.put("solutionContractFingerprint", registered.contractFingerprint());
        response.put("implementationFingerprint", implementationFingerprint);
        response.put("environmentId", identity.environmentId());
        response.put("status", reconciled ? "RECONCILED" : "MISMATCH");
        response.put("attestedBy", "system:write-exec-runner");
        response.put("writeCount", writeCount);
        response.set("cases", mapper.valueToTree(results));
        return response;
    }

    /** Resolves payload-bearing case material only inside the platform WRITE boundary. */
    private JsonNode approvedMaterial(String scope, JsonNode metadata, IntegrationRequestContext identity) {
        if (!metadata.path("materialReceipt").isObject()) return metadata;
        if (goldenMaterials == null) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Protected business case material is unavailable.");
        BusinessGoldenContractGuard.requireCurrent(states, scope, metadata);
        JsonNode material = goldenMaterials.read(metadata.path("materialReceipt"), identity);
        if (!metadata.path("goldenCaseFingerprint").asText().equals(
                material.path("goldenCaseFingerprint").asText())) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected business case material does not match its case metadata.");
        }
        ObjectNode approved = (ObjectNode) material.deepCopy();
        approved.put("lifecycle", metadata.path("lifecycle").asText());
        approved.set("expect", material.at("/proposedOracle/expect").deepCopy());
        return approved;
    }

    private Map<String, Object> persistAndConvert(String scope, String solutionRef, JsonNode response) {
        return states.executeAtomically(() -> {
            states.save(scope, RECONCILIATION, solutionRef, response);
            handoffs.closeAfterReconciliation(scope, solutionRef, response);
            return mapper.convertValue(response, OBJECT_MAP);
        });
    }

    private Map<String, Object> recovery(
            String solutionRef,
            AgentTddStoredAsset evidence,
            SolutionEntityRegistry.RegisteredEntity solution,
            String implementationFingerprint,
            String environmentId) {
        return Map.ofEntries(
                Map.entry("solutionRef", solutionRef),
                Map.entry("goldenSetId", evidence.data().path("goldenSetId").asText()),
                Map.entry("evidenceFingerprint", evidence.fingerprint()),
                Map.entry("solutionRevision", solution.revision()),
                Map.entry("solutionContractFingerprint", solution.contractFingerprint()),
                Map.entry("implementationFingerprint", implementationFingerprint),
                Map.entry("environmentId", environmentId),
                Map.entry("status", "RECOVERY_REQUIRED"),
                Map.entry("attestedBy", "system:write-exec-runner"),
                Map.entry("writeCount", 0),
                Map.entry("cases", List.of()));
    }

    private SolutionEntityRegistry.RegisteredEntity registered(String scope, String solutionRef) {
        try {
            return registry.requireRegisteredSolution(scope, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
    }

    private void requireAuthority(IntegrationRequestContext identity) {
        if (identity == null || !IntegrationOperation.AGENT_TDD_WRITE_EXEC.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", "Controlled WRITE authority is required.");
        }
        if (!SANDBOX_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(java.util.Locale.ROOT))) {
            throw new AgentTddToolException("EGRESS_NOT_ALLOWED", "Controlled WRITE is sandbox-only.");
        }
    }

    private String fingerprint(JsonNode value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        if (array.isArray()) array.forEach(result::add);
        return result;
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) {
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey()) || !contains(actual.path(field.getKey()), field.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }
}
