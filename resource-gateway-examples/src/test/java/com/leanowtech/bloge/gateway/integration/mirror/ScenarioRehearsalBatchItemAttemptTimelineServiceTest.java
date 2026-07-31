package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchItemAttemptTimelineServiceTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final Instant START =
            Instant.parse("2026-07-30T10:00:00Z");

    @Test
    void projectsExactRetryAndTerminalObservationsWithoutWorkerIdentity() {
        ScenarioRehearsalBatchService batches =
                mock(ScenarioRehearsalBatchService.class);
        ScenarioRehearsalBatchLifecycleAuditRepository audit =
                mock(ScenarioRehearsalBatchLifecycleAuditRepository.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        ScenarioRehearsalBatchJob job = job();
        MirrorArtifactRef planRef = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "plan-001",
                3,
                "sha256:" + "b".repeat(64));
        ScenarioRehearsalBatchItemPage page =
                mock(ScenarioRehearsalBatchItemPage.class);
        ScenarioRehearsalBatchItemPage.Item item =
                mock(ScenarioRehearsalBatchItemPage.Item.class);
        when(batches.find("job-001", identity))
                .thenReturn(Optional.of(job));
        when(batches.page("job-001", 0, 1, identity))
                .thenReturn(page);
        when(page.items()).thenReturn(List.of(item));
        when(item.itemIndex()).thenReturn(0);
        when(item.compiledPlanRef()).thenReturn(planRef);
        when(item.runId()).thenReturn("run-001");
        when(item.attemptCount()).thenReturn(2);
        when(audit.itemLifecycle(SCOPE, "job-001", 0))
                .thenReturn(List.of(
                        claim(1, 1, START),
                        observed(
                                2,
                                1,
                                START.plusSeconds(3),
                                ScenarioRehearsalBatchLifecycleAuditEvent
                                        .Transition.ITEM_RETRY_SCHEDULED,
                                ScenarioRehearsalBatchLifecycleAuditEvent
                                        .ItemStatus.PENDING,
                                "RG.DEPENDENCY.TIMEOUT"),
                        claim(3, 2, START.plusSeconds(8)),
                        observed(
                                4,
                                2,
                                START.plusSeconds(11),
                                ScenarioRehearsalBatchLifecycleAuditEvent
                                        .Transition.ITEM_TERMINALIZED,
                                ScenarioRehearsalBatchLifecycleAuditEvent
                                        .ItemStatus.FAILED,
                                "RG.DEPENDENCY.TIMEOUT")));
        ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget authorTarget =
                new ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget(
                        ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget
                                .Kind.GRAPH_DRAFT,
                        "answer-graph",
                        "Answer graph",
                        "answer-draft",
                        7,
                        "sha256:" + "c".repeat(64),
                        "grounding",
                        "golden-answer",
                        "visual-run-44",
                        "Knowledge Answers",
                        "Scenario author");
        ScenarioRehearsalAuthorTargetResolver authorTargets =
                (scope, exactPlan, runId) -> {
                    assertThat(scope).isEqualTo(SCOPE);
                    assertThat(exactPlan).isEqualTo(planRef);
                    assertThat(runId).isEqualTo("run-001");
                    return Optional.of(authorTarget);
                };
        ScenarioRehearsalBatchItemAttemptTimelineService service =
                new ScenarioRehearsalBatchItemAttemptTimelineService(
                        batches, audit, authorTargets);

        ScenarioRehearsalBatchItemAttemptTimeline result =
                service.timeline("job-001", 0, identity);

        assertThat(result.attemptsUsed()).isEqualTo(2);
        assertThat(result.attemptsRemaining()).isEqualTo(1);
        assertThat(result.historyComplete()).isTrue();
        assertThat(result.authorTarget()).isEqualTo(authorTarget);
        assertThat(result.attempts())
                .extracting(
                        ScenarioRehearsalBatchItemAttemptTimeline.Attempt
                                ::state)
                .containsExactly(
                        ScenarioRehearsalBatchItemAttemptTimeline.Attempt
                                .State.RETRY_SCHEDULED,
                        ScenarioRehearsalBatchItemAttemptTimeline.Attempt
                                .State.TERMINAL);
        assertThat(result.attempts().getFirst())
                .satisfies(attempt -> {
                    assertThat(attempt.startedAt()).isEqualTo(START);
                    assertThat(attempt.observedAt())
                            .isEqualTo(START.plusSeconds(3));
                    assertThat(attempt.reasonCode())
                            .isEqualTo("RG.DEPENDENCY.TIMEOUT");
                    assertThat(attempt.outcome()).isBlank();
                });
        assertThat(result.toString())
                .doesNotContain("worker-a");
        verify(audit).itemLifecycle(SCOPE, "job-001", 0);
    }

    @Test
    void keepsAnUnobservedClaimExplicitlyRunning() {
        List<ScenarioRehearsalBatchItemAttemptTimeline.Attempt> attempts =
                ScenarioRehearsalBatchItemAttemptTimelineService
                        .projectAttempts(List.of(
                                claim(1, 1, START)));

        assertThat(attempts).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.state()).isEqualTo(
                            ScenarioRehearsalBatchItemAttemptTimeline
                                    .Attempt.State.RUNNING);
                    assertThat(attempt.observedAt()).isNull();
                    assertThat(attempt.reasonCode()).isBlank();
                });
    }

    @Test
    void rejectsMissingItemsBeforeReadingAuditFacts() {
        ScenarioRehearsalBatchService batches =
                mock(ScenarioRehearsalBatchService.class);
        ScenarioRehearsalBatchLifecycleAuditRepository audit =
                mock(ScenarioRehearsalBatchLifecycleAuditRepository.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        ScenarioRehearsalBatchJob job = job();
        when(identity.correlationId()).thenReturn("corr-001");
        when(batches.find("job-001", identity))
                .thenReturn(Optional.of(job));
        ScenarioRehearsalBatchItemAttemptTimelineService service =
                new ScenarioRehearsalBatchItemAttemptTimelineService(
                        batches, audit);

        assertThatThrownBy(() ->
                service.timeline("job-001", 2, identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(
                        ((IntegrationProblemException) error)
                                .problem().code())
                        .isEqualTo(
                                "RG.MIRROR.REHEARSAL_BATCH.ITEM_NOT_FOUND"));
    }

    private static ScenarioRehearsalBatchJob job() {
        ScenarioRehearsalBatchJob job =
                mock(ScenarioRehearsalBatchJob.class);
        when(job.jobId()).thenReturn("job-001");
        when(job.scope()).thenReturn(SCOPE);
        when(job.maximumItemAttempts()).thenReturn(3);
        when(job.deadlineAt()).thenReturn(
                START.plusSeconds(60));
        when(job.failureMode()).thenReturn(
                ScenarioRehearsalBatchPolicy.FailureMode.COLLECT_ALL);
        when(job.summary()).thenReturn(
                new ScenarioRehearsalBatchJob.Summary(
                        1, 0, 0, 0, 0, 0));
        return job;
    }

    private static ScenarioRehearsalBatchLifecycleAuditEvent claim(
            long sequence,
            int attempt,
            Instant occurredAt) {
        return new ScenarioRehearsalBatchLifecycleAuditEvent(
                sequence,
                occurredAt,
                SCOPE,
                "job-001",
                "batch-001",
                "sha256:" + "a".repeat(64),
                ScenarioRehearsalBatchLifecycleAuditEvent.Transition.CLAIMED,
                ScenarioRehearsalBatchJob.Status.RUNNING,
                0,
                ScenarioRehearsalBatchLifecycleAuditEvent.ItemStatus.RUNNING,
                attempt,
                "worker-a",
                attempt,
                "",
                "");
    }

    private static ScenarioRehearsalBatchLifecycleAuditEvent observed(
            long sequence,
            int attempt,
            Instant occurredAt,
            ScenarioRehearsalBatchLifecycleAuditEvent.Transition transition,
            ScenarioRehearsalBatchLifecycleAuditEvent.ItemStatus status,
            String reasonCode) {
        return new ScenarioRehearsalBatchLifecycleAuditEvent(
                sequence,
                occurredAt,
                SCOPE,
                "job-001",
                "batch-001",
                "sha256:" + "a".repeat(64),
                transition,
                transition
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.ITEM_RETRY_SCHEDULED
                        ? ScenarioRehearsalBatchJob.Status.QUEUED
                        : ScenarioRehearsalBatchJob.Status.RUNNING,
                0,
                status,
                attempt,
                "worker-a",
                attempt,
                "",
                reasonCode);
    }
}
