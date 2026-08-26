package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete, immutable output of the pure C2a compiler. */
public record WorldScenarioCompilation(
        FixtureBundle bundle,
        List<WorldDelegateBinding> bindings,
        WorldScenarioSourceMap sourceMap,
        String fingerprint,
        StateAccessPlan stateAccessPlan,
        WorldRunStateDescriptor runStateDescriptor
) {
    public WorldScenarioCompilation(FixtureBundle bundle,
                                    List<WorldDelegateBinding> bindings,
                                    WorldScenarioSourceMap sourceMap,
                                    String fingerprint) {
        this(bundle, bindings, sourceMap, fingerprint, StateAccessPlan.empty(),
                WorldRunStateDescriptor.legacy());
    }

    public WorldScenarioCompilation {
        if (bundle == null || bindings == null || sourceMap == null
                || fingerprint == null || fingerprint.isBlank() || stateAccessPlan == null
                || runStateDescriptor == null) {
            throw invalid();
        }
        try {
            bindings = List.copyOf(bindings);
            validateSemanticConsistency(bindings, stateAccessPlan, runStateDescriptor);
        } catch (WorldScenarioCompilationException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
        fingerprint = fingerprint.trim();
        if (!fingerprint.equals(fingerprintFor(bundle, bindings, sourceMap, stateAccessPlan,
                runStateDescriptor))) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.COMPILATION_FINGERPRINT_MISMATCH);
        }
    }

    /** Recomputes the stable fingerprint without serializing DSL, source, or binding payload. */
    public String recomputedFingerprint() {
        return fingerprintFor(bundle, bindings, sourceMap, stateAccessPlan, runStateDescriptor);
    }

    public boolean fingerprintMatches() {
        return fingerprint.equals(recomputedFingerprint());
    }

    public WorldScenarioCompilation verifyFingerprint() {
        if (!fingerprintMatches()) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.COMPILATION_FINGERPRINT_MISMATCH);
        }
        return this;
    }

    private static void validateSemanticConsistency(
            List<WorldDelegateBinding> bindings,
            StateAccessPlan stateAccessPlan,
            WorldRunStateDescriptor runStateDescriptor) {
        Map<String, WorldDelegateBinding> bindingsByRule = new HashMap<>();
        Map<String, WorldDelegateBinding> bindingsByContract = new HashMap<>();
        for (WorldDelegateBinding binding : bindings) {
            if (binding == null || bindingsByRule.put(binding.ruleId(), binding) != null
                    || bindingsByContract.put(binding.logicalContractId(), binding) != null) {
                throw invalid();
            }
        }
        Map<String, StateKeySpec> descriptorKeys = new HashMap<>();
        for (StateKeySpec declaration : runStateDescriptor.stateSpec().declarations()) {
            descriptorKeys.put(declaration.key(), declaration);
        }
        boolean statefulBinding = false;
        for (WorldDelegateBinding binding : bindings) {
            if (!binding.stateSpec().isEmpty()) statefulBinding = true;
            for (StateKeySpec declaration : binding.stateSpec().declarations()) {
                StateKeySpec descriptorDeclaration = descriptorKeys.get(declaration.key());
                if (descriptorDeclaration == null || !sameSchemaAndDefault(
                        declaration, descriptorDeclaration)
                        || !capabilitiesCover(descriptorDeclaration, declaration)) {
                    throw invalid();
                }
            }
        }
        Set<String> accessedStatefulRules = new HashSet<>();
        for (StateAccessPlan.Access access : stateAccessPlan.accesses()) {
            WorldDelegateBinding binding = bindingsByRule.get(access.ruleId());
            if (binding == null || binding.stateSpec().isEmpty()) throw invalid();
            accessedStatefulRules.add(binding.ruleId());
            List<String> expectedReads = binding.stateSpec().declarations().stream()
                    .filter(declaration -> declaration.access() != StateKeySpec.Access.WRITE)
                    .map(StateKeySpec::key).sorted().toList();
            List<String> expectedWrites = binding.stateSpec().declarations().stream()
                    .filter(StateKeySpec::writes).map(StateKeySpec::key).sorted().toList();
            if (!expectedReads.equals(access.readKeys()) || !expectedWrites.equals(access.writeKeys())) {
                throw invalid();
            }
            access.readKeys().forEach(key -> requireDescriptorKey(descriptorKeys, key));
            access.writeKeys().forEach(key -> requireDescriptorKey(descriptorKeys, key));
        }
        for (WorldDelegateBinding binding : bindings) {
            if (!binding.stateSpec().isEmpty()
                    && !accessedStatefulRules.contains(binding.ruleId())) {
                throw invalid();
            }
        }
        if (!runStateDescriptor.stateful()
                && (!stateAccessPlan.accesses().isEmpty() || statefulBinding)) {
            throw invalid();
        }
    }

    private static boolean sameSchemaAndDefault(StateKeySpec left, StateKeySpec right) {
        return left.schema().equals(right.schema())
                && java.util.Objects.equals(left.defaultValue(), right.defaultValue());
    }

    private static boolean capabilitiesCover(StateKeySpec descriptor, StateKeySpec binding) {
        boolean descriptorReadable = descriptor.access() != StateKeySpec.Access.WRITE;
        boolean descriptorWritable = descriptor.writes();
        boolean bindingReadable = binding.access() != StateKeySpec.Access.WRITE;
        boolean bindingWritable = binding.writes();
        return (!bindingReadable || descriptorReadable) && (!bindingWritable || descriptorWritable);
    }

    private static void requireDescriptorKey(Map<String, StateKeySpec> descriptorKeys, String key) {
        if (!descriptorKeys.containsKey(key)) throw invalid();
    }

    public static String fingerprintFor(FixtureBundle bundle,
                                        List<WorldDelegateBinding> bindings,
                                        WorldScenarioSourceMap sourceMap) {
        return fingerprintFor(bundle, bindings, sourceMap, StateAccessPlan.empty());
    }

    public static String fingerprintFor(FixtureBundle bundle,
                                        List<WorldDelegateBinding> bindings,
                                        WorldScenarioSourceMap sourceMap,
                                        StateAccessPlan stateAccessPlan) {
        return fingerprintFor(bundle, bindings, sourceMap, stateAccessPlan, null);
    }

    public static String fingerprintFor(FixtureBundle bundle,
                                        List<WorldDelegateBinding> bindings,
                                        WorldScenarioSourceMap sourceMap,
                                        StateAccessPlan stateAccessPlan,
                                        WorldRunStateDescriptor runStateDescriptor) {
        if (bundle == null || bindings == null || sourceMap == null || stateAccessPlan == null) {
            throw invalid();
        }
        List<Map<String, Object>> bindingMaterial = bindings.stream()
                .sorted(Comparator.comparing(WorldDelegateBinding::logicalContractId)
                        .thenComparing(WorldDelegateBinding::ruleId))
                .map(WorldScenarioCompilation::bindingMaterial)
                .toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("bundle", bundleMaterial(bundle));
        material.put("bindings", bindingMaterial);
        material.put("sourceMap", Map.of(
                "sourceToOutputs", sourceMap.sourceToOutputs(),
                "outputToSources", sourceMap.outputToSources()));
        if (!stateAccessPlan.accesses().isEmpty()) {
            material.put("stateAccessPlan", Map.of(
                    "fingerprint", stateAccessPlan.fingerprint(),
                    "accesses", stateAccessPlan.accesses()));
        }
        if (runStateDescriptor != null && runStateDescriptor.stateful()) {
            material.put("runStateDescriptorFingerprint", runStateDescriptor.fingerprint());
        }
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static Map<String, Object> bindingMaterial(WorldDelegateBinding binding) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("ruleId", binding.ruleId());
        material.put("logicalContractId", binding.logicalContractId());
        material.put("contractFingerprint", binding.contractFingerprint());
        if (!binding.stateSpec().isEmpty()) {
            material.put("stateSpecFingerprint", binding.stateSpec().fingerprint());
        }
        material.put("fragment", Map.of(
                    "id", binding.fragment().artifactId(),
                    "revision", binding.fragment().revision(),
                    "fingerprint", binding.fragment().fingerprint()));
        return material;
    }

    private static Map<String, Object> bundleMaterial(FixtureBundle bundle) {
        List<Map<String, Object>> rules = bundle.rules().stream()
                .sorted(Comparator.comparing(FixtureRule::ruleId))
                .map(WorldScenarioCompilation::ruleMaterial)
                .toList();
        List<Map<String, Object>> assertions = new ArrayList<>();
        for (int index = 0; index < bundle.assertions().size(); index++) {
            FixtureBundle.Assertion assertion = bundle.assertions().get(index);
            Map<String, Object> assertionMaterial = new LinkedHashMap<>();
            assertionMaterial.put("index", index);
            assertionMaterial.put("scope", assertion.scope());
            assertionMaterial.put("nodeId", assertion.nodeId());
            assertionMaterial.put("path", assertion.path());
            assertionMaterial.put("operator", assertion.operator());
            assertionMaterial.put("numericTolerance", assertion.numericTolerance());
            assertions.add(assertionMaterial);
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", bundle.schemaVersion());
        material.put("fixtureBundleId", bundle.fixtureBundleId());
        material.put("revision", bundle.revision());
        material.put("targetFingerprint", bundle.targetFingerprint());
        material.put("classification", bundle.classification());
        material.put("logicalClock", bundle.logicalClock() == null ? "" : bundle.logicalClock().toString());
        material.put("randomSeed", bundle.randomSeed() == null ? "" : bundle.randomSeed());
        material.put("rules", rules);
        material.put("assertions", assertions);
        material.put("metadata", payloadFreeMetadata(bundle.metadata()));
        return material;
    }

    private static Map<String, Object> ruleMaterial(FixtureRule rule) {
        FixtureRule.Selector selector = rule.selector();
        FixtureRule.Behavior behavior = rule.behavior();
        FixtureRule.Consumption consumption = rule.consumption();
        FixtureRule.SchemaCheck schemaCheck = rule.schemaCheck();
        Map<String, Object> selectorMaterial = new LinkedHashMap<>();
        selectorMaterial.put("graphPath", selector.graphPath());
        selectorMaterial.put("nodeId", selector.nodeId());
        selectorMaterial.put("operatorRef", selector.operatorRef());
        selectorMaterial.put("resourceRef", selector.resourceRef());
        selectorMaterial.put("functionRef", selector.functionRef());
        selectorMaterial.put("capabilities", selector.capabilities());
        selectorMaterial.put("tags", selector.tags());
        selectorMaterial.put("invocationKind", selector.invocationKind().name());
        selectorMaterial.put("attempts", selector.attempts());
        selectorMaterial.put("occurrences", selector.occurrences());
        selectorMaterial.put("correlationKey", selector.correlationKey());

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", rule.schemaVersion());
        material.put("ruleId", rule.ruleId());
        material.put("selector", selectorMaterial);
        material.put("behavior", Map.of(
                "kind", behavior.kind().name(),
                "boundary", behavior.boundary().name(),
                "errorCode", behavior.errorCode(),
                "errorType", behavior.errorType()));
        material.put("consumption", Map.of(
                "required", consumption.required(),
                "minUses", consumption.minUses(),
                "maxUses", consumption.maxUses(),
                "onExhausted", consumption.onExhausted().name(),
                "onUnmatched", consumption.onUnmatched().name()));
        material.put("schemaCheck", Map.of(
                "mode", schemaCheck.mode().name(),
                "waiverReason", schemaCheck.waiverReason()));
        return material;
    }

    private static Map<String, Object> payloadFreeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        return Map.of(
                "compilerVersion", metadata.getOrDefault("compilerVersion", ""),
                "scenario", metadata.getOrDefault("scenario", Map.of()),
                "world", metadata.getOrDefault("world", Map.of()),
                "fragments", metadata.getOrDefault("fragments", List.of()));
    }

    private static WorldScenarioCompilationException invalid() {
        return new WorldScenarioCompilationException(
                WorldScenarioCompilationException.Code.INVALID_COMPILATION);
    }
}
