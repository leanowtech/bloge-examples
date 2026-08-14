package com.leanowtech.bloge.gateway.businessmirror.impact;

import java.time.Instant;
import java.util.List;

/** Bounded maintenance result for rebuilding stale Package impact projections. */
public record BusinessAssetImpactRebuildReport(
        String schemaVersion,
        int projectedCount,
        int replayedCount,
        List<String> packageIds,
        String nextCursor,
        Instant completedAt
) {
    public static final String SCHEMA_VERSION =
            "resourceGateway.businessAssetImpactRebuildReport.v1";

    public BusinessAssetImpactRebuildReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || projectedCount < 0 || replayedCount < 0
                || projectedCount + replayedCount > 200) {
            throw new IllegalArgumentException("business asset impact rebuild report is invalid");
        }
        List<String> supplied = packageIds == null ? List.of() : packageIds;
        if (supplied.size() > 200) {
            throw new IllegalArgumentException("business asset impact rebuild page exceeds 200");
        }
        packageIds = supplied.stream().map(BusinessAssetImpactRebuildReport::identifier)
                .sorted().toList();
        if (packageIds.stream().distinct().count() != packageIds.size()
                || projectedCount + replayedCount != packageIds.size()) {
            throw new IllegalArgumentException(
                    "business asset impact rebuild counts do not match package coordinates");
        }
        nextCursor = nextCursor == null ? "" : nextCursor.trim();
        if (!nextCursor.isBlank()
                && (packageIds.isEmpty() || !identifier(nextCursor).equals(packageIds.getLast()))) {
            throw new IllegalArgumentException(
                    "business asset impact rebuild cursor must equal the last package id");
        }
        completedAt = java.util.Objects.requireNonNull(completedAt, "completedAt");
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512
                || !exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}")) {
            throw new IllegalArgumentException("business asset impact package id is invalid");
        }
        return exact;
    }
}
