package com.leanowtech.bloge.gateway.visual.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates table-driven operator fixtures against catalog schemas without invoking an operator
 * runtime binding. Results are explicitly classified as {@code SCHEMA_CONTRACT} evidence.
 */
@Service
public class VisualOperatorContractTestService {

    private final VisualOperatorCatalog catalog;
    private final JsonSchemaSampleGenerator sampleGenerator;
    private final ObjectMapper objectMapper;

    /**
     * @param catalog visual operator catalog
     * @param sampleGenerator deterministic schema sample generator
     * @param objectMapper JSON mapper for assertions and schema parsing
     */
    public VisualOperatorContractTestService(VisualOperatorCatalog catalog,
                                             JsonSchemaSampleGenerator sampleGenerator,
                                             ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.sampleGenerator = sampleGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs a table-driven operator contract-test suite.
     *
     * @param request suite request
     * @return suite result
     */
    public VisualOperatorContractTestSuiteResult run(VisualOperatorContractTestSuiteRequest request) {
        VisualOperatorContractTestSuiteRequest safeRequest = request == null
                ? new VisualOperatorContractTestSuiteRequest("", List.of())
                : request;
        List<VisualDiagnostic> diagnostics = validateSuiteHeader(safeRequest);
        Optional<OperatorDefinition> operator = safeRequest.operatorRef().isBlank()
                ? Optional.empty()
                : catalog.find(safeRequest.operatorRef());
        if (operator.isEmpty() && !safeRequest.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorUnknown",
                    "Operator '%s' was not found in the visual operator catalog."
                            .formatted(safeRequest.operatorRef()),
                    "/operatorRef"));
        }
        if (!diagnostics.isEmpty()) {
            return suiteResult(safeRequest.operatorRef(), "", List.of(), diagnostics);
        }

        OperatorDefinition definition = operator.orElseThrow();
        return run(definition, safeRequest);
    }

    /**
     * Runs a table-driven suite against an explicit canonical definition.
     *
     * <p>This path lets authoring surfaces validate an exact, uncommitted draft without
     * publishing it into the shared visual catalog first.</p>
     *
     * @param definition exact canonical operator definition
     * @param request suite request
     * @return suite result
     */
    public VisualOperatorContractTestSuiteResult run(OperatorDefinition definition,
                                                     VisualOperatorContractTestSuiteRequest request) {
        VisualOperatorContractTestSuiteRequest safeRequest = request == null
                ? new VisualOperatorContractTestSuiteRequest("", List.of())
                : request;
        List<VisualDiagnostic> diagnostics = validateSuiteHeader(safeRequest);
        if (definition == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorUnknown",
                    "An exact operator definition is required.",
                    "/operatorRef"));
        } else if (!safeRequest.operatorRef().isBlank()
                && !definition.operatorRef().equals(safeRequest.operatorRef())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorRefMismatch",
                    "The suite operatorRef does not match the exact operator definition.",
                    "/operatorRef"));
        }
        if (!diagnostics.isEmpty()) {
            return suiteResult(
                    safeRequest.operatorRef(),
                    definition == null ? "" : definition.operatorVersion(),
                    List.of(),
                    diagnostics);
        }

        List<VisualOperatorContractTestCaseResult> results = safeRequest.cases().stream()
                .map(testCase -> runCase(definition, testCase))
                .toList();
        return suiteResult(definition.operatorRef(), definition.operatorVersion(), results, List.of());
    }

    /**
     * Runs one stored operator contract-test suite.
     *
     * @param suite stored suite
     * @return suite result
     */
    public VisualOperatorContractTestSuiteResult run(VisualOperatorContractTestSuite suite) {
        VisualOperatorContractTestSuite safeSuite = suite == null
                ? new VisualOperatorContractTestSuite("", "", "", List.of(),
                        new VisualOperatorContractTestSuiteRequest("", List.of()))
                : suite;
        return run(safeSuite.request());
    }

    /**
     * Runs all selected stored suites and returns aggregate evidence.
     *
     * @param suites suites to run
     * @return batch result
     */
    public VisualOperatorContractTestBatchResult runAll(Collection<VisualOperatorContractTestSuite> suites) {
        Collection<VisualOperatorContractTestSuite> safeSuites = suites == null ? List.of() : suites;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (safeSuites.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.batchNoSuites",
                    "At least one stored operator contract-test suite is required for a batch run.",
                    "/suites"));
        }

        List<VisualOperatorContractTestSuiteRunResult> results = new ArrayList<>();
        for (VisualOperatorContractTestSuite suite : safeSuites) {
            VisualOperatorContractTestSuite safeSuite = suite == null
                    ? new VisualOperatorContractTestSuite("", "", "", List.of(),
                            new VisualOperatorContractTestSuiteRequest("", List.of()))
                    : suite;
            VisualOperatorContractTestSuiteResult result = run(safeSuite);
            results.add(new VisualOperatorContractTestSuiteRunResult(
                    safeSuite.suiteId(),
                    safeSuite.displayName(),
                    safeSuite.request().operatorRef(),
                    safeSuite.tags(),
                    result));
        }

        int totalSuites = results.size();
        int passedSuites = (int) results.stream()
                .filter(row -> row.result() != null && row.result().passed())
                .count();
        int failedSuites = totalSuites - passedSuites;
        int totalCases = results.stream()
                .map(VisualOperatorContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(VisualOperatorContractTestSuiteResult::totalCases)
                .sum();
        int passedCases = results.stream()
                .map(VisualOperatorContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(VisualOperatorContractTestSuiteResult::passedCases)
                .sum();
        int failedCases = results.stream()
                .map(VisualOperatorContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(VisualOperatorContractTestSuiteResult::failedCases)
                .sum();
        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error)
                && totalSuites > 0
                && failedSuites == 0;
        return new VisualOperatorContractTestBatchResult(
                VisualOperatorContractTestBatchResult.SCHEMA_VERSION,
                passed,
                totalSuites,
                passedSuites,
                failedSuites,
                totalCases,
                passedCases,
                failedCases,
                aggregateCoverage(results),
                results,
                diagnostics);
    }

    /**
     * Builds an editable suite draft from the operator's input/config/output schemas.
     *
     * @param request draft request
     * @return generated suite draft
     */
    public VisualOperatorContractTestDraftResponse draft(VisualOperatorContractTestDraftRequest request) {
        VisualOperatorContractTestDraftRequest safeRequest = request == null
                ? new VisualOperatorContractTestDraftRequest("", "", "", true, Map.of(), Map.of(), Map.of())
                : request;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!VisualOperatorContractTestDraftRequest.SCHEMA_VERSION.equals(safeRequest.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.draftSchemaVersionUnsupported",
                    "Operator contract-test draft schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(safeRequest.schemaVersion(),
                                    VisualOperatorContractTestDraftRequest.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (safeRequest.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorRefMissing",
                    "operatorRef is required.",
                    "/operatorRef"));
        }
        Optional<OperatorDefinition> operator = safeRequest.operatorRef().isBlank()
                ? Optional.empty()
                : catalog.find(safeRequest.operatorRef());
        if (operator.isEmpty() && !safeRequest.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorUnknown",
                    "Operator '%s' was not found in the visual operator catalog."
                            .formatted(safeRequest.operatorRef()),
                    "/operatorRef"));
        }
        if (operator.isEmpty()) {
            return new VisualOperatorContractTestDraftResponse(
                    VisualOperatorContractTestDraftResponse.SCHEMA_VERSION,
                    safeRequest.operatorRef(),
                    new VisualOperatorContractTestSuiteRequest(safeRequest.operatorRef(), List.of()),
                    diagnostics);
        }

        OperatorDefinition definition = operator.orElseThrow();
        return draft(definition, safeRequest);
    }

    /**
     * Builds an editable suite draft from an explicit canonical definition.
     *
     * @param definition exact canonical operator definition
     * @param request draft request
     * @return generated suite draft
     */
    public VisualOperatorContractTestDraftResponse draft(
            OperatorDefinition definition,
            VisualOperatorContractTestDraftRequest request) {
        VisualOperatorContractTestDraftRequest safeRequest = request == null
                ? new VisualOperatorContractTestDraftRequest("", "", "", true, Map.of(), Map.of(), Map.of())
                : request;
        List<VisualDiagnostic> diagnostics = validateDraftRequest(safeRequest);
        if (definition == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorUnknown",
                    "An exact operator definition is required.",
                    "/operatorRef"));
        } else if (!safeRequest.operatorRef().isBlank()
                && !definition.operatorRef().equals(safeRequest.operatorRef())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorRefMismatch",
                    "The draft operatorRef does not match the exact operator definition.",
                    "/operatorRef"));
        }
        if (!diagnostics.isEmpty()) {
            return new VisualOperatorContractTestDraftResponse(
                    VisualOperatorContractTestDraftResponse.SCHEMA_VERSION,
                    safeRequest.operatorRef(),
                    new VisualOperatorContractTestSuiteRequest(safeRequest.operatorRef(), List.of()),
                    diagnostics);
        }

        Map<String, Object> inputs = generatedPortValues(
                definition.ports().inputs(),
                safeRequest.includeOptionalPorts(),
                safeRequest.inputOverrides());
        Map<String, Object> mockedOutputs = generatedPortValues(
                definition.ports().outputs(),
                safeRequest.includeOptionalPorts(),
                safeRequest.mockedOutputOverrides());
        Map<String, Object> config = generatedConfig(definition.configSchema(), safeRequest.configOverrides());
        Map<String, List<VisualOperatorTestAssertion>> assertions = generatedOutputAssertions(
                definition.ports().outputs(),
                mockedOutputs.keySet());
        VisualOperatorContractTestSuiteRequest suite = new VisualOperatorContractTestSuiteRequest(
                definition.operatorRef(),
                List.of(new VisualOperatorContractTestCase(
                        safeRequest.caseName(),
                        inputs,
                        config,
                        mockedOutputs,
                        assertions)));
        return new VisualOperatorContractTestDraftResponse(
                VisualOperatorContractTestDraftResponse.SCHEMA_VERSION,
                definition.operatorRef(),
                suite,
                diagnostics);
    }

    private List<VisualDiagnostic> validateDraftRequest(VisualOperatorContractTestDraftRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!VisualOperatorContractTestDraftRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.draftSchemaVersionUnsupported",
                    "Operator contract-test draft schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(request.schemaVersion(),
                                    VisualOperatorContractTestDraftRequest.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (request.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorRefMissing",
                    "operatorRef is required.",
                    "/operatorRef"));
        }
        return diagnostics;
    }

    private VisualOperatorContractTestCaseResult runCase(OperatorDefinition operator,
                                                         VisualOperatorContractTestCase testCase) {
        VisualOperatorContractTestCase safeCase = testCase == null
                ? new VisualOperatorContractTestCase("", Map.of(), Map.of(), Map.of(), Map.of())
                : testCase;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!VisualOperatorContractTestCase.SCHEMA_VERSION.equals(safeCase.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.caseSchemaVersionUnsupported",
                    "Operator contract test case schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(safeCase.schemaVersion(), VisualOperatorContractTestCase.SCHEMA_VERSION),
                    "/schemaVersion"));
        }

        Map<String, OperatorDefinition.Port> inputPorts = portsByName(operator.ports().inputs());
        Map<String, OperatorDefinition.Port> outputPorts = portsByName(operator.ports().outputs());
        int inputValidated = validateInputs(safeCase, inputPorts, diagnostics);
        boolean configValidated = validateConfig(operator, safeCase, diagnostics);
        int outputValidated = validateOutputs(safeCase, outputPorts, diagnostics);
        int assertionCount = assertionCount(safeCase);
        diagnostics.addAll(outputAssertionDiagnostics(safeCase, outputPorts));

        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualOperatorContractTestCaseResult(
                safeCase.name(),
                passed,
                inputValidated,
                configValidated,
                outputValidated,
                assertionCount,
                diagnostics,
                safeCase.inputs(),
                safeCase.config(),
                safeCase.mockedOutputs());
    }

    private List<VisualDiagnostic> validateSuiteHeader(VisualOperatorContractTestSuiteRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!VisualOperatorContractTestSuiteRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.schemaVersionUnsupported",
                    "Operator contract test suite schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(request.schemaVersion(),
                                    VisualOperatorContractTestSuiteRequest.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (request.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.operatorRefMissing",
                    "operatorRef is required.",
                    "/operatorRef"));
        }
        if (request.cases().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.noCases",
                    "At least one operator table test case is required.",
                    "/cases"));
        }
        return diagnostics;
    }

    private int validateInputs(VisualOperatorContractTestCase testCase,
                               Map<String, OperatorDefinition.Port> inputPorts,
                               List<VisualDiagnostic> diagnostics) {
        int validated = 0;
        for (OperatorDefinition.Port port : inputPorts.values()) {
            String target = "/inputs/" + pointerSegment(port.name());
            if (!testCase.inputs().containsKey(port.name())) {
                if (port.required()) {
                    diagnostics.add(VisualDiagnostic.error(
                            "visual.operatorContractTest.inputMissing",
                            "Required input port '%s' must have a mock value.".formatted(port.name()),
                            target));
                }
                continue;
            }
            List<VisualDiagnostic> portDiagnostics = VisualSchemaValidator.validateValue(
                    port.schema(),
                    testCase.inputs().get(port.name()),
                    target);
            diagnostics.addAll(portDiagnostics);
            if (portDiagnostics.stream().noneMatch(VisualDiagnostic::error)) {
                validated++;
            }
        }
        unknownNames(testCase.inputs(), inputPorts).forEach(name -> diagnostics.add(VisualDiagnostic.error(
                "visual.operatorContractTest.inputPortUnknown",
                "Input port '%s' is not declared by the operator.".formatted(name),
                "/inputs/" + pointerSegment(name))));
        return validated;
    }

    private boolean validateConfig(OperatorDefinition operator,
                                   VisualOperatorContractTestCase testCase,
                                   List<VisualDiagnostic> diagnostics) {
        List<VisualDiagnostic> configDiagnostics = VisualSchemaValidator.validateValue(
                operator.configSchema(),
                testCase.config(),
                "/config");
        diagnostics.addAll(configDiagnostics);
        return configDiagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    private int validateOutputs(VisualOperatorContractTestCase testCase,
                                Map<String, OperatorDefinition.Port> outputPorts,
                                List<VisualDiagnostic> diagnostics) {
        int validated = 0;
        for (OperatorDefinition.Port port : outputPorts.values()) {
            String target = "/mockedOutputs/" + pointerSegment(port.name());
            if (!testCase.mockedOutputs().containsKey(port.name())) {
                if (port.required()) {
                    diagnostics.add(VisualDiagnostic.error(
                            "visual.operatorContractTest.outputMissing",
                            "Required output port '%s' must have a mocked output value.".formatted(port.name()),
                            target));
                }
                continue;
            }
            List<VisualDiagnostic> portDiagnostics = VisualSchemaValidator.validateValue(
                    port.schema(),
                    testCase.mockedOutputs().get(port.name()),
                    target);
            diagnostics.addAll(portDiagnostics);
            if (portDiagnostics.stream().noneMatch(VisualDiagnostic::error)) {
                validated++;
            }
        }
        unknownNames(testCase.mockedOutputs(), outputPorts).forEach(name -> diagnostics.add(VisualDiagnostic.error(
                "visual.operatorContractTest.outputPortUnknown",
                "Output port '%s' is not declared by the operator.".formatted(name),
                "/mockedOutputs/" + pointerSegment(name))));
        return validated;
    }

    private List<VisualDiagnostic> outputAssertionDiagnostics(VisualOperatorContractTestCase testCase,
                                                              Map<String, OperatorDefinition.Port> outputPorts) {
        if (testCase.outputAssertions().isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        testCase.outputAssertions().forEach((portName, assertions) -> {
            String targetPrefix = "/outputAssertions/" + pointerSegment(portName);
            if (!outputPorts.containsKey(portName)) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.operatorContractTest.assertionOutputPortUnknown",
                        "Assertion output port '%s' is not declared by the operator.".formatted(portName),
                        targetPrefix));
                return;
            }
            if (!testCase.mockedOutputs().containsKey(portName)) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.operatorContractTest.assertionOutputMissing",
                        "Assertion output port '%s' has no mocked output value.".formatted(portName),
                        targetPrefix));
                return;
            }
            Object output = testCase.mockedOutputs().get(portName);
            JsonNode outputNode = objectMapper.valueToTree(output);
            for (int i = 0; i < assertions.size(); i++) {
                VisualOperatorTestAssertion assertion = assertions.get(i);
                diagnostics.addAll(assertionDiagnostics(
                        testCase.name(),
                        portName,
                        assertion,
                        output,
                        outputNode,
                        targetPrefix + "/" + i));
            }
        });
        return diagnostics;
    }

    private List<VisualDiagnostic> assertionDiagnostics(String caseName,
                                                        String portName,
                                                        VisualOperatorTestAssertion assertion,
                                                        Object output,
                                                        JsonNode outputNode,
                                                        String target) {
        if (assertion.mode() == VisualOperatorTestAssertion.Mode.OUTPUT_EQUALS) {
            return jsonEquals(assertion.expectedValue(), output)
                    ? List.of()
                    : List.of(assertionFailed(caseName, portName, "output equals expected value",
                            target + "/expectedValue"));
        }
        if (assertion.mode() == VisualOperatorTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA) {
            return schemaAssertionDiagnostics(caseName, portName, assertion, output, target + "/expectedValue");
        }
        if (!validJsonPointer(assertion.path())) {
            return List.of(VisualDiagnostic.error(
                    "visual.operatorContractTest.assertionInvalidPath",
                    "Operator contract test case '%s' assertion path '%s' is not a JSON Pointer."
                            .formatted(caseName, assertion.path()),
                    target + "/path"));
        }
        JsonNode actualValue = outputNode.at(assertion.path());
        return switch (assertion.mode()) {
            case PATH_EQUALS -> jsonEquals(assertion.expectedValue(), actualValue)
                    ? List.of()
                    : List.of(assertionFailed(caseName, portName,
                            "path '%s' equals expected value".formatted(assertion.path()),
                            target + "/expectedValue"));
            case PATH_EXISTS -> !actualValue.isMissingNode()
                    ? List.of()
                    : List.of(assertionFailed(caseName, portName,
                            "path '%s' exists".formatted(assertion.path()),
                            target + "/path"));
            case PATH_ABSENT -> actualValue.isMissingNode()
                    ? List.of()
                    : List.of(assertionFailed(caseName, portName,
                            "path '%s' is absent".formatted(assertion.path()),
                            target + "/path"));
            default -> List.of();
        };
    }

    private List<VisualDiagnostic> schemaAssertionDiagnostics(String caseName,
                                                              String portName,
                                                              VisualOperatorTestAssertion assertion,
                                                              Object output,
                                                              String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Optional<SchemaEnvelope> schema = assertionSchemaEnvelope(assertion.expectedValue(), target, diagnostics);
        if (schema.isEmpty() || diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return diagnostics;
        }
        diagnostics.addAll(VisualSchemaValidator.validateValue(schema.get(), output, target).stream()
                .map(diagnostic -> VisualDiagnostic.error(
                        "visual.operatorContractTest.schemaAssertionFailed",
                        "Operator contract test case '%s' output port '%s' does not satisfy assertion schema."
                                .formatted(caseName, portName),
                        diagnostic.target()))
                .toList());
        return diagnostics;
    }

    private Optional<SchemaEnvelope> assertionSchemaEnvelope(Object value,
                                                            String target,
                                                            List<VisualDiagnostic> diagnostics) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.assertionSchemaInvalid",
                    "Schema assertion expectedValue must be a JSON schema object or SchemaEnvelope.",
                    target));
            return Optional.empty();
        }
        try {
            Map<String, Object> schemaMap = stringKeyMap(rawMap);
            SchemaEnvelope envelope = schemaMap.get("schema") instanceof Map<?, ?>
                    ? objectMapper.convertValue(schemaMap, SchemaEnvelope.class)
                    : new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaMap);
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(envelope, target));
            return Optional.of(envelope);
        } catch (IllegalArgumentException ex) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.operatorContractTest.assertionSchemaInvalid",
                    "Schema assertion expectedValue could not be parsed as a visual schema.",
                    target));
            return Optional.empty();
        }
    }

    private VisualDiagnostic assertionFailed(String caseName,
                                             String portName,
                                             String expectation,
                                             String target) {
        return VisualDiagnostic.error(
                "visual.operatorContractTest.assertionFailed",
                "Operator contract test case '%s' output port '%s' assertion failed: expected %s."
                        .formatted(caseName, portName, expectation),
                target);
    }

    private VisualOperatorContractTestSuiteResult suiteResult(
            String operatorRef,
            String operatorVersion,
            List<VisualOperatorContractTestCaseResult> results,
            List<VisualDiagnostic> diagnostics) {
        int totalCases = results.size();
        int passedCases = (int) results.stream().filter(VisualOperatorContractTestCaseResult::passed).count();
        int failedCases = totalCases - passedCases;
        int inputValidated = results.stream()
                .mapToInt(VisualOperatorContractTestCaseResult::inputPortSchemaValidated)
                .sum();
        int configValidated = (int) results.stream()
                .filter(VisualOperatorContractTestCaseResult::configSchemaValidated)
                .count();
        int outputValidated = results.stream()
                .mapToInt(VisualOperatorContractTestCaseResult::mockedOutputSchemaValidated)
                .sum();
        int mockedOutputCount = results.stream()
                .mapToInt(result -> result.mockedOutputs().size())
                .sum();
        int assertionCount = results.stream()
                .mapToInt(VisualOperatorContractTestCaseResult::assertionCount)
                .sum();
        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error)
                && totalCases > 0
                && failedCases == 0;
        return new VisualOperatorContractTestSuiteResult(
                VisualOperatorContractTestSuiteResult.SCHEMA_VERSION,
                operatorRef,
                operatorVersion,
                VisualOperatorContractTestSuiteResult.Mode.SCHEMA_CONTRACT,
                passed,
                totalCases,
                passedCases,
                failedCases,
                new VisualOperatorContractTestSuiteResult.Coverage(
                        inputValidated,
                        configValidated,
                        outputValidated,
                        mockedOutputCount,
                        assertionCount),
                results,
                diagnostics);
    }

    private static VisualOperatorContractTestSuiteResult.Coverage aggregateCoverage(
            List<VisualOperatorContractTestSuiteRunResult> results) {
        int input = 0;
        int config = 0;
        int output = 0;
        int mockedOutput = 0;
        int assertions = 0;
        for (VisualOperatorContractTestSuiteRunResult row : results) {
            if (row.result() == null || row.result().coverage() == null) {
                continue;
            }
            input += row.result().coverage().inputPortSchemaValidated();
            config += row.result().coverage().configSchemaValidated();
            output += row.result().coverage().mockedOutputSchemaValidated();
            mockedOutput += row.result().coverage().mockedOutputCount();
            assertions += row.result().coverage().assertionCount();
        }
        return new VisualOperatorContractTestSuiteResult.Coverage(
                input,
                config,
                output,
                mockedOutput,
                assertions);
    }

    private Map<String, Object> generatedPortValues(List<OperatorDefinition.Port> ports,
                                                    boolean includeOptionalPorts,
                                                    Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (OperatorDefinition.Port port : ports) {
            if (port.required() || includeOptionalPorts) {
                values.put(port.name(), sampleGenerator.generate(port.schema()));
            }
        }
        overrides.forEach(values::put);
        return values;
    }

    private Map<String, Object> generatedConfig(SchemaEnvelope schema, Map<String, Object> overrides) {
        Object generated = sampleGenerator.generate(schema);
        Map<String, Object> config = generated instanceof Map<?, ?> map ? stringKeyMap(map) : new LinkedHashMap<>();
        overrides.forEach(config::put);
        return config;
    }

    private Map<String, List<VisualOperatorTestAssertion>> generatedOutputAssertions(
            List<OperatorDefinition.Port> ports,
            Iterable<String> selectedPorts) {
        Map<String, OperatorDefinition.Port> portsByName = portsByName(ports);
        Map<String, List<VisualOperatorTestAssertion>> assertions = new LinkedHashMap<>();
        for (String portName : selectedPorts) {
            OperatorDefinition.Port port = portsByName.get(portName);
            if (port == null) {
                continue;
            }
            assertions.put(portName, List.of(new VisualOperatorTestAssertion(
                    VisualOperatorTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA,
                    "",
                    port.schema().schema())));
        }
        return assertions;
    }

    private static Map<String, OperatorDefinition.Port> portsByName(List<OperatorDefinition.Port> ports) {
        Map<String, OperatorDefinition.Port> byName = new LinkedHashMap<>();
        for (OperatorDefinition.Port port : ports) {
            byName.put(port.name(), port);
        }
        return byName;
    }

    private static List<String> unknownNames(Map<String, Object> values,
                                             Map<String, OperatorDefinition.Port> ports) {
        return values.keySet().stream()
                .filter(name -> !ports.containsKey(name))
                .toList();
    }

    private static int assertionCount(VisualOperatorContractTestCase testCase) {
        return testCase.outputAssertions().values().stream().mapToInt(List::size).sum();
    }

    private boolean jsonEquals(Object expected, Object actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        JsonNode actualNode = objectMapper.valueToTree(actual);
        return expectedNode.equals(actualNode);
    }

    private boolean jsonEquals(Object expected, JsonNode actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        return expectedNode.equals(actual);
    }

    private static boolean validJsonPointer(String path) {
        return path == null || path.isBlank() || path.startsWith("/");
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> values = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    private static String pointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
