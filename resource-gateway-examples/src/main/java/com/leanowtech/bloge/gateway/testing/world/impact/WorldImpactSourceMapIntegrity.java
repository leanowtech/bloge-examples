package com.leanowtech.bloge.gateway.testing.world.impact;

import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies the compiler-owned world dependency chain before impact facts are trusted. */
final class WorldImpactSourceMapIntegrity {
    private static final String LOGICAL_PREFIX = "logical-contract:";
    private static final String INVOCATION_PREFIX = "invocation-site:";
    private static final String WORLD_PREFIX = "world-slice:";
    private static final String FRAGMENT_PREFIX = "fragment:";
    private static final String FIXTURE_PREFIX = "fixture-rule:";
    private static final String SCENARIO_PREFIX = "scenario:";
    private static final String EXPECTATION_PREFIX = "scenario-expectation:";
    private static final String ASSERTION_PREFIX = "fixture-assertion:";

    private WorldImpactSourceMapIntegrity() {
    }

    static Verified verify(WorldScenarioCompilation compilation) {
        if (compilation == null || compilation.sourceMap() == null || compilation.bindings() == null) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        WorldScenarioSourceMap sourceMap = compilation.sourceMap();
        Map<String, WorldDelegateBinding> byRule = new HashMap<>();
        Map<String, WorldDelegateBinding> byContract = new HashMap<>();
        Map<String, Set<String>> expectedFragmentOutputs = new HashMap<>();
        Map<String, String> expectedLogicalSources = new HashMap<>();
        Map<String, String> expectedSiteLogical = new HashMap<>();
        Map<String, String> worldSourceByRule = new HashMap<>();
        Set<String> expectedWorldSources = new HashSet<>();
        Set<String> expectedFragmentSources = new HashSet<>();
        Set<String> expectedFixtureOutputs = new HashSet<>();
        Set<String> expectedInvocationOutputs = new HashSet<>();
        Set<String> expectedAssertionOutputs = new HashSet<>();

        Set<String> bundleRuleIds = compilation.bundle().rules().stream()
                .map(FixtureRule::ruleId).collect(java.util.stream.Collectors.toSet());
        for (WorldDelegateBinding binding : compilation.bindings()) {
            if (binding == null || byRule.put(binding.ruleId(), binding) != null
                    || byContract.put(binding.logicalContractId(), binding) != null
                    || !bundleRuleIds.contains(binding.ruleId())) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            String fixture = WorldScenarioSourceMap.coordinate("fixture-rule", binding.ruleId());
            String logical = WorldScenarioSourceMap.coordinate("logical-contract",
                    binding.logicalContractId() + "@" + binding.contractFingerprint());
            String fragment = WorldScenarioSourceMap.coordinate("fragment", fragmentCoordinate(binding.fragment()));
            List<String> fixtureSources = sourceMap.outputToSources(fixture);
            if (fixtureSources.size() != 2 || !fixtureSources.contains(fragment)) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            List<String> worldSources = fixtureSources.stream().filter(value ->
                    value.startsWith(WORLD_PREFIX + binding.logicalContractId() + "@")).toList();
            if (worldSources.size() != 1) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            String world = worldSources.getFirst();
            if (!validFingerprintSuffix(world, WORLD_PREFIX + binding.logicalContractId() + "@")) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            requireExact(sourceMap.sourceToOutputs(world), fixture);
            worldSourceByRule.put(binding.ruleId(), world);
            List<String> fragmentOutputs = sourceMap.sourceToOutputs(fragment);
            if (fragmentOutputs.isEmpty() || !fragmentOutputs.contains(fixture)
                    || fragmentOutputs.stream().anyMatch(value -> !value.startsWith(FIXTURE_PREFIX))) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            expectedFragmentOutputs.computeIfAbsent(fragment, ignored -> new HashSet<>()).add(fixture);
            expectedLogicalSources.put(logical, binding.ruleId());
            expectedWorldSources.add(world);
            expectedFragmentSources.add(fragment);
            expectedFixtureOutputs.add(fixture);

            List<String> logicalOutputs = sourceMap.sourceToOutputs(logical);
            if (logicalOutputs.isEmpty() || logicalOutputs.stream().anyMatch(value ->
                    !value.startsWith(INVOCATION_PREFIX) || value.length() == INVOCATION_PREFIX.length())) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            Set<String> uniqueOutputs = new HashSet<>(logicalOutputs);
            if (uniqueOutputs.size() != logicalOutputs.size()) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            for (String output : logicalOutputs) {
                if (!sourceMap.outputToSources(output).equals(List.of(logical))) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
                String site = output.substring(INVOCATION_PREFIX.length());
                if (!expectedInvocationOutputs.add(output) || expectedSiteLogical.put(site, logical) != null) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
            }
        }

        for (Map.Entry<String, Set<String>> expected : expectedFragmentOutputs.entrySet()) {
            List<String> actual = sourceMap.sourceToOutputs(expected.getKey());
            List<String> required = expected.getValue().stream().sorted().toList();
            if (!actual.equals(required)) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            for (String fixture : required) {
                List<String> reverse = sourceMap.outputToSources(fixture);
                if (!reverse.contains(expected.getKey())) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }

        Set<String> scenarioSources = new HashSet<>();
        String bundleOutput = WorldScenarioSourceMap.coordinate("fixture-bundle",
                compilation.bundle().fixtureBundleId());
        for (Map.Entry<String, List<String>> sourceEntry : sourceMap.sourceToOutputs().entrySet()) {
            String source = sourceEntry.getKey();
            List<String> outputs = sourceEntry.getValue();
            if (source.startsWith(LOGICAL_PREFIX) && !expectedLogicalSources.containsKey(source)) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            if (source.startsWith(WORLD_PREFIX) && !expectedWorldSources.contains(source)) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            if (source.startsWith(FRAGMENT_PREFIX) && !expectedFragmentSources.contains(source)) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            if (source.startsWith(SCENARIO_PREFIX)) {
                if (!scenarioSources.add(source) || !outputs.equals(List.of(bundleOutput))) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
            } else if (source.startsWith(EXPECTATION_PREFIX)) {
                String suffix = source.substring(EXPECTATION_PREFIX.length());
                String assertion = ASSERTION_PREFIX + suffix;
                if (suffix.isBlank() || !outputs.equals(List.of(assertion))) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
                expectedAssertionOutputs.add(assertion);
            } else if (!source.startsWith(LOGICAL_PREFIX) && !source.startsWith(WORLD_PREFIX)
                    && !source.startsWith(FRAGMENT_PREFIX)) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }
        if (scenarioSources.size() != 1) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);

        Set<String> expectedOutputs = new HashSet<>(expectedFixtureOutputs);
        expectedOutputs.addAll(expectedInvocationOutputs);
        expectedOutputs.add(bundleOutput);
        expectedOutputs.addAll(expectedAssertionOutputs);
        for (String output : sourceMap.outputToSources().keySet()) {
            if (!expectedOutputs.contains(output)) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        if (!sourceMap.outputToSources(bundleOutput).equals(scenarioSources.stream().toList())) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        for (String fixture : expectedFixtureOutputs) {
            WorldDelegateBinding binding = byRule.values().stream().filter(value ->
                    WorldScenarioSourceMap.coordinate("fixture-rule", value.ruleId()).equals(fixture))
                    .findFirst().orElseThrow(WorldImpactSourceMapIntegrity::invalid);
            String fragment = WorldScenarioSourceMap.coordinate("fragment", fragmentCoordinate(binding.fragment()));
            String world = worldSourceByRule.get(binding.ruleId());
            if (!sourceMap.outputToSources(fixture).equals(List.of(fragment, world).stream().sorted().toList())) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }
        for (Map.Entry<String, String> site : expectedSiteLogical.entrySet()) {
            String output = INVOCATION_PREFIX + site.getKey();
            if (!sourceMap.outputToSources(output).equals(List.of(site.getValue()))) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }
        for (String assertion : expectedAssertionOutputs) {
            String source = EXPECTATION_PREFIX + assertion.substring(ASSERTION_PREFIX.length());
            if (!sourceMap.outputToSources(assertion).equals(List.of(source))) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }

        Map<String, String> siteToRule = new HashMap<>();
        Map<String, String> siteToLogical = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : sourceMap.outputToSources().entrySet()) {
            String output = entry.getKey();
            if (!output.startsWith(INVOCATION_PREFIX)) continue;
            if (output.length() == INVOCATION_PREFIX.length() || entry.getValue().size() != 1) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            String logical = entry.getValue().getFirst();
            String ruleId = expectedLogicalSources.get(logical);
            if (ruleId == null || !sourceMap.sourceToOutputs(logical).contains(output)
                    || siteToRule.put(output.substring(INVOCATION_PREFIX.length()), ruleId) != null) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            siteToLogical.put(output.substring(INVOCATION_PREFIX.length()), logical);
        }
        return new Verified(Map.copyOf(byRule), Map.copyOf(byContract), Map.copyOf(siteToRule),
                Map.copyOf(siteToLogical), Map.copyOf(worldSourceByRule));
    }

    private static void requireExact(List<String> actual, String expected) {
        if (!actual.equals(List.of(expected))) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
    }

    private static boolean validFingerprintSuffix(String value, String prefix) {
        String suffix = value.substring(prefix.length());
        return suffix.matches("sha256:[0-9a-f]{64}");
    }

    private static String fragmentCoordinate(BlogeFragmentRef fragment) {
        return fragment.artifactId() + "@" + fragment.revision() + "@" + fragment.fingerprint();
    }

    private static WorldImpactException fail(WorldImpactException.Code code) {
        return WorldImpactSupport.fail(code);
    }

    private static WorldImpactException invalid() {
        return fail(WorldImpactException.Code.SOURCE_INTEGRITY);
    }

    record Verified(Map<String, WorldDelegateBinding> byRule,
                    Map<String, WorldDelegateBinding> byContract,
                    Map<String, String> siteToRule,
                    Map<String, String> siteToLogical,
                    Map<String, String> worldSourceByRule) {
        Verified {
            byRule = Map.copyOf(byRule);
            byContract = Map.copyOf(byContract);
            siteToRule = Map.copyOf(siteToRule);
            siteToLogical = Map.copyOf(siteToLogical);
            worldSourceByRule = Map.copyOf(worldSourceByRule);
        }

        List<String> sitesForRule(String ruleId) {
            return siteToRule.entrySet().stream().filter(entry -> entry.getValue().equals(ruleId))
                    .map(Map.Entry::getKey).sorted().toList();
        }
    }
}
