package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityAttemptCancellationCoordinatorTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private TestSuiteStabilityAttemptCancellationCommand command;
    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor;
    private TestSuiteStabilityAttemptCancellationReceipt.Attestation confirmed;
    private TestSuiteStabilityAttemptCancellationReceipt.Attestation notFound;
    private RecordingJournal journal;
    private TestSuiteStabilityAttemptCancellationCallSupervisor supervisor;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        command = TestSuiteStabilityAttemptCancellationCommand.create(
                mapper, "tenant-a", "test", "stability-job-" + "1".repeat(64),
                "stability-attempt-" + "2".repeat(64), "worker-a", 7,
                "sha256:" + "3".repeat(64),
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                requestedAt, requestedAt.plusSeconds(30), challenge('a'));
        descriptor = descriptor("attempt-runtime-a.generation-7");
        confirmed = attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED, 11);
        notFound = attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND, 12);
        journal = new RecordingJournal(events);
        supervisor = new TestSuiteStabilityAttemptCancellationCallSupervisor(
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMillis(100), 1));
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void ordersFreshCancellationAroundDurablePreparation() {
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance result =
                coordinator().cancel(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "cancel", "accept");
    }

    @Test
    void replaysConfirmedTerminalWithoutCallingProvider() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED,
                descriptor, Optional.of(confirmed)));
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance result =
                coordinator().cancel(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry()).isEqualTo(journal.retained.orElseThrow());
        assertThat(events).containsExactly("find");
        assertThat(authority.cancellationCalls()).isZero();
    }

    @Test
    void replaysUnconfirmedTerminalWithoutCallingProvider() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED,
                descriptor, Optional.of(notFound)));
        RecordingAuthority authority = authority(descriptor, ignored -> notFound);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance result =
                coordinator().cancel(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED);
        assertThat(events).containsExactly("find");
        assertThat(authority.cancellationCalls()).isZero();
    }

    @Test
    void resumesPreparedCommandAfterRevalidatingCurrentDescriptor() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance result =
                coordinator().cancel(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "cancel", "accept");
    }

    @Test
    void refusesProviderCallWhenPreparedDescriptorDrifts() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(
                descriptor("attempt-runtime-a.generation-8"), ignored -> confirmed);

        assertThatThrownBy(() -> coordinator().cancel(authority, command))
                .isInstanceOf(TestSuiteStabilityAttemptCancellationJournal.ConflictException.class)
                .extracting(error -> ((TestSuiteStabilityAttemptCancellationJournal
                        .ConflictException) error).reason())
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal
                        .ConflictReason.IDEMPOTENCY_CONFLICT);
        assertThat(events).containsExactly("find", "descriptor", "prepare");
        assertThat(authority.cancellationCalls()).isZero();
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void leavesPreparedCommandRecoverableWhenProviderTimesOut() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return confirmed;
        });

        assertThatThrownBy(() -> coordinator().cancel(authority, command))
                .isInstanceOf(TestSuiteStabilityAttemptCancellationCallSupervisor
                        .InvocationException.class)
                .extracting(error -> ((TestSuiteStabilityAttemptCancellationCallSupervisor
                        .InvocationException) error).disposition())
                .isEqualTo(TestSuiteStabilityAttemptCancellationCallSupervisor
                        .Disposition.TIMED_OUT);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "cancel");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void hidesProviderDiagnosticsAndLeavesPreparedCommandOnFailure() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            throw new IllegalStateException("credential=business-secret");
        });

        assertThatThrownBy(() -> coordinator().cancel(authority, command))
                .isInstanceOf(TestSuiteStabilityAttemptCancellationCallSupervisor
                        .InvocationException.class)
                .hasMessageNotContaining("business-secret")
                .extracting(error -> ((TestSuiteStabilityAttemptCancellationCallSupervisor
                        .InvocationException) error).disposition())
                .isEqualTo(TestSuiteStabilityAttemptCancellationCallSupervisor
                        .Disposition.UNAVAILABLE);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "cancel");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void leavesPreparedCommandWhenJournalRejectsAttestation() {
        journal.acceptFailure = new IllegalArgumentException("invalid attestation");
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        assertThatThrownBy(() -> coordinator().cancel(authority, command))
                .isSameAs(journal.acceptFailure);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "cancel", "accept");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void returnsUnconfirmedWithoutClaimingProviderTermination() {
        RecordingAuthority authority = authority(descriptor, ignored -> notFound);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance result =
                coordinator().cancel(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.UNCONFIRMED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED);
        assertThat(result.entry().attestation().orElseThrow().receipt()
                .terminationConfirmed()).isFalse();
    }

    private TestSuiteStabilityAttemptCancellationCoordinator coordinator() {
        return new TestSuiteStabilityAttemptCancellationCoordinator(journal, supervisor);
    }

    private RecordingAuthority authority(
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor current,
            Cancellation cancellation) {
        return new RecordingAuthority(events, current, cancellation);
    }

    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor(
            String deploymentId) {
        return new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                "attempt-runtime-a", deploymentId, "key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(1));
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation(
            TestSuiteStabilityAttemptCancellationReceipt.Outcome outcome,
            long providerSequence) {
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(),
                        descriptor.providerId(), descriptor.deploymentId(), command.attemptId(),
                        command.leaseEpoch(), providerSequence,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        outcome,
                        outcome == TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED
                                ? TestSuiteStabilityAttemptCancellationReceipt
                                .TerminationMode.PROCESS_KILL
                                : TestSuiteStabilityAttemptCancellationReceipt
                                .TerminationMode.NONE,
                        "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                        command.requestedAt().plusSeconds(1));
        return new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, descriptor.keyId(), Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]));
    }

    private TestSuiteStabilityAttemptCancellationJournal.Entry entry(
            TestSuiteStabilityAttemptCancellationJournal.Status status,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor frozenDescriptor,
            Optional<TestSuiteStabilityAttemptCancellationReceipt.Attestation> attestation) {
        Instant preparedAt = command.requestedAt().plusMillis(10);
        return new TestSuiteStabilityAttemptCancellationJournal.Entry(
                TestSuiteStabilityAttemptCancellationJournal.Entry.SCHEMA_VERSION,
                command, frozenDescriptor, status, attestation, preparedAt,
                status == TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED
                        ? preparedAt : preparedAt.plusMillis(10),
                "sha256:" + "6".repeat(64));
    }

    private static String challenge(char value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.valueOf(value).repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @FunctionalInterface
    private interface Cancellation {
        TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
                TestSuiteStabilityAttemptCancellationCommand command);
    }

    private record RecordingAuthority(
            List<String> events,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor current,
            Cancellation cancellation,
            AtomicInteger calls)
            implements TestSuiteStabilityAttemptCancellationAuthority {

        private RecordingAuthority(
                List<String> events,
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor current,
                Cancellation cancellation) {
            this(events, current, cancellation, new AtomicInteger());
        }

        @Override
        public Descriptor descriptor() {
            events.add("descriptor");
            return current;
        }

        @Override
        public TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
                TestSuiteStabilityAttemptCancellationCommand command) {
            events.add("cancel");
            calls.incrementAndGet();
            return cancellation.cancel(command);
        }

        private int cancellationCalls() {
            return calls.get();
        }
    }

    private final class RecordingJournal
            implements TestSuiteStabilityAttemptCancellationJournal {

        private final List<String> events;
        private Optional<Entry> retained = Optional.empty();
        private RuntimeException acceptFailure;

        private RecordingJournal(List<String> events) {
            this.events = events;
        }

        @Override
        public Preparation prepare(
                TestSuiteStabilityAttemptCancellationCommand candidate,
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor candidateDescriptor) {
            events.add("prepare");
            if (retained.isPresent()) {
                Entry existing = retained.orElseThrow();
                if (!existing.command().equals(candidate)
                        || !existing.descriptor().equals(candidateDescriptor)) {
                    throw new ConflictException(ConflictReason.IDEMPOTENCY_CONFLICT);
                }
                return new Preparation(PreparationStatus.REPLAYED, existing);
            }
            Entry prepared = entry(Status.PREPARED, candidateDescriptor, Optional.empty());
            retained = Optional.of(prepared);
            return new Preparation(PreparationStatus.PREPARED, prepared);
        }

        @Override
        public Acceptance accept(
                String commandId,
                TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation) {
            events.add("accept");
            if (acceptFailure != null) {
                throw acceptFailure;
            }
            Status status = attestation.receipt().terminationConfirmed()
                    ? Status.CONFIRMED : Status.UNCONFIRMED;
            Entry terminal = entry(status, retained.orElseThrow().descriptor(),
                    Optional.of(attestation));
            retained = Optional.of(terminal);
            return new Acceptance(
                    status == Status.CONFIRMED
                            ? AcceptanceStatus.CONFIRMED : AcceptanceStatus.UNCONFIRMED,
                    terminal);
        }

        @Override
        public Optional<Entry> find(String tenantId, String environmentId, String commandId) {
            events.add("find");
            return retained;
        }
    }
}
