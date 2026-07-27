package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.contract.GraphContractSemantics;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractScenarioProtocolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authoritativeSchemasMatchSerializedProtocolFieldsAndRemainStrict() throws Exception {
        GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
        ContractDraft contract = new ContractDraftProjectionService().project(
                graph, ScenarioValidationServiceTest.fingerprint('a'));
        ScenarioDraftSet draftSet = draftSet(contract);
        ScenarioValidationReport report = new ScenarioValidationService(mapper)
                .validate(draftSet, contract, graph);

        JsonNode contractSchema = schema("bloge-contract-draft-v1.schema.json");
        JsonNode draftSetSchema = schema("bloge-scenario-draft-set-v1.schema.json");
        JsonNode reportSchema = schema("bloge-scenario-validation-report-v1.schema.json");
        JsonNode storedSchema = schema("bloge-stored-scenario-draft-set-v1.schema.json");
        JsonNode publicationSchema = schema("bloge-scenario-publication-report-v1.schema.json");
        JsonNode storedPublicationSchema =
                schema("bloge-stored-scenario-publication-v1.schema.json");
        JsonNode projectionSchema =
                schema("bloge-scenario-contract-projection-v1.schema.json");
        JsonNode semanticsSchema =
                schema("bloge-graph-contract-semantics-v1.schema.json");
        JsonNode workspaceSchema =
                schema("bloge-visual-authoring-workspace-bundle-v1.schema.json");
        StoredScenarioDraftSet stored = new StoredScenarioDraftSet(
                "",
                draftSet.scenarioDraftSetId(),
                draftSet.revision(),
                ScenarioValidationServiceTest.fingerprint('b'),
                draftSet,
                Instant.parse("2026-07-27T00:00:00Z"),
                "author-a");
        ScenarioPublicationReport publication = new ScenarioPublicationReport(
                "",
                "scenario-publication-loan-scenarios-r3-abc",
                draftSet.scope(),
                new ScenarioPublicationReport.SourceRef(
                        draftSet.scenarioDraftSetId(), draftSet.revision(),
                        ScenarioValidationServiceTest.fingerprint('b'),
                        draftSet.target().fingerprint(), draftSet.contractFingerprint(),
                        ScenarioGovernedCompilationPlan.SCHEMA_VERSION,
                        ScenarioValidationServiceTest.fingerprint('e')),
                new com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest.Target(
                        "GRAPH", "loanPolicy", ScenarioValidationServiceTest.fingerprint('c')),
                ScenarioPublicationReport.Status.IN_PROGRESS,
                1,
                List.of(),
                null,
                List.of(),
                ScenarioPublicationReport.Failure.none(),
                Instant.parse("2026-07-27T00:00:00Z"),
                Instant.parse("2026-07-27T00:00:00Z"),
                null,
                "publisher-a");
        StoredScenarioPublication storedPublication = new StoredScenarioPublication(
                "", 1, ScenarioValidationServiceTest.fingerprint('d'), publication);
        ScenarioContractProjection projection = new ScenarioContractProjection(
                "",
                draftSet.scope(),
                contract,
                contract.fingerprint(mapper));
        GraphContractSemantics semantics = new GraphContractSemantics(
                "",
                contract.errorContract(),
                contract.executionSemantics(),
                contract.invariants(),
                contract.compatibilityPolicy(),
                contract.fieldMetadata());

        assertProperties(mapper.valueToTree(contract), contractSchema.path("properties"));
        assertProperties(mapper.valueToTree(contract.target()), contractSchema.at("/$defs/target/properties"));
        assertProperties(mapper.valueToTree(draftSet), draftSetSchema.path("properties"));
        assertProperties(mapper.valueToTree(draftSet.scenarios().getFirst()),
                draftSetSchema.at("/$defs/scenario/properties"));
        assertProperties(mapper.valueToTree(draftSet.scenarios().getFirst().dependencies().getFirst()),
                draftSetSchema.at("/$defs/dependency/properties"));
        assertProperties(mapper.valueToTree(report), reportSchema.path("properties"));
        assertProperties(mapper.valueToTree(stored), storedSchema.path("properties"));
        assertProperties(mapper.valueToTree(publication), publicationSchema.path("properties"));
        assertProperties(mapper.valueToTree(storedPublication),
                storedPublicationSchema.path("properties"));
        assertProperties(mapper.valueToTree(projection), projectionSchema.path("properties"));
        assertProperties(mapper.valueToTree(semantics), semanticsSchema.path("properties"));

        assertThat(contractSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(draftSetSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(reportSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(storedSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(publicationSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(storedPublicationSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(projectionSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(semanticsSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(workspaceSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(storedSchema.at("/properties/draftSet/$ref").asText())
                .isEqualTo("bloge-scenario-draft-set-v1.schema.json");
        assertThat(reportSchema.at("/$defs/diagnostic/required"))
                .extracting(JsonNode::asText)
                .doesNotContain("metadata");
        assertThat(reportSchema.at("/$defs/optionalFingerprint/anyOf/1/const").asText()).isEmpty();
        assertThat(reportSchema.at("/allOf/0/then/properties/targetFingerprint/$ref").asText())
                .isEqualTo("#/$defs/fingerprint");
        assertThat(draftSetSchema.at("/$defs/behavior/properties/kind/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("REAL", "RETURN", "ERROR", "DELAY", "TIMEOUT", "REPLAY",
                        "OBSERVE", "MUST_NOT_CALL");
        assertThat(storedPublicationSchema.at("/properties/report/$ref").asText())
                .isEqualTo("bloge-scenario-publication-report-v1.schema.json");
        assertThat(publicationSchema.at("/$defs/runtimeTarget/properties/kind/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("GRAPH", "OPERATOR");
        assertThat(publicationSchema.at("/$defs/source/required"))
                .extracting(JsonNode::asText)
                .doesNotContain("targetKind", "targetId");
        assertThat(publicationSchema.at("/$defs/source/properties/targetKind/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("", "GRAPH", "OPERATOR");
        assertThat(projectionSchema.at("/properties/scope/$ref").asText())
                .isEqualTo("bloge-scenario-draft-set-v1.schema.json#/$defs/scope");
        assertThat(projectionSchema.at("/properties/contract/$ref").asText())
                .isEqualTo("bloge-contract-draft-v1.schema.json");
        assertThat(semanticsSchema.at("/properties/executionSemantics/$ref").asText())
                .isEqualTo("bloge-contract-draft-v1.schema.json#/$defs/executionSemantics");
        assertThat(workspaceSchema.at("/properties/contractProjection/$ref").asText())
                .isEqualTo("bloge-scenario-contract-projection-v1.schema.json");
        assertThat(workspaceSchema.at("/properties/scenarioDraftSet/$ref").asText())
                .isEqualTo("bloge-scenario-draft-set-v1.schema.json");
        assertThat(workspaceSchema.at("/properties/graphDraft/properties/schemaVersion/const")
                .asText()).isEqualTo("bloge.visualGraphDraft.v1");
        assertThat(workspaceSchema.at("/$defs/operatorSnapshotRef/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(publicationSchema.path("properties").has("given")).isFalse();
        assertThat(publicationSchema.path("properties").has("input")).isFalse();
        assertThat(publicationSchema.path("properties").has("output")).isFalse();
        assertThat(publicationSchema.path("properties").has("expected")).isFalse();
        assertThat(publicationSchema.path("properties").has("payload")).isFalse();
    }

    private ScenarioDraftSet draftSet(ContractDraft contract) {
        ScenarioDraftSet.DependencyBehaviorDraft dependency = new ScenarioDraftSet.DependencyBehaviorDraft(
                "crm-return",
                ScenarioDraftSet.DependencySelector.node("crm"),
                ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720)),
                ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict(),
                "AUTHORED"
        );
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "happy-path",
                "Eligible applicant",
                "CRM returns a qualifying score.",
                ScenarioDraftSet.CaseType.GOLDEN,
                List.of("loan"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED
                ),
                List.of(dependency),
                new ScenarioDraftSet.Then(List.of())
        );
        return new ScenarioDraftSet(
                "",
                "loan-scenarios",
                3,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"
                ),
                contract.target(),
                contract.fingerprint(mapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata("credit-platform", "INTERNAL", null, null, Map.of())
        );
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
