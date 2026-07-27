package com.leanowtech.bloge.gateway.visual.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioValidationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ContractDraftProjectionService projector = new ContractDraftProjectionService();
    private final ScenarioValidationService validator = new ScenarioValidationService(objectMapper);

    @Test
    void validatesExactGraphContractAndScenarioInput() {
        GraphDraft graph = graphDraft();
        ContractDraft contract = projector.project(graph, fingerprint('a'));

        ScenarioValidationReport report = validator.validate(validDraftSet(graph, contract), contract, graph);

        assertThat(report.valid()).isTrue();
        assertThat(report.status()).isEqualTo(ScenarioValidationReport.Status.VALID);
        assertThat(report.diagnostics()).isEmpty();
    }

    @Test
    void rejectsStaleContractFingerprintAndInvalidInput() {
        GraphDraft graph = graphDraft();
        ContractDraft contract = projector.project(graph, fingerprint('a'));
        ScenarioDraftSet source = validDraftSet(graph, contract);
        ScenarioDraftSet.ScenarioDraft invalidScenario = scenario(
                new ScenarioDraftSet.Given(Map.of("applicantId", 42), ScenarioDraftSet.ValueProvenance.AUTHORED),
                source.scenarios().getFirst().dependencies()
        );
        ScenarioDraftSet stale = new ScenarioDraftSet(
                "",
                "loan-scenarios",
                3,
                source.scope(),
                source.target(),
                fingerprint('f'),
                List.of(invalidScenario),
                source.metadata()
        );

        ScenarioValidationReport report = validator.validate(stale, contract, graph);

        assertThat(report.status()).isEqualTo(ScenarioValidationReport.Status.INVALID);
        assertThat(report.diagnostics()).extracting("code")
                .contains("visual.scenario.contract.stale", "visual.context.typeMismatch");
    }

    @Test
    void rejectsUnknownNodeAndDuplicateAuthoringIds() {
        GraphDraft graph = graphDraft();
        ContractDraft contract = projector.project(graph, fingerprint('a'));
        ScenarioDraftSet.DependencyBehaviorDraft unknown = dependency(
                "dependency-a",
                ScenarioDraftSet.DependencySelector.node("missing"),
                ScenarioDraftSet.DependencyBehavior.returning(Map.of("status", "missing"))
        );
        ScenarioDraftSet.ScenarioDraft first = scenario(
                new ScenarioDraftSet.Given(Map.of("applicantId", "A-1"), ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(unknown, unknown)
        );
        ScenarioDraftSet.ScenarioDraft duplicate = scenario(first.given(), List.of());
        ScenarioDraftSet set = draftSet(graph, contract, List.of(first, duplicate));

        ScenarioValidationReport report = validator.validate(set, contract, graph);

        assertThat(report.diagnostics()).extracting("code")
                .contains(
                        "visual.scenario.idDuplicate",
                        "visual.scenario.dependency.idDuplicate",
                        "visual.scenario.dependency.nodeUnknown"
                );
    }

    @Test
    void enforcesBehaviorSpecificAndWaiverRequirements() {
        GraphDraft graph = graphDraft();
        ContractDraft contract = projector.project(graph, fingerprint('a'));
        ScenarioDraftSet.DependencyBehavior timeout = new ScenarioDraftSet.DependencyBehavior(
                ScenarioDraftSet.BehaviorKind.TIMEOUT,
                ScenarioDraftSet.BehaviorBoundary.NODE,
                null,
                null,
                "",
                null,
                Map.of(),
                "",
                "",
                "",
                Duration.ZERO,
                ""
        );
        ScenarioDraftSet.DependencyBehaviorDraft dependency = new ScenarioDraftSet.DependencyBehaviorDraft(
                "crm-timeout",
                ScenarioDraftSet.DependencySelector.node("crm"),
                timeout,
                new ScenarioDraftSet.Consumption(true, 2, 1, "FAIL", "FAIL"),
                new ScenarioDraftSet.SchemaCheck("WAIVED", ""),
                "AUTHORED"
        );

        ScenarioValidationReport report = validator.validate(
                draftSet(graph, contract, List.of(scenario(
                        new ScenarioDraftSet.Given(
                                Map.of("applicantId", "A-1"),
                                ScenarioDraftSet.ValueProvenance.AUTHORED
                        ),
                        List.of(dependency)
                ))),
                contract,
                graph
        );

        assertThat(report.diagnostics()).extracting("code")
                .contains(
                        "visual.scenario.behavior.durationMissing",
                        "visual.scenario.dependency.consumptionInvalid",
                        "visual.scenario.dependency.waiverReasonMissing"
                );
    }

    private ScenarioDraftSet validDraftSet(GraphDraft graph, ContractDraft contract) {
        return draftSet(graph, contract, List.of(scenario(
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED
                ),
                List.of(dependency(
                        "crm-return",
                        ScenarioDraftSet.DependencySelector.node("crm"),
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720))
                ))
        )));
    }

    private ScenarioDraftSet draftSet(GraphDraft graph,
                                      ContractDraft contract,
                                      List<ScenarioDraftSet.ScenarioDraft> scenarios) {
        return new ScenarioDraftSet(
                "",
                "loan-scenarios",
                3,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"
                ),
                contract.target(),
                contract.fingerprint(objectMapper),
                scenarios,
                new ScenarioDraftSet.Metadata(
                        "credit-platform",
                        "INTERNAL",
                        null,
                        null,
                        Map.of("source", "test")
                )
        );
    }

    private static ScenarioDraftSet.ScenarioDraft scenario(
            ScenarioDraftSet.Given given,
            List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies) {
        return new ScenarioDraftSet.ScenarioDraft(
                "happy-path",
                "Eligible applicant",
                "CRM returns a qualifying score.",
                ScenarioDraftSet.CaseType.GOLDEN,
                List.of("loan"),
                given,
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
    }

    private static ScenarioDraftSet.DependencyBehaviorDraft dependency(
            String id,
            ScenarioDraftSet.DependencySelector selector,
            ScenarioDraftSet.DependencyBehavior behavior) {
        return new ScenarioDraftSet.DependencyBehaviorDraft(
                id,
                selector,
                behavior,
                ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict(),
                "AUTHORED"
        );
    }

    static GraphDraft graphDraft() {
        SchemaEnvelope input = SchemaEnvelope.object(
                Map.of("applicantId", Map.of("type", "string")),
                List.of("applicantId")
        );
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("decision", Map.of("type", "string")),
                List.of("decision")
        );
        return new GraphDraft(
                "",
                "draft-a",
                4,
                "loanPolicy",
                "tenant-a",
                "local",
                "test",
                "",
                input,
                output,
                List.of(
                        new GraphDraft.DraftNode(
                                "crm",
                                "crm:lookup",
                                "CRM",
                                Map.of(),
                                Map.of(),
                                new GraphDraft.Position(0, 0)
                        ),
                        new GraphDraft.DraftNode(
                                "decision",
                                "bloge:transform",
                                "Decision",
                                Map.of(),
                                Map.of(),
                                new GraphDraft.Position(300, 0)
                        )
                ),
                List.of(new GraphDraft.DraftEdge(
                        "crm-decision",
                        "data",
                        new GraphDraft.Endpoint("crm", "profile", ""),
                        new GraphDraft.Endpoint("decision", "applicant", "")
                )),
                Map.of(),
                Map.of("crm", new GraphDraft.NodeFixture(Map.of("score", 600))),
                new GraphDraft.OutputSelection("decision", ""),
                Map.of("crm", fingerprint('c'), "decision", fingerprint('d')),
                Map.of(),
                GraphDraft.RevisionMetadata.empty()
        );
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
