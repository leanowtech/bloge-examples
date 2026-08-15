package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioImportMaterializationServiceTest {

    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String CONTRACT_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    private ObjectMapper mapper;
    private ScenarioDraftSetAuthoringService authoring;
    private MemoryReceipts receipts;
    private ScenarioImportMaterializationService service;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        authoring = mock(ScenarioDraftSetAuthoringService.class);
        receipts = new MemoryReceipts();
        service = new ScenarioImportMaterializationService(
                mapper, authoring, receipts, Clock.fixed(NOW, ZoneOffset.UTC));
        identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "author-1", "", "TEST_SUITE_WRITE", "corr-1",
                java.util.Set.of(), "RESTRICTED", "");
        when(authoring.validate(any(), any())).thenReturn(new ScenarioValidationReport(
                "", TARGET_FINGERPRINT, CONTRACT_FINGERPRINT, 1,
                ScenarioValidationReport.Status.VALID,
                List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void keepsTheCrossRuntimeSourceFingerprintGolden() {
        String source = "id,name\nA,Case A";
        ObjectNode sourceMaterial = mapper.createObjectNode();
        sourceMaterial.put("kind", "CSV");
        sourceMaterial.put("encoding", "UTF-8");
        sourceMaterial.put("delimiter", ",");
        sourceMaterial.put("parser", "papaparse-v5");
        sourceMaterial.put("text", source);

        assertThat(fingerprint(sourceMaterial)).isEqualTo(
                "sha256:1bd05be8e4c511fd47b1d52c21f4d689e96e1fc413ca36d8e07a0f4e55a70997");
    }

    @Test
    void reparsesQuotedCsvAndReturnsTheDurableResultForAnIdenticalRetry() {
        String source = "id,name,caseType,field01,note\n"
                + "A,\"Prime, approved\",boundary,42,\"line one\nline two\"\n"
                + "B,Declined,negative,7,plain";
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode plan = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE"),
                binding("/caseType", "case:type", "CASE_TYPE", List.of(), "IDENTITY", "VALUE"),
                binding("/field01", "given:/field01", "GIVEN", List.of("field01"), "NUMBER", "VALUE")
        ), "/id");
        ScenarioImportMaterializationRequest request = request(source, plan, draftSet);

        ScenarioImportMaterializationResult first = service.materialize(request, identity);
        ScenarioImportMaterializationResult retried = service.materialize(request, identity);

        assertThat(retried).isEqualTo(first);
        assertThat(first.receipt().path("acceptedRowCount").asInt()).isEqualTo(2);
        assertThat(first.receipt().path("rejectedRowCount").asInt()).isZero();
        assertThat(first.receipt().path("materializedAt").asText()).isEqualTo(NOW.toString());
        assertThat(first.receipt().path("receiptFingerprint").asText())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(first.draftSet().scenarios()).hasSize(3);
        ScenarioDraftSet.ScenarioDraft imported = first.draftSet().scenarios().get(1);
        assertThat(imported.name()).isEqualTo("Prime, approved");
        assertThat(imported.caseType()).isEqualTo(ScenarioDraftSet.CaseType.BOUNDARY);
        assertThat(imported.given().input()).isEqualTo(Map.of("field01", 42.0, "field02", "template"));
        verify(authoring).validate(any(), any());
    }

    @Test
    void acceptsAnEquivalentBrowserIntegerTargetRevisionWithoutFalseDrift() {
        String source = "id,name\nA,Imported";
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode plan = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        ((ObjectNode) plan.path("target")).put("revision", 1);
        refreshPlanFingerprint(plan);

        ScenarioImportMaterializationResult result = service.materialize(
                request(source, plan, draftSet), identity);

        assertThat(result.receipt().path("acceptedRowCount").asInt()).isOne();
    }

    @Test
    void exposesOnlyStableDiagnosticCodesWhenMaterializedScenariosFailValidation() {
        String source = "id,name\nA,do-not-echo";
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode plan = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        when(authoring.validate(any(), any())).thenReturn(new ScenarioValidationReport(
                "", TARGET_FINGERPRINT, CONTRACT_FINGERPRINT, 1,
                ScenarioValidationReport.Status.INVALID,
                List.of(
                        VisualDiagnostic.error("visual.scenario.target.contractMismatch",
                                "do-not-echo", "/target"),
                        VisualDiagnostic.error("visual.scenario.contract.stale",
                                "do-not-echo", "/contractFingerprint"),
                        VisualDiagnostic.error("visual.scenario.contract.stale",
                                "do-not-echo", "/contractFingerprint")),
                List.of(), List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.materialize(request(source, plan, draftSet), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.SCENARIO_IMPORT.MATERIALIZED_INVALID");
                    assertThat(failure.problem().details()).containsEntry(
                            "diagnosticCodes",
                            List.of(
                                    "visual.scenario.contract.stale",
                                    "visual.scenario.target.contractMismatch"));
                    assertThat(failure.problem().toString()).doesNotContain("do-not-echo");
                });
        assertThat(receipts.results).isEmpty();
    }

    @Test
    void appliesNullMissingDefaultAndJsonConvertersWithoutConflatingLiteralNull() {
        String source = """
                [{
                  "id":"A",
                  "name":"Imported",
                  "emptyNull":"",
                  "emptyMissing":"",
                  "emptyDefault":"",
                  "literalNull":"null",
                  "json":"{\\"approved\\":true}"
                }]
                """;
        ScenarioDraftSet draftSet = draftSet();
        List<ObjectNode> bindings = List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE"),
                binding("/emptyNull", "given:/field01", "GIVEN", List.of("field01"), "IDENTITY", "NULL"),
                binding("/emptyMissing", "given:/field02", "GIVEN", List.of("field02"), "IDENTITY", "MISSING"),
                bindingWithDefault("/emptyDefault", "given:/field03", List.of("field03"), 99),
                binding("/literalNull", "given:/field04", "GIVEN", List.of("field04"), "IDENTITY", "VALUE"),
                binding("/json", "given:/field05", "GIVEN", List.of("field05"), "JSON", "VALUE")
        );
        ScenarioImportMaterializationResult result = service.materialize(
                request(source, plan(source, draftSet, bindings, "/id"), draftSet), identity);

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) result.draftSet().scenarios().get(1).given().input();
        assertThat(input).containsEntry("field01", null)
                .containsEntry("field03", 99)
                .containsEntry("field04", "null");
        assertThat(input).doesNotContainKey("field02");
        assertThat(input.get("field05")).isEqualTo(Map.of("approved", true));
    }

    @Test
    void rejectsDuplicateExplicitIdentityAndEmptyNumbersPerRow() {
        String source = "id,name,field01\nA,First,\nA,Second,7";
        ScenarioDraftSet draftSet = draftSet();
        List<ObjectNode> bindings = List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE"),
                binding("/field01", "given:/field01", "GIVEN", List.of("field01"), "NUMBER", "VALUE")
        );

        ScenarioImportMaterializationResult result = service.materialize(
                request(source, plan(source, draftSet, bindings, "/id"), draftSet), identity);

        assertThat(result.receipt().path("acceptedRowCount").asInt()).isZero();
        assertThat(result.receipt().path("rejectedRowCount").asInt()).isEqualTo(2);
        assertThat(result.receipt().path("rows").get(0).path("diagnosticCode").asText())
                .isEqualTo("RG.SCENARIO_IMPORT.NUMBER_INVALID");
        assertThat(result.receipt().path("rows").get(1).path("diagnosticCode").asText())
                .isEqualTo("RG.SCENARIO_IMPORT.IDENTITY_DUPLICATE");
    }

    @Test
    void rejectsUnsupportedParserAndInconsistentDeclaredSemantics() {
        String source = "id,name\nA,Imported";
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode unsupported = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        unsupported.withObject("source").put("parser", "ad-hoc-v0");
        refreshPlanFingerprint(unsupported);
        assertProblem(request(source, unsupported, draftSet),
                "RG.SCENARIO_IMPORT.PARSER_UNSUPPORTED", source);

        ObjectNode inconsistent = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        inconsistent.withObject("valueSemantics").put("/name->case:name", "NULL");
        refreshPlanFingerprint(inconsistent);
        assertProblem(request(source, inconsistent, draftSet),
                "RG.SCENARIO_IMPORT.BINDING_SEMANTICS_INVALID", source);

        ObjectNode unknownReason = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        ((ObjectNode) unknownReason.withArray("bindings").get(0)).put("reason", "HEURISTIC");
        unknownReason.put("mappingFingerprint", fingerprint(unknownReason.path("bindings")));
        refreshPlanFingerprint(unknownReason);
        assertProblem(request(source, unknownReason, draftSet),
                "RG.SCENARIO_IMPORT.BINDING_INVALID", source);

        ObjectNode unknownIdentity = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        unknownIdentity.withObject("rowIdentityPolicy").put("kind", "ROW_NUMBER");
        refreshPlanFingerprint(unknownIdentity);
        assertProblem(request(source, unknownIdentity, draftSet),
                "RG.SCENARIO_IMPORT.IDENTITY_INVALID", source);

        ObjectNode unknownConflict = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");
        unknownConflict.put("conflictPolicy", "OVERWRITE");
        refreshPlanFingerprint(unknownConflict);
        assertProblem(request(source, unknownConflict, draftSet),
                "RG.SCENARIO_IMPORT.CONFLICT_POLICY_INVALID", source);
    }

    @Test
    void rejectsSourceMappingPlanAndContractDriftWithPayloadFreeProblems() {
        String payload = "id,name,field01\nA,do-not-echo,1";
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode plan = plan(payload, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE")
        ), "/id");

        assertProblem(request(payload + "-changed", plan, draftSet), "RG.SCENARIO_IMPORT.SOURCE_DRIFT", payload);

        ObjectNode mappingDrift = plan.deepCopy();
        mappingDrift.put("mappingFingerprint", "sha256:" + "c".repeat(64));
        assertProblem(request(payload, mappingDrift, draftSet), "RG.SCENARIO_IMPORT.MAPPING_DRIFT", payload);

        ObjectNode planDrift = plan.deepCopy();
        planDrift.put("planFingerprint", "sha256:" + "d".repeat(64));
        assertProblem(request(payload, planDrift, draftSet), "RG.SCENARIO_IMPORT.PLAN_DRIFT", payload);

        ScenarioDraftSet stale = new ScenarioDraftSet(
                draftSet.schemaVersion(), draftSet.scenarioDraftSetId(), draftSet.revision(),
                draftSet.scope(), draftSet.target(), "sha256:" + "e".repeat(64),
                draftSet.scenarios(), draftSet.metadata());
        assertProblem(request(payload, plan, stale), "RG.SCENARIO_IMPORT.CONTRACT_DRIFT", payload);
    }

    @Test
    void rejectsRawSecretMaterialAndDoesNotPersistTheSource() {
        String secret = "customer-super-secret-token";
        String source = "id,name,api_token\nA,Imported," + secret;
        ScenarioDraftSet draftSet = draftSet();
        ObjectNode plan = plan(source, draftSet, List.of(
                binding("/name", "case:name", "NAME", List.of(), "IDENTITY", "VALUE"),
                binding("/api_token", "given:/apiToken", "GIVEN", List.of("apiToken"), "IDENTITY", "VALUE")
        ), "/id");

        assertThatThrownBy(() -> service.materialize(request(source, plan, draftSet), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code()).isEqualTo("RG.SCENARIO_IMPORT.RAW_SECRET_FORBIDDEN");
                    assertThat(failure.problem().toString()).doesNotContain(secret);
                });
        assertThat(receipts.results).isEmpty();
    }

    @Test
    void failsClosedForMalformedAndOverBudgetSources() {
        ScenarioDraftSet draftSet = draftSet();
        String malformed = "[{";
        ObjectNode malformedPlan = planWithSourceFingerprintOnly(malformed, draftSet, "JSON", "", 500);
        assertProblem(request(malformed, malformedPlan, draftSet),
                "RG.SCENARIO_IMPORT.JSON_INVALID", malformed);

        String oversized = "id,name\nA,one\nB,two";
        ObjectNode oversizedPlan = planWithSourceFingerprintOnly(oversized, draftSet, "CSV", ",", 1);
        assertProblem(request(oversized, oversizedPlan, draftSet),
                "RG.SCENARIO_IMPORT.ROWS_EXCEEDED", oversized);
    }

    private ObjectNode plan(
            String source,
            ScenarioDraftSet draftSet,
            List<ObjectNode> bindings,
            String identityPath) {
        String kind = source.stripLeading().startsWith("[") ? "JSON" : "CSV";
        String delimiter = "CSV".equals(kind) ? "," : "";
        ObjectNode plan = planWithSourceFingerprintOnly(source, draftSet, kind, delimiter, 500);
        ArrayNode bindingArray = plan.putArray("bindings");
        bindings.forEach(bindingArray::add);
        plan.set("valueSemantics", semantics(bindings));
        plan.put("mappingFingerprint", fingerprint(bindingArray));
        ArrayNode rows = plan.putArray("rowSelection");
        sourceRows(source, kind, delimiter).forEach(row -> rows.add(row.rowId()));
        ObjectNode identity = plan.putObject("rowIdentityPolicy");
        identity.put("kind", identityPath.isBlank() ? "CANONICAL_ROW_HASH" : "SOURCE_COLUMN");
        identity.put("sourcePath", identityPath);
        plan.put("conflictPolicy", "FAIL");
        ObjectNode material = plan.deepCopy();
        material.remove("planFingerprint");
        plan.put("planFingerprint", fingerprint(material));
        return plan;
    }

    private ObjectNode planWithSourceFingerprintOnly(
            String source,
            ScenarioDraftSet draftSet,
            String kind,
            String delimiter,
            int maxRows) {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("schemaVersion", "bloge.scenarioMaterializationPlan.v1");
        ObjectNode sourceNode = plan.putObject("source");
        ObjectNode sourceMaterial = mapper.createObjectNode();
        sourceMaterial.put("kind", kind);
        sourceMaterial.put("encoding", "UTF-8");
        sourceMaterial.put("delimiter", delimiter);
        sourceMaterial.put("parser", "CSV".equals(kind) ? "papaparse-v5" : "json-standard-v1");
        sourceMaterial.put("text", source);
        sourceNode.put("kind", kind);
        sourceNode.put("fingerprint", fingerprint(sourceMaterial));
        sourceNode.put("encoding", "UTF-8");
        sourceNode.put("delimiter", delimiter);
        sourceNode.put("parser", "CSV".equals(kind) ? "papaparse-v5" : "json-standard-v1");
        sourceNode.put("classification", "INTERNAL");
        plan.set("target", mapper.valueToTree(draftSet.target()));
        plan.put("contractFingerprint", draftSet.contractFingerprint());
        plan.putArray("bindings");
        plan.putObject("valueSemantics");
        plan.putArray("rowSelection");
        plan.putObject("rowIdentityPolicy").put("kind", "CANONICAL_ROW_HASH").put("sourcePath", "");
        plan.put("conflictPolicy", "FAIL");
        ObjectNode budget = plan.putObject("budget");
        budget.put("maxBytes", 1_048_576);
        budget.put("maxRows", maxRows);
        budget.put("maxColumns", 100);
        plan.put("mappingFingerprint", fingerprint(plan.path("bindings")));
        ObjectNode material = plan.deepCopy();
        material.remove("planFingerprint");
        plan.put("planFingerprint", fingerprint(material));
        return plan;
    }

    private ObjectNode binding(
            String sourcePath,
            String targetId,
            String kind,
            List<String> valuePath,
            String converter,
            String semantics) {
        ObjectNode binding = mapper.createObjectNode();
        binding.put("bindingId", sourcePath + "->" + targetId);
        binding.put("sourcePath", sourcePath);
        ObjectNode target = binding.putObject("target");
        target.put("targetId", targetId);
        target.put("group", switch (kind) {
            case "NAME", "CASE_TYPE", "TAGS" -> "CASE";
            case "GIVEN" -> "GIVEN";
            case "DEPENDENCY_OUTPUT" -> "DEPENDENCY";
            default -> "THEN";
        });
        target.put("label", targetId);
        target.put("path", targetId);
        target.put("kind", kind);
        if (!valuePath.isEmpty()) target.set("valuePath", mapper.valueToTree(valuePath));
        binding.put("confidence", 1.0);
        binding.put("reason", "MANUAL");
        binding.put("confirmed", true);
        binding.put("converter", converter);
        binding.put("valueSemantics", semantics);
        return binding;
    }

    private ObjectNode bindingWithDefault(
            String sourcePath,
            String targetId,
            List<String> valuePath,
            Object defaultValue) {
        ObjectNode binding = binding(sourcePath, targetId, "GIVEN", valuePath, "IDENTITY", "DEFAULT");
        binding.set("defaultValue", mapper.valueToTree(defaultValue));
        return binding;
    }

    private ObjectNode semantics(List<ObjectNode> bindings) {
        ObjectNode result = mapper.createObjectNode();
        bindings.forEach(binding -> result.put(
                binding.path("bindingId").asText(), binding.path("valueSemantics").asText()));
        return result;
    }

    private List<TestRow> sourceRows(String source, String kind, String delimiter) {
        try {
            List<Map<String, JsonNode>> values = new java.util.ArrayList<>();
            if ("JSON".equals(kind)) {
                for (JsonNode row : mapper.readTree(source)) {
                    Map<String, JsonNode> flattened = new LinkedHashMap<>();
                    row.fields().forEachRemaining(entry -> flattened.put("/" + entry.getKey(), entry.getValue()));
                    values.add(flattened);
                }
            } else {
                try (CSVParser parser = CSVParser.parse(source, CSVFormat.DEFAULT.builder()
                        .setDelimiter(delimiter.charAt(0)).setIgnoreEmptyLines(false).get())) {
                    var records = parser.getRecords();
                    List<String> headers = new java.util.ArrayList<>();
                    records.getFirst().forEach(headers::add);
                    for (var record : records.subList(1, records.size())) {
                        if (record.stream().allMatch(value -> value.trim().isEmpty())) continue;
                    Map<String, JsonNode> row = new LinkedHashMap<>();
                        for (int index = 0; index < record.size(); index++) {
                            row.put("/" + headers.get(index), mapper.valueToTree(record.get(index)));
                        }
                        values.add(row);
                    }
                }
            }
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            return values.stream().map(value -> {
                String fingerprint = fingerprint(value);
                int occurrence = occurrences.merge(fingerprint, 1, Integer::sum);
                return new TestRow("row-" + fingerprint.substring(7, 23) + "-" + occurrence);
            }).toList();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ScenarioImportMaterializationRequest request(
            String source,
            ObjectNode plan,
            ScenarioDraftSet draftSet) {
        return new ScenarioImportMaterializationRequest(
                ScenarioImportMaterializationRequest.SCHEMA_VERSION,
                source, plan, draftSet, "template");
    }

    private ScenarioDraftSet draftSet() {
        ContractDraft.Target target = new ContractDraft.Target(
                ContractDraft.TargetKind.GRAPH, "loan", 1, TARGET_FINGERPRINT);
        ScenarioDraftSet.ScenarioDraft template = new ScenarioDraftSet.ScenarioDraft(
                "template", "Template", "", ScenarioDraftSet.CaseType.GOLDEN,
                List.of("loan"),
                new ScenarioDraftSet.Given(
                        Map.of("field01", 0, "field02", "template"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(), ScenarioDraftSet.Then.empty());
        return new ScenarioDraftSet(
                "", "loan-scenarios", 1,
                new ScenarioDraftSet.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"),
                target, CONTRACT_FINGERPRINT, List.of(template),
                new ScenarioDraftSet.Metadata(
                        "author-1", "INTERNAL", null, null, Map.of()));
    }

    private String fingerprint(Object value) {
        return ScenarioImportFingerprint.of(mapper, value, 16 * 1_048_576);
    }

    private void refreshPlanFingerprint(ObjectNode plan) {
        ObjectNode material = plan.deepCopy();
        material.remove("planFingerprint");
        plan.put("planFingerprint", fingerprint(material));
    }

    private void assertProblem(
            ScenarioImportMaterializationRequest request,
            String code,
            String payload) {
        assertThatThrownBy(() -> service.materialize(request, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().toString()).doesNotContain(payload);
                });
    }

    private record TestRow(String rowId) {
    }

    private static final class MemoryReceipts implements ScenarioImportReceiptRepository {
        private final Map<String, ScenarioImportMaterializationResult> results = new LinkedHashMap<>();

        @Override
        public Optional<ScenarioImportMaterializationResult> find(
                ScenarioDraftSet.EnterpriseScope scope,
                String planFingerprint) {
            return Optional.ofNullable(results.get(scope + ":" + planFingerprint));
        }

        @Override
        public ScenarioImportMaterializationResult saveIfAbsent(
                ScenarioDraftSet.EnterpriseScope scope,
                String planFingerprint,
                ScenarioImportMaterializationResult result) {
            return results.computeIfAbsent(scope + ":" + planFingerprint, ignored -> result);
        }
    }
}
