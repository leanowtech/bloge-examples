package com.leanowtech.bloge.gateway.visual.testing;

import java.util.List;

/**
 * Stored operator contract-test suite catalog row.
 *
 * @param suiteId stable suite id
 * @param displayName human-readable name
 * @param description suite description
 * @param tags grouping tags
 * @param operatorRef tested operator reference
 * @param caseCount number of table rows
 */
public record VisualOperatorContractTestSuiteSummary(
        String suiteId,
        String displayName,
        String description,
        List<String> tags,
        String operatorRef,
        int caseCount
) {
    /**
     * @param suite stored suite
     * @return summary row
     */
    public static VisualOperatorContractTestSuiteSummary from(VisualOperatorContractTestSuite suite) {
        VisualOperatorContractTestSuite safeSuite = suite == null
                ? new VisualOperatorContractTestSuite("", "", "", List.of(),
                        new VisualOperatorContractTestSuiteRequest("", List.of()))
                : suite;
        return new VisualOperatorContractTestSuiteSummary(
                safeSuite.suiteId(),
                safeSuite.displayName(),
                safeSuite.description(),
                safeSuite.tags(),
                safeSuite.request().operatorRef(),
                safeSuite.request().cases().size());
    }
}
