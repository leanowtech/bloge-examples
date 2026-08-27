package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.DecisionTableOperator;
import com.leanowtech.bloge.core.operator.TransformOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Plans mutations from a parsed BLOGE AST and independently recompiles every candidate. */
public final class WorldMutationPlanner {
    public static final int MAX_MUTANTS = 512;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final WorldFragmentTestKit admission;

    public WorldMutationPlanner() { this(new WorldFragmentTestKit()); }
    public WorldMutationPlanner(WorldFragmentTestKit admission) { this.admission = Objects.requireNonNull(admission, "admission"); }

    public WorldMutationPlan plan(WorldSlice slice) { return plan(slice, WorldMutationPlan.Policy.defaults()); }

    public WorldMutationPlan plan(ResourceWorldModel world, WorldSlice slice) {
        return plan(world, slice, WorldMutationPlan.Policy.defaults());
    }

    public WorldMutationPlan plan(ResourceWorldModel world, WorldSlice slice,
                                  WorldMutationPlan.Policy policy) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(slice, "slice");
        if (!world.tenantId().equals(slice.tenantId()) || !world.slices().contains(slice)) {
            throw new IllegalArgumentException("World slice is not part of the requested tenant/world");
        }
        return planInternal(world.worldModelId(), world.revision(), world.fingerprint(), slice, policy);
    }

    public WorldMutationPlan plan(WorldSlice slice, int maxMutants) {
        return plan(slice, new WorldMutationPlan.Policy(maxMutants, false));
    }

    public WorldMutationPlan plan(WorldSlice slice, WorldMutationPlan.Policy policy) {
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(policy, "policy");
        return planInternal("slice:" + slice.logicalContractId(), 1, slice.fingerprint(), slice, policy);
    }

    private WorldMutationPlan planInternal(String worldModelId, long worldRevision,
                                            String worldFingerprint, WorldSlice slice,
                                            WorldMutationPlan.Policy policy) {
        if (policy.maxMutants() > MAX_MUTANTS) throw new IllegalArgumentException("maxMutants exceeds planner bound");
        DefaultOperatorRegistry registry = registry();
        String baselineFragment = slice.behavior().fingerprint();
        List<WorldMutationPlan.PlanningGap> gaps = new ArrayList<>();
        Graph baseline;
        AstNode.GraphDef ast;
        try {
            admission.admit(slice.behavior());
            DslCompiler compiler = new DslCompiler(registry);
            ast = WorldMutationAst.parse(compiler, slice.behavior().source());
            baseline = compiler.compile(ast);
        } catch (RuntimeException rejected) {
            String emptyGraph = ProtocolFingerprint.ofText("world-baseline-unavailable");
            for (WorldMutationPlan.MutationKind kind : WorldMutationPlan.MutationKind.values()) {
                gaps.add(new WorldMutationPlan.PlanningGap(kind,
                        "BASELINE_COMPILATION_REJECTED", "/"));
            }
            return plan(worldModelId, worldRevision, worldFingerprint, slice, policy,
                    baselineFragment, emptyGraph, List.of(), gaps);
        }
        String baselineGraph = GraphArtifactFingerprint.of(MAPPER, baseline);
        List<WorldMutationAst.Site> sites = allSites(ast);
        for (WorldMutationPlan.MutationKind kind : WorldMutationPlan.MutationKind.values()) {
            if (sites.stream().noneMatch(site -> site.kind() == kind)) {
                gaps.add(new WorldMutationPlan.PlanningGap(kind, "NO_SUPPORTED_MUTATION_SITE", "/members"));
            }
        }
        List<WorldMutationPlan.PlannedMutant> mutants = new ArrayList<>();
        Set<String> sourceFingerprints = new LinkedHashSet<>();
        sourceFingerprints.add(ProtocolFingerprint.ofText(slice.behavior().source()));
        for (WorldMutationAst.Site site : sites) {
            if (mutants.size() >= policy.maxMutants()) {
                gaps.add(new WorldMutationPlan.PlanningGap(site.kind(), "MUTANT_LIMIT_REACHED", site.path()));
                continue;
            }
            try {
                AstNode.GraphDef mutatedAst = WorldMutationAst.mutate(ast, site.kind(), site.path());
                String mutatedSource = WorldMutationAst.generate(mutatedAst);
                BlogeFragmentRef candidate = BlogeFragmentRef.frozen(
                        "world-mutant-candidate.bloge", slice.behavior().revision(), mutatedSource,
                        slice.behavior().outputNodeId());
                mutatedSource = candidate.source();
                String sourceFingerprint = ProtocolFingerprint.ofText(mutatedSource);
                if (!sourceFingerprints.add(sourceFingerprint)) {
                    gaps.add(new WorldMutationPlan.PlanningGap(site.kind(), "MUTANT_DUPLICATE_REJECTED", site.path()));
                    continue;
                }
                AstNode.GraphDef reparsed = WorldMutationAst.parse(new DslCompiler(registry), mutatedSource);
                Graph mutantGraph = new DslCompiler(registry).compile(reparsed);
                admission.admit(candidate);
                String graphFingerprint = GraphArtifactFingerprint.of(MAPPER, mutantGraph);
                String content = ProtocolFingerprint.ofText(baselineGraph + "\n" + site.kind()
                        + "\n" + site.path() + "\n" + sourceFingerprint + "\n" + graphFingerprint);
                String targetFingerprint = WorldMutationPlan.targetFingerprintFor(
                        worldFingerprint, slice.fingerprint(), graphFingerprint);
                String id = "world-mutant-%04d".formatted(mutants.size() + 1);
                mutants.add(new WorldMutationPlan.PlannedMutant(id, site.kind(),
                        new WorldMutationPlan.Site(site.kind(), site.path(), site.line(), site.column()),
                        baselineFragment, sourceFingerprint, graphFingerprint, targetFingerprint, content));
            } catch (RuntimeException rejected) {
                gaps.add(new WorldMutationPlan.PlanningGap(site.kind(), "MUTANT_COMPILATION_REJECTED", site.path()));
            }
        }
        if (policy.requireAllKinds()) {
            EnumSet<WorldMutationPlan.MutationKind> present = EnumSet.noneOf(WorldMutationPlan.MutationKind.class);
            mutants.forEach(mutant -> present.add(mutant.kind()));
            for (WorldMutationPlan.MutationKind kind : WorldMutationPlan.MutationKind.values()) {
                if (!present.contains(kind) && gaps.stream().noneMatch(gap -> gap.kind() == kind)) {
                    gaps.add(new WorldMutationPlan.PlanningGap(kind, "NO_SUPPORTED_MUTATION_SITE", "/members"));
                }
            }
        }
        return plan(worldModelId, worldRevision, worldFingerprint, slice, policy,
                baselineFragment, baselineGraph, mutants, gaps);
    }

    /** Rebuilds exactly one executable candidate from the current fragment; the plan remains payload-free. */
    public BlogeFragmentRef regenerate(WorldSlice slice, WorldMutationPlan plan, String mutantId) {
        plan.verifyAgainst(slice);
        Objects.requireNonNull(mutantId, "mutantId");
        WorldMutationPlan.PlannedMutant expected = plan.mutants().stream()
                .filter(mutant -> mutant.mutantId().equals(mutantId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("mutant is not in the reviewed plan"));
        try {
            DefaultOperatorRegistry registry = registry();
            AstNode.GraphDef ast = WorldMutationAst.parse(new DslCompiler(registry), slice.behavior().source());
            String source = WorldMutationAst.generate(WorldMutationAst.mutate(ast, expected.kind(), expected.site().astPath()));
            BlogeFragmentRef candidate = BlogeFragmentRef.frozen(expected.mutantId() + ".bloge",
                    slice.behavior().revision(), source, slice.behavior().outputNodeId());
            source = candidate.source();
            if (!expected.mutantSourceFingerprint().equals(ProtocolFingerprint.ofText(source))) {
                throw new IllegalArgumentException("regenerated mutant source fingerprint differs");
            }
            AstNode.GraphDef reparsed = WorldMutationAst.parse(new DslCompiler(registry), source);
            Graph graph = new DslCompiler(registry).compile(reparsed);
            admission.admit(candidate);
            String graphFingerprint = GraphArtifactFingerprint.of(MAPPER, graph);
            if (!expected.mutantGraphFingerprint().equals(graphFingerprint)
                    || !expected.mutantTargetFingerprint().equals(WorldMutationPlan.targetFingerprintFor(
                    plan.worldFingerprint(), plan.sliceFingerprint(), graphFingerprint))) {
                throw new IllegalArgumentException("regenerated mutant graph fingerprint differs");
            }
            return candidate;
        } catch (RuntimeException rejected) {
            throw new IllegalArgumentException("reviewed World mutant cannot be regenerated", rejected);
        }
    }

    private static WorldMutationPlan plan(String worldModelId, long worldRevision, String worldFingerprint,
                                          WorldSlice slice, WorldMutationPlan.Policy policy, String fragment,
                                          String graph, List<WorldMutationPlan.PlannedMutant> mutants,
                                          List<WorldMutationPlan.PlanningGap> gaps) {
        return new WorldMutationPlan(slice.tenantId(), worldModelId, worldRevision,
                worldFingerprint, slice.fingerprint(), slice.behavior().artifactId(), slice.behavior().revision(),
                fragment, graph, WorldMutationPlan.PLANNER_VERSION, policy, mutants, gaps);
    }

    private static List<WorldMutationAst.Site> allSites(AstNode.GraphDef ast) {
        List<WorldMutationAst.Site> sites = new ArrayList<>();
        for (WorldMutationPlan.MutationKind kind : WorldMutationPlan.MutationKind.values()) {
            sites.addAll(WorldMutationAst.sites(ast, kind));
        }
        return sites.stream().sorted(Comparator.comparing((WorldMutationAst.Site site) -> site.kind().ordinal())
                .thenComparing(WorldMutationAst.Site::path)).toList();
    }

    private static DefaultOperatorRegistry registry() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw(DecisionTableOperator.OPERATOR_REF, DecisionTableOperator.INSTANCE);
        registry.registerRaw(TransformOperator.OPERATOR_REF, TransformOperator.INSTANCE);
        return registry;
    }
}
