package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates a complete World Scenario x mutant matrix and combines it with an independent graph
 * mutation score. The World denominator is every non-equivalent mutant, including inconclusive
 * and survived mutants; no execution result can disappear by being reclassified as equivalent.
 */
public final class WorldMutationEvaluator {
    public static final String DEFAULT_PURPOSE = "WORLD_MUTATION_EQUIVALENCE";

    public enum Mode { CERTIFIABLE, EXPLORATORY }
    public enum GateStatus { PASSED, BLOCKED, WARNING, INCOMPLETE, NOT_APPLICABLE }
    public enum MutantStatus { KILLED, SURVIVED, INCONCLUSIVE, EQUIVALENT }
    public enum EquivalenceSource { NONE, INDEPENDENT_SEMANTIC_PROOF, HUMAN_REVIEW }
    public enum GraphMutantStatus { KILLED, SURVIVED, INCONCLUSIVE, UNCLASSIFIED, EQUIVALENT }

    /** Exact graph mutant closure required by S3-E; aggregate counts alone cannot expose survivors. */
    public record GraphMutantSummary(String mutantId, GraphMutantStatus status,
                                     EquivalenceSource equivalenceSource) {
        public GraphMutantSummary {
            mutantId = stableIdentity(mutantId);
            status = Objects.requireNonNull(status, "status");
            equivalenceSource = equivalenceSource == null ? EquivalenceSource.NONE : equivalenceSource;
            if (status == GraphMutantStatus.EQUIVALENT && equivalenceSource == EquivalenceSource.NONE) {
                throw new IllegalArgumentException("graph equivalent mutant requires a source");
            }
            if (status != GraphMutantStatus.EQUIVALENT && equivalenceSource != EquivalenceSource.NONE) {
                throw new IllegalArgumentException("graph equivalence source is not bound to an equivalent mutant");
            }
        }
    }

    public record GraphLayerInput(List<GraphMutantSummary> mutants,
                                  TestSuiteRunEvidenceV5.MutationScoreStatus status,
                                  List<String> reasons, List<String> naReasons) {
        public GraphLayerInput {
            mutants = List.copyOf(mutants == null ? List.of() : mutants);
            status = Objects.requireNonNull(status, "status");
            reasons = sortedCodes(reasons);
            naReasons = sortedCodes(naReasons);
            Set<String> ids = new LinkedHashSet<>();
            for (GraphMutantSummary mutant : mutants) {
                if (!ids.add(mutant.mutantId())) throw new IllegalArgumentException("duplicate graph mutant id");
            }
            if (mutants.isEmpty() && naReasons.isEmpty()) {
                throw new IllegalArgumentException("empty graph input requires an N/A reason");
            }
        }

        public static GraphLayerInput of(List<GraphMutantSummary> mutants,
                                         TestSuiteRunEvidenceV5.MutationScoreStatus status) {
            return new GraphLayerInput(mutants, status, List.of(), List.of());
        }
    }

    public record Policy(int minimumGraphScoreBasisPoints, int minimumWorldScoreBasisPoints,
                         int maximumInconclusiveMutants, boolean requireNoSurvivors,
                         boolean requireExecutableWorldMutants, String purpose) {
        public Policy {
            if (minimumGraphScoreBasisPoints < 0 || minimumGraphScoreBasisPoints > 10_000
                    || minimumWorldScoreBasisPoints < 0 || minimumWorldScoreBasisPoints > 10_000
                    || maximumInconclusiveMutants < 0 || maximumInconclusiveMutants > 512) {
                throw new IllegalArgumentException("mutation gate policy is out of bounds");
            }
            purpose = code(purpose);
        }

        public static Policy defaults() {
            return new Policy(10_000, 10_000, 0, true, true, DEFAULT_PURPOSE);
        }
    }

    public record ScenarioResult(String scenarioId, String mutantId, MutantStatus status,
                                 String evidenceFingerprint, String reasonCode) {
        public ScenarioResult {
            scenarioId = text(scenarioId);
            mutantId = text(mutantId);
            status = Objects.requireNonNull(status, "status");
            evidenceFingerprint = optionalFingerprint(evidenceFingerprint);
            reasonCode = optionalCode(reasonCode);
        }
    }

    public record MutantResult(String mutantId, WorldMutationPlan.MutationKind kind,
                               EquivalenceSource equivalenceSource, MutantStatus status,
                               int scenarioCount, int killedScenarioCount,
                               List<ScenarioResult> scenarios, String reasonCode) {
        public MutantResult {
            mutantId = text(mutantId);
            kind = Objects.requireNonNull(kind, "kind");
            equivalenceSource = Objects.requireNonNull(equivalenceSource, "equivalenceSource");
            status = Objects.requireNonNull(status, "status");
            scenarios = List.copyOf(scenarios == null ? List.of() : scenarios);
            reasonCode = optionalCode(reasonCode);
            if (scenarioCount < 0 || killedScenarioCount < 0 || killedScenarioCount > scenarioCount
                    || scenarioCount != scenarios.size()) {
                throw new IllegalArgumentException("mutant result count closure is invalid");
            }
            Set<String> scenarioIds = new LinkedHashSet<>();
            int actualKilled = 0;
            boolean hasInconclusive = false;
            for (ScenarioResult scenario : scenarios) {
                if (!mutantId.equals(scenario.mutantId()) || !scenarioIds.add(scenario.scenarioId())) {
                    throw new IllegalArgumentException("mutant scenario closure is invalid");
                }
                if (scenario.status() == MutantStatus.KILLED) actualKilled++;
                if (scenario.status() == MutantStatus.INCONCLUSIVE) hasInconclusive = true;
            }
            if (killedScenarioCount != actualKilled) {
                throw new IllegalArgumentException("mutant killed scenario count is invalid");
            }
            MutantStatus expected = actualKilled > 0 ? MutantStatus.KILLED
                    : hasInconclusive ? MutantStatus.INCONCLUSIVE
                    : !scenarios.isEmpty() && scenarios.stream()
                    .allMatch(value -> value.status() == MutantStatus.SURVIVED)
                    ? MutantStatus.SURVIVED : MutantStatus.EQUIVALENT;
            if (status != expected || (status == MutantStatus.EQUIVALENT && !scenarios.isEmpty())) {
                throw new IllegalArgumentException("mutant aggregate status is invalid");
            }
        }
    }

    public record LayerReport(String layer, int planned, int equivalent, int nonEquivalent,
                              int killed, int survived, int inconclusive, int denominator,
                              int scoreBasisPoints, List<String> survivors,
                              List<String> equivalenceSources, List<String> naReasons,
                              boolean complete) {
        public LayerReport {
            layer = text(layer);
            if (!layer.equals("GRAPH") && !layer.equals("WORLD")) {
                throw new IllegalArgumentException("invalid mutation layer");
            }
            if (planned < 0 || equivalent < 0 || nonEquivalent < 0 || killed < 0 || survived < 0
                    || inconclusive < 0 || denominator < 0 || planned != equivalent + nonEquivalent
                    || nonEquivalent != killed + survived + inconclusive
                    || denominator != nonEquivalent || scoreBasisPoints < 0 || scoreBasisPoints > 10_000) {
                throw new IllegalArgumentException("mutation layer count closure is invalid");
            }
            survivors = sortedStableIdentities(survivors);
            equivalenceSources = sortedEquivalenceSources(equivalenceSources);
            naReasons = sortedCodes(naReasons);
            if (survivors.size() != survived || new LinkedHashSet<>(survivors).size() != survivors.size()) {
                throw new IllegalArgumentException("mutation survivor closure is invalid");
            }
            if (equivalenceSources.size() != equivalent) {
                throw new IllegalArgumentException("mutation equivalence source closure is invalid");
            }
        }
    }

    public record GateReport(Mode mode, GateStatus status, LayerReport graph, LayerReport world,
                             List<MutantResult> mutants, List<String> reasons) {
        public GateReport {
            mode = Objects.requireNonNull(mode, "mode");
            status = Objects.requireNonNull(status, "status");
            graph = Objects.requireNonNull(graph, "graph");
            world = Objects.requireNonNull(world, "world");
            mutants = List.copyOf(mutants == null ? List.of() : mutants);
            if (mutants.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("mutation report contains a null mutant");
            }
            if (!"GRAPH".equals(graph.layer()) || !"WORLD".equals(world.layer())) {
                throw new IllegalArgumentException("mutation report layers are invalid");
            }
            validateWorldClosure(world, mutants);
            reasons = sortedCodes(reasons);
        }
    }

    private final WorldMutationReceiptLedger ledger;
    private final WorldMutationEquivalenceAuthority authority;

    public WorldMutationEvaluator() {
        this(new WorldMutationReceiptLedger(), WorldMutationEquivalenceAuthority.rejecting());
    }

    public WorldMutationEvaluator(WorldMutationReceiptLedger ledger) {
        this(ledger, WorldMutationEquivalenceAuthority.rejecting());
    }

    public WorldMutationEvaluator(WorldMutationEquivalenceAuthority authority) {
        this(new WorldMutationReceiptLedger(), authority);
    }

    public WorldMutationEvaluator(WorldMutationReceiptLedger ledger,
                                  WorldMutationEquivalenceAuthority authority) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    public GateReport evaluate(WorldMutationPlan plan,
                               WorldMutationMatrix.ScenarioMutantMatrix matrix,
                               TestSuiteRunEvidenceV5.MutationScoreVerdict graphScore,
                               List<WorldMutationEquivalenceReceipt> receipts,
                               Mode mode, Policy policy) {
        Objects.requireNonNull(graphScore, "graphScore");
        return evaluate(plan, matrix, graphInput(graphScore), receipts, mode, policy);
    }

    public GateReport evaluate(WorldMutationPlan plan,
                               WorldMutationMatrix.ScenarioMutantMatrix matrix,
                               GraphLayerInput graphInput,
                               List<WorldMutationEquivalenceReceipt> receipts,
                               Mode mode, Policy policy) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(graphInput, "graphInput");
        Mode actualMode = mode == null ? Mode.CERTIFIABLE : mode;
        Policy actualPolicy = policy == null ? Policy.defaults() : policy;
        if (!actualPolicy.purpose().equals(DEFAULT_PURPOSE)
                && actualPolicy.purpose().length() > 128) {
            throw new WorldMutationException(WorldMutationException.Code.INVALID_INPUT);
        }
        Map<String, WorldMutationEquivalenceReceipt> equivalent = consumeReceipts(
                plan, receipts == null ? List.of() : receipts, actualPolicy.purpose());
        Map<String, WorldMutationMatrix.ScenarioRef> scenarios = new LinkedHashMap<>();
        for (WorldMutationMatrix.ScenarioRef scenario : matrix.scenarios()) {
            scenarios.put(scenario.scenarioId(), scenario);
        }
        validateMatrix(plan, matrix, scenarios);

        List<MutantResult> results = new ArrayList<>();
        for (WorldMutationPlan.PlannedMutant mutant : plan.mutants()) {
            WorldMutationEquivalenceReceipt receipt = equivalent.get(mutant.mutantId());
            if (receipt != null) {
                results.add(new MutantResult(mutant.mutantId(), mutant.kind(),
                        sourceOf(receipt), MutantStatus.EQUIVALENT, 0, 0, List.of(), ""));
                continue;
            }
            List<ScenarioResult> scenarioResults = new ArrayList<>();
            for (WorldMutationMatrix.ScenarioRef scenario : matrix.scenarios()) {
                WorldMutationMatrix.Observation observation = matrix.observations().stream()
                        .filter(value -> value.scenarioId().equals(scenario.scenarioId())
                                && value.mutantId().equals(mutant.mutantId())).findFirst().orElseThrow();
                MutantStatus status = switch (observation.status()) {
                    case ASSERTION_FAILED -> MutantStatus.KILLED;
                    case PASSED -> MutantStatus.SURVIVED;
                    case MOCKED -> MutantStatus.SURVIVED;
                    case EXECUTION_FAILED, TIMEOUT, SKIPPED, CANCELLED -> MutantStatus.INCONCLUSIVE;
                };
                scenarioResults.add(new ScenarioResult(scenario.scenarioId(), mutant.mutantId(), status,
                        observation.evidenceFingerprint(), observation.diagnosticCode()));
            }
            int killed = (int) scenarioResults.stream().filter(value -> value.status() == MutantStatus.KILLED).count();
            boolean incomplete = scenarioResults.stream().anyMatch(value -> value.status() == MutantStatus.INCONCLUSIVE);
            MutantStatus aggregate = killed > 0 ? MutantStatus.KILLED
                    : incomplete ? MutantStatus.INCONCLUSIVE
                    : scenarioResults.stream().allMatch(value -> value.status() == MutantStatus.SURVIVED)
                    ? MutantStatus.SURVIVED : MutantStatus.INCONCLUSIVE;
            results.add(new MutantResult(mutant.mutantId(), mutant.kind(), EquivalenceSource.NONE,
                    aggregate, scenarioResults.size(), killed, scenarioResults,
                    aggregate == MutantStatus.SURVIVED ? "SURVIVED" : ""));
        }
        LayerReport world = worldLayer(plan, results);
        LayerReport graph = graphLayer(graphInput);
        return new GateReport(actualMode, gateStatus(actualMode, graph, world, actualPolicy),
                graph, world, results, gateReasons(graph, world, actualPolicy, graphInput.reasons()));
    }

    private Map<String, WorldMutationEquivalenceReceipt> consumeReceipts(
            WorldMutationPlan plan, List<WorldMutationEquivalenceReceipt> receipts, String purpose) {
        Map<String, WorldMutationEquivalenceReceipt> accepted = new LinkedHashMap<>();
        for (WorldMutationEquivalenceReceipt receipt : receipts) {
            WorldMutationPlan.PlannedMutant mutant = plan.mutants().stream()
                    .filter(value -> value.mutantId().equals(receipt.mutantId())).findFirst()
                    .orElseThrow(() -> new WorldMutationException(
                            WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID));
            if (accepted.putIfAbsent(receipt.mutantId(), receipt) != null) {
                throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
            }
            receipt.verifyFor(plan.tenantId(), plan, mutant, purpose);
            WorldMutationEquivalenceAuthority.Verification verification;
            try {
                verification = authority.verify(receipt, plan.tenantId(), plan, mutant, purpose);
            } catch (RuntimeException ex) {
                throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
            }
            ledger.consume(verification, receipt, plan.tenantId(), plan, mutant, purpose);
        }
        return accepted;
    }

    private static void validateMatrix(WorldMutationPlan plan,
                                       WorldMutationMatrix.ScenarioMutantMatrix matrix,
                                       Map<String, WorldMutationMatrix.ScenarioRef> scenarios) {
        int expected = scenarios.size() * plan.mutants().size();
        if (matrix.observations().size() != expected) {
            throw new WorldMutationException(WorldMutationException.Code.MATRIX_INCOMPLETE);
        }
        Set<String> mutantIds = plan.mutants().stream().map(WorldMutationPlan.PlannedMutant::mutantId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (WorldMutationMatrix.Observation observation : matrix.observations()) {
            WorldMutationMatrix.ScenarioRef scenario = scenarios.get(observation.scenarioId());
            WorldMutationPlan.PlannedMutant mutant = plan.mutants().stream()
                    .filter(value -> value.mutantId().equals(observation.mutantId())).findFirst().orElse(null);
            if (scenario == null || mutant == null || !mutantIds.contains(observation.mutantId())
                    || !scenario.scenarioFingerprint().equals(observation.scenarioFingerprint())
                    || !mutant.mutantTargetFingerprint().equals(observation.mutantTargetFingerprint())) {
                throw new WorldMutationException(WorldMutationException.Code.MATRIX_INCOMPLETE);
            }
        }
    }

    private static LayerReport worldLayer(WorldMutationPlan plan, List<MutantResult> results) {
        int equivalent = (int) results.stream().filter(value -> value.status() == MutantStatus.EQUIVALENT).count();
        List<MutantResult> executable = results.stream().filter(value -> value.status() != MutantStatus.EQUIVALENT).toList();
        int killed = (int) executable.stream().filter(value -> value.status() == MutantStatus.KILLED).count();
        int survived = (int) executable.stream().filter(value -> value.status() == MutantStatus.SURVIVED).count();
        int inconclusive = executable.size() - killed - survived;
        List<String> survivors = executable.stream().filter(value -> value.status() == MutantStatus.SURVIVED)
                .map(MutantResult::mutantId).toList();
        List<String> sources = results.stream().filter(value -> value.equivalenceSource() != EquivalenceSource.NONE)
                .map(value -> value.equivalenceSource().name()).toList();
        List<String> naReasons = new ArrayList<>();
        if (plan.mutants().isEmpty()) naReasons.add("WORLD_MUTATION_NO_MUTANTS");
        else if (executable.isEmpty()) naReasons.add("ALL_MUTANTS_EQUIVALENT");
        int denominator = executable.size();
        int score = denominator == 0 ? 0 : killed * 10_000 / denominator;
        return new LayerReport("WORLD", plan.mutants().size(), equivalent, executable.size(), killed,
                survived, inconclusive, denominator, score, survivors, sources, naReasons,
                inconclusive == 0 && !plan.mutants().isEmpty());
    }

    private static LayerReport graphLayer(GraphLayerInput input) {
        int planned = input.mutants().size();
        int equivalent = count(input.mutants(), GraphMutantStatus.EQUIVALENT);
        int killed = count(input.mutants(), GraphMutantStatus.KILLED);
        int survived = count(input.mutants(), GraphMutantStatus.SURVIVED);
        int inconclusive = count(input.mutants(), GraphMutantStatus.INCONCLUSIVE);
        int unclassified = count(input.mutants(), GraphMutantStatus.UNCLASSIFIED);
        int nonEquivalent = planned - equivalent;
        int denominator = nonEquivalent;
        int score = denominator == 0 ? 0 : killed * 10_000 / denominator;
        List<String> reasons = new ArrayList<>(input.reasons());
        if (survived > 0) reasons.add("GRAPH_SURVIVORS_PRESENT");
        List<String> survivors = input.mutants().stream()
                .filter(value -> value.status() == GraphMutantStatus.SURVIVED)
                .map(GraphMutantSummary::mutantId).toList();
        List<String> sources = input.mutants().stream()
                .filter(value -> value.status() == GraphMutantStatus.EQUIVALENT)
                .map(value -> value.equivalenceSource().name()).toList();
        List<String> naReasons = new ArrayList<>(input.naReasons());
        if (planned == 0 && naReasons.isEmpty()) naReasons.add("GRAPH_MUTATION_NO_MUTANTS");
        boolean complete = input.status() != TestSuiteRunEvidenceV5.MutationScoreStatus.NOT_EVALUATED
                && input.status() != TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE
                && unclassified == 0;
        return new LayerReport("GRAPH", planned, equivalent, nonEquivalent, killed, survived,
                inconclusive + unclassified, denominator, score, survivors, sources, naReasons, complete);
    }

    private static GateStatus gateStatus(Mode mode, LayerReport graph, LayerReport world, Policy policy) {
        boolean optionalWorldNA = !policy.requireExecutableWorldMutants()
                && world.nonEquivalent() == 0
                && !world.naReasons().isEmpty();
        if (optionalWorldNA && graphHealthy(graph, policy)) return GateStatus.NOT_APPLICABLE;
        if (graph.naReasons().size() > 0 || world.naReasons().size() > 0) {
            return mode == Mode.CERTIFIABLE ? GateStatus.BLOCKED : GateStatus.WARNING;
        }
        boolean failed = !graph.complete() || !world.complete()
                || graph.scoreBasisPoints() < policy.minimumGraphScoreBasisPoints()
                || world.scoreBasisPoints() < policy.minimumWorldScoreBasisPoints()
                || graph.inconclusive() > policy.maximumInconclusiveMutants()
                || world.inconclusive() > policy.maximumInconclusiveMutants()
                || (policy.requireNoSurvivors() && (graph.survived() > 0 || world.survived() > 0));
        if (!failed) return GateStatus.PASSED;
        return mode == Mode.CERTIFIABLE ? GateStatus.BLOCKED : GateStatus.WARNING;
    }

    private static List<String> gateReasons(LayerReport graph, LayerReport world, Policy policy,
                                            List<String> graphInputReasons) {
        List<String> reasons = new ArrayList<>();
        reasons.addAll(graphInputReasons);
        reasons.addAll(graph.naReasons());
        reasons.addAll(world.naReasons());
        boolean optionalWorldNA = !policy.requireExecutableWorldMutants()
                && world.nonEquivalent() == 0 && !world.naReasons().isEmpty();
        if (!graph.complete()) reasons.add("GRAPH_LAYER_INCOMPLETE");
        if (!optionalWorldNA && !world.complete()) reasons.add("WORLD_LAYER_INCOMPLETE");
        if (graph.scoreBasisPoints() < policy.minimumGraphScoreBasisPoints()) reasons.add("GRAPH_SCORE_BELOW_THRESHOLD");
        if (!optionalWorldNA && world.scoreBasisPoints() < policy.minimumWorldScoreBasisPoints()) {
            reasons.add("WORLD_SCORE_BELOW_THRESHOLD");
        }
        if (world.nonEquivalent() == 0 && !world.naReasons().isEmpty()
                && policy.requireExecutableWorldMutants()) {
            reasons.add("WORLD_EXECUTABLE_MUTANTS_REQUIRED");
        }
        if (graph.inconclusive() > policy.maximumInconclusiveMutants()) {
            reasons.add("GRAPH_INCONCLUSIVE_LIMIT_EXCEEDED");
        }
        if (world.inconclusive() > policy.maximumInconclusiveMutants()) reasons.add("WORLD_INCONCLUSIVE_LIMIT_EXCEEDED");
        if (policy.requireNoSurvivors() && (graph.survived() > 0 || world.survived() > 0)) {
            reasons.add("MUTATION_SURVIVOR_FORBIDDEN");
        }
        return reasons;
    }

    private static boolean graphHealthy(LayerReport graph, Policy policy) {
        return graph.naReasons().isEmpty() && graph.complete()
                && graph.scoreBasisPoints() >= policy.minimumGraphScoreBasisPoints()
                && graph.inconclusive() <= policy.maximumInconclusiveMutants()
                && (!policy.requireNoSurvivors() || graph.survived() == 0);
    }

    private static void validateWorldClosure(LayerReport world, List<MutantResult> mutants) {
        if (mutants.size() != world.planned()) {
            throw new IllegalArgumentException("mutation mutant count closure is invalid");
        }
        Set<String> ids = new LinkedHashSet<>();
        List<String> survivors = new ArrayList<>();
        List<String> equivalenceSources = new ArrayList<>();
        int equivalent = 0;
        int killed = 0;
        int survived = 0;
        int inconclusive = 0;
        for (MutantResult mutant : mutants) {
            String mutantId = stableIdentity(mutant.mutantId());
            if (!ids.add(mutantId)) throw new IllegalArgumentException("duplicate mutation mutant id");
            if (mutant.status() == MutantStatus.EQUIVALENT) {
                if (mutant.equivalenceSource() == EquivalenceSource.NONE
                        || mutant.scenarioCount() != 0 || mutant.killedScenarioCount() != 0) {
                    throw new IllegalArgumentException("mutation equivalent result is invalid");
                }
                equivalent++;
                equivalenceSources.add(mutant.equivalenceSource().name());
            } else {
                if (mutant.equivalenceSource() != EquivalenceSource.NONE || mutant.scenarioCount() < 1) {
                    throw new IllegalArgumentException("mutation non-equivalent result is invalid");
                }
                switch (mutant.status()) {
                    case KILLED -> {
                        if (mutant.killedScenarioCount() < 1) {
                            throw new IllegalArgumentException("mutation killed result is invalid");
                        }
                        killed++;
                    }
                    case SURVIVED -> {
                        if (mutant.killedScenarioCount() != 0) {
                            throw new IllegalArgumentException("mutation survived result is invalid");
                        }
                        survived++;
                        survivors.add(mutantId);
                    }
                    case INCONCLUSIVE -> {
                        if (mutant.killedScenarioCount() != 0) {
                            throw new IllegalArgumentException("mutation inconclusive result is invalid");
                        }
                        inconclusive++;
                    }
                    case EQUIVALENT -> throw new AssertionError("equivalent result handled above");
                }
            }
        }
        if (equivalent != world.equivalent() || killed != world.killed()
                || survived != world.survived() || inconclusive != world.inconclusive()) {
            throw new IllegalArgumentException("mutation result statistics are invalid");
        }
        if (!world.survivors().equals(sortedStableIdentities(survivors))) {
            throw new IllegalArgumentException("mutation survivor IDs are invalid");
        }
        if (!world.equivalenceSources().equals(equivalenceSources.stream().sorted().toList())) {
            throw new IllegalArgumentException("mutation equivalence sources are invalid");
        }
    }

    private static GraphLayerInput graphInput(TestSuiteRunEvidenceV5.MutationScoreVerdict score) {
        if (score.survivedMutants() > 0) {
            throw new WorldMutationException(WorldMutationException.Code.GATE_INCOMPLETE);
        }
        List<GraphMutantSummary> mutants = new ArrayList<>();
        addLegacy(mutants, "killed", score.killedMutants(), GraphMutantStatus.KILLED);
        addLegacy(mutants, "inconclusive", score.inconclusiveMutants(), GraphMutantStatus.INCONCLUSIVE);
        addLegacy(mutants, "unclassified", score.unclassifiedMutants(), GraphMutantStatus.UNCLASSIFIED);
        addLegacy(mutants, "equivalent", score.equivalentMutantsExcluded(), GraphMutantStatus.EQUIVALENT);
        return new GraphLayerInput(mutants, score.status(), score.reasons(),
                score.plannedMutants() == 0 ? List.of("GRAPH_MUTATION_NO_MUTANTS") : List.of());
    }

    private static void addLegacy(List<GraphMutantSummary> target, String prefix, int count,
                                  GraphMutantStatus status) {
        for (int index = 1; index <= count; index++) {
            target.add(new GraphMutantSummary("legacy-graph-" + prefix + "-" + index, status,
                    EquivalenceSource.NONE));
        }
    }

    private static int count(List<GraphMutantSummary> mutants, GraphMutantStatus status) {
        return (int) mutants.stream().filter(value -> value.status() == status).count();
    }

    private static EquivalenceSource sourceOf(WorldMutationEquivalenceReceipt receipt) {
        return receipt.source() == WorldMutationEquivalenceReceipt.Source.HUMAN_REVIEW
                ? EquivalenceSource.HUMAN_REVIEW : EquivalenceSource.INDEPENDENT_SEMANTIC_PROOF;
    }

    private static List<String> sortedStrings(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .map(WorldMutationEvaluator::text).sorted().toList();
    }

    private static List<String> sortedCodes(List<String> values) {
        return sortedStrings(values).stream().map(WorldMutationEvaluator::code).toList();
    }

    private static List<String> sortedStableIdentities(List<String> values) {
        return sortedStrings(values).stream().map(WorldMutationEvaluator::stableIdentity).toList();
    }

    private static List<String> sortedEquivalenceSources(List<String> values) {
        return sortedStrings(values).stream().map(value -> {
            try {
                EquivalenceSource source = EquivalenceSource.valueOf(value);
                if (source == EquivalenceSource.NONE) throw new IllegalArgumentException("NONE is not an equivalence proof");
                return source.name();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid equivalence source");
            }
        }).toList();
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("invalid identity");
        return value.trim();
    }

    private static String stableIdentity(String value) {
        String normalized = text(value);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("invalid stable identity");
        }
        return normalized;
    }
    private static String code(String value) {
        String normalized = text(value);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,127}")) throw new IllegalArgumentException("invalid code");
        return normalized;
    }
    private static String optionalCode(String value) {
        return value == null || value.isBlank() ? "" : code(value);
    }
    private static String optionalFingerprint(String value) {
        if (value == null || value.isBlank()) return "";
        if (!value.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("invalid fingerprint");
        return value;
    }
}
