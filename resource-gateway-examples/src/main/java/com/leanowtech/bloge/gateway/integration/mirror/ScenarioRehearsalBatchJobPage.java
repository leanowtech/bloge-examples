package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stable keyset page over payload-free Scenario rehearsal batch projections.
 *
 * <p>Rows are ordered by immutable {@code createdAt DESC, jobId DESC}; mutable status changes
 * therefore cannot move a row between pages. The cursor is an ordering coordinate, not an
 * authorization claim. Repositories must always apply the authenticated complete enterprise scope
 * independently of caller-supplied cursor values.</p>
 *
 * @param schemaVersion exact page protocol version
 * @param scope authenticated complete enterprise scope
 * @param jobs bounded newest-first job projections
 * @param nextCursor coordinate for the next older page, or null at the end
 */
public record ScenarioRehearsalBatchJobPage(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        List<ScenarioRehearsalBatchJob> jobs,
        Cursor nextCursor
) {
    /** Current batch-list page protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchJobPage.v1";
    /** Maximum number of jobs returned by one list operation. */
    public static final int MAXIMUM_PAGE_SIZE = 100;
    private static final Pattern JOB_ID =
            Pattern.compile("scenario-batch-[a-f0-9]{64}");

    /** Enforces exact scope, bounded rows, stable order, and cursor correspondence. */
    public ScenarioRehearsalBatchJobPage {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch job page schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope");
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        if (jobs.size() > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Scenario batch job page is too large");
        }
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < jobs.size(); index++) {
            ScenarioRehearsalBatchJob job = Objects.requireNonNull(
                    jobs.get(index), "job");
            if (!scope.equals(job.scope())
                    || !identities.add(job.jobId())
                    || index > 0
                    && !newerThan(jobs.get(index - 1), job)) {
                throw new IllegalArgumentException(
                        "Scenario batch job page scope or order is invalid");
            }
        }
        if (nextCursor != null
                && (jobs.isEmpty()
                || !nextCursor.equals(Cursor.after(jobs.getLast())))) {
            throw new IllegalArgumentException(
                    "Scenario batch next cursor must match the last returned job");
        }
    }

    /**
     * Immutable keyset coordinate for the next older page.
     *
     * @param createdAt immutable creation time of the last returned row
     * @param jobId deterministic identity of the last returned row
     */
    public record Cursor(
            Instant createdAt,
            String jobId
    ) {
        /** Rejects incomplete or non-canonical coordinates. */
        public Cursor {
            createdAt = Objects.requireNonNull(
                    createdAt, "createdAt");
            jobId = jobId == null ? "" : jobId.trim();
            if (!JOB_ID.matcher(jobId).matches()) {
                throw new IllegalArgumentException(
                        "Scenario batch cursor jobId is invalid");
            }
        }

        /** @return cursor immediately after the supplied page row */
        public static Cursor after(
                ScenarioRehearsalBatchJob job) {
            ScenarioRehearsalBatchJob value =
                    Objects.requireNonNull(job, "job");
            return new Cursor(value.createdAt(), value.jobId());
        }
    }

    private static boolean newerThan(
            ScenarioRehearsalBatchJob left,
            ScenarioRehearsalBatchJob right) {
        int time = left.createdAt().compareTo(right.createdAt());
        return time > 0
                || time == 0
                && left.jobId().compareTo(right.jobId()) > 0;
    }
}
