package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionAssertion;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCase;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionSuite;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringTestMachineSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void operatorDraftAndRunRequestsMatchTheirMachineContracts() throws Exception {
        OperatorDraftRequest draft = new OperatorDraftRequest(
                OperatorDraftRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestDraftRequest(
                        VisualOperatorContractTestDraftRequest.SCHEMA_VERSION,
                        "demo:echo",
                        "generated contract case",
                        true,
                        Map.of(),
                        Map.of(),
                        Map.of()));
        OperatorRunRequest run = new OperatorRunRequest(
                OperatorRunRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestSuiteRequest(
                        "demo:echo",
                        List.of(new VisualOperatorContractTestCase(
                                "echoes a value",
                                Map.of("request", "hello"),
                                Map.of(),
                                Map.of("result", "hello"),
                                Map.of()))));

        assertThat(validate("bloge-visual-authoring-operator-test-draft-request-v1.schema.json",
                mapper.convertValue(draft, Object.class))).isEmpty();
        assertThat(validate("bloge-visual-authoring-operator-test-run-request-v1.schema.json",
                mapper.convertValue(run, Object.class))).isEmpty();

        Map<String, Object> invalid = mapper.convertValue(run, Map.class);
        Map<String, Object> suite = new LinkedHashMap<>((Map<String, Object>) invalid.get("suite"));
        suite.put("cases", List.of());
        invalid.put("suite", suite);
        assertThat(validate("bloge-visual-authoring-operator-test-run-request-v1.schema.json", invalid))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/suite/cases"));
    }

    @Test
    void functionRunRequestSupportsNullFixturesButRejectsTooManyArguments() throws Exception {
        FunctionRunRequest run = new FunctionRunRequest(
                FunctionRunRequest.SCHEMA_VERSION,
                new FunctionSuite(
                        FunctionSuite.SCHEMA_VERSION,
                        "coalesce",
                        List.of(new FunctionCase(
                                FunctionCase.SCHEMA_VERSION,
                                "null boundary",
                                FunctionCaseKind.BOUNDARY,
                                java.util.Arrays.asList(null, "fallback"),
                                FunctionAssertion.EQUALS,
                                "fallback",
                                null))));

        assertThat(validate("bloge-visual-authoring-function-test-run-request-v1.schema.json",
                mapper.convertValue(run, Object.class))).isEmpty();

        Map<String, Object> invalid = mapper.convertValue(run, Map.class);
        Map<String, Object> suite = new LinkedHashMap<>((Map<String, Object>) invalid.get("suite"));
        List<Map<String, Object>> cases =
                (List<Map<String, Object>>) suite.get("cases");
        Map<String, Object> testCase = new LinkedHashMap<>(cases.getFirst());
        testCase.put("args", java.util.Collections.nCopies(33, "value"));
        suite.put("cases", List.of(testCase));
        invalid.put("suite", suite);
        assertThat(validate("bloge-visual-authoring-function-test-run-request-v1.schema.json", invalid))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/args"));
    }

    @SuppressWarnings("unchecked")
    private List<VisualDiagnostic> validate(String schemaFile, Object value) throws Exception {
        Path path = Path.of("..", "docs", "schemas", schemaFile);
        Map<String, Object> schema = mapper.readValue(Files.readString(path), Map.class);
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/authoringTest");
    }
}
