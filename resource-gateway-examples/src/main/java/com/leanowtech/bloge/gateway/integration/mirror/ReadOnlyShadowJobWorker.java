package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * One-step durable worker for read-only Shadow jobs.
 *
 * <p>The worker claims one database-fenced job, invokes the trusted payload-isolated data plane,
 * reconstructs a v2 comparison from immutable job coordinates plus runtime proof, signs it, and
 * atomically publishes terminal success. Raw connector exceptions are never persisted. Retry is
 * allowed only for the closed failure classes marked retryable by the data plane and remains
 * bounded by the job's frozen attempt count and database deadline.</p>
 */
public final class ReadOnlyShadowJobWorker {
    private static final String RESULT_INVALID =
            "RG.MIRROR.SHADOW.RESULT_INVALID";
    private static final String UNEXPECTED_FAILURE =
            "RG.MIRROR.SHADOW.UNEXPECTED_FAILURE";

    private final ReadOnlyShadowJobRepository repository;
    private final ReadOnlyShadowDataPlane dataPlane;
    private final ReadOnlyShadowComparisonIntegrity integrity;
    private final ReadOnlyShadowJobPolicy policy;

    /**
     * Creates one worker.
     *
     * @param repository durable queue and comparison publisher
     * @param dataPlane trusted payload-isolated execution boundary
     * @param integrity managed comparison signer
     * @param policy server-owned lease and retry controls
     */
    public ReadOnlyShadowJobWorker(
            ReadOnlyShadowJobRepository repository,
            ReadOnlyShadowDataPlane dataPlane,
            ReadOnlyShadowComparisonIntegrity integrity,
            ReadOnlyShadowJobPolicy policy) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.dataPlane = Objects.requireNonNull(
                dataPlane, "dataPlane");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
    }

    /**
     * Claims and processes at most one job in an exact region/environment partition.
     *
     * @return resulting claim; an acquired claim's job is the pre-execution running projection
     */
    public ReadOnlyShadowJobRepository.Claim runOne(
            String region,
            String environmentId,
            String ownerId) {
        if (!ready()) {
            return ReadOnlyShadowJobRepository.Claim
                    .noWork(repository.observedAt());
        }
        ReadOnlyShadowJobRepository.Claim claim =
                repository.claimNext(
                        region,
                        environmentId,
                        ownerId,
                        policy);
        if (claim.outcome()
                == ReadOnlyShadowJobRepository
                .ClaimOutcome.NO_WORK) {
            return claim;
        }
        MutableControl control = new MutableControl(
                claim.lease());
        try {
            ReadOnlyShadowDataPlane.ExecutionResult result =
                    dataPlane.execute(
                            new ReadOnlyShadowDataPlane.Permit(
                                    claim.job().jobId(),
                                    claim.request(),
                                    claim.job().attemptCount(),
                                    claim.job().deadlineAt(),
                                    control));
            ReadOnlyShadowComparison comparison =
                    buildComparison(
                            claim,
                            result);
            repository.complete(
                    control.lease(),
                    integrity.sign(comparison));
        } catch (ReadOnlyShadowDataPlane.Failure failure) {
            repository.fail(
                    control.lease(),
                    "RG.MIRROR.SHADOW."
                            + failure.reason().name(),
                    failure.reason().retryable(),
                    policy);
        } catch (ReadOnlyShadowJobRepository.Violation lost) {
            if (lost.reason()
                    != ReadOnlyShadowJobRepository
                    .Reason.LEASE_LOST) {
                failCurrent(
                        control,
                        RESULT_INVALID,
                        false);
            }
        } catch (IllegalArgumentException invalid) {
            failCurrent(
                    control,
                    RESULT_INVALID,
                    false);
        } catch (RuntimeException unexpected) {
            failCurrent(
                    control,
                    UNEXPECTED_FAILURE,
                    false);
        }
        return claim;
    }

    /** @return whether the complete payload-isolated execution authority currently reports ready */
    public boolean ready() {
        try {
            return integrity.available()
                    && safeReady();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private ReadOnlyShadowComparison buildComparison(
            ReadOnlyShadowJobRepository.Claim claim,
            ReadOnlyShadowDataPlane.ExecutionResult result) {
        ReadOnlyShadowJobRequest request =
                claim.request();
        if (!request.accessGrant().zeroWriteProof()
                .equals(result.accessProof())) {
            throw new IllegalArgumentException(
                    "Shadow data-plane proof differs from admitted grant");
        }
        return new ReadOnlyShadowComparison(
                ReadOnlyShadowComparison.SCHEMA_VERSION,
                claim.job().jobId(),
                1,
                "",
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.comparisonPolicyRef(),
                result.sourceResolutionAttestationRef(),
                result.authorityProof(),
                result.accessProof(),
                result.baseline(),
                result.candidate(),
                result.observedAt(),
                result.results(),
                null);
    }

    private void failCurrent(
            MutableControl control,
            String failureCode,
            boolean retryable) {
        try {
            repository.fail(
                    control.lease(),
                    failureCode,
                    retryable,
                    policy);
        } catch (ReadOnlyShadowJobRepository.Violation ignored) {
            // A concurrently expired lease owns its own later reconciliation.
        }
    }

    private boolean safeReady() {
        try {
            return dataPlane.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private final class MutableControl
            implements ReadOnlyShadowDataPlane.ExecutionControl {
        private ReadOnlyShadowJobRepository.Lease lease;

        private MutableControl(
                ReadOnlyShadowJobRepository.Lease lease) {
            this.lease = Objects.requireNonNull(
                    lease, "lease");
        }

        @Override
        public Instant leaseExpiresAt() {
            return lease.expiresAt();
        }

        @Override
        public Instant heartbeat() {
            ReadOnlyShadowJobRepository.Heartbeat renewed =
                    repository.heartbeat(
                            lease, policy);
            lease = renewed.lease();
            return lease.expiresAt();
        }

        private ReadOnlyShadowJobRepository.Lease lease() {
            return lease;
        }
    }
}
