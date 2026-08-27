package com.leanowtech.bloge.gateway.testing.world.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioContractTagCodec;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioSimulationCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioSimulationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioValidationService;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldMigrationServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String TENANT = "tenant-a";
    private static final String ORGANIZATION = "org-a";
    private static final String PROJECT = "project-a";
    private static final String ENVIRONMENT = "env-test";
    private static final String REGION = "region-a";
    private static final String TARGET = fp('a');
    private static final String CONTRACT = "logical.customer";
    private static final String CONTRACT_FP = fp('b');
    private static final String SITE = "/root/lookup#PRIMARY";
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void mapsReturnRuleAssertionsAndExactSourceBidirectionallyWithoutPayload() throws Exception {
        FixtureRule rule = taggedRule("return-rule", FixtureRule.Behavior.returning(
                Map.of("result", "RESPONSE-CANARY")));
        FixtureBundle bundle = bundle(List.of(rule), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE))));

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(input.value());

        assertThat(result.worldDrafts()).hasSize(1);
        assertThat(result.worldDrafts().getFirst().readiness())
                .isEqualTo(WorldDraftMaterializationPlan.Readiness.NEEDS_PREREQUISITES);
        assertThat(result.worldDrafts().getFirst().readyToMaterialize()).isFalse();
        assertThat(result.worldDrafts().getFirst().rules().getFirst().kind())
                .isEqualTo(WorldDraftMaterializationPlan.MaterializationKind.RETURN);
        assertThat(result.worldDrafts().getFirst().rules().getFirst().resultFingerprint())
                .isNotBlank();
        assertThat(result.scenarioDrafts()).hasSize(1);
        assertThat(result.scenarioDrafts().getFirst().then().assertions()).hasSize(1);
        assertThat(result.scenarioDrafts().getFirst().given().input().toString())
                .contains("INPUT-CANARY");
        assertThat(result.scenarioDrafts().getFirst().then().assertions().getFirst().expected())
                .isEqualTo("ASSERTION-CANARY");
        assertThat(result.scenarioDraftSet().contractFingerprint()).isBlank();
        assertThat(result.diagnostics()).extracting(WorldMigrationDraftPackage.Diagnostic::code)
                .contains(WorldMigrationDraftPackage.DiagnosticCode.MATERIALIZATION_PREREQUISITE_MISSING);
        assertThat(result.legacyToDraft()).extracting(WorldMigrationDraftPackage.SourceMapping::sourceKind)
                .contains("fixture-rule", "test-suite", "fixture-bundle");
        assertThat(result.draftToLegacy()).isNotEmpty();
        assertThat(MAPPER.writeValueAsString(result.payloadFreeProjection())).doesNotContain(
                "RESPONSE-CANARY", "INPUT-CANARY", "ASSERTION-CANARY");
        assertThat(result.toString()).doesNotContain("RESPONSE-CANARY", "INPUT-CANARY", "ASSERTION-CANARY");
    }

    @Test
    void mapsReplayThrowAndTimeoutToExplicitWorldBehaviorKinds() {
        List<FixtureRule> rules = List.of(
                taggedRule("replay-rule", FixtureRule.Behavior.replaying(
                        "bloge-replay:customer-response@2#" + fp('c'))),
                taggedRule("throw-rule", FixtureRule.Behavior.throwing("UPSTREAM_REJECTED", "Rejected", "secret")),
                taggedRule("timeout-rule", FixtureRule.Behavior.timeout(java.time.Duration.ofSeconds(1))));
        FixtureBundle bundle = bundle(rules, Map.of());
        List<String> sites = List.of("/root/replay#PRIMARY", "/root/throw#PRIMARY", "/root/timeout#PRIMARY");
        Map<String, List<String>> links = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            links.put(WorldScenarioSourceMap.coordinate("logical-contract", CONTRACT + "@" + CONTRACT_FP),
                    sites.stream().map(this::siteCoordinate).toList());
        }
        Input input = input(bundle, sites, links);

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(input.value());

        assertThat(result.worldDrafts()).hasSize(1);
        assertThat(result.worldDrafts().getFirst().rules()).extracting(
                WorldDraftMaterializationPlan.RulePlan::kind)
                .containsExactly(WorldDraftMaterializationPlan.MaterializationKind.REPLAY,
                        WorldDraftMaterializationPlan.MaterializationKind.FAILURE,
                        WorldDraftMaterializationPlan.MaterializationKind.FAILURE);
        assertThat(result.worldDrafts().getFirst().rules().getFirst().replayRef())
                .startsWith("bloge-replay:");
        assertThat(result.toString()).doesNotContain("Rejected", "secret");
        assertThat(result.worldDrafts().getFirst().rules()).extracting(
                WorldDraftMaterializationPlan.RulePlan::behavior)
                .containsExactly(FixtureRule.BehaviorKind.REPLAY,
                        FixtureRule.BehaviorKind.THROW,
                        FixtureRule.BehaviorKind.TIMEOUT);
    }

    @Test
    void schemaStandinIsExplorationOnlyAndNodeOnlyIsNeverGuessed() {
        FixtureRule standin = new FixtureRule("", "standin", new FixtureRule.Selector(
                "/root", "lookup", "", "", "", List.of(), List.of(),
                InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.returning("standin"), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureRule nodeOnly = new FixtureRule("", "node-only", new FixtureRule.Selector(
                "/root", "lookup", "httpResource", "", "", List.of(), List.of(),
                InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.returning("guess-me"), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = bundle(List.of(standin, nodeOnly), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of());

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(input.value());

        assertThat(result.worldDrafts()).hasSize(1);
        assertThat(result.worldDrafts().getFirst().rules().getFirst().kind())
                .isEqualTo(WorldDraftMaterializationPlan.MaterializationKind.SCHEMA_STANDIN);
        assertThat(result.diagnostics()).extracting(WorldMigrationDraftPackage.Diagnostic::code)
                .contains(WorldMigrationDraftPackage.DiagnosticCode.SCHEMA_STANDIN_EXPLORATION,
                        WorldMigrationDraftPackage.DiagnosticCode.UNMAPPED_NO_LOGICAL_CONTRACT);
        assertThat(result.logicalContractCandidates()).isEmpty();
    }

    @Test
    void spyAllowRealFuzzyAndUnfrozenReplayBecomeHumanCompletionDiagnostics() {
        FixtureRule spy = taggedRule("spy", FixtureRule.Behavior.spy());
        FixtureRule allowReal = new FixtureRule("", "allow-real", spy.selector(),
                FixtureRule.Behavior.returning("x"), new FixtureRule.Consumption(
                false, 0, 1, FixtureRule.ExhaustedAction.FALLBACK_TO_REAL,
                FixtureRule.UnmatchedAction.FAIL), FixtureRule.SchemaCheck.strict());
        FixtureRule fuzzy = new FixtureRule("", "fuzzy", new FixtureRule.Selector(
                "", "", "operator", "", "", List.of(), List.of(WorldScenarioContractTagCodec.encode(
                CONTRACT, CONTRACT_FP)), InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "",
                FixtureRule.Match.none()), FixtureRule.Behavior.returning("x"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureRule unfrozen = taggedRule("unfrozen", FixtureRule.Behavior.replaying("latest-response"));
        FixtureBundle bundle = bundle(List.of(spy, allowReal, fuzzy, unfrozen), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE))));

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(input.value());

        assertThat(result.worldDrafts()).isEmpty();
        assertThat(result.diagnostics()).extracting(WorldMigrationDraftPackage.Diagnostic::code)
                .contains(WorldMigrationDraftPackage.DiagnosticCode.SPY_NOT_PROMOTABLE,
                        WorldMigrationDraftPackage.DiagnosticCode.ALLOW_REAL_NOT_PROMOTABLE,
                        WorldMigrationDraftPackage.DiagnosticCode.FUZZY_SELECTOR_NOT_PROMOTABLE,
                        WorldMigrationDraftPackage.DiagnosticCode.UNFROZEN_REPLAY);
        assertThat(result.completionChecklist()).hasSize(5);
    }

    @Test
    void executionServiceFixtureBecomesScenarioControlNotWorldRule() throws Exception {
        Map<String, Object> executionServices = Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of("tenant", "TENANT-CANARY"),
                "featureFlags", Map.of("new-flow", true));
        FixtureBundle bundle = bundle(List.of(), Map.of(FixtureExecutionServices.METADATA_KEY, executionServices));
        Input input = input(bundle, List.of(SITE), Map.of());

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(input.value());

        assertThat(result.worldDrafts()).isEmpty();
        assertThat(result.scenarioDraftSet().metadata().provenance()
                .get("executionServiceControlFingerprint")).isNotEqualTo("");
        assertThat(MAPPER.writeValueAsString(result.payloadFreeProjection())).doesNotContain("TENANT-CANARY");
    }

    @Test
    void unknownFixtureSiteAndSourceMapExtraLinkFailClosed() {
        FixtureBundle bundle = bundle(List.of(taggedRule("return-rule", FixtureRule.Behavior.returning("x"))), Map.of());
        Input unknownSite = input(bundle, List.of(SITE), Map.of(logicalSource(),
                List.of(siteCoordinate("/root/not-in-inventory#PRIMARY"))));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(unknownSite.value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.MAPPING_MISSING));

        Input extraLink = input(bundle, List.of(SITE), Map.of(logicalSource(),
                List.of(siteCoordinate(SITE), "fixture-rule:foreign")));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(extraLink.value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));
    }

    @Test
    void ambiguousSourceMapAndWrongRequestFingerprintFailClosed() {
        FixtureBundle bundle = bundle(List.of(taggedRule("return-rule", FixtureRule.Behavior.returning("x"))), Map.of());
        String otherSource = WorldScenarioSourceMap.coordinate("logical-contract", "other@" + CONTRACT_FP);
        Input ambiguous = input(bundle, List.of(SITE), Map.of(
                logicalSource(), List.of(siteCoordinate(SITE)),
                otherSource, List.of(siteCoordinate(SITE))));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(ambiguous.value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));

        WorldMigrationSource.Request wrong = new WorldMigrationSource.Request(
                "bundle", 1, ambiguous.fixture().fingerprint(), "suite", 1,
                ambiguous.suite().fingerprint(), fp('d'));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(
                new WorldMigrationSource.Access(TENANT, "actor", "migration"),
                new InMemoryWorldMigrationSource(ambiguous.value()), wrong))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_TAMPERED));
    }

    @Test
    void sourceTenantAndStoredFingerprintAreValidatedBeforeDraftCreation() {
        FixtureBundle bundle = bundle(List.of(), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of());
        WorldMigrationInput crossTenant = new WorldMigrationInput("tenant-b", input.fixture(), input.suite(),
                input.value().compilation(), input.value().inventory());
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(crossTenant))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_TENANT_MISMATCH));

        StoredFixtureBundle tampered = new StoredFixtureBundle("", TENANT, "env", "bundle", 1,
                fp('e'), bundle, NOW, "actor");
        WorldMigrationInput inputWithTamper = new WorldMigrationInput(TENANT, tampered, input.suite(),
                input.value().compilation(), input.value().inventory());
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(inputWithTamper))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));
    }

    @Test
    void mixedSuiteCasesBecomeIndependentScenarioDraftsAndWrongContractNeverGuesses() {
        FixtureBundle bundle = bundle(List.of(), Map.of());
        Input base = input(bundle, List.of(SITE), Map.of());
        String fixtureFp = base.fixture().fingerprint();
        List<TestSuite.TestCase> cases = List.of(
                new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN, Map.of("case", "A"),
                        new TestSuite.FixtureBundleRef("bundle", 1, fixtureFp), List.of("happy"), Map.of()),
                new TestSuite.TestCase("negative", TestSuite.CaseType.NEGATIVE, Map.of("case", "B"),
                        new TestSuite.FixtureBundleRef("bundle", 1, fixtureFp), List.of("failure"), Map.of()));
        StoredTestSuite suite = suiteEnvelope(fixtureFp, cases);
        WorldMigrationInput mixed = new WorldMigrationInput(TENANT, base.fixture(), suite,
                base.value().compilation(), base.value().inventory());

        WorldMigrationDraftPackage result = new WorldMigrationService(MAPPER).migrate(mixed);

        assertThat(result.scenarioDrafts()).extracting(ScenarioDraftSet.ScenarioDraft::scenarioId)
                .containsExactly("scenario-migration:suite:golden", "scenario-migration:suite:negative");
        assertThat(result.scenarioDrafts()).allSatisfy(scenario ->
                assertThat(scenario.then().assertions()).hasSize(1));

        FixtureRule wrongContract = new FixtureRule("", "wrong-contract", new FixtureRule.Selector(
                "", "", "", "", "", List.of(), List.of(WorldScenarioContractTagCodec.encode(
                "logical.order", CONTRACT_FP)), InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "",
                FixtureRule.Match.none()), FixtureRule.Behavior.returning("guess"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        Input wrong = input(bundle(List.of(wrongContract), Map.of()), List.of(SITE),
                Map.of(WorldScenarioSourceMap.coordinate("logical-contract", "logical.order@" + CONTRACT_FP),
                        List.of(siteCoordinate(SITE))));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(wrong.value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));
    }

    @Test
    void reorderedAuthoritativeInventoryProducesTheSamePackageTwentyTimes() {
        FixtureRule rule = taggedRule("return-rule", FixtureRule.Behavior.returning("x"));
        FixtureBundle bundle = bundle(List.of(rule), Map.of());
        List<String> firstSites = List.of("/root/alpha#PRIMARY", "/root/beta#PRIMARY");
        Map<String, List<String>> firstLinks = Map.of(logicalSource(), firstSites.stream()
                .map(this::siteCoordinate).toList());
        Input first = input(bundle, firstSites, firstLinks);
        List<String> reversedSites = List.of("/root/beta#PRIMARY", "/root/alpha#PRIMARY");
        Map<String, List<String>> reversedLinks = Map.of(logicalSource(), reversedSites.stream()
                .map(this::siteCoordinate).toList());
        Input reversed = input(bundle, reversedSites, reversedLinks);

        WorldMigrationService service = new WorldMigrationService(MAPPER);
        String expected = service.migrate(first.value()).fingerprint();
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(service.migrate(reversed.value()).fingerprint()).isEqualTo(expected);
        }
    }

    @Test
    void duplicateInventoryIdentityIsRejectedBeforeAnyDraftIsReturned() {
        FixtureBundle bundle = bundle(List.of(), Map.of());
        Input input = input(bundle, List.of(SITE, SITE), Map.of());

        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(input.value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));
    }

    @Test
    void migrationIsDeterministicTwentyTimesAndSinkIsAtomicAndIdempotent() throws Exception {
        FixtureBundle bundle = bundle(List.of(
                taggedRule("b", FixtureRule.Behavior.returning("b")),
                taggedRule("a", FixtureRule.Behavior.throwing("E", "T", "secret"))), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE))));
        WorldMigrationService service = new WorldMigrationService(MAPPER);
        WorldMigrationDraftPackage first = service.migrate(input.value());
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(service.migrate(input.value()).fingerprint()).isEqualTo(first.fingerprint());
        }
        byte[] before = MAPPER.writeValueAsBytes(List.of(input.fixture(), input.suite()));
        InMemoryWorldMigrationDraftSink sink = new InMemoryWorldMigrationDraftSink();
        sink.failNextWrite();
        assertThatThrownBy(() -> service.migrateAndStore(new WorldMigrationSource.Access(
                TENANT, "actor", "migration"), new InMemoryWorldMigrationSource(input.value()),
                request(input), sink)).isInstanceOf(WorldMigrationException.class);
        assertThat(sink.find(TENANT, first.fingerprint())).isEmpty();
        assertThat(MAPPER.writeValueAsBytes(List.of(input.fixture(), input.suite()))).isEqualTo(before);

        assertThat(service.migrateAndStore(new WorldMigrationSource.Access(TENANT, "actor", "migration"),
                new InMemoryWorldMigrationSource(input.value()), request(input), sink).created()).isTrue();
        assertThat(service.migrateAndStore(new WorldMigrationSource.Access(TENANT, "actor", "migration"),
                new InMemoryWorldMigrationSource(input.value()), request(input), sink).created()).isFalse();
    }

    @Test
    void oversizedRuleIdentityAndDuplicateInventoryFailClosedWithoutPayloadInError() {
        FixtureRule huge = taggedRule("r".repeat(513), FixtureRule.Behavior.returning("PAYLOAD-CANARY"));
        FixtureBundle bundle = bundle(List.of(huge), Map.of());
        Input input = input(bundle, List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE))));
        assertThatThrownBy(() -> new WorldMigrationService(MAPPER).migrate(input.value()))
                .isInstanceOf(WorldMigrationException.class)
                .hasMessageNotContaining("PAYLOAD-CANARY");
    }

    @Test
    void migratedAuthoringDraftCompilesThroughExistingScenarioSimulationPath() {
        FixtureRule rule = taggedExactRule("return-rule", FixtureRule.Behavior.returning(Map.of("score", 720)));
        FixtureBundle bundle = bundle(List.of(rule), Map.of());
        String site = "/root/crm#PRIMARY";
        Input input = input(bundle, List.of(site), Map.of(logicalSource(), List.of(siteCoordinate(site))));

        WorldMigrationDraftPackage migrated = new WorldMigrationService(MAPPER).migrate(input.value());
        GraphDraft graph = scenarioGraph();
        ContractDraft contract = new ContractDraftProjectionService().project(graph, TARGET);
        assertThat(migrated.scenarioDraftSet().contractFingerprint()).isBlank();
        assertThat(new ScenarioValidationService(MAPPER)
                .validate(migrated.scenarioDraftSet(), contract, graph).valid()).isFalse();
        ScenarioDraftSet authoring = migrated.scenarioDraftSetFor(contract, MAPPER);
        assertThat(authoring.scope()).isEqualTo(new ScenarioDraftSet.EnterpriseScope(
                TENANT, ORGANIZATION, PROJECT, ENVIRONMENT, REGION));
        assertThat(authoring.scope().organizationId()).isNotEqualTo("migrated");
        assertThat(new ScenarioValidationService(MAPPER).validate(authoring, contract, graph).valid()).isTrue();
        ScenarioSimulationPlan compiled = new ScenarioSimulationCompiler(
                new ScenarioValidationService(MAPPER)).compile(graph, contract, authoring,
                authoring.scenarios().getFirst().scenarioId());

        assertThat(compiled.compiled()).as("compiler diagnostics: %s", compiled.diagnostics()).isTrue();
        assertThat(compiled.request().fixtures().get("crm").output())
                .isEqualTo(Map.of("score", 720));
        assertThat(compiled.assertions().getFirst().expected()).isEqualTo("ASSERTION-CANARY");
        assertThat(authoring.scenarios().getFirst().given().provenance())
                .isEqualTo(ScenarioDraftSet.ValueProvenance.MIGRATED);
    }

    @Test
    void worldMaterializerReceivesAnExactGovernedRuleAndReturnsAnExistingUnpublishedRule() {
        FixtureRule rule = exactRule("return-rule", Map.of("request", "REQUEST-CANARY"),
                Map.of("type", "object"), Map.of("response", "RESPONSE-CANARY"));
        String site = "/root/crm#PRIMARY";
        Input input = input(bundle(List.of(rule), Map.of()), List.of(site),
                Map.of(logicalSource(), List.of(siteCoordinate(site))));
        WorldMigrationService service = new WorldMigrationService(MAPPER);
        WorldMigrationSource.Access access = new WorldMigrationSource.Access(TENANT, "actor", "migration");

        WorldMigrationDraftMaterializer.Result materialized = service.materializeWorldRule(
                access, new InMemoryWorldMigrationSource(input.value()), request(input),
                "world-migration:suite:1", "return-rule", materialization -> {
                    FixtureRule source = materialization.sourceRule();
                    WorldDraftRule expected = new WorldDraftRule(
                            MigrationSupport.hash(source.selector().match().schema()),
                            MigrationSupport.hash(source.selector().match().canonicalInput()),
                            materialization.plan().resultFingerprint());
                WorldDraftRule ruleWithFragment = new WorldDraftRule(
                            expected.requestSchemaFingerprint(), expected.inputFingerprint(),
                            expected.responseFingerprint(), new WorldDraftFragmentRef(
                            BlogeFragmentRef.frozen("migrated-world.bloge",
                            "graph world { transform response { value = ctx.value } }")));
                    assertThat(materialization.sourceRule().behavior().value())
                            .isEqualTo(Map.of("response", "RESPONSE-CANARY"));
                    return new WorldMigrationDraftMaterializer.Result(ruleWithFragment,
                            ruleWithFragment.fingerprint());
                });

        assertThat(materialized.rule().fragment()).isNotNull();
        assertThat(input.value().compilation()).isNotNull();
        assertThat(new WorldMigrationService(MAPPER).migrate(input.value()).worldDrafts().getFirst().readiness())
                .isEqualTo(WorldDraftMaterializationPlan.Readiness.READY_TO_MATERIALIZE);
        assertThat(materialized.rule().responseFingerprint())
                .isEqualTo(MigrationSupport.hash(Map.of("response", "RESPONSE-CANARY")));
        assertThat(materialized.toString()).doesNotContain("RESPONSE-CANARY", "REQUEST-CANARY");
    }

    @Test
    void worldMaterializerDoesNotGuessWhenLegacyRuleHasNoExactInput() {
        FixtureRule rule = taggedRule("return-rule", FixtureRule.Behavior.returning(Map.of("response", "A")));
        Input input = input(bundle(List.of(rule), Map.of()), List.of(SITE),
                Map.of(logicalSource(), List.of(siteCoordinate(SITE))));
        WorldMigrationService service = new WorldMigrationService(MAPPER);

        assertThatThrownBy(() -> service.materializeWorldRule(
                new WorldMigrationSource.Access(TENANT, "actor", "migration"),
                new InMemoryWorldMigrationSource(input.value()), request(input),
                "world-migration:suite:1", "return-rule", ignored -> null))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldMigrationException.Code.MATERIALIZATION_PREREQUISITE_MISSING));
    }

    @Test
    void migrationPlanCannotMasqueradeAsWorldAssetOrGovernedSourceRef() {
        assertThat(java.util.Arrays.stream(WorldDraftMaterializationPlan.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("status");
        assertThat(java.util.Arrays.stream(WorldDraftMaterializationPlan.RulePlan.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getType)
                .map(Class::getName))
                .doesNotContain(WorldDraftRule.class.getName(),
                        "com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftSourceRef");
    }

    @Test
    void assertionIdsAreCanonicalAcrossReorderingAndDuplicateContentFailsClosed() {
        FixtureBundle.Assertion first = new FixtureBundle.Assertion(
                "OUTPUT_PATH", "decision", "/result", "EQUALS", "ASSERTION-A", null);
        FixtureBundle.Assertion second = new FixtureBundle.Assertion(
                "OUTPUT_PATH", "decision", "/result", "EQUALS", "ASSERTION-B", null);
        FixtureRule rule = taggedRule("return-rule", FixtureRule.Behavior.returning("x"));
        WorldMigrationService service = new WorldMigrationService(MAPPER);
        WorldMigrationDraftPackage ordered = service.migrate(input(
                bundleWithAssertions(List.of(rule), List.of(first, second), Map.of()),
                List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE)))).value());
        WorldMigrationDraftPackage reversed = service.migrate(input(
                bundleWithAssertions(List.of(rule), List.of(second, first), Map.of()),
                List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE)))).value());
        assertThat(ordered.scenarioDrafts().getFirst().then().assertions())
                .extracting(ScenarioDraftSet.AssertionDraft::assertionId)
                .containsExactlyElementsOf(reversed.scenarioDrafts().getFirst().then().assertions().stream()
                        .map(ScenarioDraftSet.AssertionDraft::assertionId).toList());

        assertThatThrownBy(() -> service.migrate(input(
                bundleWithAssertions(List.of(rule), List.of(first, first), Map.of()),
                List.of(SITE), Map.of(logicalSource(), List.of(siteCoordinate(SITE)))).value()))
                .isInstanceOfSatisfying(WorldMigrationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldMigrationException.Code.SOURCE_INTEGRITY));
    }

    private static FixtureRule taggedRule(String id, FixtureRule.Behavior behavior) {
        return new FixtureRule("", id, new FixtureRule.Selector("", "", "", "", "", List.of(),
                List.of(WorldScenarioContractTagCodec.encode(CONTRACT, CONTRACT_FP)),
                InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", FixtureRule.Match.none()),
                behavior, FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule taggedExactRule(String id, FixtureRule.Behavior behavior) {
        return new FixtureRule("", id, new FixtureRule.Selector("/root", "crm", "", "", "", List.of(),
                List.of(WorldScenarioContractTagCodec.encode(CONTRACT, CONTRACT_FP)),
                InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "", FixtureRule.Match.none()),
                behavior, FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule exactRule(String id, Object input, Map<String, Object> schema, Object output) {
        return new FixtureRule("", id, new FixtureRule.Selector("/root", "crm", "", "", "", List.of(),
                List.of(WorldScenarioContractTagCodec.encode(CONTRACT, CONTRACT_FP)),
                InvocationSite.InvocationKind.PRIMARY, List.of(), List.of(), "",
                new FixtureRule.Match(input, Map.of(), List.of(), List.of(), schema, "", Map.of())),
                FixtureRule.Behavior.returning(output), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
    }

    private static FixtureBundle bundle(List<FixtureRule> rules, Map<String, Object> metadata) {
        return bundleWithAssertions(rules, List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "decision", "/result", "EQUALS", "ASSERTION-CANARY", null)), metadata);
    }

    private static FixtureBundle bundleWithAssertions(List<FixtureRule> rules,
                                                      List<FixtureBundle.Assertion> assertions,
                                                      Map<String, Object> metadata) {
        return new FixtureBundle("", "bundle", 1, TARGET, "INTERNAL", null, null, rules,
                assertions, metadata);
    }

    private static GraphDraft scenarioGraph() {
        SchemaEnvelope input = SchemaEnvelope.object(
                Map.of("applicantId", Map.of("type", "string")), List.of("applicantId"));
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("decision", Map.of("type", "string")), List.of("decision"));
        return new GraphDraft("", "graph", 1, "loanPolicy", TENANT, "local", "test", "", input, output,
                List.of(new GraphDraft.DraftNode("crm", "crm:lookup", "CRM", Map.of(), Map.of(),
                                new GraphDraft.Position(0, 0)),
                        new GraphDraft.DraftNode("decision", "bloge:transform", "Decision", Map.of(), Map.of(),
                                new GraphDraft.Position(300, 0))),
                List.of(new GraphDraft.DraftEdge("crm-decision", "data",
                        new GraphDraft.Endpoint("crm", "profile", ""),
                        new GraphDraft.Endpoint("decision", "applicant", ""))),
                Map.of(), Map.of(), new GraphDraft.OutputSelection("decision", ""), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static Input input(FixtureBundle bundle, List<String> sites,
                               Map<String, List<String>> logicalLinks) {
        String bundleFp = ProtocolFingerprint.of(MAPPER, bundle);
        StoredFixtureBundle fixture = new StoredFixtureBundle("", TENANT, ORGANIZATION, PROJECT,
                ENVIRONMENT, REGION, "bundle", 1, bundleFp, bundle, NOW, "actor");
        StoredTestSuite storedSuite = suiteEnvelope(bundleFp, List.of(new TestSuite.TestCase("case-1",
                TestSuite.CaseType.GOLDEN, Map.of("applicantId", "INPUT-CANARY"),
                new TestSuite.FixtureBundleRef("bundle", 1, bundleFp), List.of(), Map.of())));
        InvocationInventory inventory = inventory(sites);
        WorldScenarioCompilation compilation = compilation(bundle, logicalLinks, inventory, sites);
        return new Input(new WorldMigrationInput(TENANT, fixture, storedSuite, compilation, inventory),
                fixture, storedSuite);
    }

    private static StoredTestSuite suiteEnvelope(String bundleFingerprint, List<TestSuite.TestCase> cases) {
        TestSuite suite = new TestSuite("", "suite", 1, new TestSuite.Target("GRAPH", "graph", TARGET),
                "INTERNAL", cases, TestSuite.CoveragePolicy.defaults(),
                TestSuite.PromotionPolicy.defaults(), Map.of());
        String suiteFp = new TestSuiteProtocolCodec(MAPPER).fingerprint(suite);
        return new StoredTestSuite("", TENANT, ORGANIZATION, PROJECT, ENVIRONMENT, REGION,
                "suite", 1, suiteFp, suite, NOW, "actor");
    }

    private static InvocationInventory inventory(List<String> siteIds) {
        List<InvocationInventory.Entry> entries = new ArrayList<>();
        for (String siteId : siteIds) {
            InvocationSite site = new InvocationSite("", TARGET, "/root", siteId.substring("/root/".length(),
                    siteId.indexOf('#')), "operator", "", "", "", InvocationSite.InvocationKind.PRIMARY,
                    null, "", null);
            InvocationInventory.Entry entry = new InvocationInventory.Entry(mock(Graph.class), mock(NodeSpec.class),
                    site, "engine-" + siteId, new Object());
            entries.add(entry);
        }
        Map<String, InvocationInventory.Entry> byId = new HashMap<>();
        for (InvocationInventory.Entry entry : entries) byId.put(entry.site().invocationSiteId(), entry);
        return new InvocationInventory(entries, byId, byId);
    }

    private static WorldScenarioCompilation compilation(FixtureBundle bundle,
                                                        Map<String, List<String>> links,
                                                        InvocationInventory inventory,
                                                        List<String> sites) {
        WorldScenarioSourceMap sourceMap = mock(WorldScenarioSourceMap.class);
        Map<String, List<String>> forward = new LinkedHashMap<>(links);
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        forward.forEach((source, outputs) -> outputs.forEach(output -> reverse.computeIfAbsent(output,
                ignored -> new ArrayList<>()).add(source)));
        when(sourceMap.sourceToOutputs()).thenReturn(forward);
        when(sourceMap.outputToSources()).thenReturn(reverse);
        forward.forEach((source, outputs) -> when(sourceMap.sourceToOutputs(source)).thenReturn(outputs));
        reverse.forEach((output, sources) -> when(sourceMap.outputToSources(output)).thenReturn(sources));
        WorldDelegateBinding binding = new WorldDelegateBinding("rule", CONTRACT, CONTRACT_FP,
                BlogeFragmentRef.frozen("world.bloge", "graph world { transform response { value = ctx.value } }"));
        WorldScenarioCompilation compilation = mock(WorldScenarioCompilation.class);
        when(compilation.bundle()).thenReturn(bundle);
        when(compilation.bindings()).thenReturn(List.of(binding));
        when(compilation.sourceMap()).thenReturn(sourceMap);
        when(compilation.fingerprint()).thenReturn(fp('f'));
        when(compilation.verifyFingerprint()).thenReturn(compilation);
        return compilation;
    }

    private static WorldMigrationSource.Request request(Input input) {
        return new WorldMigrationSource.Request("bundle", 1, input.fixture().fingerprint(), "suite", 1,
                input.suite().fingerprint(), TARGET);
    }

    private static String logicalSource() {
        return WorldScenarioSourceMap.coordinate("logical-contract", CONTRACT + "@" + CONTRACT_FP);
    }

    private String siteCoordinate(String site) {
        return WorldScenarioSourceMap.coordinate("invocation-site", site);
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Input(WorldMigrationInput value, StoredFixtureBundle fixture, StoredTestSuite suite) { }
}
