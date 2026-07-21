package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Orders one provider-confirmed attempt cancellation around its durable journal.
 *
 * <p>The coordinator first replays any exact terminal fact, otherwise resolves the current
 * provider descriptor through the bounded call supervisor, durably prepares the exact command and
 * descriptor, invokes the idempotent provider, and finally asks the journal to verify and accept
 * the detached attestation. A provider timeout, adapter failure, invalid attestation, or journal
 * ambiguity leaves the command {@link TestSuiteStabilityAttemptCancellationJournal.Status#PREPARED}
 * for explicit recovery; none is converted into cancellation success.</p>
 *
 * <p>The injected call supervisor is process-scoped and remains owned by the caller. This class
 * deliberately does not close it and does not project a confirmed receipt into queue state. Queue
 * projection requires a separate database-authoritative linearization against the exact worker
 * lease.</p>
 *
 * <p>An {@code UNCONFIRMED} entry is terminal only for the immutable command, not proof that the
 * physical attempt stopped. This coordinator replays that fact and never retries it. Likewise, it
 * fails closed when a prepared command's frozen provider deployment is no longer the deployment
 * exposed by the supplied authority. Routing either case to a bounded reconciliation/orphan lane
 * is deliberately outside this ordering primitive.</p>
 */
public final class TestSuiteStabilityAttemptCancellationCoordinator {

    private final TestSuiteStabilityAttemptCancellationJournal journal;
    private final TestSuiteStabilityAttemptCancellationCallSupervisor supervisor;

    /**
     * Creates a fail-closed cancellation ordering boundary.
     *
     * @param journal durable command and provider-receipt authority
     * @param supervisor fixed-capacity provider-call boundary owned by the composition root
     */
    public TestSuiteStabilityAttemptCancellationCoordinator(
            TestSuiteStabilityAttemptCancellationJournal journal,
            TestSuiteStabilityAttemptCancellationCallSupervisor supervisor) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    /**
     * Requests and durably verifies termination of one exact physically isolated attempt.
     *
     * <p>An already retained {@code CONFIRMED} or {@code UNCONFIRMED} entry is returned as an exact
     * replay without touching the provider. Callers must inspect the retained entry status because
     * replay does not upgrade {@code UNCONFIRMED} into termination proof. A retained
     * {@code PREPARED} entry must still match the provider's current descriptor before the
     * idempotent cancellation call is retried.</p>
     *
     * @param authority opaque physical-attempt provider adapter
     * @param command exact content-addressed cancellation command
     * @return newly accepted or exactly replayed terminal journal fact
     * @throws TestSuiteStabilityAttemptCancellationCallSupervisor.InvocationException when a
     *         bounded provider call cannot complete; the durable command remains recoverable
     * @throws TestSuiteStabilityAttemptCancellationJournal.ConflictException when exact command,
     *         provider, time, or sequence invariants reject the operation
     */
    public TestSuiteStabilityAttemptCancellationJournal.Acceptance cancel(
            TestSuiteStabilityAttemptCancellationAuthority authority,
            TestSuiteStabilityAttemptCancellationCommand command) {
        TestSuiteStabilityAttemptCancellationAuthority requiredAuthority =
                Objects.requireNonNull(authority, "authority");
        TestSuiteStabilityAttemptCancellationCommand requiredCommand =
                Objects.requireNonNull(command, "command");

        Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> retained =
                requireOptional(journal.find(
                        requiredCommand.tenantId(), requiredCommand.environmentId(),
                        requiredCommand.commandId()));
        if (retained.isPresent()) {
            TestSuiteStabilityAttemptCancellationJournal.Entry existing = retained.orElseThrow();
            requireCommand(requiredCommand, existing);
            if (existing.status()
                    != TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED) {
                return replay(existing);
            }
        }

        TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor =
                supervisor.descriptor(requiredAuthority);
        TestSuiteStabilityAttemptCancellationJournal.Preparation preparation =
                Objects.requireNonNull(
                        journal.prepare(requiredCommand, descriptor),
                        "attempt cancellation journal preparation");
        TestSuiteStabilityAttemptCancellationJournal.Entry prepared = preparation.entry();
        requireBinding(requiredCommand, descriptor, prepared);
        if (prepared.status() != TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED) {
            return replay(prepared);
        }

        TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation =
                supervisor.cancel(requiredAuthority, requiredCommand);
        TestSuiteStabilityAttemptCancellationJournal.Acceptance acceptance =
                Objects.requireNonNull(
                        journal.accept(requiredCommand.commandId(), attestation),
                        "attempt cancellation journal acceptance");
        requireBinding(requiredCommand, descriptor, acceptance.entry());
        return acceptance;
    }

    private static Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> requireOptional(
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> value) {
        if (value == null) {
            throw contractViolation();
        }
        return value;
    }

    private static void requireCommand(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationJournal.Entry entry) {
        if (!entry.command().equals(command)) {
            throw contractViolation();
        }
    }

    private static void requireBinding(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor,
            TestSuiteStabilityAttemptCancellationJournal.Entry entry) {
        requireCommand(command, entry);
        if (!entry.descriptor().equals(descriptor)) {
            throw contractViolation();
        }
    }

    private static TestSuiteStabilityAttemptCancellationJournal.Acceptance replay(
            TestSuiteStabilityAttemptCancellationJournal.Entry entry) {
        if (entry.status() == TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED) {
            throw contractViolation();
        }
        return new TestSuiteStabilityAttemptCancellationJournal.Acceptance(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.REPLAYED, entry);
    }

    private static IllegalStateException contractViolation() {
        return new IllegalStateException(
                "Suite-stability attempt cancellation journal violated its contract");
    }
}
