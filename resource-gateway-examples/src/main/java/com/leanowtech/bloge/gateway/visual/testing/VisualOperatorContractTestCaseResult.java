package com.leanowtech.bloge.gateway.visual.testing;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result for one operator contract-test row.
 *
 * @param name row display name
 * @param passed whether the row passed
 * @param inputPortSchemaValidated count of input ports whose mock value passed schema validation
 * @param configSchemaValidated whether config passed schema validation
 * @param mockedOutputSchemaValidated count of output ports whose mock output passed schema validation
 * @param assertionCount assertions evaluated
 * @param diagnostics row diagnostics
 * @param inputs normalized input values
 * @param config normalized config values
 * @param mockedOutputs normalized mocked outputs
 */
public record VisualOperatorContractTestCaseResult(
        String name,
        boolean passed,
        int inputPortSchemaValidated,
        boolean configSchemaValidated,
        int mockedOutputSchemaValidated,
        int assertionCount,
        List<VisualDiagnostic> diagnostics,
        Map<String, Object> inputs,
        Map<String, Object> config,
        Map<String, Object> mockedOutputs
) {
    /**
     * Creates a case result.
     */
    public VisualOperatorContractTestCaseResult {
        name = name == null || name.isBlank() ? "Operator contract test case" : name;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        inputs = immutableJsonObject(inputs);
        config = immutableJsonObject(config);
        mockedOutputs = immutableJsonObject(mockedOutputs);
    }

    private static Map<String, Object> immutableJsonObject(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
