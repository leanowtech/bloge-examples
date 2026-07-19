package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;

/**
 * Persistence-authoritative result of one bounded exact-suite history query.
 *
 * @param records retained terminal records in chronological order
 * @param expiredMatchingRuns matching rows excluded by evidence retention
 * @param truncated whether at least one matching row exceeded the caller budget
 * @param observedAt database time used for the retention decision
 */
public record TestSuiteStabilityHistoryWindow(
        List<TestSuiteStabilityRunRecord> records,
        int expiredMatchingRuns,
        boolean truncated,
        Instant observedAt
) {
    /** Freezes source order and rejects contradictory persistence facts. */
    public TestSuiteStabilityHistoryWindow {
        records = records == null ? List.of() : List.copyOf(records);
        if (expiredMatchingRuns < 0 || observedAt == null) {
            throw new IllegalArgumentException("Stability history window facts are invalid");
        }
    }

    /** @return true only when retention and query bounds preserved the complete source set */
    public boolean complete() {
        return expiredMatchingRuns == 0 && !truncated;
    }
}
