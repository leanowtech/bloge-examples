package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.ScenarioTreeEvaluator;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import com.leanowtech.bloge.gateway.solution.SolutionExecutionService;
import com.leanowtech.bloge.gateway.solution.journey.BusinessFixtureCompiler;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenContractGuard;
import com.leanowtech.bloge.gateway.solution.journey.ControlledFeatureAdapter;
import com.leanowtech.bloge.gateway.solution.journey.ControlledInstructionAdapter;
import com.leanowtech.bloge.gateway.solution.journey.ControlledTestEgressGuard;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs Scenario contract tests and approved Solution GOLDEN baselines with zero real egress. */
public final class SolutionTestingService {
    /** Durable payload-free Solution baseline evidence kind. */
    public static final String SOLUTION_EVIDENCE = "SOLUTION_EVIDENCE";
    /** Version of the controlled Scenario/Solution test compiler represented in evidence. */
    public static final String COMPILER_VERSION = "rg.solution-controlled-test.v1";
    /** External-call policy enforced by every controlled Solution baseline. */
    public static final String EGRESS_POLICY = "DENY_ALL";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;
    private final BusinessGoldenMaterialStore goldenMaterials;
    private final ControlledTestEgressGuard egressGuard;
    private final Consumer<SolutionExecutableSnapshot> snapshotObserver;

    /** Creates a testing pyramid over the canonical entities and shared approved-case repository. */
    public SolutionTestingService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel instructionChannel) {
        this(states, registry, mapper, instructionChannel, null);
    }

    /** Creates the business testing boundary with protected GOLDEN material resolution. */
    public SolutionTestingService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel instructionChannel,
            BusinessGoldenMaterialStore goldenMaterials) {
        this(states, registry, mapper, instructionChannel, goldenMaterials,
                new ControlledTestEgressGuard(), ignored -> { });
    }

    /** Creates a testing boundary with an explicit deny-all egress probe. */
    public SolutionTestingService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel instructionChannel,
            BusinessGoldenMaterialStore goldenMaterials,
            ControlledTestEgressGuard egressGuard) {
        this(states, registry, mapper, instructionChannel, goldenMaterials, egressGuard,
                ignored -> { });
    }

    /**
     * Creates a baseline boundary with a package-visible post-freeze observer for race testing.
     * The runtime channel is accepted for constructor compatibility, validated, then discarded;
     * no controlled baseline object retains an authority that can perform real dispatch.
     */
    SolutionTestingService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel instructionChannel,
            BusinessGoldenMaterialStore goldenMaterials,
            ControlledTestEgressGuard egressGuard,
            Consumer<SolutionExecutableSnapshot> snapshotObserver) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(instructionChannel, "instructionChannel");
        this.goldenMaterials = goldenMaterials;
        this.egressGuard = Objects.requireNonNull(egressGuard, "egressGuard");
        this.snapshotObserver = Objects.requireNonNull(snapshotObserver, "snapshotObserver");
    }

    /** Evaluates explicit Feature values against expected Scenario outlet subsets. */
    public Map<String, Object> testScenario(String scopeKey, String scenarioRef, JsonNode cases) {
        if (cases == null || !cases.isArray() || cases.isEmpty()) throw schemaFailure();
        List<Map<String, Object>> byCase = new ArrayList<>();
        ScenarioTreeEvaluator evaluator = new ScenarioTreeEvaluator(registry, 8);
        cases.forEach(row -> {
            String caseId = requiredText(row, "caseId");
            if (!row.path("given").isObject() || !row.path("expect").isObject()) throw schemaFailure();
            ScenarioTreeEvaluator.Outcome result;
            try {
                result = evaluator.evaluate(scopeKey, scenarioRef, row.path("given"));
            } catch (SolutionContractException failure) {
                throw new AgentTddToolException(failure.code(), failure.getMessage());
            }
            ObjectNode actual = mapper.createObjectNode();
            actual.put("outletKind", result.outletKind());
            actual.put("ref", result.ref());
            actual.put("terminalKind", result.terminalKind());
            actual.set("bind", mapper.valueToTree(result.bind()));
            boolean pass = contains(actual, row.path("expect"));
            byCase.add(Map.of("caseId", caseId, "hitRuleId", result.rulePath().getLast(),
                    "outlet", actual, "pass", pass));
        });
        long passed = byCase.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        return Map.of("scenarioRef", scenarioRef, "byCase", byCase,
                "passed", passed, "failed", byCase.size() - passed, "realExternalCalls", 0);
    }

    /** Runs every approved ACTIVE GOLDEN row against the pure Solution and persists one evidence view. */
    public Map<String, Object> baseline(
            String scopeKey, String solutionRef, String caseSetRef, String side) {
        return baseline(scopeKey, solutionRef, caseSetRef, side, null);
    }

    /** Runs a baseline with the caller identity used only to resolve protected case material. */
    public Map<String, Object> baseline(String scopeKey, String solutionRef, String caseSetRef,
                                        String side, IntegrationRequestContext identity) {
        return baseline(scopeKey, solutionRef, caseSetRef, side, identity, null);
    }

    /** Runs a baseline bound to an optional server-locked business journey coordinate. */
    public Map<String, Object> baseline(String scopeKey, String solutionRef, String caseSetRef,
                                        String side, IntegrationRequestContext identity,
                                        BaselineContext baselineContext) {
        String normalizedSide = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        if (baselineContext != null) baselineContext.requireComplete();
        if (!List.of("RED", "GREEN").contains(normalizedSide)) throw schemaFailure();
        AgentTddStoredAsset caseSet = states.find(scopeKey, AgentTddMutationService.CASE_SET, caseSetRef)
                .filter(asset -> solutionRef.equals(asset.data().path("toolRef").asText()))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Solution case set was not found."));
        return states.executeAtomically(() -> baselineLocked(
                scopeKey, solutionRef, caseSetRef, normalizedSide, caseSet.revision(), identity,
                baselineContext));
    }

    private Map<String, Object> baselineLocked(
            String scopeKey,
            String solutionRef,
            String caseSetRef,
            String normalizedSide,
            long expectedRevision,
            IntegrationRequestContext identity,
            BaselineContext baselineContext) {
        AgentTddStoredAsset caseSet = states.lockRevision(
                scopeKey, AgentTddMutationService.CASE_SET, caseSetRef, expectedRevision);
        if (!solutionRef.equals(caseSet.data().path("toolRef").asText())) {
            throw new AgentTddToolException("DRAFT_NOT_FOUND", "Solution case set was not found.");
        }
        List<JsonNode> golden = new ArrayList<>();
        caseSet.data().path("rows").forEach(row -> {
            if ("GOLDEN".equals(row.path("category").asText())
                    && "ACTIVE".equals(row.path("lifecycle").asText())
                    && (row.path("expect").isObject() || row.path("materialReceipt").isObject())) golden.add(row);
        });
        if (golden.isEmpty()) throw new AgentTddToolException(
                "GOLDEN_REQUIRES_APPROVAL", "Approved Solution GOLDEN cases are required.");
        SolutionExecutableSnapshot executable = freezeExecutable(scopeKey, solutionRef);
        SolutionEntityRegistry.RegisteredEntity expectedSolution = executable.solutionIdentity();
        SolutionContract solutionContract = executable.contracts().solution();
        String implementationFingerprint = SolutionImplementationIdentity.fingerprint(
                mapper, executable.contracts());
        List<Map<String, Object>> frozenFeatures = frozenContracts(
                executable, SolutionEntityRegistry.FEATURE);
        List<Map<String, Object>> frozenScenarios = frozenContracts(
                executable, SolutionEntityRegistry.SCENARIO);
        List<Map<String, Object>> frozenInstructions = frozenContracts(
                executable, SolutionEntityRegistry.INSTRUCTION);
        List<Map<String, Object>> frozenExecutable = frozenContracts(executable, null);
        List<JsonNode> approvedRows = golden.stream()
                .map(metadata -> approvedMaterial(scopeKey, solutionRef, metadata, identity))
                .toList();
        snapshotObserver.accept(executable);
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> backlog = new ArrayList<>();
        List<Map.Entry<String, String>> controlledPlans = new ArrayList<>();
        LinkedHashMap<String, Integer> hitDistribution = new LinkedHashMap<>();
        for (JsonNode row : approvedRows) {
            String caseId = requiredText(row, "caseId");
            String compiledPlan = row.path("controlledAssumptionPlanFingerprint").asText();
            controlledPlans.add(Map.entry(caseId, compiledPlan.isBlank()
                    ? VisualBundleFingerprint.fromCanonicalValue(mapper,
                    Map.of("caseId", caseId, "controlledAssumptions",
                            row.path("controlledAssumptions")), MAX_BYTES)
                    : compiledPlan));
            SolutionExecutionService.ExecutionResult result;
            try {
                if (row.path("controlledAssumptions").isObject()) {
                    ControlledFeatureAdapter.Resolution features = new ControlledFeatureAdapter(
                            egressGuard).resolve(solutionContract, row.path("given"),
                            row.path("controlledAssumptions"));
                    result = features.failed()
                            ? new SolutionExecutionService.ExecutionResult(
                            features.failureResult(), features.reasoning(), "", List.of(), 0)
                            : controlledExecution(row).simulateControlledPublished(
                            scopeKey, executable.contracts(), features.values());
                } else {
                    egressGuard.verifyBeforeCase();
                    result = controlledExecution(row).simulatePublished(
                            scopeKey, executable.contracts(), row.path("given"));
                }
            } catch (SolutionContractException failure) {
                throw new AgentTddToolException(failure.code(), failure.getMessage());
            }
            ObjectNode actual = mapper.createObjectNode();
            actual.set("result", mapper.valueToTree(result.result()));
            actual.put("reasoning", result.reasoning());
            actual.put("instructionRef", result.instructionRef());
            boolean pass = contains(actual, row.path("expect"));
            String verdict = normalizedSide + "_" + (pass ? "PASS" : "FAIL");
            cases.add(Map.of("caseId", caseId, "verdict", verdict,
                    "instructionRef", result.instructionRef(), "rulePath", result.rulePath()));
            hitDistribution.merge(result.instructionRef().isBlank() ? "TERMINAL" : result.instructionRef(), 1,
                    Integer::sum);
            if (!pass) backlog.add(Map.of("caseId", caseId, "reason", verdict,
                    "owner", row.path("oracleOwner").asText("business-owner")));
        }
        long persistedCaseRevision = caseSet.revision();
        if ("GREEN".equals(normalizedSide)) {
            java.util.Set<String> passing = cases.stream()
                    .filter(row -> "GREEN_PASS".equals(row.get("verdict")))
                    .map(row -> row.get("caseId").toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            ObjectNode updated = (ObjectNode) caseSet.data().deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode rows = updated.putArray("rows");
            caseSet.data().path("rows").forEach(raw -> {
                ObjectNode row = (ObjectNode) raw.deepCopy();
                if (passing.contains(row.path("caseId").asText())) row.put("qualityState", "READY");
                rows.add(row);
            });
            persistedCaseRevision = states.saveIfRevision(scopeKey, AgentTddMutationService.CASE_SET,
                    caseSetRef, caseSet.revision(), updated).revision();
        }
        List<String> orderedGoldenFingerprints = golden.stream()
                .map(row -> row.path("goldenCaseFingerprint").asText(
                        VisualBundleFingerprint.fromCanonicalValue(mapper, row, MAX_BYTES)))
                .sorted().toList();
        String goldenSetId = VisualBundleFingerprint.fromCanonicalValue(
                mapper, orderedGoldenFingerprints, MAX_BYTES);
        List<String> controlledPlanFingerprints = controlledPlans.stream()
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        String planFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, controlledPlanFingerprints, MAX_BYTES);
        String scopeFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, scopeKey, MAX_BYTES);
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("scopeFingerprint", scopeFingerprint);
        if (baselineContext != null) {
            evidence.put("journeyRef", baselineContext.journeyRef());
            evidence.put("journeyRevision", baselineContext.journeyRevision());
            evidence.put("solutionContextFingerprint", baselineContext.solutionContextFingerprint());
        }
        evidence.put("solutionRef", solutionRef);
        evidence.put("caseSetRef", caseSetRef);
        evidence.put("caseSetRevision", persistedCaseRevision);
        evidence.put("solutionRevision", expectedSolution.revision());
        evidence.put("solutionContractFingerprint", expectedSolution.contractFingerprint());
        evidence.put("implementationFingerprint", implementationFingerprint);
        evidence.put("goldenSetId", goldenSetId);
        evidence.set("orderedGoldenCaseFingerprints", mapper.valueToTree(orderedGoldenFingerprints));
        evidence.set("controlledAssumptionPlanFingerprints",
                mapper.valueToTree(controlledPlanFingerprints));
        evidence.put("planFingerprint", planFingerprint);
        evidence.set("frozenFeatureContracts", mapper.valueToTree(frozenFeatures));
        evidence.set("frozenScenarioContracts", mapper.valueToTree(frozenScenarios));
        evidence.set("frozenInstructionContracts", mapper.valueToTree(frozenInstructions));
        evidence.set("frozenExecutableContracts", mapper.valueToTree(frozenExecutable));
        evidence.put("compilerVersion", COMPILER_VERSION);
        evidence.put("egressPolicy", EGRESS_POLICY);
        evidence.put("side", normalizedSide);
        evidence.set("cases", mapper.valueToTree(cases));
        evidence.set("businessBacklog", mapper.valueToTree(backlog));
        evidence.set("hitDistribution", mapper.valueToTree(hitDistribution));
        evidence.put("realExternalCalls", 0);
        AgentTddStoredAsset stored = states.save(scopeKey, SOLUTION_EVIDENCE, solutionRef, evidence);
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("solutionRef", solutionRef);
        response.put("caseSetRef", caseSetRef);
        response.put("caseSetRevision", persistedCaseRevision);
        response.put("solutionRevision", expectedSolution.revision());
        response.put("solutionContractFingerprint", expectedSolution.contractFingerprint());
        response.put("implementationFingerprint", implementationFingerprint);
        response.put("scopeFingerprint", scopeFingerprint);
        if (baselineContext != null) {
            response.put("journeyRef", baselineContext.journeyRef());
            response.put("journeyRevision", baselineContext.journeyRevision());
            response.put("solutionContextFingerprint", baselineContext.solutionContextFingerprint());
        }
        response.put("planFingerprint", planFingerprint);
        response.put("compilerVersion", COMPILER_VERSION);
        response.put("egressPolicy", EGRESS_POLICY);
        response.put("goldenSetId", goldenSetId);
        response.put("evidenceRef", stored.assetRef() + "@" + stored.revision());
        response.put("side", normalizedSide);
        response.put("byLayer", Map.of("integration", Map.of(
                "pass", cases.size() - backlog.size(), "fail", backlog.size())));
        response.put("cases", cases);
        response.put("businessBacklog", backlog);
        response.put("realExternalCalls", 0);
        response.put("status", backlog.isEmpty() ? "GO" : "NO_GO");
        return Map.copyOf(response);
    }

    /**
     * Captures all four entity stores at one repository read point, resolves the recursive closure
     * only from that detached view, then locks the exact coordinate vector before execution.
     */
    private SolutionExecutableSnapshot freezeExecutable(String scopeKey, String solutionRef) {
        AgentTddStateRepository.AssetReadSnapshot snapshot = states.readSnapshot(scopeKey, List.of(
                SolutionEntityRegistry.FEATURE, SolutionEntityRegistry.SCENARIO,
                SolutionEntityRegistry.INSTRUCTION, SolutionEntityRegistry.SOLUTION));
        SolutionExecutableSnapshot executable;
        try {
            executable = new SolutionEntityRegistry(
                    new SnapshotStateRepository(snapshot), mapper)
                    .freezeExecutable(scopeKey, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED",
                    "A referenced business contract is unavailable.");
        }
        executable.coordinates().forEach(coordinate -> {
            AgentTddStoredAsset locked = states.lockRevision(scopeKey, coordinate.storageKind(),
                    coordinate.ref(), coordinate.revision());
            if (!coordinate.contractFingerprint().equals(
                    locked.data().path("contractFingerprint").asText())) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "Executable Solution closure changed during baseline execution.");
            }
        });
        return executable;
    }

    /** Projects stable payload-free evidence coordinates without re-reading the registry. */
    private static List<Map<String, Object>> frozenContracts(
            SolutionExecutableSnapshot executable, String storageKind) {
        return executable.coordinates().stream()
                .filter(coordinate -> storageKind == null || storageKind.equals(coordinate.storageKind()))
                .sorted(java.util.Comparator.comparing(SolutionExecutableSnapshot.EntityCoordinate::storageKind)
                        .thenComparing(SolutionExecutableSnapshot.EntityCoordinate::ref))
                .map(coordinate -> Map.<String, Object>of(
                        "assetKind", coordinate.storageKind(),
                        "assetRef", coordinate.ref(),
                        "revision", coordinate.revision(),
                        "contractFingerprint", coordinate.contractFingerprint()))
                .toList();
    }

    private JsonNode approvedMaterial(String scopeKey, String solutionRef, JsonNode metadata,
                                      IntegrationRequestContext identity) {
        if (!metadata.path("materialReceipt").isObject()) return metadata;
        if (goldenMaterials == null || identity == null) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Protected business case material is unavailable.");
        BusinessGoldenContractGuard.requireCurrent(states, scopeKey, metadata);
        JsonNode material = goldenMaterials.read(metadata.path("materialReceipt"), identity);
        if (!metadata.path("goldenCaseFingerprint").asText().equals(
                material.path("goldenCaseFingerprint").asText())) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected business case material does not match its case metadata.");
        }
        if (!material.path("businessIntent").isTextual()) {
            ObjectNode approved = (ObjectNode) material.deepCopy();
            approved.put("lifecycle", metadata.path("lifecycle").asText());
            approved.set("expect", material.at("/proposedOracle/expect").deepCopy());
            return approved;
        }
        if (!metadata.path("businessCaseFingerprint").asText().equals(
                material.path("businessCaseFingerprint").asText())) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected business case material does not match its case metadata.");
        }
        BusinessFixtureCompiler.ControlledAssumptionPlan plan =
                new BusinessFixtureCompiler(states, mapper).compile(scopeKey, solutionRef, material);
        ObjectNode approved = mapper.createObjectNode();
        approved.put("caseId", requiredText(material, "caseId"));
        approved.put("oracleOwner", requiredText(material, "oracleOwner"));
        approved.put("lifecycle", metadata.path("lifecycle").asText());
        approved.set("given", plan.given());
        approved.set("controlledAssumptions", plan.dependencyAssumptions());
        ObjectNode expect = approved.putObject("expect");
        expect.set("result", material.at("/expectedOutcome/result").deepCopy());
        expect.set("reasoning", material.at("/expectedOutcome/reasoningClass").deepCopy());
        approved.put("controlledAssumptionPlanFingerprint", plan.planFingerprint());
        approved.put("featureValuesFingerprint", plan.featureValuesFingerprint());
        approved.put("dependencyPlanFingerprint", plan.dependencyPlanFingerprint());
        approved.put("frozenContextFingerprint", plan.frozenContextFingerprint());
        approved.set("businessContractVector", mapper.valueToTree(plan.businessContractVector()));
        return approved;
    }

    /** Builds a case-scoped channel with no reference to the governed runtime channel. */
    private SolutionExecutionService controlledExecution(JsonNode row) {
        JsonNode assumptions = row.path("controlledAssumptions");
        if (!assumptions.isObject()) return new SolutionExecutionService(
                registry, mapper, egressGuard.deniedInstructionChannel());
        return new SolutionExecutionService(
                registry, mapper, new ControlledInstructionAdapter(assumptions, mapper));
    }

    /** Read-only adapter that prevents existing registry decoders from escaping one asset snapshot. */
    private static final class SnapshotStateRepository implements AgentTddStateRepository {
        private final AssetReadSnapshot snapshot;

        private SnapshotStateRepository(AssetReadSnapshot snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef) {
            return snapshot.scopeKey().equals(scopeKey)
                    ? snapshot.find(kind, assetRef) : Optional.empty();
        }

        @Override
        public List<AgentTddStoredAsset> list(String scopeKey, String kind) {
            return snapshot.scopeKey().equals(scopeKey) ? snapshot.list(kind) : List.of();
        }

        @Override
        public AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data) {
            throw readOnly();
        }

        @Override
        public AgentTddStoredAsset saveIfRevision(
                String scopeKey, String kind, String assetRef, long expectedRevision, JsonNode data) {
            throw readOnly();
        }

        @Override
        public Optional<JsonNode> replay(
                String scopeKey, String operation, String idempotencyKey, String requestFingerprint) {
            throw readOnly();
        }

        @Override
        public void record(String scopeKey, String operation, String idempotencyKey,
                           String requestFingerprint, JsonNode response) {
            throw readOnly();
        }

        @Override
        public JsonNode executeOnce(String scopeKey, String operation, String idempotencyKey,
                                    String requestFingerprint, Supplier<JsonNode> action) {
            throw readOnly();
        }

        @Override
        public ExternalExecutionReservation reserveExternalExecution(
                String scopeKey, String operation, String idempotencyKey, String requestFingerprint) {
            throw readOnly();
        }

        @Override
        public JsonNode completeExternalExecution(String scopeKey, String operation,
                                                  String idempotencyKey, String requestFingerprint,
                                                  JsonNode response) {
            throw readOnly();
        }

        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException("Executable Solution snapshot is read-only");
        }
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

    /** Server-locked journey identity supplied only by the business journey action boundary. */
    public record BaselineContext(
            String journeyRef,
            long journeyRevision,
            String solutionContextFingerprint
    ) {
        /** Rejects incomplete or caller-fabricated-looking coordinates before execution. */
        void requireComplete() {
            if (journeyRef == null || journeyRef.isBlank() || journeyRevision < 1
                    || solutionContextFingerprint == null || solutionContextFingerprint.isBlank()) {
                throw schemaFailure();
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node == null ? "" : node.path(field).asText().trim();
        if (value.isBlank()) throw schemaFailure();
        return value;
    }

    private static AgentTddToolException schemaFailure() {
        return new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", "Solution test cases do not match the declared schema.");
    }
}
