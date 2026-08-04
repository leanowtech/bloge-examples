package com.leanowtech.bloge.gateway.authoring.scenario;

import java.util.List;

/** Exact source-bound query for one bounded Scenario Matrix page. */
public record ScenarioTablePageQuery(
        String schemaVersion,
        long expectedRevision,
        String expectedDraftFingerprint,
        String query,
        List<ScenarioDraftSet.CaseType> caseTypes,
        SortField sortField,
        SortDirection sortDirection,
        String cursor,
        int limit
) {
    /** Current Matrix page-query protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioTablePageQuery.v1";

    /** Normalizes transport values and freezes filters. */
    public ScenarioTablePageQuery {
        schemaVersion = normalized(schemaVersion);
        expectedDraftFingerprint = normalized(expectedDraftFingerprint);
        query = normalized(query);
        caseTypes = caseTypes == null ? List.of() : caseTypes.stream().distinct().toList();
        sortField = sortField == null ? SortField.CANONICAL : sortField;
        sortDirection = sortDirection == null ? SortDirection.ASC : sortDirection;
        cursor = normalized(cursor);
    }

    /** Server-supported stable sort fields. Evidence verdict is intentionally not in this source table. */
    public enum SortField {
        CANONICAL,
        NAME,
        TYPE
    }

    /** Stable page direction. */
    public enum SortDirection {
        ASC,
        DESC
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
