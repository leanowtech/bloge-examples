package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate health view for a filtered visual graph run-history window.
 *
 * @param schemaVersion stats schema version
 * @param totalRuns total matching run records
 * @param successfulRuns successful run records
 * @param failedRuns unsuccessful run records
 * @param blockedRuns records that did not compile or execute
 * @param executionFailedRuns records that compiled but did not complete successfully
 * @param successRate successful runs divided by total runs
 * @param p50ElapsedMs nearest-rank p50 elapsed time across matching records
 * @param p95ElapsedMs nearest-rank p95 elapsed time across matching records
 * @param maxElapsedMs maximum elapsed time across matching records
 * @param firstRunAt oldest matching run timestamp
 * @param latestRunAt newest matching run timestamp
 * @param bySourceKind run count by source kind
 * @param byGraphName run count by graph name
 */
public record VisualGraphRunStats(
        String schemaVersion,
        int totalRuns,
        int successfulRuns,
        int failedRuns,
        int blockedRuns,
        int executionFailedRuns,
        double successRate,
        long p50ElapsedMs,
        long p95ElapsedMs,
        long maxElapsedMs,
        Instant firstRunAt,
        Instant latestRunAt,
        Map<String, Integer> bySourceKind,
        Map<String, Integer> byGraphName
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunStats.v1";

    /**
     * Creates run stats.
     */
    public VisualGraphRunStats {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        totalRuns = Math.max(0, totalRuns);
        successfulRuns = Math.max(0, successfulRuns);
        failedRuns = Math.max(0, failedRuns);
        blockedRuns = Math.max(0, blockedRuns);
        executionFailedRuns = Math.max(0, executionFailedRuns);
        successRate = totalRuns == 0 ? 0.0D : successRate;
        p50ElapsedMs = Math.max(0, p50ElapsedMs);
        p95ElapsedMs = Math.max(0, p95ElapsedMs);
        maxElapsedMs = Math.max(0, maxElapsedMs);
        bySourceKind = bySourceKind == null ? Map.of() : new LinkedHashMap<>(bySourceKind);
        byGraphName = byGraphName == null ? Map.of() : new LinkedHashMap<>(byGraphName);
    }

    /**
     * Builds aggregate stats from newest-first or arbitrary run records.
     *
     * @param records run records
     * @return aggregate stats
     */
    public static VisualGraphRunStats from(Collection<VisualGraphRunRecord> records) {
        List<VisualGraphRunRecord> runs = records == null ? List.of() : records.stream().toList();
        if (runs.isEmpty()) {
            return new VisualGraphRunStats("", 0, 0, 0, 0, 0, 0.0D, 0, 0, 0,
                    null, null, Map.of(), Map.of());
        }

        int successful = 0;
        int blocked = 0;
        int executionFailed = 0;
        long maxElapsed = 0;
        Instant first = null;
        Instant latest = null;
        Map<String, Integer> bySource = new LinkedHashMap<>();
        Map<String, Integer> byGraph = new LinkedHashMap<>();
        List<Long> elapsed = new ArrayList<>();

        for (VisualGraphRunRecord run : runs) {
            if (run.success()) {
                successful++;
            } else if (!run.compiled()) {
                blocked++;
            } else {
                executionFailed++;
            }
            maxElapsed = Math.max(maxElapsed, run.elapsedMs());
            elapsed.add(run.elapsedMs());
            first = earliest(first, run.createdAt());
            latest = latest(latest, run.createdAt());
            increment(bySource, run.sourceKind().isBlank() ? "UNKNOWN" : run.sourceKind());
            increment(byGraph, run.graphName().isBlank() ? "unnamedGraph" : run.graphName());
        }

        int total = runs.size();
        return new VisualGraphRunStats(
                "",
                total,
                successful,
                total - successful,
                blocked,
                executionFailed,
                successful / (double) total,
                percentile(elapsed, 0.50D),
                percentile(elapsed, 0.95D),
                maxElapsed,
                first,
                latest,
                bySource,
                byGraph
        );
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static Instant earliest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
