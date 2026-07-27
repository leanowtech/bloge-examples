package com.leanowtech.bloge.gateway.visual.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden and fail-closed tests for the Scenario-to-testing-control-plane compiler.
 */
class ScenarioGovernedCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ScenarioValidationService validation = new ScenarioValidationService(objectMapper);
    private final ScenarioGovernedCompiler compiler =
            new ScenarioGovernedCompiler(validation, objectMapper);

    @Test
    void compilesEveryAuthoringBehaviorToTheExistingFixtureRuleProtocol() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioDraftSet draftSet = draftSet(graph, contract, dependencies(), assertions());

        ScenarioGovernedCompilationPlan plan =
                compiler.compile(graph, contract, draftSet, runtimeTarget());

        assertThat(plan.compiled()).isTrue();
        assertThat(plan.diagnostics()).isEmpty();
        assertThat(plan.fixtures()).hasSize(1);
        assertThat(plan.fixtures().getFirst().request().fixtureBundle().rules())
                .extracting(rule -> rule.behavior().kind())
                .containsExactly(
                        FixtureRule.BehaviorKind.REAL,
                        FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.BehaviorKind.THROW,
                        FixtureRule.BehaviorKind.DELAY,
                        FixtureRule.BehaviorKind.TIMEOUT,
                        FixtureRule.BehaviorKind.REPLAY,
                        FixtureRule.BehaviorKind.SPY,
                        FixtureRule.BehaviorKind.DENY);
        assertThat(plan.fixtures().getFirst().request().fixtureBundle().logicalClock())
                .isNotNull();
        FixtureRule matched = plan.fixtures().getFirst().request().fixtureBundle().rules().get(1);
        assertThat(matched.selector().match().pathEquals()).containsEntry("/tenant", "acme");
        assertThat(matched.selector().attempts()).containsExactly(1, 2);
        assertThat(matched.consumption().onExhausted())
                .isEqualTo(FixtureRule.ExhaustedAction.FALLBACK_TO_REAL);
    }

    @Test
    void compilesAssertionsAndExactFixtureReferencesIntoOneSuite() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioGovernedCompilationPlan plan = compiler.compile(
                graph,
                contract,
                draftSet(graph, contract, dependencies(), assertions()),
                runtimeTarget());

        var fixture = plan.fixtures().getFirst();
        var bundle = fixture.request().fixtureBundle();
        TestSuite suite = (TestSuite) plan.suite().testSuite();

        assertThat(bundle.assertions()).extracting("scope")
                .containsExactly("OUTPUT_PATH", "NODE_STATUS", "FIXTURE_USES");
        assertThat(bundle.assertions().get(1).operator()).isEqualTo("EQUALS");
        assertThat(bundle.assertions().get(2))
                .extracting("nodeId", "operator", "expected")
                .containsExactly("crm-real", "GREATER_OR_EQUAL", 1);
        assertThat(suite.cases()).singleElement().satisfies(testCase -> {
            assertThat(testCase.fixtureBundleRef().fixtureBundleId())
                    .isEqualTo(bundle.fixtureBundleId());
            assertThat(testCase.fixtureBundleRef().fingerprint())
                    .isEqualTo(fixture.fingerprint());
            assertThat(testCase.input()).isEqualTo(Map.of("applicantId", "A-1"));
        });
        assertThat(suite.coveragePolicy().requiredEdgeTransfers())
                .containsExactly(new TestSuite.EdgeTransferRef(
                        "/crm#PRIMARY", "/decision#PRIMARY"));
        assertThat(suite.coveragePolicy().minimumAssertionsPerCase()).isEqualTo(3);
        assertThat(suite.promotionPolicy().minimumCertifiableCases()).isEqualTo(1);
    }

    @Test
    void producesStableContentAddressedAssetsForTheSameExactInputs() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioDraftSet draftSet = draftSet(graph, contract, dependencies(), assertions());

        ScenarioGovernedCompilationPlan first =
                compiler.compile(graph, contract, draftSet, runtimeTarget());
        ScenarioGovernedCompilationPlan second =
                compiler.compile(graph, contract, draftSet, runtimeTarget());

        assertThat(first).isEqualTo(second);
        assertThat(first.fixtures().getFirst().fingerprint())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(first.fixtures().getFirst().request().fixtureBundle().fixtureBundleId())
                .startsWith("scenario-loan-scenarios-controlled-path-");
        assertThat(first.suite().testSuite().suiteId())
                .startsWith("scenario-suite-loan-scenarios-");
    }

    @Test
    void failsClosedBeforeEnumConversionWhenGovernanceValuesAreInvalid() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioDraftSet.DependencyBehaviorDraft invalid =
                new ScenarioDraftSet.DependencyBehaviorDraft(
                        "invalid",
                        new ScenarioDraftSet.DependencySelector(
                                "/root", "crm", "", "", "",
                                List.of(2, 1), List.of(0), "", Map.of("tenant", "acme")),
                        ScenarioDraftSet.DependencyBehavior.real(),
                        new ScenarioDraftSet.Consumption(true, 2, 1, "IGNORE", "REAL"),
                        new ScenarioDraftSet.SchemaCheck("DISABLED", ""),
                        "AUTHORED");

        ScenarioGovernedCompilationPlan plan = compiler.compile(
                graph,
                contract,
                draftSet(graph, contract, List.of(invalid), List.of()),
                runtimeTarget());

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.fixtures()).isEmpty();
        assertThat(plan.diagnostics()).extracting("code")
                .contains(
                        "visual.scenario.dependency.attemptsInvalid",
                        "visual.scenario.dependency.occurrencesInvalid",
                        "visual.scenario.dependency.matchPathInvalid",
                        "visual.scenario.dependency.consumptionInvalid",
                        "visual.scenario.dependency.onExhaustedInvalid",
                        "visual.scenario.dependency.onUnmatchedInvalid",
                        "visual.scenario.dependency.schemaCheckModeInvalid");
    }

    @Test
    void rejectsRuntimeTargetThatDoesNotMatchTheVisualGraphName() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);

        ScenarioGovernedCompilationPlan plan = compiler.compile(
                graph,
                contract,
                draftSet(graph, contract, dependencies(), assertions()),
                new TestExecutionApiRequest.Target(
                        "GRAPH", "otherGraph", ScenarioValidationServiceTest.fingerprint('e')));

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.runtimeTargetGraphMismatch");
    }

    @Test
    void rejectsEmptyOversizedPropertyAndNonCanonicalInputsBeforePublication() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioDraftSet empty = new ScenarioDraftSet(
                "", "empty", 1,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"),
                contract.target(), contract.fingerprint(objectMapper), List.of(),
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null, Map.of()));
        assertThat(compiler.compile(graph, contract, empty, runtimeTarget()).diagnostics())
                .extracting("code")
                .contains("visual.scenario.scenarios.empty");

        ScenarioDraftSet.ScenarioDraft property = scenario(
                "property", ScenarioDraftSet.CaseType.PROPERTY, List.of(), List.of());
        ScenarioDraftSet propertySet = withScenarios(graph, contract, List.of(property));
        assertThat(compiler.compile(graph, contract, propertySet, runtimeTarget()).diagnostics())
                .extracting("code")
                .contains("visual.scenario.compile.propertyMaterializationRequired");

        List<ScenarioDraftSet.ScenarioDraft> tooMany = IntStream.rangeClosed(
                        1, TestSuiteRegistryService.MAX_CASES + 1)
                .mapToObj(index -> scenario(
                        "case-" + index, ScenarioDraftSet.CaseType.GOLDEN, List.of(), List.of()))
                .toList();
        assertThat(compiler.compile(
                graph, contract, withScenarios(graph, contract, tooMany), runtimeTarget())
                .diagnostics()).extracting("code")
                .contains("visual.scenario.compile.caseLimitExceeded");

        assertThat(compiler.compile(
                graph,
                contract,
                draftSet(graph, contract, dependencies(), assertions()),
                new TestExecutionApiRequest.Target("GRAPH", "loanPolicy", "not-a-fingerprint"))
                .diagnostics()).extracting("code")
                .contains("visual.scenario.compile.runtimeTargetInvalid");
    }

    @Test
    void boundedContentAddressesRetainTheCompleteDigestForLongSourceIds() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = contract(graph);
        ScenarioDraftSet base = draftSet(graph, contract, dependencies(), assertions());
        String longSetId = "s".repeat(240);
        String longScenarioId = "c".repeat(240);
        ScenarioDraftSet longIds = new ScenarioDraftSet(
                base.schemaVersion(),
                longSetId,
                base.revision(),
                base.scope(),
                base.target(),
                base.contractFingerprint(),
                List.of(scenario(
                        longScenarioId, ScenarioDraftSet.CaseType.GOLDEN,
                        dependencies(), assertions())),
                base.metadata());

        ScenarioGovernedCompilationPlan plan =
                compiler.compile(graph, contract, longIds, runtimeTarget());
        String fixtureId = plan.fixtures().getFirst().request()
                .fixtureBundle().fixtureBundleId();
        String suiteId = plan.suite().testSuite().suiteId();

        assertThat(fixtureId).hasSizeLessThanOrEqualTo(255)
                .matches(".*-[0-9a-f]{64}$");
        assertThat(suiteId).hasSizeLessThanOrEqualTo(255)
                .matches(".*-[0-9a-f]{64}$");
    }

    private ContractDraft contract(GraphDraft graph) {
        return new ContractDraftProjectionService().project(
                graph, ScenarioValidationServiceTest.fingerprint('a'));
    }

    private ScenarioDraftSet draftSet(
            GraphDraft graph,
            ContractDraft contract,
            List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies,
            List<ScenarioDraftSet.AssertionDraft> assertions) {
        ScenarioDraftSet.ScenarioDraft scenario = scenario(
                "controlled-path", ScenarioDraftSet.CaseType.REGRESSION,
                dependencies, assertions);
        return withScenarios(graph, contract, List.of(scenario));
    }

    private ScenarioDraftSet withScenarios(
            GraphDraft graph,
            ContractDraft contract,
            List<ScenarioDraftSet.ScenarioDraft> scenarios) {
        return new ScenarioDraftSet(
                "",
                "loan-scenarios",
                7,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"),
                contract.target(),
                contract.fingerprint(objectMapper),
                scenarios,
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null, Map.of("source", "test")));
    }

    private static ScenarioDraftSet.ScenarioDraft scenario(
            String id,
            ScenarioDraftSet.CaseType caseType,
            List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies,
            List<ScenarioDraftSet.AssertionDraft> assertions) {
        return new ScenarioDraftSet.ScenarioDraft(
                id,
                "Controlled dependency path",
                "Exercises governed dependency behavior.",
                caseType,
                List.of("loan", "governed"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                dependencies,
                new ScenarioDraftSet.Then(assertions));
    }

    private static List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies() {
        return List.of(
                dependency("crm-real", ScenarioDraftSet.DependencySelector.node("crm"),
                        ScenarioDraftSet.DependencyBehavior.real()),
                new ScenarioDraftSet.DependencyBehaviorDraft(
                        "operator-return",
                        new ScenarioDraftSet.DependencySelector(
                                "/root", "", "risk:return", "", "",
                                List.of(1, 2), List.of(1), "customer-A",
                                Map.of("/tenant", "acme")),
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720)),
                        new ScenarioDraftSet.Consumption(
                                true, 1, 2, "FALLBACK_TO_REAL", "FAIL"),
                        ScenarioDraftSet.SchemaCheck.strict(),
                        "AUTHORED"),
                dependency("resource-error", resource("crm:profile"),
                        behavior(ScenarioDraftSet.BehaviorKind.ERROR, null, null,
                                "CRM_REJECTED", "UPSTREAM", "CRM rejected request.", "")),
                dependency("function-delay", function("risk.normalize"),
                        behavior(ScenarioDraftSet.BehaviorKind.DELAY, Map.of("score", 710),
                                Duration.ofMillis(50), "", "", "", "")),
                dependency("operator-timeout", operator("risk:timeout"),
                        behavior(ScenarioDraftSet.BehaviorKind.TIMEOUT, null,
                                Duration.ofMillis(500), "CRM_TIMEOUT", "", "CRM timed out.", "")),
                dependency("resource-replay", resource("crm:replay"),
                        behavior(ScenarioDraftSet.BehaviorKind.REPLAY, null, null,
                                "", "", "", "replay-1@3#sha256:abc")),
                dependency("decision-observe", ScenarioDraftSet.DependencySelector.node("decision"),
                        behavior(ScenarioDraftSet.BehaviorKind.OBSERVE, null, null,
                                "", "", "", "")),
                dependency("function-deny", function("risk.forbidden"),
                        behavior(ScenarioDraftSet.BehaviorKind.MUST_NOT_CALL, null, null,
                                "FORBIDDEN_CALL", "", "Function must not execute.", "")));
    }

    private static List<ScenarioDraftSet.AssertionDraft> assertions() {
        return List.of(
                assertion("output", ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "", "", "", "/decision",
                        ScenarioDraftSet.AssertionOperator.EQUALS, "APPROVED"),
                assertion("status", ScenarioDraftSet.AssertionScope.NODE_STATUS,
                        "decision", "", "", "",
                        ScenarioDraftSet.AssertionOperator.STATUS, "SUCCESS"),
                assertion("crm-used", ScenarioDraftSet.AssertionScope.INVOCATION,
                        "crm", "", "", "",
                        ScenarioDraftSet.AssertionOperator.USED, null),
                assertion("edge-used", ScenarioDraftSet.AssertionScope.EDGE_TRANSFER,
                        "", "crm", "decision", "",
                        ScenarioDraftSet.AssertionOperator.USED, null));
    }

    private static ScenarioDraftSet.AssertionDraft assertion(
            String id,
            ScenarioDraftSet.AssertionScope scope,
            String nodeId,
            String from,
            String to,
            String path,
            ScenarioDraftSet.AssertionOperator operator,
            Object expected) {
        return new ScenarioDraftSet.AssertionDraft(
                id, scope, nodeId, from, to, path, operator, expected, null);
    }

    private static ScenarioDraftSet.DependencyBehaviorDraft dependency(
            String id,
            ScenarioDraftSet.DependencySelector selector,
            ScenarioDraftSet.DependencyBehavior behavior) {
        return new ScenarioDraftSet.DependencyBehaviorDraft(
                id, selector, behavior, ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict(), "AUTHORED");
    }

    private static ScenarioDraftSet.DependencySelector operator(String operatorRef) {
        return new ScenarioDraftSet.DependencySelector(
                "/root", "", operatorRef, "", "", List.of(), List.of(), "", Map.of());
    }

    private static ScenarioDraftSet.DependencySelector resource(String resourceRef) {
        return new ScenarioDraftSet.DependencySelector(
                "/root", "", "", resourceRef, "", List.of(), List.of(), "", Map.of());
    }

    private static ScenarioDraftSet.DependencySelector function(String functionRef) {
        return new ScenarioDraftSet.DependencySelector(
                "/root", "", "", "", functionRef, List.of(), List.of(), "", Map.of());
    }

    private static ScenarioDraftSet.DependencyBehavior behavior(
            ScenarioDraftSet.BehaviorKind kind,
            Object output,
            Duration after,
            String errorCode,
            String errorType,
            String errorMessage,
            String replayRef) {
        return new ScenarioDraftSet.DependencyBehavior(
                kind,
                ScenarioDraftSet.BehaviorBoundary.NODE,
                output,
                null,
                "",
                null,
                Map.of(),
                errorCode,
                errorType,
                errorMessage,
                after,
                replayRef);
    }

    private static TestExecutionApiRequest.Target runtimeTarget() {
        return new TestExecutionApiRequest.Target(
                "GRAPH", "loanPolicy", ScenarioValidationServiceTest.fingerprint('e'));
    }
}
