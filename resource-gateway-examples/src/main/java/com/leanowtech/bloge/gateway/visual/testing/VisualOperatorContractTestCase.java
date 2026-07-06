package com.leanowtech.bloge.gateway.visual.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row in an operator contract-test table.
 *
 * @param schemaVersion case schema version
 * @param name row display name
 * @param description optional row description
 * @param inputs mocked operator inputs keyed by input port name
 * @param config mocked operator configuration object
 * @param mockedOutputs mocked outputs keyed by output port name
 * @param outputAssertions assertions keyed by output port name
 */
public record VisualOperatorContractTestCase(
        String schemaVersion,
        String name,
        String description,
        Map<String, Object> inputs,
        Map<String, Object> config,
        Map<String, Object> mockedOutputs,
        Map<String, List<VisualOperatorTestAssertion>> outputAssertions
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestCase.v1";

    /**
     * Creates a table row.
     */
    public VisualOperatorContractTestCase {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        name = name == null || name.isBlank() ? "Operator contract test case" : name.trim();
        description = description == null ? "" : description;
        inputs = copyObjectMap(inputs);
        config = copyObjectMap(config);
        mockedOutputs = copyObjectMap(mockedOutputs);
        outputAssertions = copyAssertions(outputAssertions);
    }

    /**
     * Convenience constructor for common rows.
     */
    public VisualOperatorContractTestCase(String name,
                                          Map<String, Object> inputs,
                                          Map<String, Object> config,
                                          Map<String, Object> mockedOutputs,
                                          Map<String, List<VisualOperatorTestAssertion>> outputAssertions) {
        this(SCHEMA_VERSION, name, "", inputs, config, mockedOutputs, outputAssertions);
    }

    private static Map<String, Object> copyObjectMap(Map<String, Object> source) {
        return source == null || source.isEmpty() ? Map.of() : new LinkedHashMap<>(source);
    }

    private static Map<String, List<VisualOperatorTestAssertion>> copyAssertions(
            Map<String, List<VisualOperatorTestAssertion>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<VisualOperatorTestAssertion>> copy = new LinkedHashMap<>();
        source.forEach((port, assertions) -> copy.put(
                port == null ? "" : port.trim(),
                assertions == null ? List.of() : List.copyOf(assertions)));
        return Map.copyOf(copy);
    }
}
