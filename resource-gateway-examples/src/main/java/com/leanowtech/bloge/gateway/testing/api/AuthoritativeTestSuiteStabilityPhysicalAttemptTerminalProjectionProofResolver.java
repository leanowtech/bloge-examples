package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * Product proof resolver over the durable cancellation, queue, and signed-parent authorities.
 *
 * <p>Cancellation resolution uses one exact attempt-fence lookup and accepts only a retained
 * provider-confirmed receipt whose command, provider deployment, and physical isolation identity
 * all match the observed attempt. Parent-success resolution derives the deterministic parent from
 * the retained queue job, distinguishes an unfinished parent from a terminal contradiction, and
 * asks the parent authority to independently verify the retained evidence and detached
 * signature.</p>
 *
 * <p>Absent proof that can still become authoritative is {@code PENDING}. Ambiguous, corrupt,
 * wrong-scope, wrong-shape, or terminally contradictory proof is {@code CONFLICT}. Repository,
 * database, and trust-service outages deliberately escape as runtime exceptions so the projection
 * coordinator records {@code PROOF_RESOLUTION_UNAVAILABLE}; this class never fabricates proof or
 * includes business payload in its result.</p>
 */
public final class
        AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
        implements TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver {

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAttemptCancellationJournal cancellations;
    private final TestSuiteStabilityJobRepository jobs;
    private final TestSuiteStabilityRunRepository parentRuns;
    private final TestSuiteStabilityJobParentAuthority parentAuthority;

    /**
     * Creates a resolver over independently integrity-verifying product authorities.
     *
     * @param objectMapper canonical protocol mapper used for deterministic parent identity
     * @param cancellations provider-confirmed attempt-cancellation journal
     * @param jobs durable queue and immutable execution-intent authority
     * @param parentRuns signed parent run and stop-tombstone repository
     * @param parentAuthority independent retained-parent signature verifier
     */
    public AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver(
            ObjectMapper objectMapper,
            TestSuiteStabilityAttemptCancellationJournal cancellations,
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityRunRepository parentRuns,
            TestSuiteStabilityJobParentAuthority parentAuthority) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.parentRuns = Objects.requireNonNull(parentRuns, "parentRuns");
        this.parentAuthority = Objects.requireNonNull(parentAuthority, "parentAuthority");
    }

    /** {@inheritDoc} */
    @Override
    public Resolution resolve(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                    disposition) {
        TestSuiteStabilityPhysicalAttemptIdentity exactIdentity = Objects.requireNonNull(
                identity, "identity");
        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition exactDisposition =
                Objects.requireNonNull(disposition, "disposition");
        return switch (exactDisposition) {
            case CANCELLED -> cancellation(exactIdentity);
            case SUCCEEDED -> parentSuccess(exactIdentity);
            case NONE, FAILED, TIMED_OUT, PROVIDER_ABORTED -> throw new IllegalArgumentException(
                    "Terminal projection proof is not required for this disposition");
        };
    }

    private Resolution cancellation(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        TestSuiteStabilityAttemptCancellationJournal.AttemptLookup lookup =
                Objects.requireNonNull(cancellations.findByAttempt(
                        identity.tenantId(), identity.environmentId(), identity.attemptId(),
                        identity.leaseEpoch()), "attempt cancellation lookup");
        if (lookup.status()
                == TestSuiteStabilityAttemptCancellationJournal.AttemptLookupStatus.ABSENT) {
            return Resolution.pending(Reason.CANCELLATION_NOT_CONFIRMED);
        }
        if (lookup.status()
                == TestSuiteStabilityAttemptCancellationJournal.AttemptLookupStatus.CONFLICT) {
            return Resolution.conflict(lookup.reason()
                    == TestSuiteStabilityAttemptCancellationJournal.AttemptLookupReason.AMBIGUOUS
                    ? Reason.AMBIGUOUS_PROOF : Reason.PROOF_CONFLICT);
        }

        TestSuiteStabilityAttemptCancellationJournal.Entry entry =
                lookup.entry().orElseThrow();
        if (!matches(identity, entry)) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }
        return switch (entry.status()) {
            case PREPARED -> Resolution.pending(Reason.CANCELLATION_NOT_CONFIRMED);
            case UNCONFIRMED -> Resolution.conflict(Reason.PROOF_CONFLICT);
            case CONFIRMED -> Resolution.ready(Proof.cancellation(entry));
        };
    }

    private Resolution parentSuccess(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        Optional<TestSuiteStabilityJobRecord> retainedJob = Objects.requireNonNull(
                jobs.find(identity.tenantId(), identity.environmentId(), identity.jobId()),
                "terminal projection parent job lookup");
        if (retainedJob.isEmpty()) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }
        TestSuiteStabilityJobRecord job = retainedJob.orElseThrow();
        if (!matches(identity, job)) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }

        TestSuiteStabilityExecutionDescriptor parent =
                TestSuiteStabilityExecutionIdentity.descriptor(objectMapper, job);
        Optional<TestSuiteStabilityRunRecord> retainedParent = Objects.requireNonNull(
                parentRuns.find(identity.tenantId(), identity.environmentId(),
                        parent.stabilityRunId()), "terminal projection parent run lookup");
        if (retainedParent.isEmpty()) {
            if (job.status().terminal()
                    || Objects.requireNonNull(parentRuns.findStop(
                    identity.tenantId(), identity.environmentId(), parent.stabilityRunId()),
                    "terminal projection parent stop lookup").isPresent()) {
                return Resolution.conflict(Reason.PROOF_CONFLICT);
            }
            return Resolution.pending(Reason.PARENT_NOT_CONFIRMED);
        }
        if (job.status().terminal()
                && job.status() != TestSuiteStabilityJobRecord.Status.SUCCEEDED) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }

        TestSuiteStabilityRunRecord candidate = retainedParent.orElseThrow();
        if (!candidate.stabilityRunId().equals(parent.stabilityRunId())
                || !candidate.tenantId().equals(identity.tenantId())
                || !candidate.environmentId().equals(identity.environmentId())
                || !candidate.clientRequestId().equals(parent.clientRequestId())
                || !candidate.requestFingerprint().equals(identity.requestFingerprint())) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }
        try {
            TestSuiteStabilityJobParentAuthority.Resolution verified = parentAuthority
                    .requireCompleted(job, candidate.stabilityRunId(),
                            candidate.evidenceFingerprint());
            if (verified == null
                    || verified.outcome()
                    != TestSuiteStabilityJobParentAuthority.Outcome.COMPLETED
                    || !verified.stabilityRunId().equals(candidate.stabilityRunId())
                    || !verified.evidenceFingerprint().equals(
                    candidate.evidenceFingerprint())) {
                return Resolution.conflict(Reason.PROOF_CONFLICT);
            }
            return Resolution.ready(Proof.parentSuccess(
                    verified.stabilityRunId(), verified.evidenceFingerprint()));
        } catch (TestSuiteStabilityRunConflictException conflict) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        } catch (IllegalArgumentException invalidProof) {
            return Resolution.conflict(Reason.PROOF_CONFLICT);
        }
    }

    private static boolean matches(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityAttemptCancellationJournal.Entry entry) {
        TestSuiteStabilityAttemptCancellationCommand command = entry.command();
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor =
                entry.descriptor();
        Optional<TestSuiteStabilityAttemptCancellationReceipt.Attestation> attestation =
                entry.attestation();
        if (!command.tenantId().equals(identity.tenantId())
                || !command.environmentId().equals(identity.environmentId())
                || !command.jobId().equals(identity.jobId())
                || !command.attemptId().equals(identity.attemptId())
                || !command.ownerId().equals(identity.ownerId())
                || command.leaseEpoch() != identity.leaseEpoch()
                || !command.runtimeBindingFingerprint().equals(
                identity.runtimeBindingFingerprint())
                || !descriptor.providerId().equals(identity.providerId())
                || !descriptor.deploymentId().equals(identity.deploymentId())
                || !descriptor.available()
                || !descriptor.isolationModes().contains(identity.isolationMode())) {
            return false;
        }
        if (entry.status()
                != TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED) {
            return attestation.isEmpty();
        }
        if (attestation.isEmpty()) {
            return false;
        }
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                attestation.orElseThrow().receipt();
        return attestation.orElseThrow().keyId().equals(descriptor.keyId())
                && receipt.terminationConfirmed()
                && receipt.commandId().equals(command.commandId())
                && receipt.commandFingerprint().equals(command.commandFingerprint())
                && receipt.providerId().equals(identity.providerId())
                && receipt.deploymentId().equals(identity.deploymentId())
                && receipt.attemptId().equals(identity.attemptId())
                && receipt.leaseEpoch() == identity.leaseEpoch()
                && receipt.isolationMode() == identity.isolationMode();
    }

    private static boolean matches(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityJobRecord job) {
        return job.jobId().equals(identity.jobId())
                && job.tenantId().equals(identity.tenantId())
                && job.environmentId().equals(identity.environmentId())
                && job.requestFingerprint().equals(identity.requestFingerprint());
    }
}
