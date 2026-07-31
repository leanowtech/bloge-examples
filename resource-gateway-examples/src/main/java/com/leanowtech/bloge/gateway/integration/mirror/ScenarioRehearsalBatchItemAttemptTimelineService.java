package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Produces an exact public item-attempt timeline from payload-free lifecycle facts.
 */
public final class ScenarioRehearsalBatchItemAttemptTimelineService {
    private final ScenarioRehearsalBatchService batches;
    private final ScenarioRehearsalBatchLifecycleAuditRepository audit;
    private final ScenarioRehearsalAuthorTargetResolver authorTargets;

    /** Creates the scope-validating timeline projection boundary. */
    public ScenarioRehearsalBatchItemAttemptTimelineService(
            ScenarioRehearsalBatchService batches,
            ScenarioRehearsalBatchLifecycleAuditRepository audit) {
        this(
                batches,
                audit,
                ScenarioRehearsalAuthorTargetResolver.unavailable());
    }

    /** Creates the timeline boundary with an exact host-owned Author binding resolver. */
    public ScenarioRehearsalBatchItemAttemptTimelineService(
            ScenarioRehearsalBatchService batches,
            ScenarioRehearsalBatchLifecycleAuditRepository audit,
            ScenarioRehearsalAuthorTargetResolver authorTargets) {
        this.batches = Objects.requireNonNull(batches, "batches");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.authorTargets = Objects.requireNonNull(
                authorTargets, "authorTargets");
    }

    /**
     * Reads one exact item timeline under the authenticated batch scope.
     *
     * @param jobId stable batch identity
     * @param itemIndex zero-based immutable manifest position
     * @param identity authenticated read identity
     * @return payload-free exact timeline
     */
    public ScenarioRehearsalBatchItemAttemptTimeline timeline(
            String jobId,
            int itemIndex,
            IntegrationRequestContext identity) {
        ScenarioRehearsalBatchJob job = batches.find(jobId, identity)
                .orElseThrow(() -> problem(
                        identity,
                        "RG.MIRROR.REHEARSAL_BATCH.JOB_NOT_FOUND",
                        "Scenario rehearsal batch was not found.",
                        false));
        if (itemIndex < 0
                || itemIndex >= job.summary().totalItems()) {
            throw problem(
                    identity,
                    "RG.MIRROR.REHEARSAL_BATCH.ITEM_NOT_FOUND",
                    "Scenario rehearsal batch item was not found.",
                    false);
        }
        ScenarioRehearsalBatchItemPage page =
                batches.page(job.jobId(), itemIndex, 1, identity);
        ScenarioRehearsalBatchItemPage.Item item =
                page.items().stream()
                        .filter(candidate ->
                                candidate.itemIndex() == itemIndex)
                        .findFirst()
                        .orElseThrow(() -> problem(
                                identity,
                                "RG.MIRROR.REHEARSAL_BATCH.ITEM_NOT_FOUND",
                                "Scenario rehearsal batch item was not found.",
                                false));
        List<ScenarioRehearsalBatchLifecycleAuditEvent> facts =
                audit.itemLifecycle(job.scope(), job.jobId(), itemIndex);
        List<ScenarioRehearsalBatchItemAttemptTimeline.Attempt> attempts =
                projectAttempts(facts);
        boolean historyComplete =
                attempts.size() == item.attemptCount();
        if ((!facts.isEmpty() && !historyComplete)
                || item.attemptCount() > job.maximumItemAttempts()) {
            throw problem(
                    identity,
                    "RG.MIRROR.REHEARSAL_BATCH.ATTEMPT_HISTORY_INVALID",
                    "Scenario rehearsal attempt history differs from the item projection.",
                    true);
        }
        return new ScenarioRehearsalBatchItemAttemptTimeline(
                "",
                job.jobId(),
                itemIndex,
                job.maximumItemAttempts(),
                item.attemptCount(),
                job.maximumItemAttempts() - item.attemptCount(),
                job.deadlineAt(),
                job.failureMode(),
                historyComplete,
                historyComplete ? attempts : List.of(),
                authorTargets.resolve(
                        job.scope(),
                        item.compiledPlanRef(),
                        item.runId()).orElse(null));
    }

    static List<ScenarioRehearsalBatchItemAttemptTimeline.Attempt>
    projectAttempts(
            List<ScenarioRehearsalBatchLifecycleAuditEvent> facts) {
        List<ScenarioRehearsalBatchItemAttemptTimeline.Attempt> attempts =
                new ArrayList<>();
        ScenarioRehearsalBatchLifecycleAuditEvent claim = null;
        for (ScenarioRehearsalBatchLifecycleAuditEvent fact
                : facts == null ? List.<ScenarioRehearsalBatchLifecycleAuditEvent>of() : facts) {
            switch (fact.transition()) {
                case CLAIMED -> {
                    if (claim != null
                            || fact.attemptCount() != attempts.size() + 1) {
                        throw new IllegalStateException(
                                "Scenario rehearsal item audit claim order is invalid");
                    }
                    claim = fact;
                }
                case ITEM_RETRY_SCHEDULED, ITEM_TERMINALIZED -> {
                    if (claim == null
                            || fact.attemptCount() != claim.attemptCount()
                            || fact.sequence() <= claim.sequence()) {
                        throw new IllegalStateException(
                                "Scenario rehearsal item audit observation is unpaired");
                    }
                    boolean retry = fact.transition()
                            == ScenarioRehearsalBatchLifecycleAuditEvent
                            .Transition.ITEM_RETRY_SCHEDULED;
                    attempts.add(
                            new ScenarioRehearsalBatchItemAttemptTimeline.Attempt(
                                    claim.attemptCount(),
                                    retry
                                            ? ScenarioRehearsalBatchItemAttemptTimeline
                                            .Attempt.State.RETRY_SCHEDULED
                                            : ScenarioRehearsalBatchItemAttemptTimeline
                                            .Attempt.State.TERMINAL,
                                    claim.occurredAt(),
                                    fact.occurredAt(),
                                    retry ? "" : fact.itemStatus().name(),
                                    fact.reasonCode(),
                                    claim.sequence(),
                                    fact.sequence()));
                    claim = null;
                }
                default -> throw new IllegalStateException(
                        "Scenario rehearsal item audit contains a job-level transition");
            }
        }
        if (claim != null) {
            attempts.add(
                    new ScenarioRehearsalBatchItemAttemptTimeline.Attempt(
                            claim.attemptCount(),
                            ScenarioRehearsalBatchItemAttemptTimeline
                                    .Attempt.State.RUNNING,
                            claim.occurredAt(),
                            null,
                            "",
                            "",
                            claim.sequence(),
                            0));
        }
        return List.copyOf(attempts);
    }

    private static IntegrationProblemException problem(
            IntegrationRequestContext identity,
            String code,
            String title,
            boolean conflict) {
        IntegrationProblem value = conflict
                ? IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of())
                : IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of());
        return new IntegrationProblemException(value);
    }
}
