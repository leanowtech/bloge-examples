package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Orders one physical-attempt provider start around its durable journal.
 *
 * <p>The coordinator replays an exact terminal fact without provider I/O. Otherwise it resolves a
 * descriptor through the bounded supervisor, durably prepares the command, re-authorizes the live
 * reservation and queue lease using database time, invokes the idempotent provider, and asks the
 * journal to verify and accept the detached attestation.</p>
 *
 * <p>A timeout, adapter failure, invalid attestation, or journal ambiguity leaves the command
 * {@link TestSuiteStabilityPhysicalAttemptStartJournal.Status#PREPARED}. None proves remote
 * non-start. An {@code UNCONFIRMED} rejection is terminal only for that immutable command and is
 * replayed without trying to upgrade it.</p>
 *
 * <p>The process-scoped supervisor remains owned by the composition root. This coordinator does
 * not close it and does not project start into queue, slot, cancellation, or natural-terminal
 * state; those transitions require a separate database-authoritative linearization.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptStartCoordinator {

    private final TestSuiteStabilityPhysicalAttemptStartJournal journal;
    private final TestSuiteStabilityPhysicalAttemptStartCallSupervisor supervisor;

    /**
     * Creates a fail-closed physical-attempt start ordering boundary.
     *
     * @param journal durable command and provider-attestation authority
     * @param supervisor fixed-capacity provider-call boundary owned by the composition root
     */
    public TestSuiteStabilityPhysicalAttemptStartCoordinator(
            TestSuiteStabilityPhysicalAttemptStartJournal journal,
            TestSuiteStabilityPhysicalAttemptStartCallSupervisor supervisor) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    /**
     * Requests and durably verifies start of one exact reserved physical attempt.
     *
     * <p>A retained {@code CONFIRMED} or {@code UNCONFIRMED} entry returns as exact replay with no
     * descriptor or start call. A retained {@code PREPARED} entry must still match the descriptor
     * currently exposed by the supplied authority before invocation can be re-authorized.</p>
     *
     * @param authority opaque isolated-runtime provider adapter
     * @param command exact content-addressed start command
     * @return newly accepted or exactly replayed terminal journal fact
     * @throws TestSuiteStabilityPhysicalAttemptStartCallSupervisor.InvocationException when a
     *         bounded provider call cannot complete
     * @throws TestSuiteStabilityPhysicalAttemptStartJournal.ConflictException when command,
     *         reservation, provider, time, or sequence invariants reject the operation
     */
    public TestSuiteStabilityPhysicalAttemptStartJournal.Acceptance start(
            TestSuiteStabilityPhysicalAttemptStartAuthority authority,
            TestSuiteStabilityPhysicalAttemptStartCommand command) {
        TestSuiteStabilityPhysicalAttemptStartAuthority requiredAuthority =
                Objects.requireNonNull(authority, "authority");
        TestSuiteStabilityPhysicalAttemptStartCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptIdentity identity = requiredCommand.identity();

        Optional<TestSuiteStabilityPhysicalAttemptStartJournal.Entry> retained =
                requireOptional(journal.find(
                        identity.tenantId(), identity.environmentId(),
                        requiredCommand.commandId()));
        if (retained.isPresent()) {
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry existing =
                    retained.orElseThrow();
            requireCommand(requiredCommand, existing);
            if (existing.status()
                    != TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED) {
                return replay(existing);
            }
        }

        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor =
                supervisor.descriptor(requiredAuthority);
        TestSuiteStabilityPhysicalAttemptStartJournal.Preparation preparation =
                Objects.requireNonNull(
                        journal.prepare(requiredCommand, descriptor),
                        "physical-attempt start journal preparation");
        TestSuiteStabilityPhysicalAttemptStartJournal.Entry prepared = preparation.entry();
        requireBinding(requiredCommand, descriptor, prepared);
        if (prepared.status() != TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED) {
            return replay(prepared);
        }

        journal.authorizeInvocation(requiredCommand.commandId());
        TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation =
                supervisor.start(requiredAuthority, requiredCommand);
        TestSuiteStabilityPhysicalAttemptStartJournal.Acceptance acceptance =
                Objects.requireNonNull(
                        journal.accept(requiredCommand.commandId(), attestation),
                        "physical-attempt start journal acceptance");
        requireBinding(requiredCommand, descriptor, acceptance.entry());
        return acceptance;
    }

    private static Optional<TestSuiteStabilityPhysicalAttemptStartJournal.Entry> requireOptional(
            Optional<TestSuiteStabilityPhysicalAttemptStartJournal.Entry> value) {
        if (value == null) {
            throw contractViolation();
        }
        return value;
    }

    private static void requireCommand(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry entry) {
        if (!entry.command().equals(command)) {
            throw contractViolation();
        }
    }

    private static void requireBinding(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry entry) {
        requireCommand(command, entry);
        if (!entry.descriptor().equals(descriptor)) {
            throw contractViolation();
        }
    }

    private static TestSuiteStabilityPhysicalAttemptStartJournal.Acceptance replay(
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry entry) {
        if (entry.status() == TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED) {
            throw contractViolation();
        }
        return new TestSuiteStabilityPhysicalAttemptStartJournal.Acceptance(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.REPLAYED, entry);
    }

    private static IllegalStateException contractViolation() {
        return new IllegalStateException(
                "Suite-stability physical-attempt start journal violated its contract");
    }
}
