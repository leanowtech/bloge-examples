package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Outcome trend view for a filtered visual graph run-history window.
 *
 * @param schemaVersion trend schema version
 * @param totalRuns total matching run records
 * @param successfulRuns successful run records
 * @param failedRuns unsuccessful run records
 * @param blockedRuns records that did not compile or execute
 * @param executionFailedRuns records that compiled but did not complete successfully
 * @param successRate successful runs divided by total runs
 * @param latestOutcome newest run outcome, or {@code NONE} when the window is empty
 * @param successToFailureTransitions chronological successful-to-failed outcome turns
 * @param failureToSuccessTransitions chronological failed-to-successful outcome turns
 * @param latestFailureStreak consecutive failed records from the newest run backward
 * @param latestSuccessStreak consecutive successful records from the newest run backward
 * @param latestRunRegressed whether the newest run changed from prior success to failure
 * @param latestElapsedMs newest run elapsed milliseconds
 * @param previousSuccessfulElapsedMs nearest older successful run elapsed milliseconds
 * @param latestLatencyDeltaMs newest successful run latency delta against the prior successful run
 * @param points newest-first trend points
 */
public record VisualGraphRunTrend(
        String schemaVersion,
        int totalRuns,
        int successfulRuns,
        int failedRuns,
        int blockedRuns,
        int executionFailedRuns,
        double successRate,
        String latestOutcome,
        int successToFailureTransitions,
        int failureToSuccessTransitions,
        int latestFailureStreak,
        int latestSuccessStreak,
        boolean latestRunRegressed,
        long latestElapsedMs,
        long previousSuccessfulElapsedMs,
        long latestLatencyDeltaMs,
        List<Point> points
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunTrend.v1";

    /**
     * Creates a normalized trend response.
     */
    public VisualGraphRunTrend {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        totalRuns = Math.max(0, totalRuns);
        successfulRuns = Math.max(0, successfulRuns);
        failedRuns = Math.max(0, failedRuns);
        blockedRuns = Math.max(0, blockedRuns);
        executionFailedRuns = Math.max(0, executionFailedRuns);
        successRate = totalRuns == 0 ? 0.0D : successRate;
        latestOutcome = latestOutcome == null || latestOutcome.isBlank() ? "NONE" : latestOutcome;
        successToFailureTransitions = Math.max(0, successToFailureTransitions);
        failureToSuccessTransitions = Math.max(0, failureToSuccessTransitions);
        latestFailureStreak = Math.max(0, latestFailureStreak);
        latestSuccessStreak = Math.max(0, latestSuccessStreak);
        latestElapsedMs = Math.max(0, latestElapsedMs);
        previousSuccessfulElapsedMs = Math.max(0, previousSuccessfulElapsedMs);
        points = points == null ? List.of() : List.copyOf(points);
    }

    /**
     * Builds a trend from newest-first or arbitrary run records.
     *
     * @param records run records
     * @return outcome trend
     */
    public static VisualGraphRunTrend from(Collection<VisualGraphRunRecord> records) {
        List<VisualGraphRunRecord> runs = records == null
                ? List.of()
                : records.stream()
                .sorted(Comparator.comparing(VisualGraphRunRecord::createdAt).reversed()
                        .thenComparing(VisualGraphRunRecord::runId))
                .toList();
        if (runs.isEmpty()) {
            return new VisualGraphRunTrend("", 0, 0, 0, 0, 0, 0.0D, "", 0, 0,
                    0, 0, false, 0, 0, 0, List.of());
        }

        List<Point> points = points(runs);
        int successful = 0;
        int blocked = 0;
        int executionFailed = 0;
        for (Point point : points) {
            if (point.success()) {
                successful++;
            } else if ("BLOCKED".equals(point.outcome())) {
                blocked++;
            } else {
                executionFailed++;
            }
        }

        int successToFailure = 0;
        int failureToSuccess = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            Point newer = points.get(i);
            Point older = points.get(i + 1);
            if (older.success() && !newer.success()) {
                successToFailure++;
            }
            if (!older.success() && newer.success()) {
                failureToSuccess++;
            }
        }

        int total = points.size();
        Point latest = points.getFirst();
        long previousSuccessfulElapsed = previousSuccessfulElapsedMs(points);
        long latestLatencyDelta = latest.success() && previousSuccessfulElapsed > 0
                ? latest.elapsedMs() - previousSuccessfulElapsed
                : 0;
        return new VisualGraphRunTrend(
                "",
                total,
                successful,
                total - successful,
                blocked,
                executionFailed,
                successful / (double) total,
                latest.outcome(),
                successToFailure,
                failureToSuccess,
                streak(points, false),
                streak(points, true),
                points.size() > 1 && points.get(1).success() && !latest.success(),
                latest.elapsedMs(),
                previousSuccessfulElapsed,
                latestLatencyDelta,
                points
        );
    }

    private static List<Point> points(List<VisualGraphRunRecord> runs) {
        return IntStream.range(0, runs.size())
                .mapToObj(index -> Point.from(index, runs.get(index)))
                .toList();
    }

    private static int streak(List<Point> points, boolean success) {
        int count = 0;
        for (Point point : points) {
            if (point.success() != success) {
                break;
            }
            count++;
        }
        return count;
    }

    private static long previousSuccessfulElapsedMs(List<Point> points) {
        return points.stream()
                .skip(1)
                .filter(Point::success)
                .mapToLong(Point::elapsedMs)
                .findFirst()
                .orElse(0L);
    }

    /**
     * One newest-first point in the run trend.
     *
     * @param index zero-based newest-first index
     * @param runId run id
     * @param createdAt run creation timestamp
     * @param sourceKind run source kind
     * @param sourceArtifactKind publication artifact kind when present
     * @param graphName graph name
     * @param success whether execution succeeded
     * @param compiled whether compilation passed
     * @param outcome normalized outcome: {@code SUCCESS}, {@code FAILED}, or {@code BLOCKED}
     * @param elapsedMs run elapsed milliseconds
     * @param diagnosticCount diagnostic count on the run record
     * @param errorCount error count on the run record
     */
    public record Point(
            int index,
            String runId,
            Instant createdAt,
            String sourceKind,
            String sourceArtifactKind,
            String graphName,
            boolean success,
            boolean compiled,
            String outcome,
            long elapsedMs,
            int diagnosticCount,
            int errorCount
    ) {

        /**
         * Creates a normalized trend point.
         */
        public Point {
            index = Math.max(0, index);
            runId = runId == null ? "" : runId;
            sourceKind = sourceKind == null ? "" : sourceKind;
            sourceArtifactKind = sourceArtifactKind == null ? "" : sourceArtifactKind;
            graphName = graphName == null ? "" : graphName;
            outcome = outcome == null || outcome.isBlank() ? "SUCCESS" : outcome;
            elapsedMs = Math.max(0, elapsedMs);
            diagnosticCount = Math.max(0, diagnosticCount);
            errorCount = Math.max(0, errorCount);
        }

        private static Point from(int index, VisualGraphRunRecord run) {
            return new Point(index,
                    run.runId(),
                    run.createdAt(),
                    run.sourceKind(),
                    run.sourceArtifactKind(),
                    run.graphName(),
                    run.success(),
                    run.compiled(),
                    outcome(run),
                    run.elapsedMs(),
                    run.diagnostics().size(),
                    run.errors().size());
        }

        private static String outcome(VisualGraphRunRecord run) {
            if (run.success()) {
                return "SUCCESS";
            }
            return run.compiled() ? "FAILED" : "BLOCKED";
        }
    }
}
