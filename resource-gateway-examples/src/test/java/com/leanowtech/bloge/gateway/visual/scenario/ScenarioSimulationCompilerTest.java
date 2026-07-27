package com.leanowtech.bloge.gateway.visual.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioSimulationCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ContractDraftProjectionService projector = new ContractDraftProjectionService();
    private final ScenarioValidationService validator = new ScenarioValidationService(objectMapper);
    private final ScenarioSimulationCompiler compiler = new ScenarioSimulationCompiler(validator);

    @Test
    void compilesReturnBehaviorAndExpectedResultsIntoExistingSimulationProtocol() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = projector.project(graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet draftSet = draftSet(
                graph,
                contract,
                List.of(returnDependency())
        );

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isTrue();
        assertThat(plan.request().context()).containsEntry("applicantId", "A-1");
        assertThat(plan.request().fixtures()).containsKey("crm");
        assertThat(plan.request().fixtures().get("crm").output()).isEqualTo(Map.of("score", 720));
        assertThat(plan.assertions()).extracting(ScenarioDraftSet.AssertionDraft::assertionId)
                .containsExactly("decision-approved");
    }

    @Test
    void explicitRealBehaviorRemovesPersistedAuthoringFixture() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = projector.project(graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet.DependencyBehaviorDraft real = dependency(
                new ScenarioDraftSet.DependencyBehavior(
                        ScenarioDraftSet.BehaviorKind.REAL,
                        ScenarioDraftSet.BehaviorBoundary.NODE,
                        null,
                        null,
                        "",
                        null,
                        Map.of(),
                        "",
                        "",
                        "",
                        null,
                        ""
                )
        );
        ScenarioDraftSet draftSet = draftSet(graph, contract, List.of(real));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isTrue();
        assertThat(plan.request().draft().nodeFixtures()).doesNotContainKey("crm");
        assertThat(plan.request().fixtures()).isEmpty();
    }

    @Test
    void advancedBehaviorFailsClosedInsteadOfDowngradingToNodeFixture() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = projector.project(graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet.DependencyBehavior timeout = new ScenarioDraftSet.DependencyBehavior(
                ScenarioDraftSet.BehaviorKind.TIMEOUT,
                ScenarioDraftSet.BehaviorBoundary.NODE,
                null,
                null,
                "",
                null,
                Map.of(),
                "CRM_TIMEOUT",
                "TIMEOUT",
                "CRM did not respond.",
                Duration.ofMillis(800),
                ""
        );
        ScenarioDraftSet draftSet = draftSet(graph, contract, List.of(dependency(timeout)));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.request()).isNull();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.governedBehaviorRequired");
    }

    @Test
    void missingScenarioAndStaleInputsBothRemainVisible() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = projector.project(graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet valid = draftSet(graph, contract, List.of(returnDependency()));
        ScenarioDraftSet stale = new ScenarioDraftSet(
                valid.schemaVersion(),
                valid.scenarioDraftSetId(),
                valid.revision(),
                valid.scope(),
                valid.target(),
                ScenarioValidationServiceTest.fingerprint('f'),
                valid.scenarios(),
                valid.metadata()
        );

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, stale, "missing");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.contract.stale", "visual.scenario.compile.scenarioMissing");
    }

    private ScenarioDraftSet draftSet(GraphDraft graph,
                                      ContractDraft contract,
                                      List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "fallback",
                "CRM fallback",
                "Return a controlled CRM response.",
                ScenarioDraftSet.CaseType.REGRESSION,
                List.of("crm"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED
                ),
                dependencies,
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        "decision-approved",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "",
                        "",
                        "",
                        "/decision",
                        ScenarioDraftSet.AssertionOperator.EQUALS,
                        "APPROVED",
                        null
                )))
        );
        return new ScenarioDraftSet(
                "",
                "loan-scenarios",
                3,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"
                ),
                contract.target(),
                contract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata("credit-platform", "INTERNAL", null, null, Map.of())
        );
    }

    private static ScenarioDraftSet.DependencyBehaviorDraft returnDependency() {
        return dependency(ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720)));
    }

    private static ScenarioDraftSet.DependencyBehaviorDraft dependency(
            ScenarioDraftSet.DependencyBehavior behavior) {
        return new ScenarioDraftSet.DependencyBehaviorDraft(
                "crm-control",
                ScenarioDraftSet.DependencySelector.node("crm"),
                behavior,
                ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict(),
                "AUTHORED"
        );
    }
}
