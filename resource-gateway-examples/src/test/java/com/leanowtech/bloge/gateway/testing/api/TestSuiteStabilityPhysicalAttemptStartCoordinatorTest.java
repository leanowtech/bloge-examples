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

class TestSuiteStabilityPhysicalAttemptStartCoordinatorTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private TestSuiteStabilityPhysicalAttemptStartCommand command;
    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor;
    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation confirmed;
    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation rejected;
    private RecordingJournal journal;
    private TestSuiteStabilityPhysicalAttemptStartCallSupervisor supervisor;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TestSuiteStabilityJobLease lease = new TestSuiteStabilityJobLease(
                "stability-job-" + "1".repeat(64), "tenant-a", "test",
                "sha256:" + "2".repeat(64), "worker-a", 7,
                requestedAt.plusSeconds(60));
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                TestSuiteStabilityPhysicalAttemptIdentity.create(
                        mapper, lease, "sha256:" + "3".repeat(64),
                        "attempt-runtime-a", "attempt-runtime-a.generation-7",
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        command = TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, identity, "stability-envelope-" + "4".repeat(64),
                "sha256:" + "5".repeat(64), requestedAt,
                requestedAt.plusSeconds(30), challenge('a'));
        descriptor = descriptor("attempt-runtime-a.generation-7");
        confirmed = attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED, 11);
        rejected = attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED, 12);
        journal = new RecordingJournal(events);
        supervisor = new TestSuiteStabilityPhysicalAttemptStartCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMillis(100), 1));
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void ordersFreshStartAroundDurablePreparation() {
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        TestSuiteStabilityPhysicalAttemptStartJournal.Acceptance result =
                coordinator().start(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "start", "accept");
    }

    @Test
    void replaysConfirmedTerminalWithoutCallingProvider() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED,
                descriptor, Optional.of(confirmed)));
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        var result = coordinator().start(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry()).isEqualTo(journal.retained.orElseThrow());
        assertThat(events).containsExactly("find");
        assertThat(authority.startCalls()).isZero();
    }

    @Test
    void replaysUnconfirmedTerminalWithoutTryingToUpgradeIt() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.UNCONFIRMED,
                descriptor, Optional.of(rejected)));
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        var result = coordinator().start(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.UNCONFIRMED);
        assertThat(events).containsExactly("find");
        assertThat(authority.startCalls()).isZero();
    }

    @Test
    void resumesPreparedCommandAfterRevalidatingDescriptorAndLease() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        var result = coordinator().start(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "start", "accept");
    }

    @Test
    void refusesProviderCallWhenPreparedDescriptorDrifts() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(
                descriptor("attempt-runtime-a.generation-8"), ignored -> confirmed);

        assertThatThrownBy(() -> coordinator().start(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                                        .IDEMPOTENCY_CONFLICT));
        assertThat(events).containsExactly("find", "descriptor", "prepare");
        assertThat(authority.startCalls()).isZero();
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void timeoutLeavesPreparedCommandWithoutClaimingNonStart() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return confirmed;
        });

        assertThatThrownBy(() -> coordinator().start(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                        .Disposition.TIMED_OUT));
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "start");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void hidesProviderDiagnosticsAndLeavesPreparedOnAdapterFailure() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            throw new IllegalStateException("credential=business-secret");
        });

        assertThatThrownBy(() -> coordinator().start(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                        .Disposition.UNAVAILABLE))
                .hasMessageNotContaining("business-secret");
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "start");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void leavesPreparedWhenJournalRejectsAttestation() {
        journal.acceptFailure = new IllegalArgumentException("invalid attestation");
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        assertThatThrownBy(() -> coordinator().start(authority, command))
                .isSameAs(journal.acceptFailure);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "start", "accept");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void returnsUnconfirmedWithoutClaimingNonStart() {
        RecordingAuthority authority = authority(descriptor, ignored -> rejected);

        var result = coordinator().start(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.UNCONFIRMED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.UNCONFIRMED);
        assertThat(result.entry().attestation().orElseThrow().receipt()
                .startConfirmed()).isFalse();
    }

    @Test
    void databaseTimeOrLeaseRejectionPreventsProviderCall() {
        journal.authorizationFailure = new TestSuiteStabilityPhysicalAttemptStartJournal
                .ConflictException(TestSuiteStabilityPhysicalAttemptStartJournal
                .ConflictReason.RESERVATION_NOT_ACTIVE);
        RecordingAuthority authority = authority(descriptor, ignored -> confirmed);

        assertThatThrownBy(() -> coordinator().start(authority, command))
                .isSameAs(journal.authorizationFailure);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "authorize");
        assertThat(authority.startCalls()).isZero();
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    private TestSuiteStabilityPhysicalAttemptStartCoordinator coordinator() {
        return new TestSuiteStabilityPhysicalAttemptStartCoordinator(journal, supervisor);
    }

    private RecordingAuthority authority(
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor current,
            Start start) {
        return new RecordingAuthority(events, current, start);
    }

    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor(
            String deploymentId) {
        return new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                "attempt-runtime-a", deploymentId, "key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(1));
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome,
            long providerSequence) {
        boolean startConfirmed = outcome != TestSuiteStabilityPhysicalAttemptStartReceipt
                .Outcome.REJECTED;
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptStartReceipt(
                        TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(),
                        descriptor.providerId(), descriptor.deploymentId(),
                        command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.identity().leaseEpoch(), providerSequence,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        outcome, startConfirmed ? "sha256:" + "6".repeat(64) : "",
                        startConfirmed ? "sha256:" + "7".repeat(64) : "",
                        command.requestedAt().plusSeconds(1));
        return new TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, descriptor.keyId(), Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]));
    }

    private TestSuiteStabilityPhysicalAttemptStartJournal.Entry entry(
            TestSuiteStabilityPhysicalAttemptStartJournal.Status status,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor frozenDescriptor,
            Optional<TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation> attestation) {
        Instant preparedAt = command.requestedAt().plusMillis(10);
        return new TestSuiteStabilityPhysicalAttemptStartJournal.Entry(
                TestSuiteStabilityPhysicalAttemptStartJournal.Entry.SCHEMA_VERSION,
                command, frozenDescriptor, status, attestation, preparedAt,
                status == TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED
                        ? preparedAt : preparedAt.plusMillis(10),
                "sha256:" + "8".repeat(64));
    }

    private static String challenge(char value) {
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    @FunctionalInterface
    private interface Start {
        TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation start(
                TestSuiteStabilityPhysicalAttemptStartCommand command);
    }

    private record RecordingAuthority(
            List<String> events,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor current,
            Start start,
            AtomicInteger calls)
            implements TestSuiteStabilityPhysicalAttemptStartAuthority {

        private RecordingAuthority(
                List<String> events,
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor current,
                Start start) {
            this(events, current, start, new AtomicInteger());
        }

        @Override
        public Descriptor descriptor() {
            events.add("descriptor");
            return current;
        }

        @Override
        public TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation start(
                TestSuiteStabilityPhysicalAttemptStartCommand command) {
            events.add("start");
            calls.incrementAndGet();
            return start.start(command);
        }

        private int startCalls() {
            return calls.get();
        }
    }

    private final class RecordingJournal
            implements TestSuiteStabilityPhysicalAttemptStartJournal {

        private final List<String> events;
        private Optional<Entry> retained = Optional.empty();
        private RuntimeException authorizationFailure;
        private RuntimeException acceptFailure;

        private RecordingJournal(List<String> events) {
            this.events = events;
        }

        @Override
        public Preparation prepare(
                TestSuiteStabilityPhysicalAttemptStartCommand candidate,
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor candidateDescriptor) {
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
        public void authorizeInvocation(String commandId) {
            events.add("authorize");
            if (authorizationFailure != null) {
                throw authorizationFailure;
            }
        }

        @Override
        public Acceptance accept(
                String commandId,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation) {
            events.add("accept");
            if (acceptFailure != null) {
                throw acceptFailure;
            }
            Status status = attestation.receipt().startConfirmed()
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
        public Optional<Entry> find(
                String tenantId, String environmentId, String commandId) {
            events.add("find");
            return retained;
        }
    }
}
