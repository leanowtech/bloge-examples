package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.planning.SelectorResolver;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure C2a compiler from an exact Scenario and ResourceWorldModel to FixtureBundle. */
public final class WorldScenarioCompiler {
    public static final String COMPILER_VERSION = "1.2.1-S1-C2a";
    public static final String LOGICAL_CONTRACT_TAG_PREFIX = WorldScenarioContractTagCodec.PREFIX;
    public static final String WORLD_DELEGATE_UNBOUND = "WORLD_DELEGATE_UNBOUND";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Compiles one complete pure bundle. The selection map is keyed by exact logical contract id;
     * every dependency must have one entry and every entry must be consumed.
     */
    public WorldScenarioCompilation compile(
            Scenario scenario,
            ResourceWorldModel world,
            Graph graph,
            OperatorRegistry registry,
            Map<String, WorldSliceSelection> selections) {
        try {
            return compileInternal(scenario, world, graph, registry, selections);
        } catch (WorldScenarioCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
    }

    private WorldScenarioCompilation compileInternal(
            Scenario scenario,
            ResourceWorldModel world,
            Graph graph,
            OperatorRegistry registry,
            Map<String, WorldSliceSelection> selections) {
        if (scenario == null || world == null || graph == null || registry == null || selections == null) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }

        try {
            new WorldModelAdmissionService().admit(world);
        } catch (RuntimeException rejected) {
            throw failure(WorldScenarioCompilationException.Code.ADMISSION_REJECTED);
        }

        String graphFingerprint;
        try {
            graphFingerprint = GraphArtifactFingerprint.of(MAPPER, graph);
        } catch (RuntimeException rejected) {
            throw failure(WorldScenarioCompilationException.Code.TARGET_DRIFT);
        }
        if (!"GRAPH".equals(scenario.target().kind())) {
            throw failure(WorldScenarioCompilationException.Code.TARGET_KIND_UNSUPPORTED);
        }
        if (!scenario.target().fingerprint().equals(graphFingerprint)) {
            throw failure(WorldScenarioCompilationException.Code.TARGET_DRIFT);
        }
        if (!scenario.tenantId().equals(world.tenantId())
                || !scenario.world().worldModelId().equals(world.worldModelId())
                || scenario.world().revision() != world.revision()
                || !scenario.world().fingerprint().equals(world.fingerprint())) {
            throw failure(WorldScenarioCompilationException.Code.WORLD_DRIFT);
        }

        Map<String, Scenario.ContractDependency> dependencies = new TreeMap<>();
        for (Scenario.ContractDependency dependency : scenario.contractDependencies()) {
            dependencies.put(dependency.contractId(), dependency);
        }
        Set<String> selectionKeys = new HashSet<>(selections.keySet());
        if (!selectionKeys.equals(dependencies.keySet())) {
            Set<String> missing = new HashSet<>(dependencies.keySet());
            missing.removeAll(selectionKeys);
            throw failure(missing.isEmpty()
                    ? WorldScenarioCompilationException.Code.SELECTION_EXTRA
                    : WorldScenarioCompilationException.Code.SELECTION_MISSING);
        }

        Map<String, WorldSlice> selectedSlices = new TreeMap<>();
        for (String contractId : dependencies.keySet()) {
            WorldSliceSelection selection = selections.get(contractId);
            if (selection == null || selection.provider().isBlank() || selection.apiVersion().isBlank()
                    || selection.sliceFingerprint().isBlank()) {
                throw failure(WorldScenarioCompilationException.Code.SELECTION_MISSING);
            }
            List<WorldSlice> matches = world.slices().stream()
                    .filter(slice -> slice.logicalContractId().equals(contractId))
                    .filter(slice -> slice.provider().equals(selection.provider()))
                    .filter(slice -> slice.apiVersion().equals(selection.apiVersion()))
                    .filter(slice -> slice.fingerprint().equals(selection.sliceFingerprint()))
                    .toList();
            if (matches.size() != 1) {
                throw failure(matches.isEmpty()
                        ? WorldScenarioCompilationException.Code.CONTRACT_DRIFT
                        : WorldScenarioCompilationException.Code.SELECTION_NOT_UNIQUE);
            }
            WorldSlice slice = matches.getFirst();
            if (!slice.contract().contractId().equals(contractId)
                    || !dependencies.get(contractId).baselineFingerprint()
                    .equals(slice.contract().contractFingerprint())) {
                throw failure(WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
            }
            try {
                if (!scenario.validateCompatibility(slice.contract()).valid()) {
                    throw failure(WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
                }
            } catch (ScenarioException rejected) {
                throw failure(WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
            }
            selectedSlices.put(contractId, slice);
        }

        InvocationInventory inventory;
        try {
            inventory = new InvocationInventoryBuilder(registry).build(graph, graphFingerprint);
        } catch (RuntimeException rejected) {
            throw failure(WorldScenarioCompilationException.Code.INVOCATION_INVENTORY);
        }
        Map<String, List<InvocationInventory.Entry>> matchesByContract = contractMatches(
                inventory, dependencies, selectedSlices);

        List<FixtureRule> rules = new ArrayList<>();
        List<WorldDelegateBinding> bindings = new ArrayList<>();
        List<WorldScenarioSourceMap.Link> links = new ArrayList<>();
        Map<String, Set<String>> expectedSiteIdsByRule = new TreeMap<>();
        for (Map.Entry<String, WorldSlice> selected : selectedSlices.entrySet()) {
            String contractId = selected.getKey();
            WorldSlice slice = selected.getValue();
            List<InvocationInventory.Entry> entries = matchesByContract.get(contractId);
            if (entries == null || entries.isEmpty()) {
                throw failure(WorldScenarioCompilationException.Code.ZERO_MATCH);
            }
            Set<InvocationSite.InvocationKind> kinds = entries.stream()
                    .map(entry -> entry.site().invocationKind()).collect(java.util.stream.Collectors.toSet());
            if (kinds.stream().anyMatch(kind -> kind != InvocationSite.InvocationKind.PRIMARY
                    && kind != InvocationSite.InvocationKind.RESOURCE)) {
                throw failure(WorldScenarioCompilationException.Code.ZERO_MATCH);
            }
            InvocationSite.InvocationKind kind = kinds.contains(InvocationSite.InvocationKind.PRIMARY)
                    ? InvocationSite.InvocationKind.PRIMARY : InvocationSite.InvocationKind.RESOURCE;
            String tag = logicalContractTag(contractId, slice.contractFingerprint());
            FixtureRule.Selector selector = new FixtureRule.Selector(
                    "", "", "", "", "", List.of(), List.of(tag), kind,
                    List.of(), List.of(), "", FixtureRule.Match.none());
            String ruleId = "world-delegate:" + contractId;
            FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, ruleId, selector,
                    FixtureRule.Behavior.deny(WORLD_DELEGATE_UNBOUND, WORLD_DELEGATE_UNBOUND),
                    new FixtureRule.Consumption(true, 1, 0,
                            FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL),
                    FixtureRule.SchemaCheck.strict());
            rules.add(rule);
            expectedSiteIdsByRule.put(ruleId, entries.stream()
                    .map(entry -> entry.site().invocationSiteId())
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
            bindings.add(new WorldDelegateBinding(ruleId, contractId,
                    slice.contractFingerprint(), slice.behavior()));
            String ruleOutput = WorldScenarioSourceMap.coordinate("fixture-rule", ruleId);
            links.add(WorldScenarioSourceMap.link(
                    WorldScenarioSourceMap.coordinate("world-slice", contractId + "@" + slice.fingerprint()),
                    ruleOutput));
            links.add(WorldScenarioSourceMap.link(
                    WorldScenarioSourceMap.coordinate("fragment", fragmentCoordinate(slice.behavior())),
                    ruleOutput));
            String logicalSource = WorldScenarioSourceMap.coordinate("logical-contract",
                    contractId + "@" + slice.contractFingerprint());
            entries.stream().sorted(Comparator.comparing(entry -> entry.site().invocationSiteId()))
                    .forEach(entry -> links.add(WorldScenarioSourceMap.link(logicalSource,
                            WorldScenarioSourceMap.coordinate("invocation-site",
                                    entry.site().invocationSiteId()))));
        }
        rules.sort(Comparator.comparing(FixtureRule::ruleId));
        bindings.sort(Comparator.comparing(WorldDelegateBinding::logicalContractId));

        assertSelectorResolution(inventory, rules, expectedSiteIdsByRule);

        List<FixtureBundle.Assertion> assertions = scenario.expect().stream()
                .map(Scenario.Expectation::toFixtureAssertion).toList();
        String bundleId = "world-scenario:" + scenario.scenarioId() + ":" + scenario.revision();
        Map<String, Object> metadata = metadata(scenario, selectedSlices.values().stream().toList());
        FixtureBundle bundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, bundleId,
                scenario.revision(), scenario.target().fingerprint(), "INTERNAL", null, null,
                rules, assertions, metadata);
        String bundleOutput = WorldScenarioSourceMap.coordinate("fixture-bundle", bundleId);
        links.add(WorldScenarioSourceMap.link(scenarioCoordinate(scenario), bundleOutput));
        for (int index = 0; index < assertions.size(); index++) {
            String expectationFingerprint = expectationFingerprint(scenario.expect().get(index));
            String expectationCoordinate = index + "@" + expectationFingerprint;
            String expectationSource = WorldScenarioSourceMap.coordinate("scenario-expectation",
                    expectationCoordinate);
            links.add(WorldScenarioSourceMap.link(expectationSource,
                    WorldScenarioSourceMap.coordinate("fixture-assertion", expectationCoordinate)));
        }
        WorldScenarioSourceMap sourceMap = WorldScenarioSourceMap.of(links);
        String compilationFingerprint = WorldScenarioCompilation.fingerprintFor(
                bundle, bindings, sourceMap);
        return new WorldScenarioCompilation(bundle, bindings, sourceMap, compilationFingerprint);
    }

    private static void assertSelectorResolution(
            InvocationInventory inventory,
            List<FixtureRule> rules,
            Map<String, Set<String>> expectedSiteIdsByRule) {
        Map<String, CompiledExecutionControl.ResolvedControl> resolved;
        try {
            resolved = new SelectorResolver().resolve(inventory, rules);
        } catch (RuntimeException rejected) {
            throw failure(WorldScenarioCompilationException.Code.SELECTOR_RESOLUTION);
        }
        Map<String, Set<String>> actualSiteIdsByRule = new TreeMap<>();
        for (CompiledExecutionControl.ResolvedControl control : resolved.values()) {
            for (FixtureRule rule : control.rules()) {
                actualSiteIdsByRule.computeIfAbsent(rule.ruleId(), ignored -> new java.util.TreeSet<>())
                        .add(control.site().invocationSiteId());
            }
        }
        if (!actualSiteIdsByRule.equals(expectedSiteIdsByRule)) {
            throw failure(WorldScenarioCompilationException.Code.SELECTOR_RESOLUTION);
        }
    }

    private static Map<String, List<InvocationInventory.Entry>> contractMatches(
            InvocationInventory inventory,
            Map<String, Scenario.ContractDependency> dependencies,
            Map<String, WorldSlice> selectedSlices) {
        Map<String, List<InvocationInventory.Entry>> result = new TreeMap<>();
        for (InvocationInventory.Entry entry : inventory.entries()) {
            InvocationSite.InvocationKind kind = entry.site().invocationKind();
            if (kind != InvocationSite.InvocationKind.PRIMARY && kind != InvocationSite.InvocationKind.RESOURCE) {
                continue;
            }
            List<String> contractTags = logicalContractTags(entry.node().metadata().attributes().get("tags"));
            if (contractTags.size() > 1) {
                throw failure(WorldScenarioCompilationException.Code.MULTIPLE_CONTRACT_TAGS);
            }
            if (contractTags.isEmpty()) {
                continue;
            }
            WorldScenarioContractTagCodec.Decoded decoded;
            try {
                decoded = WorldScenarioContractTagCodec.decode(contractTags.getFirst());
            } catch (WorldScenarioCompilationException rejected) {
                throw rejected;
            }
            String contractId = decoded.contractId();
            String contractFingerprint = decoded.contractFingerprint();
            WorldSlice selected = selectedSlices.get(contractId);
            if (!dependencies.containsKey(contractId)) {
                throw failure(WorldScenarioCompilationException.Code.CONTRACT_NOT_DECLARED);
            }
            if (selected == null || !selected.contractFingerprint().equals(contractFingerprint)) {
                throw failure(WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
            }
            result.computeIfAbsent(contractId, ignored -> new ArrayList<>()).add(entry);
        }
        return result;
    }

    private static List<String> logicalContractTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : rawTags.split(",")) {
            String tag = raw.trim();
            if (tag.startsWith(LOGICAL_CONTRACT_TAG_PREFIX)) {
                result.add(tag);
            }
        }
        return result;
    }

    public static String logicalContractTag(String id, String fingerprint) {
        return WorldScenarioContractTagCodec.encode(id, fingerprint);
    }

    public static WorldScenarioContractTagCodec.Decoded decodeLogicalContractTag(String tag) {
        return WorldScenarioContractTagCodec.decode(tag);
    }

    private static String fragmentCoordinate(BlogeFragmentRef fragment) {
        return fragment.artifactId() + "@" + fragment.revision() + "@" + fragment.fingerprint();
    }

    private static String scenarioCoordinate(Scenario scenario) {
        return WorldScenarioSourceMap.coordinate("scenario",
                scenario.scenarioId() + "@" + scenario.revision() + "@" + scenario.fingerprint());
    }

    private static String expectationFingerprint(Scenario.Expectation expectation) {
        Map<String, Object> material = new java.util.LinkedHashMap<>();
        material.put("scope", expectation.scope());
        material.put("nodeId", expectation.nodeId());
        material.put("path", expectation.path());
        material.put("operator", expectation.operator());
        material.put("expected", expectation.expected());
        material.put("numericTolerance", expectation.numericTolerance());
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static Map<String, Object> metadata(Scenario scenario, List<WorldSlice> slices) {
        List<Map<String, Object>> fragments = slices.stream()
                .map(WorldSlice::behavior)
                .distinct()
                .sorted(Comparator.comparing(BlogeFragmentRef::artifactId)
                        .thenComparingLong(BlogeFragmentRef::revision)
                        .thenComparing(BlogeFragmentRef::fingerprint))
                .map(fragment -> Map.<String, Object>of(
                        "id", fragment.artifactId(),
                        "revision", fragment.revision(),
                        "fingerprint", fragment.fingerprint()))
                .toList();
        return Map.of(
                "compilerVersion", COMPILER_VERSION,
                "scenario", Map.of("id", scenario.scenarioId(), "revision", scenario.revision(),
                        "fingerprint", scenario.fingerprint()),
                "world", Map.of("id", scenario.world().worldModelId(),
                        "revision", scenario.world().revision(), "fingerprint", scenario.world().fingerprint()),
                "fragments", fragments);
    }

    private static WorldScenarioCompilationException failure(WorldScenarioCompilationException.Code code) {
        return new WorldScenarioCompilationException(code);
    }
}
