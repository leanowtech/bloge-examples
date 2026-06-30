package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Collection;
import java.util.List;

/**
 * Query constraints for visual graph run history.
 *
 * @param sourceKind optional run source kind
 * @param draftId optional draft id
 * @param publicationId optional publication id
 * @param graphName optional graph name
 * @param success optional execution outcome
 * @param limit maximum number of records to return, or {@code 0} for unbounded
 */
public record VisualGraphRunQuery(
        String sourceKind,
        String draftId,
        String publicationId,
        String graphName,
        Boolean success,
        int limit
) {
    private static final int MAX_LIMIT = 200;

    /**
     * Normalizes query values.
     */
    public VisualGraphRunQuery {
        sourceKind = normalizeUpper(sourceKind);
        draftId = normalize(draftId);
        publicationId = normalize(publicationId);
        graphName = normalize(graphName);
        limit = limit <= 0 ? 0 : Math.min(limit, MAX_LIMIT);
    }

    /**
     * Applies the query to already-newest-first records.
     *
     * @param records records to filter
     * @param query query, or {@code null} for no constraints
     * @return matching records
     */
    public static List<VisualGraphRunRecord> apply(Collection<VisualGraphRunRecord> records,
                                                   VisualGraphRunQuery query) {
        VisualGraphRunQuery actual = query == null
                ? new VisualGraphRunQuery("", "", "", "", null, 0)
                : query;
        return records.stream()
                .filter(actual::matches)
                .limit(actual.limit() == 0 ? Long.MAX_VALUE : actual.limit())
                .toList();
    }

    /**
     * @param record run record
     * @return whether the record satisfies this query
     */
    public boolean matches(VisualGraphRunRecord record) {
        if (record == null) {
            return false;
        }
        return matches(sourceKind, normalizeUpper(record.sourceKind()))
                && matches(draftId, normalize(record.draftId()))
                && matches(publicationId, normalize(record.publicationId()))
                && matches(graphName, normalize(record.graphName()))
                && (success == null || success == record.success());
    }

    private static boolean matches(String expected, String actual) {
        return expected.isBlank() || expected.equals(actual);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeUpper(String value) {
        return normalize(value).toUpperCase();
    }
}
