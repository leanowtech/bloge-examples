package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.ast.AstNode.BranchCase;
import com.leanowtech.bloge.dsl.ast.AstNode.BranchDef;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionCondition;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionRule;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionTableDef;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.ast.AstNode.NodeDef;
import com.leanowtech.bloge.dsl.ast.AstNode.TransformDef;
import com.leanowtech.bloge.dsl.ast.AstNode.TransformField;
import com.leanowtech.bloge.dsl.ast.Expression;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.EquivalenceClassification;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.GapCode;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.MutationKind;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.MutationPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.PlannedMutant;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.PlanningGap;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates deterministic bounded mutants from a graph's recoverable BLOGE DSL AST.
 *
 * <p>Generation one mutates only orchestration primitives. It never rewrites an operator
 * reference, operator implementation, external request, fixture, or business payload. The
 * baseline and every candidate are independently compiled with the runtime operator registry;
 * only successful candidates whose complete target identity can be re-derived enter the plan.</p>
 */
public final class TestDslMutationPlanner {
    /** Stable planner generation included in every plan fingerprint. */
    public static final String PLANNER_VERSION = TestMutationCasePlan.PLANNER_VERSION;
    /** Maximum mutants accepted by the public protocol. */
    public static final int MAX_MUTANTS = 128;
    /** Required recoverable source format. */
    public static final String SOURCE_FORMAT = TestMutationCasePlan.SOURCE_FORMAT;
    /** Baseline and candidate verification boundary. */
    public static final String VERIFICATION_MODE = TestMutationCasePlan.VERIFICATION_MODE;

    private final ObjectMapper objectMapper;
    private final OperatorRegistry operatorRegistry;
    private final JsonCodec jsonCodec;
    private final RecoverableDslAstDecoder astDecoder;

    /**
     * @param objectMapper canonical protocol fingerprint mapper
     * @param operatorRegistry exact runtime operator registry used for independent compilation
     */
    public TestDslMutationPlanner(ObjectMapper objectMapper, OperatorRegistry operatorRegistry) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.jsonCodec = JsonCodec.DEFAULT;
        this.astDecoder = new RecoverableDslAstDecoder(objectMapper);
    }

    /**
     * Plans independently compiling pure-DSL mutants for one exact graph target.
     *
     * @param target exact baseline graph target
     * @param graph frozen baseline graph
     * @param dependencyFingerprints frozen resource dependency fingerprints
     * @param maxMutants caller-selected result bound from 1 through 128
     * @return content-addressed authoring plan without executable source text
     */
    public TestMutationCasePlan plan(
            TestExecutionApiRequest.Target target,
            Graph graph,
            Map<String, String> dependencyFingerprints,
            int maxMutants) {
        return prepare(target, graph, dependencyFingerprints, maxMutants).plan();
    }

    /**
     * Regenerates one exact mutant exclusively from the current recoverable baseline source.
     *
     * <p>The caller cannot supply executable DSL or a mutated graph. This method reruns baseline
     * verification and deterministic bounded planning, requires the complete authoring plan to be
     * byte-semantically equal to the reviewed plan, and returns only the server-derived graph whose
     * source, graph-artifact, and composite target fingerprints match the selected coordinate.</p>
     *
     * @param target exact current baseline graph target
     * @param graph frozen current baseline graph
     * @param dependencyFingerprints frozen resource dependency fingerprints
     * @param expectedPlan exact previously reviewed authoring plan
     * @param mutantId plan-local mutant coordinate
     * @return verified in-memory mutant artifact for an isolated server-side runner
     * @throws MutationRegenerationException when the plan or selected coordinate cannot be proven
     */
    public RegeneratedMutant regenerate(
            TestExecutionApiRequest.Target target,
            Graph graph,
            Map<String, String> dependencyFingerprints,
            TestMutationCasePlan expectedPlan,
            String mutantId) {
        List<RegeneratedMutant> regenerated = regenerateAll(
                target, graph, dependencyFingerprints, expectedPlan);
        String selectedId = mutantId == null ? "" : mutantId.trim();
        return regenerated.stream()
                .filter(mutant -> mutant.coordinate().mutantId().equals(selectedId))
                .findFirst()
                .orElseThrow(() -> new MutationRegenerationException(
                        RegenerationFailure.MUTANT_NOT_FOUND,
                        "Selected mutant does not belong to the reviewed plan"));
    }

    /**
     * Atomically regenerates every mutant in one exact reviewed plan.
     *
     * <p>No executable artifact is returned until the current baseline has reproduced the entire
     * reviewed plan and every generated graph has independently matched its source, graph-artifact,
     * and composite target fingerprints. The immutable result preserves plan order so a caller can
     * establish the complete execution closure before it creates a run or acquires runtime work.</p>
     *
     * @param target exact current baseline graph target
     * @param graph frozen current baseline graph
     * @param dependencyFingerprints frozen resource dependency fingerprints
     * @param expectedPlan exact previously reviewed authoring plan
     * @return ordered immutable complete mutant closure
     * @throws MutationRegenerationException when any part of the reviewed closure cannot be proven
     */
    public List<RegeneratedMutant> regenerateAll(
            TestExecutionApiRequest.Target target,
            Graph graph,
            Map<String, String> dependencyFingerprints,
            TestMutationCasePlan expectedPlan) {
        if (expectedPlan == null) {
            throw new MutationRegenerationException(
                    RegenerationFailure.PLAN_REQUIRED, "An exact reviewed mutation plan is required");
        }
        Map<String, String> dependencies = dependencyFingerprints == null
                ? Map.of() : Map.copyOf(dependencyFingerprints);
        PreparedPlan prepared = prepare(target, graph, dependencyFingerprints,
                expectedPlan.policy().maxMutants());
        if (!expectedPlan.equals(prepared.plan())) {
            throw new MutationRegenerationException(RegenerationFailure.PLAN_MISMATCH,
                    "Current deterministic mutation plan differs from the reviewed plan");
        }
        List<RegeneratedMutant> verified = new ArrayList<>(expectedPlan.mutants().size());
        for (PlannedMutant coordinate : expectedPlan.mutants()) {
            Graph regenerated = prepared.artifacts().get(coordinate.mutantId());
            if (regenerated == null) {
                throw new MutationRegenerationException(RegenerationFailure.ARTIFACT_MISSING,
                        "A reviewed mutant was not regenerated with the complete plan");
            }
            String graphFingerprint = GraphArtifactFingerprint.of(objectMapper, regenerated);
            String sourceFingerprint = regenerated.definitionSource() == null ? ""
                    : ProtocolFingerprint.ofText(regenerated.definitionSource().payloadJson());
            String targetFingerprint = targetFingerprint(graphFingerprint, dependencies);
            if (!coordinate.mutantSourceFingerprint().equals(sourceFingerprint)
                    || !coordinate.mutantGraphArtifactFingerprint().equals(graphFingerprint)
                    || !coordinate.mutantTargetFingerprint().equals(targetFingerprint)) {
                throw new MutationRegenerationException(RegenerationFailure.ARTIFACT_MISMATCH,
                        "A regenerated mutant identity differs from the reviewed coordinate");
            }
            verified.add(new RegeneratedMutant(
                    expectedPlan.planFingerprint(), coordinate, regenerated));
        }
        if (prepared.artifacts().size() != verified.size()) {
            throw new MutationRegenerationException(RegenerationFailure.ARTIFACT_MISMATCH,
                    "Regenerated mutant closure differs from the reviewed plan");
        }
        return List.copyOf(verified);
    }

    private PreparedPlan prepare(
            TestExecutionApiRequest.Target target,
            Graph graph,
            Map<String, String> dependencyFingerprints,
            int maxMutants) {
        if (maxMutants < 1 || maxMutants > MAX_MUTANTS) {
            throw new IllegalArgumentException("maxMutants must be between 1 and " + MAX_MUTANTS);
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(graph, "graph");
        Map<String, String> dependencies = dependencyFingerprints == null
                ? Map.of() : Map.copyOf(dependencyFingerprints);
        String baselineGraphFingerprint = GraphArtifactFingerprint.of(objectMapper, graph);
        MutationPolicy policy = new MutationPolicy(PLANNER_VERSION, maxMutants, SOURCE_FORMAT,
                VERIFICATION_MODE, false, false);
        State state = new State();
        GraphDefinitionSource source = graph.definitionSource();
        if (source == null) {
            state.gap(GapCode.RECOVERABLE_DSL_SOURCE_UNAVAILABLE, "/", "");
            return preparedResult(target, "UNAVAILABLE", ProtocolFingerprint.ofText(""),
                    baselineGraphFingerprint, policy, state);
        }
        String sourceFingerprint = ProtocolFingerprint.ofText(source.payloadJson());
        if (!SOURCE_FORMAT.equals(source.format())) {
            state.gap(GapCode.UNSUPPORTED_DSL_SOURCE_FORMAT, "/definitionSource/format", "");
            return preparedResult(target, disclosedSourceFormat(source.format()), sourceFingerprint,
                    baselineGraphFingerprint, policy, state);
        }

        GraphDef baseline = decode(source, state);
        if (baseline == null) {
            return preparedResult(target, source.format(), sourceFingerprint,
                    baselineGraphFingerprint, policy, state);
        }
        if (baseline.members().stream().anyMatch(AstNode.ImportDef.class::isInstance)) {
            state.gap(GapCode.IMPORTED_GRAPH_MUTATION_UNSUPPORTED, "/members", "");
            return preparedResult(target, source.format(), sourceFingerprint,
                    baselineGraphFingerprint, policy, state);
        }
        Graph recompiled = compile(baseline, source.graphVersion());
        if (recompiled == null) {
            state.gap(GapCode.BASELINE_RECOMPILATION_FAILED, "/", "");
            return preparedResult(target, source.format(), sourceFingerprint,
                    baselineGraphFingerprint, policy, state);
        }
        String recompiledFingerprint = GraphArtifactFingerprint.of(objectMapper, recompiled);
        String recompiledTarget = targetFingerprint(recompiledFingerprint, dependencies);
        if (!baselineGraphFingerprint.equals(recompiledFingerprint)
                || !target.fingerprint().equals(recompiledTarget)) {
            state.gap(GapCode.BASELINE_RECOMPILATION_MISMATCH, "/", "");
            return preparedResult(target, source.format(), sourceFingerprint,
                    baselineGraphFingerprint, policy, state);
        }

        List<Candidate> candidates = candidates(baseline, state);
        if (candidates.isEmpty()) {
            state.gap(GapCode.NO_SUPPORTED_MUTATION_SITE, "/members", "");
        }
        Set<String> sourceFingerprints = new LinkedHashSet<>();
        sourceFingerprints.add(sourceFingerprint);
        for (Candidate candidate : candidates) {
            if (state.mutants.size() >= maxMutants) {
                state.gap(GapCode.MUTANT_LIMIT_REACHED, candidate.astPath(), candidate.kind().name());
                break;
            }
            GraphDef mutated;
            String payload;
            try {
                mutated = candidate.mutation().apply(baseline);
                payload = jsonCodec.serialize(mutated);
            } catch (RuntimeException rejected) {
                state.gap(GapCode.MUTANT_COMPILATION_REJECTED,
                        candidate.astPath(), candidate.kind().name());
                continue;
            }
            String mutantSourceFingerprint = ProtocolFingerprint.ofText(payload);
            if (!sourceFingerprints.add(mutantSourceFingerprint)) {
                state.gap(GapCode.MUTANT_DUPLICATE_REJECTED,
                        candidate.astPath(), candidate.kind().name());
                continue;
            }
            Graph mutant = compile(mutated, source.graphVersion());
            if (mutant == null || !graph.name().equals(mutant.name())) {
                state.gap(GapCode.MUTANT_COMPILATION_REJECTED,
                        candidate.astPath(), candidate.kind().name());
                continue;
            }
            String graphFingerprint = GraphArtifactFingerprint.of(objectMapper, mutant);
            String mutantId = "mutant-%03d".formatted(state.mutants.size() + 1);
            state.mutants.add(new PlannedMutant(
                    mutantId,
                    candidate.kind(), candidate.astPath(), candidate.line(), candidate.column(),
                    mutantSourceFingerprint, graphFingerprint,
                    targetFingerprint(graphFingerprint, dependencies),
                    EquivalenceClassification.UNKNOWN));
            state.artifacts.put(mutantId, mutant);
        }
        if (state.mutants.isEmpty() && state.gaps.stream()
                .noneMatch(gap -> gap.code() == GapCode.NO_SUPPORTED_MUTATION_SITE)) {
            state.gap(GapCode.NO_SUPPORTED_MUTATION_SITE, "/members", "");
        }
        return preparedResult(target, source.format(), sourceFingerprint,
                baselineGraphFingerprint, policy, state);
    }

    private GraphDef decode(GraphDefinitionSource source, State state) {
        try {
            return astDecoder.decode(source.payloadJson());
        } catch (RuntimeException ignored) {
            // Converted to a stable payload-free gap below.
        }
        state.gap(GapCode.DSL_SOURCE_DECODE_FAILED, "/definitionSource/payloadJson", "");
        return null;
    }

    private Graph compile(GraphDef graph, String graphVersion) {
        try {
            return new DslCompiler(operatorRegistry).withGraphVersion(graphVersion).compile(graph);
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    private List<Candidate> candidates(GraphDef graph, State state) {
        List<Candidate> candidates = new ArrayList<>();
        for (int memberIndex = 0; memberIndex < graph.members().size(); memberIndex++) {
            AstNode member = graph.members().get(memberIndex);
            String path = "/members/" + memberIndex;
            switch (member) {
                case BranchDef branch -> branchCandidates(
                        candidates, graph, memberIndex, path, branch);
                case DecisionTableDef table -> decisionCandidates(
                        candidates, graph, memberIndex, path, table);
                case TransformDef transform -> transformCandidates(
                        candidates, graph, memberIndex, path, transform);
                case NodeDef node -> nodeCandidates(
                        candidates, graph, memberIndex, path, node);
                case AstNode.ForEachDef ignored -> state.gap(
                        GapCode.NESTED_SCOPE_NOT_EXPANDED, path, "FOREACH");
                case AstNode.LoopDef ignored -> state.gap(
                        GapCode.NESTED_SCOPE_NOT_EXPANDED, path, "LOOP");
                case AstNode.ParallelDef ignored -> state.gap(
                        GapCode.NESTED_SCOPE_NOT_EXPANDED, path, "PARALLEL");
                case AstNode.ExtensionDef extension -> state.gap(
                        GapCode.EXTENSION_MUTATION_UNSUPPORTED, path, extension.kind());
                default -> {
                    // Schema, wait, script, comments, and imports have no generation-one operator.
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static void branchCandidates(
            List<Candidate> candidates,
            GraphDef graph,
            int memberIndex,
            String path,
            BranchDef branch) {
        candidates.add(new Candidate(MutationKind.BRANCH_MODE_TOGGLED,
                path + "/inclusive", branch.line(), branch.column(), ignored -> replaceMember(
                graph, memberIndex, new BranchDef(branch.condition(), branch.cases(),
                        branch.otherwise(), !branch.inclusive(), branch.description(),
                        branch.line(), branch.column()))));
        for (int caseIndex = 0; caseIndex < branch.cases().size(); caseIndex++) {
            BranchCase original = branch.cases().get(caseIndex);
            String replacement = differentBranchTarget(branch, original.target());
            if (replacement == null) {
                continue;
            }
            int selected = caseIndex;
            candidates.add(new Candidate(MutationKind.BRANCH_CASE_TARGET_REPLACED,
                    path + "/cases/" + caseIndex + "/target", branch.line(), branch.column(),
                    ignored -> replaceMember(graph, memberIndex,
                            replaceBranchCase(branch, selected, replacement))));
        }
        if (branch.otherwise() != null) {
            String replacement = differentBranchTarget(branch, branch.otherwise());
            if (replacement != null) {
                candidates.add(new Candidate(MutationKind.BRANCH_OTHERWISE_TARGET_REPLACED,
                        path + "/otherwise", branch.line(), branch.column(), ignored -> replaceMember(
                        graph, memberIndex, new BranchDef(branch.condition(), branch.cases(),
                                replacement, branch.inclusive(), branch.description(),
                                branch.line(), branch.column()))));
            }
        }
    }

    private static void decisionCandidates(
            List<Candidate> candidates,
            GraphDef graph,
            int memberIndex,
            String path,
            DecisionTableDef table) {
        for (int ruleIndex = 0; ruleIndex < table.rules().size(); ruleIndex++) {
            DecisionRule rule = table.rules().get(ruleIndex);
            for (int conditionIndex = 0; conditionIndex < rule.conditions().size(); conditionIndex++) {
                DecisionCondition condition = rule.conditions().get(conditionIndex);
                int selectedRule = ruleIndex;
                int selectedCondition = conditionIndex;
                candidates.add(new Candidate(MutationKind.DECISION_CONDITION_NEGATED,
                        path + "/rules/" + ruleIndex + "/conditions/" + conditionIndex
                                + "/predicate",
                        condition.line(), condition.column(), ignored -> replaceMember(
                        graph, memberIndex, negateDecisionCondition(
                                table, selectedRule, selectedCondition))));
            }
        }
        if (table.hitPolicy() == AstNode.HitPolicyKind.FIRST) {
            for (int index = 0; index + 1 < table.rules().size(); index++) {
                if (table.rules().get(index).isOtherwise()
                        || table.rules().get(index + 1).isOtherwise()) {
                    continue;
                }
                int selected = index;
                candidates.add(new Candidate(MutationKind.DECISION_FIRST_RULE_ORDER_SWAPPED,
                        path + "/rules/" + index, table.rules().get(index).line(),
                        table.rules().get(index).column(), ignored -> replaceMember(
                        graph, memberIndex, swapDecisionRules(table, selected))));
            }
        }
        if (table.hitPolicy() == AstNode.HitPolicyKind.UNIQUE
                || table.hitPolicy() == AstNode.HitPolicyKind.ANY) {
            candidates.add(new Candidate(MutationKind.DECISION_HIT_POLICY_RELAXED,
                    path + "/hitPolicy", table.line(), table.column(), ignored -> replaceMember(
                    graph, memberIndex, new DecisionTableDef(table.id(), table.params(),
                            AstNode.HitPolicyKind.FIRST, table.outputTypeAnnotation(), table.rules(),
                            table.description(), table.line(), table.column()))));
        }
    }

    private static void transformCandidates(
            List<Candidate> candidates,
            GraphDef graph,
            int memberIndex,
            String path,
            TransformDef transform) {
        for (int index = 0; index + 1 < transform.fields().size(); index++) {
            int selected = index;
            TransformField field = transform.fields().get(index);
            candidates.add(new Candidate(MutationKind.TRANSFORM_BINDINGS_SWAPPED,
                    path + "/fields/" + index + "/value", field.line(), field.column(),
                    ignored -> replaceMember(graph, memberIndex,
                            swapTransformBindings(transform, selected))));
        }
    }

    private static void nodeCandidates(
            List<Candidate> candidates,
            GraphDef graph,
            int memberIndex,
            String path,
            NodeDef node) {
        if (node.fallback() != null) {
            candidates.add(new Candidate(MutationKind.FALLBACK_REMOVED,
                    path + "/fallback", node.line(), node.column(), ignored -> replaceMember(
                    graph, memberIndex, copyNode(node, node.retry(), null))));
        }
        if (node.retry() != null && node.retry().attempts() > 0) {
            AstNode.RetryDef retry = new AstNode.RetryDef(node.retry().attempts() - 1,
                    node.retry().backoff(), node.retry().strategy(),
                    node.retry().retryOnCategories());
            candidates.add(new Candidate(MutationKind.RETRY_ATTEMPTS_DECREMENTED,
                    path + "/retry/attempts", node.line(), node.column(), ignored -> replaceMember(
                    graph, memberIndex, copyNode(node, retry, node.fallback()))));
        }
    }

    private static NodeDef copyNode(
            NodeDef node, AstNode.RetryDef retry, AstNode.FallbackDef fallback) {
        return new NodeDef(node.id(), node.operatorRef(), node.input(), node.dependsOn(),
                node.dependsOnDef(), node.timeout(), retry, fallback, node.compensation(),
                node.inputSchema(), node.outputSchema(), node.scope(), node.streaming(),
                node.bufferSize(), node.executionMode(), node.workerTopic(),
                node.upstreamResolutionPolicy(), node.description(), node.line(), node.column());
    }

    private static BranchDef replaceBranchCase(
            BranchDef branch, int caseIndex, String replacementTarget) {
        List<BranchCase> cases = new ArrayList<>(branch.cases());
        BranchCase original = cases.get(caseIndex);
        cases.set(caseIndex, new BranchCase(
                original.value(), replacementTarget, original.description()));
        return new BranchDef(branch.condition(), cases, branch.otherwise(), branch.inclusive(),
                branch.description(), branch.line(), branch.column());
    }

    private static String differentBranchTarget(BranchDef branch, String original) {
        for (BranchCase candidate : branch.cases()) {
            if (!Objects.equals(original, candidate.target())) {
                return candidate.target();
            }
        }
        if (branch.otherwise() != null && !Objects.equals(original, branch.otherwise())) {
            return branch.otherwise();
        }
        return null;
    }

    private static DecisionTableDef negateDecisionCondition(
            DecisionTableDef table, int ruleIndex, int conditionIndex) {
        List<DecisionRule> rules = new ArrayList<>(table.rules());
        DecisionRule rule = rules.get(ruleIndex);
        List<DecisionCondition> conditions = new ArrayList<>(rule.conditions());
        DecisionCondition condition = conditions.get(conditionIndex);
        Expression negated = new Expression.UnaryOp(Expression.UnaryOperator.NOT,
                new Expression.GroupExpr(condition.predicate(),
                        condition.line(), condition.column()),
                condition.line(), condition.column());
        conditions.set(conditionIndex, new DecisionCondition(
                condition.paramName(), negated, condition.line(), condition.column()));
        rules.set(ruleIndex, new DecisionRule(rule.description(), conditions, rule.output(),
                rule.namedOutputs(), rule.isOtherwise(), rule.line(), rule.column()));
        return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                table.outputTypeAnnotation(), rules, table.description(),
                table.line(), table.column());
    }

    private static DecisionTableDef swapDecisionRules(DecisionTableDef table, int index) {
        List<DecisionRule> rules = new ArrayList<>(table.rules());
        DecisionRule first = rules.get(index);
        rules.set(index, rules.get(index + 1));
        rules.set(index + 1, first);
        return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                table.outputTypeAnnotation(), rules, table.description(),
                table.line(), table.column());
    }

    private static TransformDef swapTransformBindings(TransformDef transform, int index) {
        List<TransformField> fields = new ArrayList<>(transform.fields());
        TransformField first = fields.get(index);
        TransformField second = fields.get(index + 1);
        fields.set(index, new TransformField(first.name(), first.typeAnnotation(), second.value(),
                first.description(), first.line(), first.column()));
        fields.set(index + 1, new TransformField(second.name(), second.typeAnnotation(), first.value(),
                second.description(), second.line(), second.column()));
        return new TransformDef(transform.id(), transform.letBindings(), fields,
                transform.description(), transform.line(), transform.column());
    }

    private static GraphDef replaceMember(GraphDef graph, int index, AstNode replacement) {
        List<AstNode> members = new ArrayList<>(graph.members());
        members.set(index, replacement);
        return new GraphDef(graph.name(), List.copyOf(members), graph.inputSchema(),
                graph.outputSchema(), graph.streamingOutputNodeId(), graph.streamingInputs(),
                graph.description(), graph.line(), graph.column());
    }

    private String targetFingerprint(
            String graphFingerprint, Map<String, String> dependencies) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "graphFingerprint", graphFingerprint,
                "resourceDescriptorFingerprints", dependencies));
    }

    private static String disclosedSourceFormat(String sourceFormat) {
        String normalized = sourceFormat == null ? "" : sourceFormat.trim();
        return normalized.isEmpty() || normalized.length() > 128 ? "UNSUPPORTED" : normalized;
    }

    private PreparedPlan preparedResult(
            TestExecutionApiRequest.Target target,
            String sourceFormat,
            String sourceFingerprint,
            String graphFingerprint,
            MutationPolicy policy,
            State state) {
        TestMutationCasePlan.Status status;
        if (state.mutants.isEmpty()) {
            status = TestMutationCasePlan.Status.UNAVAILABLE;
        } else if (state.gaps.isEmpty()) {
            status = TestMutationCasePlan.Status.GENERATED;
        } else {
            status = TestMutationCasePlan.Status.PARTIAL;
        }
        List<PlannedMutant> mutants = List.copyOf(state.mutants);
        List<PlanningGap> gaps = state.sortedGaps();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", TestMutationCasePlan.SCHEMA_VERSION);
        material.put("target", target);
        material.put("sourceFormat", sourceFormat);
        material.put("sourceFingerprint", sourceFingerprint);
        material.put("graphArtifactFingerprint", graphFingerprint);
        material.put("status", status.name());
        material.put("policy", policy);
        material.put("mutants", mutants);
        material.put("gaps", gaps);
        TestMutationCasePlan plan = new TestMutationCasePlan("", target, sourceFormat, sourceFingerprint,
                graphFingerprint, ProtocolFingerprint.of(objectMapper, material), status,
                policy, mutants, gaps);
        return new PreparedPlan(plan, Map.copyOf(state.artifacts));
    }

    /** Stable fail-closed reasons emitted by exact mutant regeneration. */
    public enum RegenerationFailure {
        /** No reviewed plan was supplied. */
        PLAN_REQUIRED,
        /** Current source, dependencies, policy, or generated coordinates drifted. */
        PLAN_MISMATCH,
        /** The requested mutant id is absent from the exact plan. */
        MUTANT_NOT_FOUND,
        /** Deterministic preparation did not produce the selected in-memory graph. */
        ARTIFACT_MISSING,
        /** A regenerated source, graph artifact, or composite target identity drifted. */
        ARTIFACT_MISMATCH
    }

    /** Exception carrying a bounded machine-readable regeneration failure. */
    public static final class MutationRegenerationException extends RuntimeException {
        private final RegenerationFailure failure;

        /**
         * @param failure stable failure category
         * @param message bounded diagnostic without DSL or business payload data
         */
        public MutationRegenerationException(RegenerationFailure failure, String message) {
            super(message);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** @return stable failure category suitable for inconclusive evidence */
        public RegenerationFailure failure() {
            return failure;
        }
    }

    /**
     * Server-internal exact mutant artifact.
     *
     * <p>The graph is intentionally absent from every public mutation-plan protocol. Consumers
     * must keep this value inside the isolated test runtime and must not serialize it as evidence.</p>
     *
     * @param planFingerprint exact reviewed plan identity
     * @param coordinate exact planned mutant coordinate
     * @param graph independently recompiled in-memory mutant graph
     */
    public record RegeneratedMutant(
            String planFingerprint,
            PlannedMutant coordinate,
            Graph graph
    ) {
        /** Validates the server-internal proof closure. */
        public RegeneratedMutant {
            planFingerprint = planFingerprint == null ? "" : planFingerprint.trim();
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            graph = Objects.requireNonNull(graph, "graph");
            if (!planFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Regenerated mutant requires a plan fingerprint");
            }
        }
    }

    private record PreparedPlan(TestMutationCasePlan plan, Map<String, Graph> artifacts) {
        private PreparedPlan {
            plan = Objects.requireNonNull(plan, "plan");
            artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        }
    }

    @FunctionalInterface
    private interface AstMutation {
        GraphDef apply(GraphDef graph);
    }

    private record Candidate(
            MutationKind kind,
            String astPath,
            int line,
            int column,
            AstMutation mutation
    ) {
    }

    private static final class State {
        private final List<PlannedMutant> mutants = new ArrayList<>();
        private final Set<PlanningGap> gaps = new LinkedHashSet<>();
        private final Map<String, Graph> artifacts = new LinkedHashMap<>();

        private void gap(GapCode code, String path, String kind) {
            gaps.add(new PlanningGap(code, path, kind));
        }

        private List<PlanningGap> sortedGaps() {
            return gaps.stream().sorted(java.util.Comparator
                    .comparing((PlanningGap gap) -> gap.code().name())
                    .thenComparing(PlanningGap::astPath)
                    .thenComparing(PlanningGap::mutationKind)).toList();
        }
    }
}
