package com.leanowtech.bloge.graphengine.service;

import java.util.List;

/**
 * Aggregate usage statistics for one operator across visible graph definitions
 * and versions in the current tenant scope.
 *
 * @param definitionCount number of distinct definitions that reference the operator
 * @param versionCount    total number of versions that reference the operator
 * @param references      per-version reference details (capped to a reasonable size)
 */
public record OperatorUsageSummary(
        int definitionCount,
        int versionCount,
        List<OperatorUsageReference> references
) {

    /** Empty usage summary returned when no versions reference the operator. */
    public static final OperatorUsageSummary EMPTY =
            new OperatorUsageSummary(0, 0, List.of());

    public OperatorUsageSummary {
        if (definitionCount < 0) {
            throw new IllegalArgumentException("definitionCount must be >= 0");
        }
        if (versionCount < 0) {
            throw new IllegalArgumentException("versionCount must be >= 0");
        }
        references = references == null ? List.of() : List.copyOf(references);
    }
}
