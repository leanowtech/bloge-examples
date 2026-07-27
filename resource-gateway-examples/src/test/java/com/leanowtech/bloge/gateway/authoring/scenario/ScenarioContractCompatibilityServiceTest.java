package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.ChangeKind;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.Classification;
import com.leanowtech.bloge.gateway.authoring.scenario.ContractCompatibilityReport.MigrationKind;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies deterministic, fail-closed Contract compatibility and exact Scenario impact.
 */
class ScenarioContractCompatibilityServiceTest {

    private ObjectMapper objectMapper;
    private ScenarioContractCompatibilityService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ScenarioContractCompatibilityService(objectMapper);
    }

    @Test
    void producesStableUnchangedReportForTheSameExactContract() {
        ContractDraft contract = contract(
                inputSchema(Map.of("applicantId", scalar("string")), List.of("applicantId")),
                outputSchema(Map.of("decision", scalar("string")), List.of("decision")));
        StoredScenarioDraftSet source = stored(contract, Map.of("applicantId", "A-1"), "/decision");
        ScenarioContractBaseline baseline = baseline(source, contract);

        ContractCompatibilityReport first = service.analyze(source, baseline, contract);
        ContractCompatibilityReport second = service.analyze(source, baseline, contract);

        assertThat(first.classification()).isEqualTo(Classification.UNCHANGED);
        assertThat(first.findings()).isEmpty();
        assertThat(first.impactedScenarios()).isEmpty();
        assertThat(first.migrations()).isEmpty();
        assertThat(first.reportFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(second.reportFingerprint()).isEqualTo(first.reportFingerprint());
    }

    @Test
    void classifiesBreakingFieldsAndProjectsExecutableGuidedMigrations() {
        Map<String, Object> oldInput = new LinkedHashMap<>();
        oldInput.put("applicantId", scalar("string"));
        oldInput.put("legacyCode", scalar("string"));
        ContractDraft previous = contract(
                inputSchema(oldInput, List.of("applicantId")),
                outputSchema(Map.of("decision", scalar("string")), List.of("decision")));

        Map<String, Object> country = scalar("string");
        country.put("default", "SG");
        Map<String, Object> customerCode = scalar("string");
        customerCode.put("x-bloge-renamed-from", "/legacyCode");
        Map<String, Object> nextInput = new LinkedHashMap<>();
        nextInput.put("applicantId", scalar("integer"));
        nextInput.put("country", country);
        nextInput.put("customerCode", customerCode);
        Map<String, Object> outcome = scalar("string");
        outcome.put("x-bloge-renamed-from", "/decision");
        ContractDraft current = withSchemas(
                previous,
                inputSchema(nextInput, List.of("applicantId", "country")),
                outputSchema(Map.of("outcome", outcome), List.of("outcome")));
        StoredScenarioDraftSet source = stored(
                previous,
                Map.of("applicantId", "A-1", "legacyCode", "L-1"),
                "/decision");

        ContractCompatibilityReport report =
                service.analyze(source, baseline(source, previous), current);

        assertThat(report.classification()).isEqualTo(Classification.BREAKING);
        assertThat(report.findings())
                .extracting(ContractCompatibilityReport.Finding::change)
                .contains(ChangeKind.TYPE_CHANGED, ChangeKind.ADDED, ChangeKind.RENAMED);
        assertThat(report.impactedScenarios())
                .extracting(ContractCompatibilityReport.ScenarioImpact::scenarioId)
                .containsExactly("approved");
        assertThat(report.migrations())
                .extracting(ContractCompatibilityReport.MigrationAction::kind)
                .contains(
                        MigrationKind.ADD_DEFAULT,
                        MigrationKind.RENAME_INPUT,
                        MigrationKind.REBIND_OUTPUT_ASSERTION,
                        MigrationKind.CONVERT_VALUE);
        assertThat(report.migrations().stream()
                .filter(ContractCompatibilityReport.MigrationAction::automatic)
                .map(ContractCompatibilityReport.MigrationAction::kind))
                .containsExactlyInAnyOrder(
                        MigrationKind.ADD_DEFAULT,
                        MigrationKind.RENAME_INPUT,
                        MigrationKind.REBIND_OUTPUT_ASSERTION);
    }

    @Test
    void neverLabelsUnsupportedSchemaCompositionAsCompatible() {
        ContractDraft previous = contract(
                inputSchema(Map.of("applicantId", scalar("string")), List.of("applicantId")),
                outputSchema(Map.of("decision", scalar("string")), List.of("decision")));
        Map<String, Object> opaqueInput = new LinkedHashMap<>();
        opaqueInput.put("oneOf", List.of(
                Map.of("type", "object"),
                Map.of("type", "string")));
        ContractDraft current = withSchemas(
                previous,
                new SchemaEnvelope("json-schema", "2020-12", opaqueInput),
                previous.outputSchema());
        StoredScenarioDraftSet source = stored(previous, Map.of("applicantId", "A-1"), "/decision");

        ContractCompatibilityReport report =
                service.analyze(source, baseline(source, previous), current);

        assertThat(report.classification()).isEqualTo(Classification.REVIEW_REQUIRED);
        assertThat(report.findings())
                .anyMatch(finding -> finding.change() == ChangeKind.OPAQUE
                        && finding.code().equals("RG.CONTRACT.SCHEMA_OPAQUE"));
        assertThat(report.migrations())
                .anyMatch(action -> action.kind() == MigrationKind.MANUAL_REVIEW
                        && !action.automatic());
    }

    @Test
    void legacyRevisionWithoutBaselineFailsClosedAndImpactsEveryScenario() {
        ContractDraft current = contract(
                inputSchema(Map.of("applicantId", scalar("string")), List.of("applicantId")),
                outputSchema(Map.of("decision", scalar("string")), List.of("decision")));
        StoredScenarioDraftSet source = stored(current, Map.of("applicantId", "A-1"), "/decision");

        ContractCompatibilityReport report = service.analyze(source, null, current);

        assertThat(report.classification()).isEqualTo(Classification.REVIEW_REQUIRED);
        assertThat(report.findings())
                .extracting(ContractCompatibilityReport.Finding::code)
                .containsExactly("RG.CONTRACT.BASELINE_UNAVAILABLE");
        assertThat(report.impactedScenarios())
                .extracting(ContractCompatibilityReport.ScenarioImpact::scenarioId)
                .containsExactly("approved");
    }

    private ScenarioContractBaseline baseline(
            StoredScenarioDraftSet source,
            ContractDraft contract) {
        return new ScenarioContractBaseline(
                "",
                source.scenarioDraftSetId(),
                source.revision(),
                contract.fingerprint(objectMapper),
                contract,
                Instant.parse("2026-07-27T00:00:00Z"),
                "author-a");
    }

    private StoredScenarioDraftSet stored(
            ContractDraft contract,
            Map<String, Object> input,
            String outputPath) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "approved",
                "Applicant approved",
                "",
                ScenarioDraftSet.CaseType.GOLDEN,
                List.of(),
                new ScenarioDraftSet.Given(input, ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(),
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        "decision",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "", "", "", outputPath,
                        ScenarioDraftSet.AssertionOperator.EQUALS,
                        "APPROVED",
                        null))));
        ScenarioDraftSet draftSet = new ScenarioDraftSet(
                "",
                "loan-scenarios",
                1,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"),
                contract.target(),
                contract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL",
                        Instant.parse("2026-07-27T00:00:00Z"),
                        Instant.parse("2026-07-27T00:00:00Z"),
                        Map.of()));
        return new StoredScenarioDraftSet(
                "",
                draftSet.scenarioDraftSetId(),
                1,
                "sha256:" + "f".repeat(64),
                draftSet,
                Instant.parse("2026-07-27T00:00:00Z"),
                "author-a");
    }

    private ContractDraft contract(SchemaEnvelope input, SchemaEnvelope output) {
        return new ContractDraft(
                "",
                new ContractDraft.Target(
                        ContractDraft.TargetKind.GRAPH,
                        "loan-graph",
                        1,
                        "sha256:" + "a".repeat(64)),
                input,
                output,
                List.of(),
                ContractDraft.ExecutionSemantics.unknown(),
                List.of(),
                ContractDraft.CompatibilityPolicy.strict(),
                Map.of(),
                ContractDraft.Source.AUTHORED,
                ContractDraft.Confidence.EXACT);
    }

    private static ContractDraft withSchemas(
            ContractDraft source,
            SchemaEnvelope input,
            SchemaEnvelope output) {
        return new ContractDraft(
                source.schemaVersion(),
                source.target(),
                input,
                output,
                source.errorContract(),
                source.executionSemantics(),
                source.invariants(),
                source.compatibilityPolicy(),
                source.fieldMetadata(),
                source.source(),
                source.confidence());
    }

    private static SchemaEnvelope inputSchema(
            Map<String, Object> properties,
            List<String> required) {
        return SchemaEnvelope.object(properties, required);
    }

    private static SchemaEnvelope outputSchema(
            Map<String, Object> properties,
            List<String> required) {
        return SchemaEnvelope.object(properties, required);
    }

    private static Map<String, Object> scalar(String type) {
        return new LinkedHashMap<>(Map.of("type", type));
    }
}
