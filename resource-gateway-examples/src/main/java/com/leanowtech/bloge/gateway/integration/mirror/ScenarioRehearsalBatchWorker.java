package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One database-fenced worker turn for a durable Scenario rehearsal batch.
 *
 * <p>The worker executes only the exact child request frozen by the manifest. It independently
 * verifies signed aggregate evidence and the deterministic workbook seed before checkpointing the
 * queue item. Retryability comes from the structured integration problem rather than exception
 * class guesses; malformed evidence is terminal and infrastructure outages are retryable.</p>
 */
public final class ScenarioRehearsalBatchWorker {
    private static final Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    private final ScenarioRehearsalBatchRepository repository;
    private final ScenarioRehearsalRuntimeService runtime;
    private final ScenarioRehearsalEvidenceIntegrityService
            evidenceIntegrity;
    private final ScenarioRehearsalBatchPolicy policy;
    private final ObjectMapper mapper;

    /** Creates one worker over the shared durable queue and protected Scenario runtime. */
    public ScenarioRehearsalBatchWorker(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalRuntimeService runtime,
            ScenarioRehearsalEvidenceIntegrityService
                    evidenceIntegrity,
            ScenarioRehearsalBatchPolicy policy,
            ObjectMapper mapper) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Claims and processes at most one exact manifest item.
     *
     * @param region server-owned regional queue partition
     * @param environmentId server-owned environment queue partition
     * @param ownerId opaque stable worker-attempt owner
     * @return bounded turn result without business payload
     */
    public Turn runOnce(
            String region,
            String environmentId,
            String ownerId) {
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        region, environmentId, ownerId, policy);
        if (claim.outcome()
                == ScenarioRehearsalBatchRepository
                .ClaimOutcome.NO_WORK) {
            return new Turn(
                    Disposition.NO_WORK,
                    null,
                    -1,
                    "");
        }
        requireClaimClosure(claim);
        IntegrationRequestContext identity =
                claim.principal().toExecutionContext(
                        correlationId(claim));
        BatchExecutionControl control =
                new BatchExecutionControl(
                        claim.lease(),
                        identity.correlationId());
        try {
            ScenarioRehearsalEvidenceBundle produced =
                    runtime.execute(
                            new ScenarioRehearsalExecutionRequest(
                                    "",
                                    claim.item().childRequestId(),
                                    claim.item().compiledPlanRef()),
                            identity,
                            control);
            ScenarioRehearsalEvidenceBundle verified =
                    evidenceIntegrity.requireVerified(
                            produced).bundle();
            requireEvidenceClosure(claim, verified);
            ScenarioRehearsalWorkbookSeed workbook =
                    runtime.workbookSeed(
                            verified.attestation().runId(),
                            identity);
            workbook.verify(mapper);
            requireWorkbookClosure(
                    verified, workbook);
            ScenarioRehearsalBatchJob job =
                    repository.completeItem(
                            claim.lease(),
                            new ScenarioRehearsalBatchRepository
                                    .ItemCompletion(
                                    verified.result().outcome(),
                                    verified.attestation().runId(),
                                    verified.bundleFingerprint(),
                                    workbook.seedFingerprint()),
                            policy);
            return new Turn(
                    Disposition.ITEM_COMPLETED,
                    job,
                    claim.item().itemIndex(),
                    "");
        } catch (ScenarioRehearsalExecutionControlException stopped) {
            return controlledStop(
                    claim, control, stopped);
        } catch (IntegrationProblemException problem) {
            String code = failureCode(
                    problem.problem().code(),
                    "RG.MIRROR.REHEARSAL_BATCH.RUNTIME_REJECTED");
            return problem.problem().retryable()
                    ? retry(claim, code)
                    : fail(claim, code);
        } catch (IllegalArgumentException invalidEvidence) {
            return fail(
                    claim,
                    "RG.MIRROR.REHEARSAL_BATCH.EVIDENCE_INVALID");
        } catch (IllegalStateException unavailableAuthority) {
            return retry(
                    claim,
                    "RG.MIRROR.REHEARSAL_BATCH.VERIFICATION_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            return retry(
                    claim,
                    "RG.MIRROR.REHEARSAL_BATCH.WORKER_UNAVAILABLE");
        }
    }

    private Turn controlledStop(
            ScenarioRehearsalBatchRepository.Claim claim,
            BatchExecutionControl control,
            ScenarioRehearsalExecutionControlException stopped) {
        ScenarioRehearsalBatchRepository
                .ExecutionControlCheckpoint checkpoint =
                control.latest();
        if (checkpoint == null
                || !matches(
                checkpoint.outcome(), stopped.reason())) {
            return new Turn(
                    Disposition.CONTROL_INCONSISTENT,
                    checkpoint == null
                            ? claim.job() : checkpoint.job(),
                    claim.item().itemIndex(),
                    "RG.MIRROR.REHEARSAL_BATCH.CONTROL_INCONSISTENT");
        }
        Disposition disposition = switch (checkpoint.outcome()) {
            case CANCELLED -> Disposition.ITEM_CANCELLED;
            case DEADLINE_EXCEEDED -> Disposition.ITEM_EXPIRED;
            case LEASE_LOST -> Disposition.LEASE_LOST;
            case CONTINUE -> throw new IllegalStateException(
                    "Continue checkpoint cannot stop a Scenario batch");
        };
        return new Turn(
                disposition,
                checkpoint.job(),
                claim.item().itemIndex(),
                stopped.reason().code());
    }

    private static boolean matches(
            ScenarioRehearsalBatchRepository
                    .ExecutionControlOutcome outcome,
            ScenarioRehearsalExecutionControlException.Reason reason) {
        return switch (outcome) {
            case CONTINUE -> false;
            case CANCELLED ->
                    reason == ScenarioRehearsalExecutionControlException
                            .Reason.CANCELLED;
            case DEADLINE_EXCEEDED ->
                    reason == ScenarioRehearsalExecutionControlException
                            .Reason.DEADLINE_EXCEEDED;
            case LEASE_LOST ->
                    reason == ScenarioRehearsalExecutionControlException
                            .Reason.LEASE_LOST;
        };
    }

    private Turn retry(
            ScenarioRehearsalBatchRepository.Claim claim,
            String failureCode) {
        ScenarioRehearsalBatchJob job =
                repository.retryItem(
                        claim.lease(),
                        failureCode,
                        policy);
        return new Turn(
                Disposition.ITEM_RETRY_SCHEDULED,
                job,
                claim.item().itemIndex(),
                failureCode);
    }

    private Turn fail(
            ScenarioRehearsalBatchRepository.Claim claim,
            String failureCode) {
        ScenarioRehearsalBatchJob job =
                repository.failItem(
                        claim.lease(),
                        failureCode,
                        policy);
        return new Turn(
                Disposition.ITEM_FAILED,
                job,
                claim.item().itemIndex(),
                failureCode);
    }

    private static void requireClaimClosure(
            ScenarioRehearsalBatchRepository.Claim claim) {
        if (!claim.job().scope().equals(
                claim.principal().scope())
                || !claim.job().scope().equals(
                claim.lease().scope())
                || !claim.job().jobId().equals(
                claim.lease().jobId())
                || claim.item().itemIndex()
                != claim.lease().itemIndex()) {
            throw new IllegalStateException(
                    "Scenario batch claim closure is inconsistent");
        }
    }

    private void requireEvidenceClosure(
            ScenarioRehearsalBatchRepository.Claim claim,
            ScenarioRehearsalEvidenceBundle bundle) {
        if (!bundle.result().scope().equals(
                claim.job().scope())
                || !bundle.result().requestId().equals(
                claim.item().childRequestId())
                || !bundle.result().compiledPlanRef().equals(
                claim.item().compiledPlanRef())
                || !bundle.attestation().runId().equals(
                ScenarioRehearsalRunIdentity.derive(
                        mapper,
                        claim.job().scope(),
                        claim.item().childRequestId()))) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence differs from the claimed manifest item");
        }
    }

    private static void requireWorkbookClosure(
            ScenarioRehearsalEvidenceBundle bundle,
            ScenarioRehearsalWorkbookSeed workbook) {
        if (!workbook.scope().equals(bundle.result().scope())
                || !workbook.runId().equals(
                bundle.attestation().runId())
                || !workbook.requestId().equals(
                bundle.result().requestId())
                || !workbook.compiledPlanRef().equals(
                bundle.result().compiledPlanRef())
                || !workbook.evidenceBundleFingerprint()
                .equals(bundle.bundleFingerprint())
                || workbook.outcome()
                != bundle.result().outcome()) {
            throw new IllegalArgumentException(
                    "Scenario batch workbook differs from verified aggregate evidence");
        }
    }

    private static String correlationId(
            ScenarioRehearsalBatchRepository.Claim claim) {
        return "scenario-batch:"
                + claim.job().jobId()
                + ":"
                + claim.lease().epoch();
    }

    private static String failureCode(
            String candidate,
            String fallback) {
        String normalized = candidate == null
                ? "" : candidate.trim().toUpperCase(
                java.util.Locale.ROOT);
        return FAILURE_CODE.matcher(normalized).matches()
                ? normalized : fallback;
    }

    private final class BatchExecutionControl
            implements ScenarioRehearsalExecutionControl {
        private final ScenarioRehearsalBatchRepository.Lease lease;
        private final String correlationId;
        private ScenarioRehearsalBatchRepository
                .ExecutionControlCheckpoint latest;

        private BatchExecutionControl(
                ScenarioRehearsalBatchRepository.Lease lease,
                String correlationId) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.correlationId = correlationId == null
                    ? "" : correlationId.trim();
        }

        @Override
        public void checkpoint(Checkpoint checkpoint) {
            Objects.requireNonNull(checkpoint, "checkpoint");
            latest = repository.checkpointExecution(
                    lease,
                    checkpoint.nextCaseIndex(),
                    policy);
            ScenarioRehearsalExecutionControlException.Reason
                    reason = switch (latest.outcome()) {
                case CONTINUE -> null;
                case CANCELLED ->
                        ScenarioRehearsalExecutionControlException
                                .Reason.CANCELLED;
                case DEADLINE_EXCEEDED ->
                        ScenarioRehearsalExecutionControlException
                                .Reason.DEADLINE_EXCEEDED;
                case LEASE_LOST ->
                        ScenarioRehearsalExecutionControlException
                                .Reason.LEASE_LOST;
            };
            if (reason != null) {
                throw new ScenarioRehearsalExecutionControlException(
                        reason, correlationId);
            }
        }

        private ScenarioRehearsalBatchRepository
                .ExecutionControlCheckpoint latest() {
            return latest;
        }
    }

    /** Payload-free outcome of one bounded worker turn. */
    public record Turn(
            Disposition disposition,
            ScenarioRehearsalBatchJob job,
            int itemIndex,
            String failureCode
    ) {
        /** Enforces no-work versus claimed-item field correspondence. */
        public Turn {
            disposition = Objects.requireNonNull(
                    disposition, "disposition");
            failureCode = failureCode == null
                    ? "" : failureCode.trim();
            boolean noWork = disposition == Disposition.NO_WORK;
            if (noWork != (job == null && itemIndex == -1)
                    || noWork && !failureCode.isBlank()
                    || !noWork && (job == null || itemIndex < 0)) {
                throw new IllegalArgumentException(
                        "Scenario batch worker turn is inconsistent");
            }
        }
    }

    /** Closed worker-turn vocabulary. */
    public enum Disposition {
        NO_WORK,
        ITEM_COMPLETED,
        ITEM_RETRY_SCHEDULED,
        ITEM_FAILED,
        ITEM_CANCELLED,
        ITEM_EXPIRED,
        LEASE_LOST,
        CONTROL_INCONSISTENT
    }
}
