package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityPhysicalAttemptObservationCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    private final List<String> events = new CopyOnWriteArrayList<>();
    private TestSuiteStabilityPhysicalAttemptObservationCommand command;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor;
    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation positive;
    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation nonConfirming;
    private RecordingJournal journal;
    private TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TestSuiteStabilityJobLease lease = new TestSuiteStabilityJobLease(
                "stability-job-" + "1".repeat(64), "tenant-a", "test",
                fingerprint('2'), "worker-a", 7, NOW.plusSeconds(60));
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                TestSuiteStabilityPhysicalAttemptIdentity.create(
                        mapper, lease, fingerprint('3'),
                        "attempt-runtime-a", "attempt-runtime-a.generation-7",
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        TestSuiteStabilityPhysicalAttemptStartCommand start =
                TestSuiteStabilityPhysicalAttemptStartCommand.create(
                        mapper, identity, "stability-envelope-" + "4".repeat(64),
                        fingerprint('5'), NOW, NOW.plusSeconds(30), challenge('a'));
        command = TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                mapper, start, "", 0, NOW.plusSeconds(5), NOW.plusSeconds(15),
                challenge('b'));
        descriptor = descriptor("attempt-runtime-a.generation-7");
        positive = attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING, 11);
        nonConfirming = attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED, 12);
        journal = new RecordingJournal(events);
        supervisor = new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMillis(100), 1));
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void ordersFreshObservationAroundDurablePreparation() {
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance result =
                coordinator().observe(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.POSITIVE);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "observe", "accept");
    }

    @Test
    void replaysPositiveFactWithoutCallingProvider() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE,
                descriptor, Optional.of(positive)));
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        var result = coordinator().observe(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry()).isEqualTo(journal.retained.orElseThrow());
        assertThat(events).containsExactly("find");
        assertThat(authority.observationCalls()).isZero();
    }

    @Test
    void replaysNonConfirmingFactWithoutTryingToUpgradeIt() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.NON_CONFIRMING,
                descriptor, Optional.of(nonConfirming)));
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        var result = coordinator().observe(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.REPLAYED);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.NON_CONFIRMING);
        assertThat(events).containsExactly("find");
        assertThat(authority.observationCalls()).isZero();
    }

    @Test
    void resumesPreparedCommandAfterRevalidatingDescriptorAndStateFence() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        var result = coordinator().observe(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.POSITIVE);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "observe", "accept");
    }

    @Test
    void refusesProviderCallWhenPreparedDescriptorDrifts() {
        journal.retained = Optional.of(entry(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED,
                descriptor, Optional.empty()));
        RecordingAuthority authority = authority(
                descriptor("attempt-runtime-a.generation-8"), ignored -> positive);

        assertThatThrownBy(() -> coordinator().observe(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationJournal
                                .ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationJournal
                                        .ConflictReason.IDEMPOTENCY_CONFLICT));
        assertThat(events).containsExactly("find", "descriptor", "prepare");
        assertThat(authority.observationCalls()).isZero();
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    @Test
    void timeoutLeavesPreparedCommandWithoutInventingLifecycleState() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return positive;
        });

        assertThatThrownBy(() -> coordinator().observe(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                        .Disposition.TIMED_OUT));
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "observe");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    @Test
    void hidesProviderDiagnosticsAndLeavesPreparedOnAdapterFailure() {
        RecordingAuthority authority = authority(descriptor, ignored -> {
            throw new IllegalStateException("credential=business-secret");
        });

        assertThatThrownBy(() -> coordinator().observe(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                        .Disposition.UNAVAILABLE))
                .hasMessageNotContaining("business-secret");
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "observe");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    @Test
    void leavesPreparedWhenJournalRejectsAttestation() {
        journal.acceptFailure = new IllegalArgumentException("invalid attestation");
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        assertThatThrownBy(() -> coordinator().observe(authority, command))
                .isSameAs(journal.acceptFailure);
        assertThat(events).containsExactly(
                "find", "descriptor", "prepare", "authorize", "observe", "accept");
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    @Test
    void returnsNonConfirmingWithoutClaimingNonStart() {
        RecordingAuthority authority = authority(descriptor, ignored -> nonConfirming);

        var result = coordinator().observe(authority, command);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                        .NON_CONFIRMING);
        assertThat(result.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.NON_CONFIRMING);
        assertThat(result.entry().attestation().orElseThrow().receipt()
                .reconciliationRequired()).isTrue();
    }

    @Test
    void databaseTimeOrStateFenceRejectionPreventsProviderCall() {
        journal.authorizationFailure = new TestSuiteStabilityPhysicalAttemptObservationJournal
                .ConflictException(TestSuiteStabilityPhysicalAttemptObservationJournal
                .ConflictReason.STATE_FENCE_CHANGED);
        RecordingAuthority authority = authority(descriptor, ignored -> positive);

        assertThatThrownBy(() -> coordinator().observe(authority, command))
                .isSameAs(journal.authorizationFailure);
        assertThat(events).containsExactly("find", "descriptor", "prepare", "authorize");
        assertThat(authority.observationCalls()).isZero();
        assertThat(journal.retained.orElseThrow().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    private TestSuiteStabilityPhysicalAttemptObservationCoordinator coordinator() {
        return new TestSuiteStabilityPhysicalAttemptObservationCoordinator(journal, supervisor);
    }

    private RecordingAuthority authority(
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor current,
            Observe observe) {
        return new RecordingAuthority(events, current, observe);
    }

    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor(
            String deploymentId) {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                "attempt-runtime-a", deploymentId, "key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(1), Duration.ofHours(1));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            long providerSequence) {
        boolean positiveState = state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                && state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE;
        boolean process = state
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING
                || state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL;
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(),
                        descriptor.providerId(), descriptor.deploymentId(),
                        command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.startCommand().commandId(),
                        command.startCommand().commandFingerprint(),
                        command.identity().leaseEpoch(), providerSequence,
                        positiveState ? 1 : 0, command.identity().isolationMode(), state,
                        process ? fingerprint('6') : "",
                        positiveState ? fingerprint('7') : "",
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .NONE,
                        "", NOW.plusSeconds(6), NOW.plusSeconds(7));
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation.SCHEMA_VERSION,
                receipt, descriptor.keyId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]));
    }

    private TestSuiteStabilityPhysicalAttemptObservationJournal.Entry entry(
            TestSuiteStabilityPhysicalAttemptObservationJournal.Status status,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor frozenDescriptor,
            Optional<TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation>
                    attestation) {
        Instant preparedAt = command.requestedAt().plusMillis(10);
        return new TestSuiteStabilityPhysicalAttemptObservationJournal.Entry(
                TestSuiteStabilityPhysicalAttemptObservationJournal.Entry.SCHEMA_VERSION,
                command, frozenDescriptor, status, attestation, preparedAt,
                status == TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED
                        ? preparedAt : preparedAt.plusMillis(10),
                fingerprint('8'));
    }

    private static String challenge(char value) {
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    @FunctionalInterface
    private interface Observe {
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                TestSuiteStabilityPhysicalAttemptObservationCommand command);
    }

    private record RecordingAuthority(
            List<String> events,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor current,
            Observe observe,
            AtomicInteger calls)
            implements TestSuiteStabilityPhysicalAttemptObservationAuthority {

        private RecordingAuthority(
                List<String> events,
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor current,
                Observe observe) {
            this(events, current, observe, new AtomicInteger());
        }

        @Override
        public Descriptor descriptor() {
            events.add("descriptor");
            return current;
        }

        @Override
        public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                TestSuiteStabilityPhysicalAttemptObservationCommand command) {
            events.add("observe");
            calls.incrementAndGet();
            return observe.observe(command);
        }

        private int observationCalls() {
            return calls.get();
        }
    }

    private final class RecordingJournal
            implements TestSuiteStabilityPhysicalAttemptObservationJournal {

        private final List<String> events;
        private Optional<Entry> retained = Optional.empty();
        private RuntimeException authorizationFailure;
        private RuntimeException acceptFailure;

        private RecordingJournal(List<String> events) {
            this.events = events;
        }

        @Override
        public Preparation prepare(
                TestSuiteStabilityPhysicalAttemptObservationCommand candidate,
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor
                        candidateDescriptor) {
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
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation) {
            events.add("accept");
            if (acceptFailure != null) {
                throw acceptFailure;
            }
            boolean positiveState = !attestation.receipt().reconciliationRequired()
                    || attestation.receipt().state()
                    == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING;
            Status status = positiveState ? Status.POSITIVE : Status.NON_CONFIRMING;
            Entry terminal = entry(status, retained.orElseThrow().descriptor(),
                    Optional.of(attestation));
            retained = Optional.of(terminal);
            return new Acceptance(
                    positiveState
                            ? AcceptanceStatus.POSITIVE : AcceptanceStatus.NON_CONFIRMING,
                    terminal);
        }

        @Override
        public Optional<Entry> find(
                String tenantId, String environmentId, String commandId) {
            events.add("find");
            return retained;
        }

        @Override
        public Optional<PositiveState> latestPositive(
                String tenantId, String environmentId, String attemptId) {
            return Optional.empty();
        }
    }
}
