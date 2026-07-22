package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRegistry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;

import java.util.Objects;
import java.util.Optional;

/**
 * Database adapter for verified physical-attempt terminal projection.
 *
 * <p>The adapter resolves every source through its integrity-verifying journal, then delegates
 * the queue mutation and projection append to one environment-serialized transaction in the
 * queue repository. Source resolution never releases capacity by itself.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
        implements TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal {

    private final DatabaseTestSuiteStabilityJobRepository jobs;
    private final TestSuiteStabilityPhysicalAttemptRegistry attempts;
    private final TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private final TestSuiteStabilityPhysicalAttemptObservationJournal observations;
    private final TestSuiteStabilityAttemptCancellationJournal cancellations;

    /**
     * Creates a terminal projector over journals and queue storage sharing one datasource.
     *
     * @param jobs database queue repository with physical-attempt fencing enabled
     * @param attempts integrity-verifying reservation journal
     * @param starts integrity-verifying start journal
     * @param observations integrity-verifying lifecycle observation journal
     * @param cancellations integrity-verifying cancellation journal
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
            DatabaseTestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityPhysicalAttemptRegistry attempts,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityAttemptCancellationJournal cancellations) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
    }

    /** {@inheritDoc} */
    @Override
    public Projection project(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command,
            TestSuiteStabilityQueuePolicy policy) {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        Objects.requireNonNull(policy, "policy");
        try {
            Optional<Entry> retained = jobs.findPhysicalAttemptTerminalProjection(
                    requiredCommand.tenantId(), requiredCommand.environmentId(),
                    requiredCommand.projectionId());
            if (retained.isPresent()) {
                if (!retained.orElseThrow().command().equals(requiredCommand)) {
                    throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
                }
                return new Projection(ProjectionStatus.REPLAYED, retained.orElseThrow());
            }
            TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation = attempts.find(
                    requiredCommand.tenantId(), requiredCommand.environmentId(),
                    requiredCommand.attemptId()).orElseThrow(() -> conflict(
                            ConflictReason.SOURCE_NOT_RETAINED));
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start = starts.find(
                    requiredCommand.tenantId(), requiredCommand.environmentId(),
                    requiredCommand.startCommandId()).orElseThrow(() -> conflict(
                            ConflictReason.SOURCE_NOT_RETAINED));
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry observation =
                    observations.find(requiredCommand.tenantId(),
                            requiredCommand.environmentId(),
                            requiredCommand.observationCommandId()).orElseThrow(() -> conflict(
                                    ConflictReason.SOURCE_NOT_RETAINED));
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState state =
                    observations.latestPositive(requiredCommand.tenantId(),
                            requiredCommand.environmentId(),
                            requiredCommand.attemptId()).orElseThrow(() -> conflict(
                                    ConflictReason.SOURCE_NOT_RETAINED));
            if (state.receipt().state()
                    != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL) {
                throw conflict(ConflictReason.TERMINAL_NOT_CONFIRMED);
            }
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation =
                    requiredCommand.cancellationCommandId().isBlank()
                            ? Optional.empty()
                            : Optional.of(cancellations.find(requiredCommand.tenantId(),
                                    requiredCommand.environmentId(),
                                    requiredCommand.cancellationCommandId()).orElseThrow(
                                            () -> conflict(
                                                    ConflictReason
                                                            .CANCELLATION_PROOF_REQUIRED)));
            return jobs.projectPhysicalAttemptTerminal(requiredCommand, reservation, start,
                    observation, state, cancellation, policy);
        } catch (ConflictException conflict) {
            throw conflict;
        } catch (IllegalStateException sourceIntegrityFailure) {
            throw conflict(ConflictReason.SOURCE_CHANGED);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(
            String tenantId, String environmentId, String projectionId) {
        return jobs.findPhysicalAttemptTerminalProjection(
                tenantId, environmentId, projectionId);
    }

    private static ConflictException conflict(ConflictReason reason) {
        return new ConflictException(reason);
    }
}
