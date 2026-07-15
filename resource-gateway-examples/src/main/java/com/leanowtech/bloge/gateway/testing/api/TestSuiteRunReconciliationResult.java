package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;

/**
 * Payload-free operational result of one abandoned suite-run reconciliation sweep.
 *
 * @param schemaVersion result protocol version
 * @param sweptAt authoritative sweep time
 * @param scanned expired candidates inspected
 * @param reconciled candidates terminalized by this sweep
 * @param raced candidates changed after scanning
 * @param failed candidate writes that failed unexpectedly
 * @param reconciledSuiteRunIds bounded identifiers successfully terminalized
 */
public record TestSuiteRunReconciliationResult(
        String schemaVersion,
        Instant sweptAt,
        int scanned,
        int reconciled,
        int raced,
        int failed,
        List<String> reconciledSuiteRunIds
) {
    /** Current result protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunReconciliation.v1";

    /** Normalizes the protocol marker and freezes identifiers. */
    public TestSuiteRunReconciliationResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        reconciledSuiteRunIds = reconciledSuiteRunIds == null
                ? List.of() : List.copyOf(reconciledSuiteRunIds);
        if (sweptAt == null || scanned < 0 || reconciled < 0 || raced < 0 || failed < 0
                || reconciled + raced + failed > scanned) {
            throw new IllegalArgumentException("Invalid suite-run reconciliation counters");
        }
    }
}
