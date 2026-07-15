package com.leanowtech.bloge.gateway.visual.testing;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result for one operator contract-test suite.
 *
 * @param schemaVersion result schema version
 * @param operatorRef visual operator reference
 * @param operatorVersion catalog operator version
 * @param mode proof strength of this suite execution
 * @param passed whether every row passed
 * @param totalCases total rows
 * @param passedCases passing rows
 * @param failedCases failing rows
 * @param coverage schema and assertion evidence counters
 * @param results row results
 * @param diagnostics suite diagnostics
 */
public record VisualOperatorContractTestSuiteResult(
        String schemaVersion,
        String operatorRef,
        String operatorVersion,
        Mode mode,
        boolean passed,
        int totalCases,
        int passedCases,
        int failedCases,
        Coverage coverage,
        List<VisualOperatorContractTestCaseResult> results,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestSuiteResult.v1";

    /**
     * Proof strength exposed to clients. The current service only checks fixture/schema consistency
     * and must never be presented as executable operator verification.
     */
    public enum Mode {
        SCHEMA_CONTRACT
    }

    /**
     * Creates a suite result.
     */
    public VisualOperatorContractTestSuiteResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef;
        operatorVersion = operatorVersion == null ? "" : operatorVersion;
        mode = mode == null ? Mode.SCHEMA_CONTRACT : mode;
        coverage = coverage == null ? new Coverage(0, 0, 0, 0, 0) : coverage;
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Evidence counters for the executed operator suite.
     *
     * @param inputPortSchemaValidated mocked input port values that passed schema validation
     * @param configSchemaValidated rows whose config passed schema validation
     * @param mockedOutputSchemaValidated mocked output port values that passed schema validation
     * @param mockedOutputCount mocked output values supplied by rows
     * @param assertionCount assertions evaluated
     */
    public record Coverage(
            int inputPortSchemaValidated,
            int configSchemaValidated,
            int mockedOutputSchemaValidated,
            int mockedOutputCount,
            int assertionCount
    ) {}
}
