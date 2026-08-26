package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, immutable output of the pure C2a compiler. */
public record WorldScenarioCompilation(
        FixtureBundle bundle,
        List<WorldDelegateBinding> bindings,
        WorldScenarioSourceMap sourceMap,
        String fingerprint
) {
    public WorldScenarioCompilation {
        if (bundle == null || bindings == null || sourceMap == null
                || fingerprint == null || fingerprint.isBlank()) {
            throw invalid();
        }
        bindings = List.copyOf(bindings);
        fingerprint = fingerprint.trim();
        if (!fingerprint.equals(fingerprintFor(bundle, bindings, sourceMap))) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.COMPILATION_FINGERPRINT_MISMATCH);
        }
    }

    /** Recomputes the stable fingerprint without serializing DSL, source, or binding payload. */
    public String recomputedFingerprint() {
        return fingerprintFor(bundle, bindings, sourceMap);
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

    public static String fingerprintFor(FixtureBundle bundle,
                                        List<WorldDelegateBinding> bindings,
                                        WorldScenarioSourceMap sourceMap) {
        if (bundle == null || bindings == null || sourceMap == null) {
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
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static Map<String, Object> bindingMaterial(WorldDelegateBinding binding) {
        return Map.of(
                "ruleId", binding.ruleId(),
                "logicalContractId", binding.logicalContractId(),
                "contractFingerprint", binding.contractFingerprint(),
                "fragment", Map.of(
                        "id", binding.fragment().artifactId(),
                        "revision", binding.fragment().revision(),
                        "fingerprint", binding.fragment().fingerprint()));
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
