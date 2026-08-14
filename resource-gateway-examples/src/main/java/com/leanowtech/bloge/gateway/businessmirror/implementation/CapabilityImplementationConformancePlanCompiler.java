package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlPreparation;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure compiler that reverses only Proposal-target controls to one exact implementation binding.
 *
 * <p>All non-target controls, replay values, recorded corpus values, inventory identities, and
 * deterministic service configuration are inherited from the verified baseline MirrorPlan. The
 * compiler rejects every shape that cannot be intercepted by an operator-registry adapter.</p>
 */
final class CapabilityImplementationConformancePlanCompiler {
    static final String AUTHORIZED_PURPOSE = "CAPABILITY_CONFORMANCE";

    private final ObjectMapper mapper;

    CapabilityImplementationConformancePlanCompiler(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    Result compile(
            CompiledMirrorPlan baseline,
            MirrorArtifactRef temporaryCapabilityRef,
            CapabilityImplementationBinding binding,
            String conformanceId,
            String caseCoordinate) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(temporaryCapabilityRef, "temporaryCapabilityRef");
        Objects.requireNonNull(binding, "binding");
        MirrorPlan plan = baseline.plan();
        CompiledExecutionControl source = baseline.executionControl();

        Set<String> targetSites = new LinkedHashSet<>();
        Set<String> targetRuleIds = new LinkedHashSet<>();
        Set<String> nonTargetRuleIds = new LinkedHashSet<>();
        for (MirrorPlan.ExternalBinding external : plan.externalBindings()) {
            boolean target = temporaryCapabilityRef.equals(external.capabilityRef());
            if (target) {
                targetSites.add(external.invocationSiteId());
                targetRuleIds.addAll(external.fixtureRuleRefs());
            } else {
                nonTargetRuleIds.addAll(external.fixtureRuleRefs());
            }
        }
        require(!targetSites.isEmpty(), "CONFORMANCE_TARGET_SITE_MISSING");
        require(!targetRuleIds.isEmpty(), "CONFORMANCE_TARGET_RULE_MISSING");
        Set<String> sharedRules = new LinkedHashSet<>(targetRuleIds);
        sharedRules.retainAll(nonTargetRuleIds);
        require(sharedRules.isEmpty(), "CONFORMANCE_TARGET_RULE_SHARED");

        InvocationInventory inventory = source.inventory();
        Map<RuntimeCoordinate, String> coordinates = new LinkedHashMap<>();
        Set<String> targetOperatorRefs = new LinkedHashSet<>();
        for (String siteId : targetSites) {
            InvocationInventory.Entry entry = inventory.byInvocationSiteId().get(siteId);
            require(entry != null, "CONFORMANCE_TARGET_SITE_UNRESOLVED");
            require(!embedded(entry), "CONFORMANCE_TARGET_EMBEDDED_OPERATOR_UNSUPPORTED");
            targetOperatorRefs.add(entry.node().operatorRef());
            RuntimeCoordinate coordinate = new RuntimeCoordinate(
                    entry.graph().name(), entry.node().id());
            require(coordinates.putIfAbsent(coordinate, siteId) == null,
                    "CONFORMANCE_TARGET_RUNTIME_COORDINATE_AMBIGUOUS");
        }
        for (InvocationInventory.Entry entry : inventory.entries()) {
            if (!targetSites.contains(entry.site().invocationSiteId())
                    && targetOperatorRefs.contains(entry.node().operatorRef())) {
                throw rejected("CONFORMANCE_TARGET_OPERATOR_REF_REUSED");
            }
        }

        Map<String, FixtureRule> transformedById = new LinkedHashMap<>();
        List<FixtureRule> transformedRules = new ArrayList<>();
        for (FixtureRule rule : baseline.fixtureBundle().rules()) {
            FixtureRule transformed = targetRuleIds.contains(rule.ruleId())
                    ? new FixtureRule(rule.schemaVersion(), rule.ruleId(), rule.selector(),
                    FixtureRule.Behavior.real(), rule.consumption(), rule.schemaCheck())
                    : rule;
            transformedRules.add(transformed);
            require(transformedById.putIfAbsent(transformed.ruleId(), transformed) == null,
                    "CONFORMANCE_FIXTURE_RULE_DUPLICATE");
            if (!targetRuleIds.contains(rule.ruleId())) {
                requireIsolated(rule);
            }
        }
        require(transformedById.keySet().containsAll(targetRuleIds),
                "CONFORMANCE_TARGET_RULE_UNRESOLVED");

        FixtureBundle sourceFixture = baseline.fixtureBundle();
        Map<String, Object> metadata = new LinkedHashMap<>(sourceFixture.metadata());
        metadata.put("businessMirrorConformance", Map.of(
                "implementationBindingFingerprint", binding.fingerprint(),
                "baselineMirrorPlanFingerprint", plan.planFingerprint(),
                "caseCoordinate", caseCoordinate));
        FixtureBundle fixture = new FixtureBundle(sourceFixture.schemaVersion(),
                sourceFixture.fixtureBundleId(), sourceFixture.revision(),
                sourceFixture.targetFingerprint(), sourceFixture.classification(),
                sourceFixture.logicalClock(), sourceFixture.randomSeed(), transformedRules,
                sourceFixture.assertions(), metadata);

        Map<String, CompiledExecutionControl.ResolvedControl> controls = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExecutionControl.ResolvedControl> item
                : source.controls().entrySet()) {
            String siteId = item.getKey();
            CompiledExecutionControl.ResolvedControl control = item.getValue();
            if (!targetSites.contains(siteId)) {
                require(control.rules().stream()
                                .noneMatch(rule -> targetRuleIds.contains(rule.ruleId())),
                        "CONFORMANCE_TARGET_RULE_SHARED_BY_CONTROL");
                controls.put(siteId, control);
                continue;
            }
            require(!control.implicitDeny() && !control.rules().isEmpty(),
                    "CONFORMANCE_TARGET_CONTROL_DENIED");
            List<FixtureRule> rules = control.rules().stream()
                    .map(rule -> transformedById.get(rule.ruleId()))
                    .toList();
            require(rules.stream().allMatch(Objects::nonNull),
                    "CONFORMANCE_TARGET_CONTROL_RULE_UNRESOLVED");
            require(rules.stream().allMatch(rule -> targetRuleIds.contains(rule.ruleId())),
                    "CONFORMANCE_TARGET_CONTROL_RULE_MIXED");
            controls.put(siteId, new CompiledExecutionControl.ResolvedControl(
                    control.site(), rules, false));
        }
        require(controls.keySet().containsAll(targetSites),
                "CONFORMANCE_TARGET_CONTROL_UNRESOLVED");

        String fixtureFingerprint = ProtocolFingerprint.ofBounded(
                mapper, fixture, 16 * 1024 * 1024);
        ExecutionControlPreparation preparation = ExecutionControlPreparation.prepare(
                mapper, fixture, inventory);
        List<EffectiveExecutionPlan.ResolvedSite> resolvedSites = source.effectivePlan()
                .resolvedSites().stream().map(site -> targetSites.contains(site.invocationSiteId())
                        ? new EffectiveExecutionPlan.ResolvedSite(site.invocationSiteId(),
                        EffectiveExecutionPlan.Resolution.REAL, FixtureRule.BehaviorKind.REAL,
                        FixtureRule.DoubleBoundary.NODE, site.ruleRefs(), "IMPLEMENTATION")
                        : site).toList();
        Map<String, String> defaults = new TreeMap<>(source.effectivePlan().defaultPolicies());
        defaults.put("attestedImplementationTarget", "EXACT_BINDING_ONLY");
        defaults.put("nonTargetExternalEffects", "FROZEN_MIRROR_CONTROL");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("purpose", AUTHORIZED_PURPOSE);
        material.put("target", fixture.targetFingerprint());
        material.put("fixture", fixtureFingerprint);
        material.put("baselineMirrorPlan", plan.planFingerprint());
        material.put("implementationBinding", binding.artifactRef());
        material.put("targetSites", targetSites.stream().sorted().toList());
        material.put("targetRules", targetRuleIds.stream().sorted().toList());
        material.put("inventory", inventory.entries().stream().map(entry -> Map.of(
                "engineStructuralId", entry.engineStructuralId(),
                "invocationSiteId", entry.site().invocationSiteId(),
                "bindingFingerprint", entry.site().runtimeBindingFingerprint())).toList());
        material.put("resolvedSites", resolvedSites);
        material.put("replayDependencies", source.replayPayloads().planDependencies());
        source.corpusPayloads().servingGenerationToken().ifPresent(token ->
                material.put("mirrorServingGeneration", token));
        material.put("executionServiceBindings", preparation.bindings());
        material.put("defaults", defaults);
        String planFingerprint = ProtocolFingerprint.ofBounded(
                mapper, material, 16 * 1024 * 1024);
        EffectiveExecutionPlan effectivePlan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION,
                "conformance-plan-" + planFingerprint.substring("sha256:".length(), 31),
                planFingerprint, AUTHORIZED_PURPOSE, fixture.targetFingerprint(),
                fixtureFingerprint, resolvedSites, source.replayPayloads().planDependencies(),
                preparation.bindings(), Map.copyOf(defaults), List.of());
        CompiledExecutionControl compiled = preparation.assemble(planFingerprint, effectivePlan,
                controls, transformedRules, inventory, source.replayPayloads(),
                source.corpusPayloads());
        return new Result(fixture, compiled, Set.copyOf(targetSites),
                Set.copyOf(targetOperatorRefs), Map.copyOf(coordinates));
    }

    private static boolean embedded(InvocationInventory.Entry entry) {
        Graph graph = entry.graph();
        return graph.embeddedOperators().containsKey(entry.node().id())
                || graph.embeddedOperators().containsKey(entry.node().operatorRef());
    }

    private static void requireIsolated(FixtureRule rule) {
        boolean unsafe = rule.behavior().kind() == FixtureRule.BehaviorKind.REAL
                || rule.behavior().kind() == FixtureRule.BehaviorKind.SPY
                || rule.behavior().kind() == FixtureRule.BehaviorKind.STREAM
                || rule.consumption().onExhausted()
                == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL
                || rule.consumption().onUnmatched()
                == FixtureRule.UnmatchedAction.ALLOW_REAL;
        require(!unsafe, "CONFORMANCE_NON_TARGET_REAL_FORBIDDEN");
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw rejected(code);
        }
    }

    private static IllegalArgumentException rejected(String code) {
        return new IllegalArgumentException(code);
    }

    record RuntimeCoordinate(String graphName, String nodeId) {
        RuntimeCoordinate {
            graphName = Objects.requireNonNull(graphName, "graphName");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    record Result(
            FixtureBundle fixture,
            CompiledExecutionControl compiled,
            Set<String> targetSiteIds,
            Set<String> targetOperatorRefs,
            Map<RuntimeCoordinate, String> runtimeCoordinates
    ) {
        /**
         * Replaces the preflight-frozen operator only at compiler-proven target sites.
         * The shared test runtime resolves REAL through this inventory, so changing only the
         * engine registry would leave the simulated operator generation in place.
         */
        CompiledExecutionControl bindTargetOperator(Object targetOperator) {
            Objects.requireNonNull(targetOperator, "targetOperator");
            List<InvocationInventory.Entry> entries = compiled.inventory().entries().stream()
                    .map(entry -> targetSiteIds.contains(entry.site().invocationSiteId())
                            ? new InvocationInventory.Entry(entry.graph(), entry.node(), entry.site(),
                            entry.engineStructuralId(), targetOperator)
                            : entry)
                    .toList();
            Map<String, InvocationInventory.Entry> byEngine = new LinkedHashMap<>();
            Map<String, InvocationInventory.Entry> bySite = new LinkedHashMap<>();
            entries.forEach(entry -> {
                byEngine.put(entry.engineStructuralId(), entry);
                bySite.put(entry.site().invocationSiteId(), entry);
            });
            InvocationInventory inventory = new InvocationInventory(entries, byEngine, bySite);
            return new CompiledExecutionControl(compiled.effectivePlan(), compiled.controls(),
                    compiled.rules(), inventory, compiled.replayPayloads(),
                    compiled.corpusPayloads(), compiled.executionServices());
        }
    }
}
