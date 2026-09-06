package com.leanowtech.bloge.gateway.solution.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.ScenarioTreeEvaluator;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionExecutableSnapshot;
import com.leanowtech.bloge.gateway.solution.journey.BusinessFixtureCompiler;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenContractGuard;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.ControlledFeatureAdapter;
import com.leanowtech.bloge.gateway.solution.journey.ControlledTestEgressGuard;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives and measures the exact business-decision coverage denominator for one Solution.
 *
 * <p>The denominator comes only from {@link SolutionEntityRegistry#freezeExecutable(String,
 * String)}. Structured Scenario contracts are consumed directly; the visual-graph enumerator is
 * not used because its string predicate grammar is not the four-entity Scenario wire contract.
 * Rule and otherwise obligations include every recursively reachable Scenario. Dependency-fault
 * obligations include both supported controlled failures for every WRITE Instruction in the
 * frozen executable closure.</p>
 *
 * <p>Coverage is measured from approved ACTIVE business GOLDEN material under a deny-all egress
 * boundary. The human projection retains stable obligation ids and covering case ids. The Agent
 * projection exposes only fingerprints, dimensions, risks, booleans and aggregate counts.</p>
 */
public final class SolutionCoverageService {
    private static final int MAX_SNAPSHOT_ATTEMPTS = 3;
    private static final int MAX_PERSIST_ATTEMPTS = 3;
    private static final PrincipalRef PLATFORM = new PrincipalRef(
            "rg-solution-coverage", PrincipalKind.SERVICE, "Solution coverage service");

    private final SolutionEntityRegistry registry;
    private final AgentTddStateRepository states;
    private final CoverageInventoryRepository inventories;
    private final ObjectMapper mapper;
    private final BusinessGoldenMaterialStore goldenMaterials;

    /** Creates the denominator service without protected-material access for derivation-only uses. */
    public SolutionCoverageService(
            SolutionEntityRegistry registry,
            AgentTddStateRepository states,
            CoverageInventoryRepository inventories,
            ObjectMapper mapper) {
        this(registry, states, inventories, mapper, null);
    }

    /** Creates the production service with access to approved protected business examples. */
    public SolutionCoverageService(
            SolutionEntityRegistry registry,
            AgentTddStateRepository states,
            CoverageInventoryRepository inventories,
            ObjectMapper mapper,
            BusinessGoldenMaterialStore goldenMaterials) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.states = Objects.requireNonNull(states, "states");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.goldenMaterials = goldenMaterials;
    }

    /**
     * Persists or reuses the current exact Solution coverage denominator.
     *
     * @param caller trusted request identity supplying the enterprise scope
     * @param solutionRef current four-entity Solution reference
     * @return integrity-addressed persisted Inventory revision
     */
    public StoredCoverageInventory derive(
            IntegrationRequestContext caller, String solutionRef) {
        Request request = request(caller, solutionRef);
        return derive(request, freezeCurrent(request.scopeKey(), request.solutionRef())).stored();
    }

    /**
     * Measures approved business examples against the current exact denominator.
     *
     * @param caller trusted identity also used to resolve protected GOLDEN material
     * @param solutionRef current four-entity Solution reference
     * @return human-reviewable matrix with a separately bounded Agent projection
     */
    public CoverageStatus status(
            IntegrationRequestContext caller, String solutionRef) {
        Request request = request(caller, solutionRef);
        SolutionExecutableSnapshot snapshot = freezeCurrent(
                request.scopeKey(), request.solutionRef());
        DerivedInventory derived = derive(request, snapshot);
        Map<String, LinkedHashSet<String>> byObligation = new LinkedHashMap<>();
        derived.stored().inventory().obligations().forEach(obligation ->
                byObligation.put(obligation.obligationId(), new LinkedHashSet<>()));
        approvedCases(request, snapshot).forEach(observation ->
                observation.obligationIds().forEach(obligationId -> {
                    Set<String> caseIds = byObligation.get(obligationId);
                    if (caseIds != null) caseIds.add(observation.caseId());
                }));
        List<CoverageItem> items = derived.stored().inventory().obligations().stream()
                .map(obligation -> {
                    List<String> caseIds = byObligation.get(obligation.obligationId()).stream()
                            .sorted().toList();
                    return new CoverageItem(
                            obligation.obligationId(),
                            CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation),
                            obligation.dimension(), obligation.risk(), !caseIds.isEmpty(), caseIds);
                }).toList();
        int covered = Math.toIntExact(items.stream().filter(CoverageItem::covered).count());
        int highRiskUncovered = Math.toIntExact(items.stream()
                .filter(item -> !item.covered())
                .filter(item -> item.risk() == RiskLevel.HIGH
                        || item.risk() == RiskLevel.CRITICAL).count());
        return new CoverageStatus(
                derived.stored().inventory().inventoryId(),
                derived.stored().inventory().revision(),
                derived.stored().inventory().target().fingerprint(),
                items,
                new CoverageSummary(items.size(), covered,
                        items.size() - covered, highRiskUncovered));
    }

    private DerivedInventory derive(Request request, SolutionExecutableSnapshot snapshot) {
        List<CoverageObligation> obligations = obligations(snapshot);
        List<ExactSourceSnapshotRef> sources = snapshot.coordinates().stream()
                .map(coordinate -> new ExactSourceSnapshotRef(
                        logicalKind(coordinate.storageKind()), coordinate.ref(),
                        coordinate.revision(), coordinate.contractFingerprint()))
                .sorted(Comparator.comparing(ExactSourceSnapshotRef::kind)
                        .thenComparing(ExactSourceSnapshotRef::id)
                        .thenComparingLong(ExactSourceSnapshotRef::revision))
                .toList();
        ExactTargetRef target = new ExactTargetRef(
                TargetKind.SOLUTION, request.solutionRef(),
                snapshot.solutionIdentity().revision(),
                snapshot.solutionIdentity().contractFingerprint());
        String inventoryId = "solution-coverage:" + request.solutionRef();
        for (int attempt = 0; attempt < MAX_PERSIST_ATTEMPTS; attempt++) {
            StoredCoverageInventory head = inventories.findHead(
                    request.enterpriseScope(), inventoryId).orElse(null);
            if (head != null
                    && head.inventory().target().equals(target)
                    && head.inventory().obligations().equals(obligations)
                    && head.inventory().derivationSources().equals(sources)) {
                return new DerivedInventory(head, snapshot);
            }
            long expectedRevision = head == null ? 0 : head.inventory().revision();
            Instant now = Instant.now();
            AuditMetadata metadata = head == null
                    ? new AuditMetadata(now, now, PLATFORM, PLATFORM)
                    : head.inventory().metadata();
            CoverageInventory candidate = new CoverageInventory(
                    CoverageInventory.SCHEMA_VERSION, inventoryId, expectedRevision,
                    request.enterpriseScope(), target, InventoryLifecycle.DRAFT,
                    obligations, sources, ReviewRecord.pending(), metadata);
            StoredCoverageInventory saved = inventories.saveIfRevision(
                    expectedRevision, candidate, PLATFORM).orElse(null);
            if (saved != null) return new DerivedInventory(saved, snapshot);
        }
        throw new AgentTddToolException(
                "CAPABILITY_CONTEXT_STALE",
                "The Solution coverage denominator changed while it was being persisted.");
    }

    private List<CoverageObligation> obligations(SolutionExecutableSnapshot snapshot) {
        ArrayList<CoverageObligation> obligations = new ArrayList<>();
        snapshot.contracts().scenarios().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String scenarioRef = entry.getKey();
                    ScenarioContract scenario = entry.getValue();
                    scenario.rules().stream().sorted(Comparator.comparing(ScenarioContract.Rule::ruleId))
                            .forEach(rule -> obligations.add(obligation(
                                    ruleObligationId(scenarioRef, rule.ruleId()),
                                    ObligationDimension.RULE,
                                    "Rule " + rule.ruleId(),
                                    "Cover rule " + rule.ruleId() + " in Scenario " + scenarioRef + ".",
                                    RiskLevel.HIGH,
                                    List.of("scenario:" + scenarioRef, "rule:" + rule.ruleId()))));
                    obligations.add(obligation(
                            otherwiseObligationId(scenarioRef),
                            ObligationDimension.OTHERWISE,
                            "Otherwise",
                            "Cover the otherwise outcome in Scenario " + scenarioRef + ".",
                            RiskLevel.MEDIUM,
                            List.of("scenario:" + scenarioRef, "otherwise")));
                });
        snapshot.contracts().instructions().entrySet().stream()
                .filter(entry -> entry.getValue().effect() == InstructionContract.Effect.WRITE)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    obligations.add(faultObligation(entry.getKey(), "UNAVAILABLE"));
                    obligations.add(faultObligation(entry.getKey(), "FAILS_WITHOUT_EFFECT"));
                });
        return obligations.stream()
                .sorted(Comparator.comparing(CoverageObligation::obligationId))
                .toList();
    }

    private static CoverageObligation obligation(
            String id,
            ObligationDimension dimension,
            String title,
            String statement,
            RiskLevel risk,
            List<String> tags) {
        return new CoverageObligation(
                id, dimension, title, statement, risk, PLATFORM,
                ObligationSource.SOLUTION_DECISION, ObligationLifecycle.FROZEN,
                null, tags);
    }

    private static CoverageObligation faultObligation(String instructionRef, String outcome) {
        return obligation(
                faultObligationId(instructionRef, outcome),
                ObligationDimension.DEPENDENCY_FAULT,
                "Controlled dependency failure " + outcome,
                "Cover " + outcome + " for WRITE Instruction " + instructionRef + ".",
                RiskLevel.HIGH,
                List.of("instruction:" + instructionRef, "outcome:" + outcome));
    }

    private List<CaseObservation> approvedCases(
            Request request, SolutionExecutableSnapshot snapshot) {
        ArrayList<CaseObservation> observations = new ArrayList<>();
        states.list(request.scopeKey(), AgentTddMutationService.CASE_SET).stream()
                .filter(asset -> request.solutionRef().equals(asset.data().path("toolRef").asText()))
                .sorted(Comparator.comparing(AgentTddStoredAsset::assetRef)
                        .thenComparingLong(AgentTddStoredAsset::revision))
                .forEach(asset -> asset.data().path("rows").forEach(row -> {
                    if (!"GOLDEN".equals(row.path("category").asText())
                            || !"ACTIVE".equals(row.path("lifecycle").asText())
                            || !"APPROVED".equals(row.at("/proposedOracle/status").asText())) return;
                    observations.add(observeCase(request, snapshot, row));
                }));
        return List.copyOf(observations);
    }

    private CaseObservation observeCase(
            Request request, SolutionExecutableSnapshot snapshot, JsonNode metadata) {
        JsonNode material = approvedMaterial(request, metadata);
        JsonNode given;
        JsonNode assumptions;
        JsonNode expected;
        if (material.path("businessIntent").isTextual()) {
            BusinessFixtureCompiler.ControlledAssumptionPlan plan =
                    new BusinessFixtureCompiler(states, mapper).compile(
                            request.scopeKey(), request.solutionRef(), material);
            given = plan.given();
            assumptions = plan.dependencyAssumptions();
            expected = material.path("expectedOutcome");
        } else {
            given = material.path("given");
            assumptions = material.path("controlledAssumptions");
            expected = material.path("expect");
        }
        JsonNode controlledAssumptions = assumptions.isObject()
                ? assumptions : mapper.createObjectNode();
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        ControlledFeatureAdapter.Resolution features = new ControlledFeatureAdapter(
                new ControlledTestEgressGuard()).resolve(
                snapshot.contracts().solution(), given, controlledAssumptions);
        if (!features.failed()) {
            try {
                ScenarioTreeEvaluator.Outcome outcome = new ScenarioTreeEvaluator(
                        snapshot.contracts().scenarios(), 8).evaluate(
                        request.scopeKey(), snapshot.contracts().solution().rootScenarioRef(),
                        features.values());
                covered.addAll(pathObligations(snapshot, outcome.rulePath()));
            } catch (SolutionContractException failure) {
                throw new AgentTddToolException(failure.code(), failure.getMessage());
            }
        }
        controlledAssumptions.fields().forEachRemaining(entry -> {
            if (!"INSTRUCTION".equals(entry.getValue().path("assetKind").asText())) return;
            String outcome = entry.getValue().path("outcome").asText();
            if (faultExpected(expected, outcome)) {
                covered.add(faultObligationId(entry.getKey(), outcome));
            }
        });
        return new CaseObservation(requiredText(metadata, "caseId"), covered);
    }

    private JsonNode approvedMaterial(Request request, JsonNode metadata) {
        if (!metadata.path("materialReceipt").isObject()) return metadata;
        if (goldenMaterials == null) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE",
                "Protected business case material is unavailable for coverage measurement.");
        BusinessGoldenContractGuard.requireCurrent(
                states, request.scopeKey(), metadata);
        JsonNode material = goldenMaterials.read(
                metadata.path("materialReceipt"), request.caller());
        if (!metadata.path("goldenCaseFingerprint").asText().equals(
                material.path("goldenCaseFingerprint").asText())) {
            throw new AgentTddToolException(
                    "FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected business case material does not match its approved metadata.");
        }
        return material;
    }

    private static List<String> pathObligations(
            SolutionExecutableSnapshot snapshot, List<String> rulePath) {
        ArrayList<String> result = new ArrayList<>();
        String scenarioRef = snapshot.contracts().solution().rootScenarioRef();
        for (String ruleId : rulePath) {
            ScenarioContract scenario = snapshot.contracts().scenarios().get(scenarioRef);
            if (scenario == null) throw new AgentTddToolException(
                    "REFERENCE_UNRESOLVED", "A covered Scenario is absent from the frozen Solution.");
            ScenarioContract.Outlet outlet;
            if ("otherwise".equals(ruleId)) {
                result.add(otherwiseObligationId(scenarioRef));
                outlet = scenario.otherwise();
            } else {
                ScenarioContract.Rule rule = scenario.rules().stream()
                        .filter(candidate -> ruleId.equals(candidate.ruleId()))
                        .findFirst().orElseThrow(() -> new AgentTddToolException(
                                "REFERENCE_UNRESOLVED",
                                "A covered rule is absent from the frozen Solution."));
                result.add(ruleObligationId(scenarioRef, ruleId));
                outlet = rule.outlet();
            }
            if (outlet.kind() == ScenarioContract.OutletKind.SUB_SCENARIO) {
                scenarioRef = outlet.ref();
            }
        }
        return List.copyOf(result);
    }

    private SolutionExecutableSnapshot freezeCurrent(String scopeKey, String solutionRef) {
        for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            try {
                SolutionExecutableSnapshot snapshot = registry.freezeExecutable(scopeKey, solutionRef);
                if (snapshot.isCurrent(states, scopeKey)) return snapshot;
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new AgentTddToolException(
                        "REFERENCE_UNRESOLVED",
                        "The Solution or one of its referenced business contracts is unavailable.");
            }
        }
        throw new AgentTddToolException(
                "CAPABILITY_CONTEXT_STALE",
                "The Solution changed while its coverage denominator was being frozen.");
    }

    private static boolean faultExpected(JsonNode expected, String outcome) {
        String expectedStatus = expected.at("/result/dependencyStatus").asText();
        return switch (outcome) {
            case "UNAVAILABLE" -> "UNAVAILABLE".equals(expectedStatus);
            case "FAILS_WITHOUT_EFFECT" -> "FAILED_WITHOUT_EFFECT".equals(expectedStatus);
            default -> false;
        };
    }

    private static Request request(IntegrationRequestContext caller, String solutionRef) {
        Objects.requireNonNull(caller, "caller").requireComplete();
        String ref = solutionRef == null ? "" : solutionRef.trim();
        if (ref.isBlank()) throw new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", "solutionRef is required.");
        return new Request(caller, AgentTddMutationService.scopeKey(caller), ref,
                new EnterpriseScope(
                        caller.tenantId(), caller.organizationId(), caller.projectId(),
                        caller.environmentId(), caller.region()));
    }

    private static String requiredText(JsonNode value, String field) {
        String text = value.path(field).isTextual() ? value.path(field).asText().trim() : "";
        if (text.isBlank()) throw new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", "An approved business case is incomplete.");
        return text;
    }

    private static String logicalKind(String storageKind) {
        return switch (storageKind) {
            case SolutionEntityRegistry.FEATURE -> "FEATURE";
            case SolutionEntityRegistry.SCENARIO -> "SCENARIO";
            case SolutionEntityRegistry.INSTRUCTION -> "INSTRUCTION";
            case SolutionEntityRegistry.SOLUTION -> "SOLUTION";
            default -> throw new IllegalArgumentException("Unsupported Solution entity kind");
        };
    }

    private static String ruleObligationId(String scenarioRef, String ruleId) {
        return "rule:" + scenarioRef + ":" + ruleId;
    }

    private static String otherwiseObligationId(String scenarioRef) {
        return "otherwise:" + scenarioRef;
    }

    private static String faultObligationId(String instructionRef, String outcome) {
        return "fault:" + instructionRef + ":" + outcome;
    }

    /** Human-reviewable status retaining stable obligation and covering-case coordinates. */
    public record CoverageStatus(
            String inventoryId,
            long inventoryRevision,
            String solutionFingerprint,
            List<CoverageItem> obligations,
            CoverageSummary summary) {
        /** Freezes the human coverage matrix. */
        public CoverageStatus {
            inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
            solutionFingerprint = Objects.requireNonNull(solutionFingerprint, "solutionFingerprint");
            obligations = obligations == null ? List.of() : List.copyOf(obligations);
            summary = Objects.requireNonNull(summary, "summary");
            if (inventoryRevision < 1) throw new IllegalArgumentException(
                    "A persisted coverage Inventory revision is required");
        }

        /** Returns the strict payload-free MCP projection without ids or case coordinates. */
        public AgentCoverageStatus agentProjection() {
            return new AgentCoverageStatus(obligations.stream()
                    .map(item -> new AgentCoverageItem(
                            item.obligationFingerprint(), item.dimension(),
                            item.risk(), item.covered()))
                    .toList(), summary);
        }
    }

    /** One human-reviewable obligation row. */
    public record CoverageItem(
            String id,
            String obligationFingerprint,
            ObligationDimension dimension,
            RiskLevel risk,
            boolean covered,
            List<String> byCaseIds) {
        /** Freezes exact ids and covering case coordinates. */
        public CoverageItem {
            id = Objects.requireNonNull(id, "id");
            obligationFingerprint = Objects.requireNonNull(
                    obligationFingerprint, "obligationFingerprint");
            dimension = Objects.requireNonNull(dimension, "dimension");
            risk = Objects.requireNonNull(risk, "risk");
            byCaseIds = byCaseIds == null ? List.of() : List.copyOf(byCaseIds);
        }
    }

    /** Strict Agent-facing obligation row with no rule, Instruction, case or business value. */
    public record AgentCoverageItem(
            String obligationFingerprint,
            ObligationDimension dimension,
            RiskLevel risk,
            boolean covered) {
        /** Validates the payload-free coordinate. */
        public AgentCoverageItem {
            obligationFingerprint = Objects.requireNonNull(
                    obligationFingerprint, "obligationFingerprint");
            dimension = Objects.requireNonNull(dimension, "dimension");
            risk = Objects.requireNonNull(risk, "risk");
        }
    }

    /** Aggregate coverage counts shared by human and Agent projections. */
    public record CoverageSummary(
            int total,
            int covered,
            int uncovered,
            int highRiskUncovered) {
        /** Rejects inconsistent or negative aggregate counts. */
        public CoverageSummary {
            if (total < 0 || covered < 0 || uncovered < 0 || highRiskUncovered < 0
                    || covered + uncovered != total || highRiskUncovered > uncovered) {
                throw new IllegalArgumentException("Coverage summary is inconsistent");
            }
        }
    }

    /** Complete payload-free MCP response body. */
    public record AgentCoverageStatus(
            List<AgentCoverageItem> obligations,
            CoverageSummary summary) {
        /** Freezes the Agent response. */
        public AgentCoverageStatus {
            obligations = obligations == null ? List.of() : List.copyOf(obligations);
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    private record Request(
            IntegrationRequestContext caller,
            String scopeKey,
            String solutionRef,
            EnterpriseScope enterpriseScope) {
    }

    private record DerivedInventory(
            StoredCoverageInventory stored,
            SolutionExecutableSnapshot snapshot) {
    }

    private record CaseObservation(String caseId, Set<String> obligationIds) {
        private CaseObservation {
            caseId = Objects.requireNonNull(caseId, "caseId");
            obligationIds = Set.copyOf(obligationIds);
        }
    }
}
