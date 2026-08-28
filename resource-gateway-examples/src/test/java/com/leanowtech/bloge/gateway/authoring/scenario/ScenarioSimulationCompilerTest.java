package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.ScenarioOperatorTestSupport;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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
    void compilesOperatorReturnSelectorIntoTheMatchingGraphNodeFixture() {
        GraphDraft graph = operatorGraph(ScenarioOperatorTestSupport.OPERATOR_REF);
        ContractDraft contract = projector.project(ScenarioOperatorTestSupport.operator());
        ScenarioDraftSet draftSet = operatorDraftSet(
                contract,
                operatorDependency(ScenarioOperatorTestSupport.OPERATOR_REF,
                        new ScenarioDraftSet.DependencyBehavior(
                                ScenarioDraftSet.BehaviorKind.RETURN,
                                ScenarioDraftSet.BehaviorBoundary.NODE,
                                Map.of("score", 720),
                                Map.of("applicantId", "A-1"),
                                "",
                                null,
                                Map.of(),
                                "",
                                "",
                                "",
                                null,
                                "")));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isTrue();
        assertThat(plan.request().fixtures()).containsKey("crm");
        assertThat(plan.request().fixtures().get("crm").output()).isEqualTo(Map.of("score", 720));
        assertThat(plan.request().fixtures().get("crm").expectedInput())
                .isEqualTo(Map.of("applicantId", "A-1"));
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
    void compilesOperatorRealSelectorByRemovingTheMatchingGraphNodeFixture() {
        GraphDraft graph = operatorGraph(ScenarioOperatorTestSupport.OPERATOR_REF);
        ContractDraft contract = projector.project(ScenarioOperatorTestSupport.operator());
        ScenarioDraftSet draftSet = operatorDraftSet(
                contract,
                operatorDependency(ScenarioOperatorTestSupport.OPERATOR_REF,
                        ScenarioDraftSet.DependencyBehavior.real()));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isTrue();
        assertThat(plan.request().draft().nodeFixtures()).doesNotContainKey("crm");
        assertThat(plan.request().fixtures()).isEmpty();
    }

    @Test
    void rejectsOperatorSelectorWhenItDoesNotMatchTheExactTarget() {
        GraphDraft graph = operatorGraph(ScenarioOperatorTestSupport.OPERATOR_REF);
        ContractDraft contract = projector.project(ScenarioOperatorTestSupport.operator());
        ScenarioDraftSet draftSet = operatorDraftSet(
                contract,
                operatorDependency("risk:other",
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720))));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.request()).isNull();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.operatorSelectorTargetMismatch");
    }

    @Test
    void rejectsOperatorSelectorWhenNoGraphNodeMatches() {
        GraphDraft graph = operatorGraph("risk:other");
        ContractDraft contract = projector.project(ScenarioOperatorTestSupport.operator());
        ScenarioDraftSet draftSet = operatorDraftSet(
                contract,
                operatorDependency(ScenarioOperatorTestSupport.OPERATOR_REF,
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720))));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.request()).isNull();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.operatorSelectorNodeMissing");
    }

    @Test
    void rejectsOperatorSelectorWhenMultipleGraphNodesMatch() {
        GraphDraft graph = operatorGraph(ScenarioOperatorTestSupport.OPERATOR_REF,
                ScenarioOperatorTestSupport.OPERATOR_REF);
        ContractDraft contract = projector.project(ScenarioOperatorTestSupport.operator());
        ScenarioDraftSet draftSet = operatorDraftSet(
                contract,
                operatorDependency(ScenarioOperatorTestSupport.OPERATOR_REF,
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720))));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.request()).isNull();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.operatorSelectorNodeAmbiguous");
    }

    @Test
    void rejectsOperatorSelectorForGraphTarget() {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = projector.project(graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet draftSet = draftSet(
                graph,
                contract,
                List.of(operatorDependency(ScenarioOperatorTestSupport.OPERATOR_REF,
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720)))));

        ScenarioSimulationPlan plan = compiler.compile(graph, contract, draftSet, "fallback");

        assertThat(plan.compiled()).isFalse();
        assertThat(plan.request()).isNull();
        assertThat(plan.diagnostics()).extracting("code")
                .contains("visual.scenario.compile.operatorSelectorUnsupported");
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

    private ScenarioDraftSet operatorDraftSet(
            ContractDraft contract,
            ScenarioDraftSet.DependencyBehaviorDraft dependency) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "fallback",
                "Operator fallback",
                "Return a controlled operator response.",
                ScenarioDraftSet.CaseType.REGRESSION,
                List.of("operator"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(dependency),
                ScenarioDraftSet.Then.empty());
        return new ScenarioDraftSet(
                "",
                "risk-operator-scenarios",
                1,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"),
                contract.target(),
                contract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata("credit-platform", "INTERNAL", null, null, Map.of()));
    }

    private static ScenarioDraftSet.DependencyBehaviorDraft operatorDependency(
            String operatorRef,
            ScenarioDraftSet.DependencyBehavior behavior) {
        return new ScenarioDraftSet.DependencyBehaviorDraft(
                "operator-control",
                new ScenarioDraftSet.DependencySelector(
                        "", "", operatorRef, "", "", List.of(), List.of(), "", Map.of()),
                behavior,
                ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict(),
                "AUTHORED");
    }

    private static GraphDraft operatorGraph(String... operatorRefs) {
        GraphDraft base = ScenarioValidationServiceTest.graphDraft();
        List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        for (int index = 0; index < operatorRefs.length; index++) {
            GraphDraft.DraftNode source = base.nodes().getFirst();
            nodes.add(new GraphDraft.DraftNode(
                    index == 0 ? "crm" : "crm-" + index,
                    operatorRefs[index],
                    source.label(),
                    source.inputs(),
                    source.config(),
                    source.position()));
        }
        nodes.add(base.nodes().get(1));
        return new GraphDraft(
                base.schemaVersion(), base.draftId(), base.revision(), base.graphName(),
                base.tenantId(), base.namespace(), base.environment(), base.status(),
                base.inputSchema(), base.outputSchema(), nodes, base.edges(), base.visualLayout(),
                base.nodeFixtures(), base.output(), base.operatorFingerprints(), base.operatorSnapshots(),
                base.revisionMetadata());
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
