package com.leanowtech.bloge.gateway.testing.world.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioContractTagCodec;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure, one-way migration from governed legacy test assets into unpublished world drafts. */
public final class WorldMigrationService {
    private final ObjectMapper mapper;

    public WorldMigrationService(ObjectMapper mapper) {
        if (mapper == null) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        this.mapper = mapper.copy().findAndRegisterModules();
    }

    public WorldMigrationDraftPackage migrate(WorldMigrationSource.Access access,
                                              WorldMigrationSource source,
                                              WorldMigrationSource.Request request) {
        if (access == null || source == null || request == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        try {
            return migrate(source.read(access, request), access, request);
        } catch (WorldMigrationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
    }

    WorldMigrationDraftPackage migrate(WorldMigrationInput input) {
        if (input == null) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        return migrate(input, new WorldMigrationSource.Access(input.tenantId(), "server", "migration"), null);
    }

    public WorldMigrationDraftSink.Commit migrateAndStore(WorldMigrationSource.Access access,
                                                            WorldMigrationSource source,
                                                            WorldMigrationSource.Request request,
                                                            WorldMigrationDraftSink sink) {
        if (sink == null) throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        WorldMigrationDraftPackage draft = migrate(access, source, request);
        try {
            return sink.save(access, draft, draft.fingerprint());
        } catch (WorldMigrationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SINK_FAILURE);
        }
    }

    /**
     * Resolves one exact legacy rule and lets a server-owned adapter complete an unpublished
     * existing World draft rule. The adapter is never given a fuzzy selector or a guessed rule.
     */
    public WorldMigrationDraftMaterializer.Result materializeWorldRule(
            WorldMigrationSource.Access access,
            WorldMigrationSource source,
            WorldMigrationSource.Request request,
            String draftId,
            String sourceRuleId,
            WorldMigrationDraftMaterializer materializer) {
        if (access == null || source == null || request == null || materializer == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        try {
            WorldMigrationDraftPackage migrated = migrate(access, source, request);
            WorldDraftMaterializationPlan.RulePlan plan = migrated.worldDrafts().stream()
                    .filter(world -> world.draftId().equals(MigrationSupport.text(draftId)))
                    .flatMap(world -> world.rules().stream())
                    .filter(rule -> rule.sourceRuleId().equals(MigrationSupport.text(sourceRuleId)))
                    .findFirst().orElseThrow(() -> MigrationSupport.fail(
                            WorldMigrationException.Code.MAPPING_MISSING));
            if (!migrated.worldDrafts().stream()
                    .filter(world -> world.draftId().equals(MigrationSupport.text(draftId)))
                    .allMatch(WorldDraftMaterializationPlan::readyToMaterialize)
                    || !plan.exactInputAvailable()) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_PREREQUISITE_MISSING);
            }
            WorldMigrationInput resolved = source.read(access, request);
            FixtureRule legacyRule = resolved.fixtureBundle().bundle().rules().stream()
                    .filter(rule -> rule != null && rule.ruleId().equals(plan.sourceRuleId()))
                    .findFirst().orElseThrow(() -> MigrationSupport.fail(
                            WorldMigrationException.Code.MAPPING_MISSING));
            WorldDraftRule expected = exactRule(legacyRule, plan.resultFingerprint());
            if (expected == null) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_PREREQUISITE_MISSING);
            }
            WorldMigrationDraftMaterializer.Result result = materializer.materialize(
                    new WorldMigrationDraftMaterializer.Request(access, plan, legacyRule));
            if (result == null || !sameRuleIdentity(expected, result.rule())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_INVALID);
            }
            return result;
        } catch (WorldMigrationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_INVALID);
        }
    }

    private static boolean sameRuleIdentity(WorldDraftRule expected, WorldDraftRule actual) {
        return actual != null
                && expected.requestSchemaFingerprint().equals(actual.requestSchemaFingerprint())
                && expected.inputFingerprint().equals(actual.inputFingerprint())
                && expected.responseFingerprint().equals(actual.responseFingerprint());
    }

    private WorldMigrationDraftPackage migrate(WorldMigrationInput input,
                                               WorldMigrationSource.Access access,
                                               WorldMigrationSource.Request request) {
        try {
            verifyInput(input, access, request);
            StoredFixtureBundle fixtureEnvelope = StoredFixtureBundleIntegrity.verifiedSnapshot(
                    mapper, input.fixtureBundle());
            StoredTestSuite suiteEnvelope = StoredTestSuiteIntegrity.verifiedSnapshot(
                    mapper, input.testSuite());
            FixtureBundle fixture = fixtureEnvelope.bundle();
            TestSuiteProtocol suite = suiteEnvelope.suite();
            input.compilation().verifyFingerprint();
            verifyTargetAndInventory(input, fixtureEnvelope, suiteEnvelope);
            verifySourceMap(input.compilation(), input.inventory());
            String inventoryFingerprint = inventoryFingerprint(input.inventory());

            List<WorldDraftMaterializationPlan.RulePlan> worldRules = new ArrayList<>();
            List<WorldMigrationDraftPackage.Diagnostic> diagnostics = new ArrayList<>();
            Map<String, List<String>> contractRules = new TreeMap<>();
            Map<String, String> contractFingerprints = new TreeMap<>();
            Set<String> ruleIds = new HashSet<>();
            for (FixtureRule rule : fixture.rules()) {
                if (rule == null || !ruleIds.add(rule.ruleId())) {
                    throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
                }
                classifyRule(rule, input.compilation(), input.inventory(), input.fixtureBundle(),
                        worldRules, diagnostics, contractRules, contractFingerprints);
            }

            List<WorldDraftMaterializationPlan> worlds = new ArrayList<>();
            String worldDraftId = "world-migration:" + suite.suiteId() + ":" + suite.revision();
            if (!worldRules.isEmpty()) {
                List<String> prerequisites = worldRules.stream()
                        .filter(rule -> !rule.exactInputAvailable())
                        .map(rule -> "EXACT_WORLD_RULE_PREREQUISITES:" + rule.sourceRuleId())
                        .toList();
                worlds.add(WorldDraftMaterializationPlan.create(worldDraftId, input.tenantId(),
                        fixture.targetFingerprint(), worldRules, prerequisites));
            }
            List<WorldMigrationDraftPackage.LogicalContractCandidate> contracts = contractRules.entrySet()
                    .stream().map(entry -> new WorldMigrationDraftPackage.LogicalContractCandidate(
                            entry.getKey(), contractFingerprints.get(entry.getKey()), entry.getValue()))
                    .toList();
            ScenarioDraftSet scenarioDraftSet = scenarioDraftSet(
                    input.tenantId(), fixtureEnvelope, suiteEnvelope, fixture, suite);
            if (scenarioDraftSet.contractFingerprint().isBlank()) {
                diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                        WorldMigrationDraftPackage.DiagnosticCode.MATERIALIZATION_PREREQUISITE_MISSING,
                        "scenarioDraftSet", ""));
            }
            List<WorldMigrationDraftPackage.SourceMapping> legacy = new ArrayList<>();
            List<WorldMigrationDraftPackage.SourceMapping> reverse = new ArrayList<>();
            for (WorldDraftMaterializationPlan.RulePlan rule : worldRules) {
                mapping(legacy, reverse, "fixture-rule", rule.sourceRuleId(), fixture.revision(),
                        input.fixtureBundle().fingerprint(), "WORLD_DRAFT", worldDraftId);
            }
            for (ScenarioDraftSet.ScenarioDraft scenario : scenarioDraftSet.scenarios()) {
                mapping(legacy, reverse, "test-suite", suite.suiteId(), suite.revision(),
                        input.testSuite().fingerprint(), "SCENARIO_DRAFT", scenario.scenarioId());
                mapping(legacy, reverse, "fixture-bundle", fixture.fixtureBundleId(), fixture.revision(),
                        input.fixtureBundle().fingerprint(), "SCENARIO_DRAFT", scenario.scenarioId());
            }
            List<WorldMigrationDraftPackage.ChecklistItem> checklist = diagnostics.stream()
                    .map(WorldMigrationDraftPackage.Diagnostic::code).distinct().sorted(Comparator.comparing(Enum::name))
                    .map(WorldMigrationDraftPackage.ChecklistItem::new).toList();
            return WorldMigrationDraftPackage.create(input.tenantId(), input.fixtureBundle().fingerprint(),
                    input.testSuite().fingerprint(), fixture.targetFingerprint(), input.compilation().fingerprint(),
                    inventoryFingerprint, worlds, scenarioDraftSet, contracts,
                    distinctDiagnostics(diagnostics), checklist, distinctMappings(legacy), distinctMappings(reverse));
        } catch (WorldMigrationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
    }

    private void verifyInput(WorldMigrationInput input, WorldMigrationSource.Access access,
                             WorldMigrationSource.Request request) {
        if (!input.tenantId().equals(input.fixtureBundle().tenantId())
                || !input.tenantId().equals(input.testSuite().tenantId())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TENANT_MISMATCH);
        }
        if (access != null && !input.tenantId().equals(access.tenantId())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TENANT_MISMATCH);
        }
        if (request != null && (!request.fixtureBundleId().equals(input.fixtureBundle().fixtureBundleId())
                || request.fixtureRevision() != input.fixtureBundle().revision()
                || !request.fixtureFingerprint().equals(input.fixtureBundle().fingerprint())
                || !request.suiteId().equals(input.testSuite().suiteId())
                || request.suiteRevision() != input.testSuite().revision()
                || !request.suiteFingerprint().equals(input.testSuite().fingerprint()))) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
        }
    }

    private void verifyTargetAndInventory(WorldMigrationInput input, StoredFixtureBundle fixture,
                                          StoredTestSuite suite) {
        FixtureBundle bundle = fixture.bundle();
        TestSuiteProtocol testSuite = suite.suite();
        if (!"GRAPH".equals(testSuite.target().kind())
                || !bundle.targetFingerprint().equals(testSuite.target().fingerprint())
                || !bundle.targetFingerprint().equals(input.compilation().bundle().targetFingerprint())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        Set<String> sites = new HashSet<>();
        if (input.inventory().entries().size() > MigrationSupport.MAX_ENTRIES) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_LIMIT_EXCEEDED);
        }
        for (InvocationInventory.Entry entry : input.inventory().entries()) {
            if (entry == null || entry.site() == null
                    || !bundle.targetFingerprint().equals(entry.site().artifactFingerprint())
                    || !sites.add(entry.site().invocationSiteId())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            }
        }
        if (!sites.equals(input.inventory().byInvocationSiteId().keySet())
                || !sites.equals(input.inventory().byInvocationSiteId().values().stream()
                .map(entry -> entry.site().invocationSiteId()).collect(java.util.stream.Collectors.toSet()))) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
    }

    private void verifySourceMap(WorldScenarioCompilation compilation, InvocationInventory inventory) {
        WorldScenarioSourceMap sourceMap = compilation.sourceMap();
        if (sourceMap == null) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        if (sourceMap.sourceToOutputs().size() > MigrationSupport.MAX_ENTRIES
                || sourceMap.outputToSources().size() > MigrationSupport.MAX_ENTRIES) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_LIMIT_EXCEEDED);
        }
        Map<String, Set<String>> siteSources = new HashMap<>();
        for (Map.Entry<String, List<String>> source : sourceMap.sourceToOutputs().entrySet()) {
            if (source.getKey() == null || source.getValue() == null) throw MigrationSupport.fail(
                    WorldMigrationException.Code.SOURCE_INTEGRITY);
            for (String output : source.getValue()) {
                if (output == null || !sourceMap.outputToSources(output).contains(source.getKey())) {
                    throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
                }
                if (output.startsWith("invocation-site:")) {
                    String site = output.substring("invocation-site:".length());
                    if (!inventory.byInvocationSiteId().containsKey(site)) throw MigrationSupport.fail(
                            WorldMigrationException.Code.MAPPING_MISSING);
                    siteSources.computeIfAbsent(site, ignored -> new HashSet<>()).add(source.getKey());
                }
            }
        }
        for (Map.Entry<String, List<String>> output : sourceMap.outputToSources().entrySet()) {
            if (output.getKey() == null || output.getValue() == null) throw MigrationSupport.fail(
                    WorldMigrationException.Code.SOURCE_INTEGRITY);
            for (String source : output.getValue()) {
                if (source == null || !sourceMap.sourceToOutputs(source).contains(output.getKey())) {
                    throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
                }
            }
        }
        if (siteSources.values().stream().anyMatch(sources -> sources.stream()
                .filter(source -> source.startsWith("logical-contract:"))
                .count() > 1)) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        for (String source : sourceMap.sourceToOutputs().keySet()) {
            if (!source.startsWith("logical-contract:")) continue;
            LogicalSource logical = logicalSource(source);
            long matchingBindings = compilation.bindings().stream()
                    .filter(binding -> binding.logicalContractId().equals(logical.contractId())
                            && binding.contractFingerprint().equals(logical.fingerprint())).count();
            if (matchingBindings != 1) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            if (sourceMap.sourceToOutputs(source).stream().anyMatch(output -> !output.startsWith("invocation-site:"))) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            }
        }
    }

    private void classifyRule(FixtureRule rule, WorldScenarioCompilation compilation,
                              InvocationInventory inventory,
                              StoredFixtureBundle fixtureEnvelope,
                              List<WorldDraftMaterializationPlan.RulePlan> candidates,
                              List<WorldMigrationDraftPackage.Diagnostic> diagnostics,
                              Map<String, List<String>> contractRules,
                              Map<String, String> contractFingerprints) {
        if (rule == null) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        FixtureRule.Selector selector = rule.selector();
        FixtureRule.Behavior behavior = rule.behavior();
        if (selector == null || behavior == null) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        List<String> tags = selector.tags().stream().filter(tag -> tag.startsWith(WorldScenarioContractTagCodec.PREFIX)).toList();
        if (tags.size() > 1) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        boolean unsafe = allowsReal(rule) || isFuzzy(selector, tags.size() == 1);
        if (behavior.kind() == FixtureRule.BehaviorKind.SPY) {
            diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                    WorldMigrationDraftPackage.DiagnosticCode.SPY_NOT_PROMOTABLE, rule.ruleId(), ""));
            return;
        }
        if (allowsReal(rule)) {
            diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                    WorldMigrationDraftPackage.DiagnosticCode.ALLOW_REAL_NOT_PROMOTABLE, rule.ruleId(), ""));
            return;
        }
        if (unsafe) {
            diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                    isFuzzy(selector, tags.size() == 1)
                            ? WorldMigrationDraftPackage.DiagnosticCode.FUZZY_SELECTOR_NOT_PROMOTABLE
                            : WorldMigrationDraftPackage.DiagnosticCode.ALLOW_REAL_NOT_PROMOTABLE,
                    rule.ruleId(), ""));
            return;
        }
        if (tags.isEmpty()) {
            if (ExecutionMode.isSchemaStandinBehavior(selector.operatorRef(), behavior)) {
                List<String> sites = exactSelectorSites(selector, inventory);
                if (sites.isEmpty()) {
                    diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                            WorldMigrationDraftPackage.DiagnosticCode.SOURCE_MAPPING_REQUIRED, rule.ruleId(), ""));
                    return;
                }
                WorldDraftMaterializationPlan.LegacyFixtureRuleRef sourceRef = legacyRef(rule, fixtureEnvelope);
                candidates.add(rulePlan(rule, sourceRef, sites,
                        WorldDraftMaterializationPlan.MaterializationKind.SCHEMA_STANDIN,
                        true, "", "", valueFingerprint(behavior), "", ""));
                diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                        WorldMigrationDraftPackage.DiagnosticCode.SCHEMA_STANDIN_EXPLORATION, rule.ruleId(), sites.getFirst()));
            } else {
                diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                        WorldMigrationDraftPackage.DiagnosticCode.UNMAPPED_NO_LOGICAL_CONTRACT, rule.ruleId(), ""));
            }
            return;
        }
        WorldScenarioContractTagCodec.Decoded tag;
        try {
            tag = WorldScenarioContractTagCodec.decode(tags.getFirst());
        } catch (RuntimeException invalid) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
        }
        WorldDelegateBinding binding = compilation.bindings().stream()
                .filter(candidate -> candidate.logicalContractId().equals(tag.contractId())
                        && candidate.contractFingerprint().equals(tag.contractFingerprint())).findFirst().orElseThrow(
                        () -> MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING));
        List<String> sites = logicalSites(compilation.sourceMap(), tag, inventory);
        if (sites.isEmpty()) throw MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING);
        if (!selector.graphPath().isBlank() && !selector.nodeId().isBlank()
                && sites.stream().anyMatch(site -> !siteMatches(site, selector))) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        WorldDraftMaterializationPlan.RulePlan candidate = candidate(rule, tag, sites,
                legacyRef(rule, fixtureEnvelope));
        if (candidate == null) {
            diagnostics.add(new WorldMigrationDraftPackage.Diagnostic(
                    rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY
                            ? WorldMigrationDraftPackage.DiagnosticCode.UNFROZEN_REPLAY
                            : WorldMigrationDraftPackage.DiagnosticCode.UNSUPPORTED_BEHAVIOR,
                    rule.ruleId(), sites.getFirst()));
            return;
        }
        if (binding.fragment() == null) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        candidates.add(candidate);
        contractRules.computeIfAbsent(tag.contractId(), ignored -> new ArrayList<>()).add(rule.ruleId());
        contractFingerprints.put(tag.contractId(), tag.contractFingerprint());
    }

    private WorldDraftMaterializationPlan.RulePlan candidate(FixtureRule rule,
                                                              WorldScenarioContractTagCodec.Decoded tag,
                                                              List<String> sites,
                                                              WorldDraftMaterializationPlan.LegacyFixtureRuleRef sourceRef) {
        return switch (rule.behavior().kind()) {
            case RETURN -> rulePlan(rule, sourceRef, sites,
                    WorldDraftMaterializationPlan.MaterializationKind.RETURN,
                    false, tag.contractId(), tag.contractFingerprint(), valueFingerprint(rule.behavior()), "", "");
            case REPLAY -> {
                try {
                    ReplayPayloadRef replay = ReplayPayloadRef.parse(rule.behavior().replayRef());
                    yield rulePlan(rule, sourceRef, sites,
                            WorldDraftMaterializationPlan.MaterializationKind.REPLAY,
                            false, tag.contractId(), tag.contractFingerprint(), replay.fingerprint(), replay.canonical(), "");
                } catch (RuntimeException invalid) {
                    yield null;
                }
            }
            case THROW, TIMEOUT -> {
                if (rule.behavior().errorCode().isBlank()) yield null;
                yield rulePlan(rule, sourceRef, sites,
                        WorldDraftMaterializationPlan.MaterializationKind.FAILURE,
                        false, tag.contractId(), tag.contractFingerprint(), "", "", rule.behavior().errorCode());
            }
            default -> null;
        };
    }

    private static WorldDraftMaterializationPlan.LegacyFixtureRuleRef legacyRef(
            FixtureRule rule, StoredFixtureBundle fixtureEnvelope) {
        if (rule == null || fixtureEnvelope == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        return new WorldDraftMaterializationPlan.LegacyFixtureRuleRef(
                fixtureEnvelope.fixtureBundleId(), fixtureEnvelope.revision(),
                fixtureEnvelope.fingerprint(), rule.ruleId());
    }

    private static WorldDraftMaterializationPlan.RulePlan rulePlan(
            FixtureRule rule,
            WorldDraftMaterializationPlan.LegacyFixtureRuleRef sourceRef,
            List<String> sites,
            WorldDraftMaterializationPlan.MaterializationKind kind,
            boolean explorationOnly,
            String contractId,
            String contractFingerprint,
            String resultFingerprint,
            String replayRef,
            String errorCode) {
        if (sourceRef == null) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        return new WorldDraftMaterializationPlan.RulePlan(rule.ruleId(), sourceRef,
                kind, rule.behavior().kind(),
                contractId, contractFingerprint, sites, resultFingerprint, replayRef, errorCode,
                explorationOnly, hasExactInput(rule));
    }

    private static boolean hasExactInput(FixtureRule rule) {
        return rule != null && rule.selector() != null && rule.selector().match() != null
                && rule.selector().match().canonicalInput() != null
                && !rule.selector().match().schema().isEmpty();
    }

    private static WorldDraftRule exactRule(FixtureRule rule, String responseFingerprint) {
        if (rule == null || responseFingerprint == null || responseFingerprint.isBlank()
                || rule.selector() == null || rule.selector().match() == null
                || rule.selector().match().canonicalInput() == null
                || rule.selector().match().schema().isEmpty()) {
            return null;
        }
        try {
            return new WorldDraftRule(MigrationSupport.hash(rule.selector().match().schema()),
                    MigrationSupport.hash(rule.selector().match().canonicalInput()), responseFingerprint);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private ScenarioDraftSet.DependencyBehaviorDraft dependencyDraft(FixtureRule rule) {
        FixtureRule.Selector selector = rule.selector();
        FixtureRule.Behavior behavior = rule.behavior();
        ScenarioDraftSet.DependencySelector authoringSelector = new ScenarioDraftSet.DependencySelector(
                selector.graphPath(), selector.nodeId(), selector.operatorRef(), selector.resourceRef(),
                selector.functionRef(), selector.attempts(), selector.occurrences(), selector.correlationKey(),
                selector.match().pathEquals());
        ScenarioDraftSet.DependencyBehavior authoringBehavior = switch (behavior.kind()) {
            case REAL -> ScenarioDraftSet.DependencyBehavior.real();
            case RETURN -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.RETURN, boundary(behavior), behavior.value(), null,
                    behavior.rawBody(), behavior.statusCode(), behavior.headers(), "", "", "", null, "");
            case THROW -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.ERROR, boundary(behavior), null, null, "", null,
                    Map.of(), behavior.errorCode(), behavior.errorType(), behavior.errorMessage(), null, "");
            case DELAY -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.DELAY, boundary(behavior), behavior.value(), null,
                    behavior.rawBody(), behavior.statusCode(), behavior.headers(), "", "", "",
                    behavior.after(), "");
            case TIMEOUT -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.TIMEOUT, boundary(behavior), null, null, "", null,
                    Map.of(), behavior.errorCode(), behavior.errorType(), behavior.errorMessage(),
                    behavior.after(), "");
            case REPLAY -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.REPLAY, boundary(behavior), null, null, "", null,
                    Map.of(), "", "", "", null, behavior.replayRef());
            case SPY -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.OBSERVE, boundary(behavior), null, null, "", null,
                    Map.of(), "", "", "", null, "");
            case DENY -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.MUST_NOT_CALL, boundary(behavior), null, null, "", null,
                    Map.of(), behavior.errorCode(), behavior.errorType(), behavior.errorMessage(), null, "");
            default -> ScenarioDraftSet.DependencyBehavior.real();
        };
        return new ScenarioDraftSet.DependencyBehaviorDraft(rule.ruleId(), authoringSelector,
                authoringBehavior,
                new ScenarioDraftSet.Consumption(rule.consumption().required(), rule.consumption().minUses(),
                        rule.consumption().maxUses(), rule.consumption().onExhausted().name(),
                        rule.consumption().onUnmatched().name()),
                new ScenarioDraftSet.SchemaCheck(rule.schemaCheck().mode().name(),
                        rule.schemaCheck().waiverReason()), "MIGRATED");
    }

    private static ScenarioDraftSet.BehaviorBoundary boundary(FixtureRule.Behavior behavior) {
        return behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                ? ScenarioDraftSet.BehaviorBoundary.TRANSPORT : ScenarioDraftSet.BehaviorBoundary.NODE;
    }

    private static List<ScenarioDraftSet.AssertionDraft> assertionDrafts(
            List<FixtureBundle.Assertion> assertions) {
        if (assertions == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        Set<String> ids = new HashSet<>();
        List<ScenarioDraftSet.AssertionDraft> result = new ArrayList<>();
        for (FixtureBundle.Assertion assertion : assertions) {
            if (assertion == null) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            }
            ScenarioDraftSet.AssertionDraft draft = assertionDraft(assertion);
            if (!ids.add(draft.assertionId())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            }
            result.add(draft);
        }
        result.sort(java.util.Comparator.comparing(ScenarioDraftSet.AssertionDraft::assertionId));
        return List.copyOf(result);
    }

    private static ScenarioDraftSet.AssertionDraft assertionDraft(FixtureBundle.Assertion assertion) {
        return new ScenarioDraftSet.AssertionDraft(
                assertionId(assertion),
                enumValue(ScenarioDraftSet.AssertionScope.class, assertion.scope(),
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH),
                assertion.nodeId(), "", "", assertion.path(),
                enumValue(ScenarioDraftSet.AssertionOperator.class, assertion.operator(),
                        ScenarioDraftSet.AssertionOperator.EQUALS), assertion.expected(), assertion.numericTolerance());
    }

    private static String assertionId(FixtureBundle.Assertion assertion) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scope", assertion.scope());
        material.put("nodeId", assertion.nodeId());
        material.put("path", assertion.path());
        material.put("operator", assertion.operator());
        material.put("expected", assertion.expected());
        material.put("numericTolerance", assertion.numericTolerance());
        String fingerprint = MigrationSupport.hash(material);
        return "assertion-" + fingerprint.substring("sha256:".length());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return value == null ? fallback : Enum.valueOf(type, value.trim().toUpperCase()); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static ScenarioDraftSet.CaseType scenarioCaseType(TestSuite.CaseType type) {
        return type == null ? ScenarioDraftSet.CaseType.GOLDEN
                : ScenarioDraftSet.CaseType.valueOf(type.name());
    }

    private ScenarioDraftSet scenarioDraftSet(String tenant,
                                               StoredFixtureBundle fixtureEnvelope,
                                               StoredTestSuite suiteEnvelope,
                                               FixtureBundle fixture,
                                               TestSuiteProtocol suite) {
        FixtureExecutionServices services;
        try {
            services = FixtureExecutionServices.from(fixture);
        } catch (RuntimeException invalid) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        String controls = services.configured() ? MigrationSupport.hash(Map.of(
                "identity", services.configuration(com.leanowtech.bloge.core.spi.ExecutionServiceKind.IDENTITY),
                "flags", services.configuration(com.leanowtech.bloge.core.spi.ExecutionServiceKind.FEATURE_FLAG),
                "secrets", services.configuration(com.leanowtech.bloge.core.spi.ExecutionServiceKind.SECRET))) : "";
        List<ScenarioDraftSet.ScenarioDraft> result = new ArrayList<>();
        for (TestSuite.TestCase testCase : suite.cases()) {
            if (testCase == null || testCase.fixtureBundleRef() == null
                    || !fixture.fixtureBundleId().equals(testCase.fixtureBundleRef().fixtureBundleId())
                    || fixture.revision() != testCase.fixtureBundleRef().revision()
                    || !fixtureEnvelope.fingerprint().equals(testCase.fixtureBundleRef().fingerprint())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
            }
            result.add(new ScenarioDraftSet.ScenarioDraft(
                    "scenario-migration:" + suite.suiteId() + ":" + testCase.caseId(),
                    "Migrated " + testCase.caseId(), "Migrated from governed TestSuite.",
                    scenarioCaseType(testCase.caseType()), List.of("migrated"),
                    new ScenarioDraftSet.Given(testCase.input(), ScenarioDraftSet.ValueProvenance.MIGRATED),
                    fixture.rules().stream().map(this::dependencyDraft).toList(),
                    new ScenarioDraftSet.Then(assertionDrafts(fixture.assertions()))));
        }
        com.leanowtech.bloge.gateway.visual.contract.ContractDraft.Target target =
                new com.leanowtech.bloge.gateway.visual.contract.ContractDraft.Target(
                        com.leanowtech.bloge.gateway.visual.contract.ContractDraft.TargetKind.GRAPH,
                        suite.target().id(), 0, suite.target().fingerprint());
        return new ScenarioDraftSet("", "scenario-migration:" + suite.suiteId(), suite.revision(),
                new ScenarioDraftSet.EnterpriseScope(tenant, fixtureEnvelope.organizationId(),
                        fixtureEnvelope.projectId(), fixtureEnvelope.environmentId(), fixtureEnvelope.region()),
                target, "", result,
                new ScenarioDraftSet.Metadata(fixtureEnvelope.createdBy(), fixture.classification(), null, null,
                        Map.of("algorithmVersion", MigrationSupport.ALGORITHM_VERSION,
                                "fixtureBundleFingerprint", fixtureEnvelope.fingerprint(),
                                "testSuiteFingerprint", suiteEnvelope.fingerprint(),
                                "executionServiceControlFingerprint", controls)));
    }

    private static String valueFingerprint(FixtureRule.Behavior behavior) {
        if (!behavior.rawBody().isBlank()) return com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.ofText(behavior.rawBody());
        return MigrationSupport.hash(behavior.value());
    }

    private static String inventoryFingerprint(InvocationInventory inventory) {
        List<Map<String, Object>> sites = inventory.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.site().invocationSiteId()))
                .map(entry -> MigrationSupport.material(
                        "siteId", entry.site().invocationSiteId(),
                        "artifactFingerprint", entry.site().artifactFingerprint(),
                        "graphPath", entry.site().graphPath(),
                        "nodeId", entry.site().nodeId(),
                        "operatorRef", entry.site().operatorRef(),
                        "resourceRef", entry.site().resourceRef(),
                        "functionRef", entry.site().functionRef(),
                        "runtimeBindingFingerprint", entry.site().runtimeBindingFingerprint(),
                        "invocationKind", entry.site().invocationKind().name(),
                        "engineStructuralId", entry.engineStructuralId()))
                .toList();
        return MigrationSupport.hash(sites);
    }

    private static boolean allowsReal(FixtureRule rule) {
        return rule.consumption().onExhausted() == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL
                || rule.consumption().onUnmatched() == FixtureRule.UnmatchedAction.ALLOW_REAL;
    }

    private static boolean isFuzzy(FixtureRule.Selector selector, boolean hasLogicalTag) {
        if (!hasLogicalTag) return false;
        return !selector.operatorRef().isBlank() || !selector.resourceRef().isBlank()
                || !selector.functionRef().isBlank() || !selector.capabilities().isEmpty()
                || selector.tags().size() != 1 || !selector.attempts().isEmpty()
                || !selector.occurrences().isEmpty() || !selector.correlationKey().isBlank()
                || (!selector.match().equals(FixtureRule.Match.none()) && !exactMatch(selector.match()));
    }

    private static boolean exactMatch(FixtureRule.Match match) {
        return match != null && match.canonicalInput() != null && !match.schema().isEmpty()
                && match.pathEquals().isEmpty() && match.pathsExist().isEmpty()
                && match.pathsAbsent().isEmpty() && match.correlationKey().isBlank()
                && match.boundedRegex().isEmpty();
    }

    private static List<String> logicalSites(WorldScenarioSourceMap sourceMap,
                                              WorldScenarioContractTagCodec.Decoded tag,
                                              InvocationInventory inventory) {
        String source = WorldScenarioSourceMap.coordinate("logical-contract",
                tag.contractId() + "@" + tag.contractFingerprint());
        return sourceMap.sourceToOutputs(source).stream().filter(output -> output.startsWith("invocation-site:"))
                .map(output -> output.substring("invocation-site:".length()))
                .filter(inventory.byInvocationSiteId()::containsKey).distinct().sorted().toList();
    }

    private static List<String> exactSelectorSites(FixtureRule.Selector selector, InvocationInventory inventory) {
        if (selector.graphPath().isBlank() || selector.nodeId().isBlank()) return List.of();
        return inventory.entries().stream().filter(entry -> entry.site().graphPath().equals(selector.graphPath())
                && entry.site().nodeId().equals(selector.nodeId())
                && entry.site().invocationKind() == selector.invocationKind())
                .map(entry -> entry.site().invocationSiteId()).sorted().toList();
    }

    private static boolean siteMatches(String site, FixtureRule.Selector selector) {
        String id = site.startsWith("/root/") ? site.substring("/root".length()) : site;
        int hash = id.lastIndexOf('#');
        if (hash < 0) return false;
        return id.substring(0, hash).equals("/" + selector.nodeId())
                && id.substring(hash + 1).equals(selector.invocationKind().name());
    }

    private static LogicalSource logicalSource(String value) {
        String body = value.substring("logical-contract:".length());
        int at = body.lastIndexOf('@');
        if (at <= 0 || at == body.length() - 1) throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        String id = body.substring(0, at);
        String fingerprint = body.substring(at + 1);
        MigrationSupport.text(id);
        MigrationSupport.fingerprint(fingerprint);
        return new LogicalSource(id, fingerprint);
    }

    private static void mapping(List<WorldMigrationDraftPackage.SourceMapping> legacy,
                                List<WorldMigrationDraftPackage.SourceMapping> reverse,
                                String kind, String sourceId, long revision, String sourceFingerprint,
                                String draftKind, String draftId) {
        WorldMigrationDraftPackage.SourceMapping value = new WorldMigrationDraftPackage.SourceMapping(
                kind, sourceId, revision, sourceFingerprint, draftKind, draftId);
        legacy.add(value);
        reverse.add(new WorldMigrationDraftPackage.SourceMapping(
                draftKind, draftId, revision, sourceFingerprint, kind, sourceId));
    }

    private static List<WorldMigrationDraftPackage.Diagnostic> distinctDiagnostics(
            List<WorldMigrationDraftPackage.Diagnostic> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static List<WorldMigrationDraftPackage.SourceMapping> distinctMappings(
            List<WorldMigrationDraftPackage.SourceMapping> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private record LogicalSource(String contractId, String fingerprint) { }
}
