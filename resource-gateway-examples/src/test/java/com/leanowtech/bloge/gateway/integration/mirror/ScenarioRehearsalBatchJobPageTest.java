package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchJobPageTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");

    @Test
    void acceptsStrictNewestFirstRowsAndLastRowCursor() {
        ScenarioRehearsalBatchJob newest = job(
                "f", Instant.parse("2026-07-25T09:00:00Z"));
        ScenarioRehearsalBatchJob older = job(
                "e", Instant.parse("2026-07-25T08:00:00Z"));

        ScenarioRehearsalBatchJobPage page =
                new ScenarioRehearsalBatchJobPage(
                        "",
                        SCOPE,
                        List.of(newest, older),
                        ScenarioRehearsalBatchJobPage.Cursor.after(older));

        assertThat(page.schemaVersion()).isEqualTo(
                ScenarioRehearsalBatchJobPage.SCHEMA_VERSION);
        assertThat(page.jobs()).containsExactly(newest, older);
        assertThat(page.nextCursor()).isEqualTo(
                new ScenarioRehearsalBatchJobPage.Cursor(
                        older.createdAt(), older.jobId()));
    }

    @Test
    void rejectsCrossScopeDuplicatesOrderDriftAndDetachedCursor() {
        ScenarioRehearsalBatchJob newer = job(
                "f", Instant.parse("2026-07-25T09:00:00Z"));
        ScenarioRehearsalBatchJob older = job(
                "e", Instant.parse("2026-07-25T08:00:00Z"));

        assertThatThrownBy(() -> new ScenarioRehearsalBatchJobPage(
                "",
                SCOPE,
                List.of(older, newer),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioRehearsalBatchJobPage(
                "",
                SCOPE,
                List.of(newer, newer),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioRehearsalBatchJobPage(
                "",
                SCOPE,
                List.of(newer),
                ScenarioRehearsalBatchJobPage.Cursor.after(older)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioRehearsalBatchJobPage(
                "",
                SCOPE,
                List.of(job(
                        "d",
                        Instant.parse("2026-07-25T07:00:00Z"),
                        new CapabilitySnapshot.Scope(
                                "tenant-b", "org-a", "support",
                                "test", "sg"))),
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ScenarioRehearsalBatchJob job(
            String digest,
            Instant createdAt) {
        return job(digest, createdAt, SCOPE);
    }

    private static ScenarioRehearsalBatchJob job(
            String digest,
            Instant createdAt,
            CapabilitySnapshot.Scope scope) {
        String fingerprint = "sha256:" + digest.repeat(64);
        ScenarioRehearsalBatchJob unsigned =
                new ScenarioRehearsalBatchJob(
                        "",
                        "scenario-batch-" + digest.repeat(64),
                        "request-" + digest,
                        fingerprint,
                        fingerprint,
                        scope,
                        ScenarioRehearsalBatchJob.Status.QUEUED,
                        ScenarioRehearsalBatchPolicy.FailureMode
                                .COLLECT_ALL,
                        ScenarioRehearsalBatchPolicy.Priority.NORMAL,
                        3,
                        new ScenarioRehearsalBatchJob.Summary(
                                1, 0, 0, 0, 0, 0),
                        createdAt.plusSeconds(300),
                        "",
                        "",
                        "",
                        createdAt,
                        createdAt,
                        null,
                        "");
        return ScenarioRehearsalBatchIntegrity.seal(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(),
                unsigned);
    }
}
