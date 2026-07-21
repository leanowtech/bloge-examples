package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Orders one physical-attempt lifecycle observation around its durable journal.
 *
 * <p>The coordinator replays an exact accepted fact without provider I/O. Otherwise it resolves a
 * descriptor through the bounded supervisor, durably prepares the command, re-authorizes the
 * retained start and current state fence using database time, invokes the idempotent provider, and
 * asks the journal to verify and accept the detached attestation.</p>
 *
 * <p>A timeout, adapter failure, invalid attestation, or journal ambiguity leaves the command
 * {@link TestSuiteStabilityPhysicalAttemptObservationJournal.Status#PREPARED}. None proves
 * non-start, process liveness, or termination. A {@code NON_CONFIRMING} result is terminal only for
 * that immutable command and is replayed without trying to upgrade it.</p>
 *
 * <p>The process-scoped supervisor remains owned by the composition root. This coordinator does
 * not close it and does not project a positive observation into queue, slot, cancellation, or
 * natural-terminal state; those transitions require separate database-authoritative
 * linearization.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationCoordinator {

    private final TestSuiteStabilityPhysicalAttemptObservationJournal journal;
    private final TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor;

    /**
     * Creates a fail-closed physical-attempt observation ordering boundary.
     *
     * @param journal durable command and lifecycle-fact authority
     * @param supervisor fixed-capacity provider-call boundary owned by the composition root
     */
    public TestSuiteStabilityPhysicalAttemptObservationCoordinator(
            TestSuiteStabilityPhysicalAttemptObservationJournal journal,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    /**
     * Requests and durably verifies one exact physical-attempt lifecycle observation.
     *
     * <p>A retained {@code POSITIVE} or {@code NON_CONFIRMING} entry returns as exact replay with
     * no descriptor or observation call. A retained {@code PREPARED} entry must still match the
     * descriptor currently exposed by the supplied authority before invocation can be
     * re-authorized.</p>
     *
     * @param authority opaque isolated-runtime observation adapter
     * @param command exact content-addressed observation command
     * @return newly accepted or exactly replayed durable observation fact
     * @throws TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.InvocationException when
     *         a bounded provider call cannot complete
     * @throws TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictException when command,
     *         retained start, provider, time, process, revision, or sequence invariants reject the
     *         operation
     */
    public TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance observe(
            TestSuiteStabilityPhysicalAttemptObservationAuthority authority,
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptObservationAuthority requiredAuthority =
                Objects.requireNonNull(authority, "authority");
        TestSuiteStabilityPhysicalAttemptObservationCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptIdentity identity = requiredCommand.identity();

        Optional<TestSuiteStabilityPhysicalAttemptObservationJournal.Entry> retained =
                requireOptional(journal.find(
                        identity.tenantId(), identity.environmentId(),
                        requiredCommand.commandId()));
        if (retained.isPresent()) {
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry existing =
                    retained.orElseThrow();
            requireCommand(requiredCommand, existing);
            if (existing.status()
                    != TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED) {
                return replay(existing);
            }
        }

        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor =
                supervisor.descriptor(requiredAuthority);
        TestSuiteStabilityPhysicalAttemptObservationJournal.Preparation preparation =
                Objects.requireNonNull(
                        journal.prepare(requiredCommand, descriptor),
                        "physical-attempt observation journal preparation");
        TestSuiteStabilityPhysicalAttemptObservationJournal.Entry prepared = preparation.entry();
        requireBinding(requiredCommand, descriptor, prepared);
        if (prepared.status()
                != TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED) {
            return replay(prepared);
        }

        journal.authorizeInvocation(requiredCommand.commandId());
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation =
                supervisor.observe(requiredAuthority, requiredCommand);
        TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance acceptance =
                Objects.requireNonNull(
                        journal.accept(requiredCommand.commandId(), attestation),
                        "physical-attempt observation journal acceptance");
        requireBinding(requiredCommand, descriptor, acceptance.entry());
        return acceptance;
    }

    private static Optional<TestSuiteStabilityPhysicalAttemptObservationJournal.Entry>
            requireOptional(
                    Optional<TestSuiteStabilityPhysicalAttemptObservationJournal.Entry> value) {
        if (value == null) {
            throw contractViolation();
        }
        return value;
    }

    private static void requireCommand(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry entry) {
        if (!entry.command().equals(command)) {
            throw contractViolation();
        }
    }

    private static void requireBinding(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry entry) {
        requireCommand(command, entry);
        if (!entry.descriptor().equals(descriptor)) {
            throw contractViolation();
        }
    }

    private static TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance replay(
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry entry) {
        if (entry.status()
                == TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED) {
            throw contractViolation();
        }
        return new TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.REPLAYED,
                entry);
    }

    private static IllegalStateException contractViolation() {
        return new IllegalStateException(
                "Suite-stability physical-attempt observation journal violated its contract");
    }
}
