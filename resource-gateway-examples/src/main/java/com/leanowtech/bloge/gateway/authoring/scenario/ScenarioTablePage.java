package com.leanowtech.bloge.gateway.authoring.scenario;

import java.util.List;

/** Source-bound, bounded page returned by the server-side Scenario Matrix query. */
public record ScenarioTablePage(
        String schemaVersion,
        String scenarioDraftSetId,
        long revision,
        String draftFingerprint,
        String queryFingerprint,
        long totalMatching,
        List<Row> rows,
        String nextCursor
) {
    /** Current Matrix page protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioTablePage.v1";

    /** Freezes rows and normalizes source coordinates. */
    public ScenarioTablePage {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        scenarioDraftSetId = normalized(scenarioDraftSetId);
        draftFingerprint = normalized(draftFingerprint);
        queryFingerprint = normalized(queryFingerprint);
        totalMatching = Math.max(0, totalMatching);
        rows = rows == null ? List.of() : List.copyOf(rows);
        nextCursor = normalized(nextCursor);
    }

    /** One canonical Scenario row with its exact source fingerprint. */
    public record Row(
            int canonicalIndex,
            String caseFingerprint,
            ScenarioDraftSet.ScenarioDraft scenario
    ) {
        /** Rejects incomplete row projections. */
        public Row {
            if (canonicalIndex < 0 || scenario == null) {
                throw new IllegalArgumentException("Scenario Matrix row requires a canonical index and Scenario");
            }
            caseFingerprint = normalized(caseFingerprint);
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
