package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationVerifier;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Database-authoritative journal for signed physical-attempt lifecycle observations.
 *
 * <p>An attempt lock serializes preparation and positive-state transitions. A separate provider
 * lock serializes the observation-sequence floor across attempts and replicas. Every accepted
 * command is immutable; non-confirming receipts advance the provider sequence but never replace
 * the independent positive-state floor.</p>
 *
 * <p>Preparation and invocation require an integrity-verified retained start command, not a live
 * queue lease. This deliberate distinction allows bounded orphan reconciliation after lease loss
 * without allowing an observation to migrate to another attempt, process, or runtime generation.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal
        implements TestSuiteStabilityPhysicalAttemptObservationJournal {

    private static final String ENTRY_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationStoredEntry.v1";
    private static final String STATE_FLOOR_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationStateFloor.v1";
    private static final String PROVIDER_FLOOR_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationProviderFloor.v1";
    private static final String PROVIDER_SEQUENCE_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationProviderSequence.v1";
    private static final String STATE_FACT_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationStateFact.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-observe-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");
    private static final Duration MAXIMUM_CALLER_CLOCK_SKEW = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private final TestSuiteStabilityPhysicalAttemptObservationVerifier verifier;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a journal using a local transaction manager for the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param starts integrity-verifying durable start journal over the same datasource
     * @param verifier pinned provider observation-attestation verifier
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationVerifier verifier) {
        this(jdbc, objectMapper, starts, verifier, localTransactionManager(jdbc));
    }

    /**
     * Creates a journal with a caller-supplied transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param starts integrity-verifying durable start journal over the same datasource
     * @param verifier pinned provider observation-attestation verifier
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationVerifier verifier,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates payload-free command, positive-state, sequence-floor, and lock tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_locks (
                    lock_key VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_provider_locks (
                    provider_scope_fingerprint VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_entries (
                    command_id VARCHAR(128) PRIMARY KEY,
                    command_fingerprint VARCHAR(71) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    attempt_id VARCHAR(96) NOT NULL,
                    start_command_id VARCHAR(128) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    observed_state VARCHAR(32) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    attempt_revision BIGINT NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    command_json CLOB NOT NULL,
                    descriptor_json CLOB NOT NULL,
                    attestation_json CLOB NOT NULL,
                    prepared_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_stability_attempt_observation_scope
                ON rg_test_stability_attempt_observation_entries (
                    tenant_id, environment_id, attempt_id, prepared_at, command_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_state_floors (
                    attempt_id VARCHAR(96) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    identity_fingerprint VARCHAR(71) NOT NULL,
                    start_command_id VARCHAR(128) NOT NULL,
                    start_command_fingerprint VARCHAR(71) NOT NULL,
                    observation_command_id VARCHAR(128) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    observed_state VARCHAR(32) NOT NULL,
                    attempt_revision BIGINT NOT NULL,
                    process_identity_fingerprint VARCHAR(71) NOT NULL,
                    state_fact_fingerprint VARCHAR(71) NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_provider_floors (
                    provider_scope_fingerprint VARCHAR(71) PRIMARY KEY,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    command_id VARCHAR(128) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_observation_provider_sequences (
                    provider_scope_fingerprint VARCHAR(71) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    command_id VARCHAR(128) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (provider_scope_fingerprint, provider_sequence),
                    CONSTRAINT uq_rg_test_stability_attempt_observation_provider_command
                        UNIQUE (provider_scope_fingerprint, command_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Preparation prepare(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor) {
        TestSuiteStabilityPhysicalAttemptObservationCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        Preparation result = mutations.execute(status -> {
            validateCommandIdentity(requiredCommand);
            lockAttempt(requiredCommand.identity());
            StoredEntry byId = entry(requiredCommand.commandId());
            if (byId != null) {
                Entry retained = validateEntry(byId);
                if (retained.command().equals(requiredCommand)
                        && retained.descriptor().equals(requiredDescriptor)) {
                    return new Preparation(PreparationStatus.REPLAYED, retained);
                }
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }

            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start =
                    retainedStart(requiredCommand);
            Instant now = currentTime();
            PositiveState floor = positiveState(requiredCommand.identity().attemptId());
            validateCommandStateFence(requiredCommand, start, floor);
            rejectConcurrentObservation(requiredCommand.identity().attemptId(), now);
            validatePreparationTime(requiredCommand, now);
            validateProviderCompatibility(requiredCommand, requiredDescriptor);

            StoredEntry prepared = stored(
                    requiredCommand, requiredDescriptor, Status.PREPARED, null, now, now);
            insertEntry(prepared);
            return new Preparation(PreparationStatus.PREPARED,
                    validateEntry(requireEntry(requiredCommand.commandId())));
        });
        return Objects.requireNonNull(result, "physical-attempt observation preparation");
    }

    /** {@inheritDoc} */
    @Override
    public void authorizeInvocation(String commandId) {
        String exactCommandId = requireCommandId(commandId);
        Boolean authorized = mutations.execute(status -> {
            StoredEntry initial = entry(exactCommandId);
            if (initial == null) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            Entry initialEntry = validateEntry(initial);
            lockAttempt(initialEntry.command().identity());
            Entry prepared = validateEntry(requireEntry(exactCommandId));
            if (prepared.status() != Status.PREPARED) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start =
                    retainedStart(prepared.command());
            PositiveState floor = positiveState(
                    prepared.command().identity().attemptId());
            validateCommandStateFence(prepared.command(), start, floor);
            Instant now = currentTime();
            validatePreparationTime(prepared.command(), now);
            validateInvocationWindow(prepared.command(), prepared.descriptor(), now);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(authorized)) {
            throw new IllegalStateException(
                    "Physical-attempt observation invocation returned no result");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(
            String commandId,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation) {
        String exactCommandId = requireCommandId(commandId);
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Acceptance result = mutations.execute(status -> {
            StoredEntry initial = entry(exactCommandId);
            if (initial == null) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            Entry initialEntry = validateEntry(initial);
            lockAttempt(initialEntry.command().identity());
            StoredEntry current = requireEntry(exactCommandId);
            Entry prepared = validateEntry(current);
            String attestationFingerprint = ProtocolFingerprint.of(
                    objectMapper, requiredAttestation);
            if (prepared.status() != Status.PREPARED) {
                if (current.attestationFingerprint().equals(attestationFingerprint)) {
                    return new Acceptance(AcceptanceStatus.REPLAYED, prepared);
                }
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }

            retainedStart(prepared.command());
            Instant now = currentTime();
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = verifier.verify(
                    prepared.command(), prepared.descriptor(), requiredAttestation, now);
            if (receipt.confirmedAt().isBefore(prepared.preparedAt())) {
                throw conflict(ConflictReason.OBSERVATION_PRECEDES_PREPARATION);
            }

            String providerScope = providerScope(
                    receipt.providerId(), receipt.deploymentId());
            lockProvider(providerScope);
            ProviderFloor providerFloor = providerFloor(providerScope);
            validateProviderFloor(providerScope, providerFloor);
            if (providerFloor != null
                    && receipt.providerSequence() <= providerFloor.providerSequence()) {
                throw conflict(ConflictReason.PROVIDER_SEQUENCE_ROLLBACK);
            }

            PositiveState stateFloor = positiveState(receipt.attemptId());
            boolean positive = positive(receipt);
            StateTransition transition = positive
                    ? validateStateTransition(stateFloor, receipt)
                    : StateTransition.RETAIN;
            appendProviderSequence(providerScope, receipt, exactCommandId,
                    attestationFingerprint, now);
            persistProviderFloor(providerScope, receipt, exactCommandId,
                    attestationFingerprint, now);
            if (positive && transition == StateTransition.ADVANCE) {
                persistPositiveState(prepared.command(), receipt,
                        attestationFingerprint, now);
            }

            Status acceptedStatus = positive ? Status.POSITIVE : Status.NON_CONFIRMING;
            StoredEntry accepted = stored(
                    prepared.command(), prepared.descriptor(), acceptedStatus,
                    requiredAttestation, prepared.preparedAt(), now);
            updateEntry(current, accepted);
            Entry retained = validateEntry(requireEntry(exactCommandId));
            return new Acceptance(
                    positive ? AcceptanceStatus.POSITIVE
                            : AcceptanceStatus.NON_CONFIRMING,
                    retained);
        });
        return Objects.requireNonNull(result, "physical-attempt observation acceptance");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String commandId) {
        String tenant = requireIdentifier(tenantId, "tenantId");
        String environment = requireEnvironment(environmentId);
        String exactCommandId = requireCommandId(commandId);
        Optional<Entry> result = reads.execute(status -> {
            StoredEntry stored = entry(exactCommandId);
            if (stored == null) {
                return Optional.empty();
            }
            Entry validated = validateEntry(stored);
            TestSuiteStabilityPhysicalAttemptIdentity identity =
                    validated.command().identity();
            if (!identity.tenantId().equals(tenant)
                    || !identity.environmentId().equals(environment)) {
                return Optional.empty();
            }
            return Optional.of(validated);
        });
        return result == null ? Optional.empty() : result;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PositiveState> latestPositive(
            String tenantId, String environmentId, String attemptId) {
        String tenant = requireIdentifier(tenantId, "tenantId");
        String environment = requireEnvironment(environmentId);
        String exactAttemptId = requireAttemptId(attemptId);
        Optional<PositiveState> result = reads.execute(status -> {
            PositiveState state = positiveState(exactAttemptId);
            if (state == null || !state.tenantId().equals(tenant)
                    || !state.environmentId().equals(environment)) {
                return Optional.empty();
            }
            return Optional.of(state);
        });
        return result == null ? Optional.empty() : result;
    }

    private void validateCommandIdentity(
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        TestSuiteStabilityPhysicalAttemptStartCommand start = command.startCommand();
        String derivedIdentity = ProtocolFingerprint.of(
                objectMapper, identity.canonicalMaterial());
        String derivedStart = ProtocolFingerprint.of(
                objectMapper, start.canonicalMaterial());
        String derivedCommand = ProtocolFingerprint.of(
                objectMapper, command.canonicalMaterial());
        if (!derivedIdentity.equals(identity.identityFingerprint())
                || !identity.attemptId().equals("stability-attempt-"
                + derivedIdentity.substring("sha256:".length()))
                || !derivedStart.equals(start.commandFingerprint())
                || !start.commandId().equals("stability-attempt-start-"
                + derivedStart.substring("sha256:".length()))
                || !derivedCommand.equals(command.commandFingerprint())
                || !command.commandId().equals("stability-attempt-observe-"
                + derivedCommand.substring("sha256:".length()))) {
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private TestSuiteStabilityPhysicalAttemptStartJournal.Entry retainedStart(
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        TestSuiteStabilityPhysicalAttemptStartJournal.Entry retained;
        try {
            retained = starts.find(identity.tenantId(), identity.environmentId(),
                    command.startCommand().commandId()).orElse(null);
        } catch (RuntimeException unavailable) {
            throw conflict(ConflictReason.START_COMMAND_NOT_RETAINED);
        }
        if (retained == null || !retained.command().equals(command.startCommand())) {
            throw conflict(ConflictReason.START_COMMAND_NOT_RETAINED);
        }
        return retained;
    }

    private void validateCommandStateFence(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start,
            PositiveState floor) {
        long expectedRevision = floor == null ? 0 : floor.receipt().attemptRevision();
        String expectedProcess;
        if (floor != null && floor.receipt().processIdentityConfirmed()) {
            expectedProcess = floor.receipt().processIdentityFingerprint();
        } else if (start.status()
                == TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED) {
            expectedProcess = start.attestation().orElseThrow()
                    .receipt().processIdentityFingerprint();
        } else {
            expectedProcess = "";
        }
        if (command.minimumAttemptRevision() != expectedRevision
                || !command.expectedProcessIdentityFingerprint().equals(expectedProcess)) {
            throw conflict(ConflictReason.STATE_FENCE_CHANGED);
        }
    }

    private void rejectConcurrentObservation(String attemptId, Instant now) {
        List<StoredEntry> prepared = jdbc.query(entrySelect() + """
                 WHERE attempt_id = ? AND status = ?
                """, this::mapEntry, attemptId, Status.PREPARED.name());
        for (StoredEntry candidate : prepared) {
            Entry validated = validateEntry(candidate);
            if (validated.command().confirmationDeadlineAt().isAfter(now)) {
                throw conflict(ConflictReason.OBSERVATION_IN_FLIGHT);
            }
        }
    }

    private void validateProviderCompatibility(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        Duration commandWindow = Duration.between(
                command.requestedAt(), command.confirmationDeadlineAt());
        if (!descriptor.available()
                || !descriptor.providerId().equals(identity.providerId())
                || !descriptor.deploymentId().equals(identity.deploymentId())
                || !descriptor.isolationModes().contains(identity.isolationMode())
                || descriptor.maximumObservationLatency().compareTo(commandWindow) > 0) {
            throw conflict(ConflictReason.PROVIDER_INCOMPATIBLE);
        }
    }

    private void validateInvocationWindow(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            Instant now) {
        Duration remaining = Duration.between(now, command.confirmationDeadlineAt());
        if (!descriptor.available()
                || descriptor.maximumObservationLatency().compareTo(remaining) > 0) {
            throw conflict(ConflictReason.PROVIDER_INCOMPATIBLE);
        }
    }

    private void validatePreparationTime(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            Instant now) {
        if (command.requestedAt().isAfter(now.plus(MAXIMUM_CALLER_CLOCK_SKEW))
                || !now.isBefore(command.confirmationDeadlineAt())) {
            throw conflict(ConflictReason.COMMAND_EXPIRED);
        }
    }

    private StateTransition validateStateTransition(
            PositiveState floor,
            TestSuiteStabilityPhysicalAttemptObservationReceipt next) {
        if (floor == null) {
            return StateTransition.ADVANCE;
        }
        TestSuiteStabilityPhysicalAttemptObservationReceipt current = floor.receipt();
        if (next.attemptRevision() < current.attemptRevision()) {
            throw conflict(ConflictReason.ATTEMPT_REVISION_ROLLBACK);
        }
        if (current.processIdentityConfirmed() && next.processIdentityConfirmed()
                && !current.processIdentityFingerprint().equals(
                next.processIdentityFingerprint())) {
            throw conflict(ConflictReason.PROCESS_IDENTITY_CONFLICT);
        }
        if (next.attemptRevision() == current.attemptRevision()) {
            if (stateFactFingerprint(next).equals(stateFactFingerprint(current))) {
                return StateTransition.RETAIN;
            }
            throw conflict(current.terminalConfirmed()
                    ? ConflictReason.TERMINAL_STATE_CONFLICT
                    : ConflictReason.LIFECYCLE_STATE_ROLLBACK);
        }
        if (current.terminalConfirmed()) {
            throw conflict(ConflictReason.TERMINAL_STATE_CONFLICT);
        }
        if (stateRank(next.state()) < stateRank(current.state())
                || next.stateEffectiveAt().isBefore(current.stateEffectiveAt())) {
            throw conflict(ConflictReason.LIFECYCLE_STATE_ROLLBACK);
        }
        return StateTransition.ADVANCE;
    }

    private static int stateRank(
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state) {
        return switch (state) {
            case START_PENDING -> 1;
            case RUNNING -> 2;
            case TERMINAL -> 3;
            case NOT_OBSERVED, INDETERMINATE -> 0;
        };
    }

    private static boolean positive(
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt) {
        return receipt.state()
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                && receipt.state()
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE;
    }

    private StoredEntry stored(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            Status status,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation,
            Instant preparedAt,
            Instant updatedAt) {
        String attestationFingerprint = attestation == null
                ? "" : ProtocolFingerprint.of(objectMapper, attestation);
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt =
                attestation == null ? null : attestation.receipt();
        String observedState = receipt == null ? "" : receipt.state().name();
        long providerSequence = receipt == null ? 0 : receipt.providerSequence();
        long attemptRevision = receipt == null ? 0 : receipt.attemptRevision();
        String fingerprint = entryFingerprint(
                command, descriptor, status, observedState, providerSequence,
                attemptRevision, attestationFingerprint, preparedAt, updatedAt);
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        return new StoredEntry(
                command.commandId(), command.commandFingerprint(), identity.tenantId(),
                identity.environmentId(), identity.attemptId(),
                command.startCommand().commandId(), identity.leaseEpoch(),
                descriptor.providerId(), descriptor.deploymentId(), status.name(),
                observedState, providerSequence, attemptRevision,
                attestationFingerprint, encode(command), encode(descriptor),
                attestation == null ? "" : encode(attestation), preparedAt, updatedAt,
                fingerprint);
    }

    private Entry validateEntry(StoredEntry stored) {
        try {
            TestSuiteStabilityPhysicalAttemptObservationCommand command = decode(
                    stored.commandJson(),
                    TestSuiteStabilityPhysicalAttemptObservationCommand.class);
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor = decode(
                    stored.descriptorJson(),
                    TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.class);
            Status status = Status.valueOf(stored.status());
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation =
                    stored.attestationJson().isBlank() ? null : decode(
                            stored.attestationJson(),
                            TestSuiteStabilityPhysicalAttemptObservationReceipt
                                    .Attestation.class);
            TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
            String expected = entryFingerprint(
                    command, descriptor, status, stored.observedState(),
                    stored.providerSequence(), stored.attemptRevision(),
                    stored.attestationFingerprint(), stored.preparedAt(), stored.updatedAt());
            if (!stored.commandId().equals(command.commandId())
                    || !stored.commandFingerprint().equals(command.commandFingerprint())
                    || !stored.tenantId().equals(identity.tenantId())
                    || !stored.environmentId().equals(identity.environmentId())
                    || !stored.attemptId().equals(identity.attemptId())
                    || !stored.startCommandId().equals(
                    command.startCommand().commandId())
                    || stored.leaseEpoch() != identity.leaseEpoch()
                    || !stored.providerId().equals(descriptor.providerId())
                    || !stored.deploymentId().equals(descriptor.deploymentId())
                    || !stored.recordFingerprint().equals(expected)
                    || (attestation == null) != stored.attestationFingerprint().isBlank()
                    || attestation != null && (!stored.attestationFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, attestation))
                    || !stored.observedState().equals(
                    attestation.receipt().state().name())
                    || stored.providerSequence()
                    != attestation.receipt().providerSequence()
                    || stored.attemptRevision()
                    != attestation.receipt().attemptRevision())) {
                throw integrity("entry");
            }
            validateCommandIdentity(command);
            retainedStart(command);
            if (attestation != null) {
                validateRetainedProviderState(attestation,
                        stored.attestationFingerprint());
            }
            return new Entry(
                    Entry.SCHEMA_VERSION, command, descriptor, status,
                    Optional.ofNullable(attestation), stored.preparedAt(), stored.updatedAt(),
                    stored.recordFingerprint());
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException state
                    && knownIntegrityFailure(state.getMessage())) {
                throw state;
            }
            throw integrity("entry");
        }
    }

    private void validateRetainedProviderState(
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation,
            String attestationFingerprint) {
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = attestation.receipt();
        String providerScope = providerScope(receipt.providerId(), receipt.deploymentId());
        List<ProviderSequence> retained = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_provider_sequences
                WHERE provider_scope_fingerprint = ? AND command_id = ?
                """, (rs, row) -> mapProviderSequence(rs),
                providerScope, receipt.commandId());
        if (retained.size() != 1) {
            throw integrity("provider sequence continuity");
        }
        ProviderSequence sequence = retained.getFirst();
        String expected = providerSequenceFingerprint(
                sequence.providerScope(), sequence.providerSequence(),
                sequence.commandId(), sequence.attestationFingerprint(),
                sequence.acceptedAt());
        if (!sequence.recordFingerprint().equals(expected)
                || sequence.providerSequence() != receipt.providerSequence()
                || !sequence.attestationFingerprint().equals(attestationFingerprint)) {
            throw integrity("provider sequence");
        }
        validateProviderFloor(providerScope, providerFloor(providerScope));
    }

    private String entryFingerprint(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            Status status,
            String observedState,
            long providerSequence,
            long attemptRevision,
            String attestationFingerprint,
            Instant preparedAt,
            Instant updatedAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", ENTRY_SCHEMA);
        material.put("commandFingerprint", command.commandFingerprint());
        material.put("descriptorFingerprint", ProtocolFingerprint.of(objectMapper, descriptor));
        material.put("status", status);
        material.put("observedState", observedState);
        material.put("providerSequence", providerSequence);
        material.put("attemptRevision", attemptRevision);
        material.put("attestationFingerprint", attestationFingerprint);
        material.put("preparedAt", preparedAt);
        material.put("updatedAt", updatedAt);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private void insertEntry(StoredEntry value) {
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_observation_entries (
                    command_id, command_fingerprint, tenant_id, environment_id,
                    attempt_id, start_command_id, lease_epoch, provider_id,
                    deployment_id, status, observed_state, provider_sequence,
                    attempt_revision, attestation_fingerprint, command_json,
                    descriptor_json, attestation_json, prepared_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.commandId(), value.commandFingerprint(), value.tenantId(),
                value.environmentId(), value.attemptId(), value.startCommandId(),
                value.leaseEpoch(), value.providerId(), value.deploymentId(),
                value.status(), value.observedState(), value.providerSequence(),
                value.attemptRevision(), value.attestationFingerprint(),
                value.commandJson(), value.descriptorJson(), value.attestationJson(),
                Timestamp.from(value.preparedAt()), Timestamp.from(value.updatedAt()),
                value.recordFingerprint());
    }

    private void updateEntry(StoredEntry previous, StoredEntry value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_entries
                SET status = ?, observed_state = ?, provider_sequence = ?,
                    attempt_revision = ?, attestation_fingerprint = ?,
                    attestation_json = ?, updated_at = ?, record_fingerprint = ?
                WHERE command_id = ? AND record_fingerprint = ?
                """, value.status(), value.observedState(), value.providerSequence(),
                value.attemptRevision(), value.attestationFingerprint(),
                value.attestationJson(), Timestamp.from(value.updatedAt()),
                value.recordFingerprint(), previous.commandId(),
                previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Physical-attempt observation journal concurrent update failed");
        }
    }

    private StoredEntry entry(String commandId) {
        List<StoredEntry> rows = jdbc.query(entrySelect() + " WHERE command_id = ?",
                this::mapEntry, commandId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Physical-attempt observation command identity is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String entrySelect() {
        return """
                SELECT command_id, command_fingerprint, tenant_id, environment_id,
                       attempt_id, start_command_id, lease_epoch, provider_id,
                       deployment_id, status, observed_state, provider_sequence,
                       attempt_revision, attestation_fingerprint, command_json,
                       descriptor_json, attestation_json, prepared_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_observation_entries
                """;
    }

    private StoredEntry requireEntry(String commandId) {
        StoredEntry stored = entry(commandId);
        if (stored == null) {
            throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
        }
        return stored;
    }

    private StoredEntry mapEntry(ResultSet rs, int row) throws SQLException {
        return new StoredEntry(
                rs.getString("command_id"), rs.getString("command_fingerprint"),
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("attempt_id"), rs.getString("start_command_id"),
                rs.getLong("lease_epoch"), rs.getString("provider_id"),
                rs.getString("deployment_id"), rs.getString("status"),
                rs.getString("observed_state"), rs.getLong("provider_sequence"),
                rs.getLong("attempt_revision"),
                rs.getString("attestation_fingerprint"), rs.getString("command_json"),
                rs.getString("descriptor_json"), rs.getString("attestation_json"),
                rs.getTimestamp("prepared_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private PositiveState positiveState(String attemptId) {
        List<StoredStateFloor> rows = jdbc.query("""
                SELECT attempt_id, tenant_id, environment_id, identity_fingerprint,
                       start_command_id, start_command_fingerprint,
                       observation_command_id, attestation_fingerprint, observed_state,
                       attempt_revision, process_identity_fingerprint,
                       state_fact_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_state_floors
                WHERE attempt_id = ?
                """, (rs, row) -> mapStateFloor(rs), attemptId);
        if (rows.size() > 1) {
            throw integrity("state floor uniqueness");
        }
        return rows.isEmpty() ? null : validateStateFloor(rows.getFirst());
    }

    private PositiveState validateStateFloor(StoredStateFloor floor) {
        String expectedFloor = stateFloorFingerprint(floor);
        StoredEntry linked = entry(floor.observationCommandId());
        if (linked == null || !floor.recordFingerprint().equals(expectedFloor)) {
            throw integrity("state floor continuity");
        }
        Entry entry = validateEntry(linked);
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation =
                entry.attestation().orElseThrow(() -> integrity("state floor continuity"));
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = attestation.receipt();
        String attestationFingerprint = ProtocolFingerprint.of(objectMapper, attestation);
        if (entry.status() != Status.POSITIVE
                || !floor.tenantId().equals(entry.command().identity().tenantId())
                || !floor.environmentId().equals(
                entry.command().identity().environmentId())
                || !floor.attemptId().equals(receipt.attemptId())
                || !floor.identityFingerprint().equals(receipt.identityFingerprint())
                || !floor.startCommandId().equals(receipt.startCommandId())
                || !floor.startCommandFingerprint().equals(
                receipt.startCommandFingerprint())
                || !floor.observationCommandId().equals(receipt.commandId())
                || !floor.attestationFingerprint().equals(attestationFingerprint)
                || !floor.observedState().equals(receipt.state().name())
                || floor.attemptRevision() != receipt.attemptRevision()
                || !floor.processIdentityFingerprint().equals(
                receipt.processIdentityFingerprint())
                || !floor.stateFactFingerprint().equals(
                stateFactFingerprint(receipt))) {
            throw integrity("state floor");
        }
        return new PositiveState(
                PositiveState.SCHEMA_VERSION, floor.tenantId(), floor.environmentId(),
                floor.attemptId(), floor.identityFingerprint(), floor.startCommandId(),
                floor.startCommandFingerprint(), floor.observationCommandId(),
                floor.attestationFingerprint(), receipt, floor.acceptedAt(),
                floor.recordFingerprint());
    }

    private void persistPositiveState(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String attestationFingerprint,
            Instant acceptedAt) {
        StoredStateFloor floor = stateFloor(
                command, receipt, attestationFingerprint, acceptedAt);
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_observation_state_floors (
                    attempt_id, tenant_id, environment_id, identity_fingerprint,
                    start_command_id, start_command_fingerprint,
                    observation_command_id, attestation_fingerprint, observed_state,
                    attempt_revision, process_identity_fingerprint,
                    state_fact_fingerprint, accepted_at, record_fingerprint
                ) KEY (attempt_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, floor.attemptId(), floor.tenantId(), floor.environmentId(),
                floor.identityFingerprint(), floor.startCommandId(),
                floor.startCommandFingerprint(), floor.observationCommandId(),
                floor.attestationFingerprint(), floor.observedState(),
                floor.attemptRevision(), floor.processIdentityFingerprint(),
                floor.stateFactFingerprint(), Timestamp.from(floor.acceptedAt()),
                floor.recordFingerprint());
    }

    private StoredStateFloor stateFloor(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String attestationFingerprint,
            Instant acceptedAt) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        StoredStateFloor material = new StoredStateFloor(
                identity.attemptId(), identity.tenantId(), identity.environmentId(),
                identity.identityFingerprint(), command.startCommand().commandId(),
                command.startCommand().commandFingerprint(), command.commandId(),
                attestationFingerprint, receipt.state().name(),
                receipt.attemptRevision(), receipt.processIdentityFingerprint(),
                stateFactFingerprint(receipt), acceptedAt, "");
        return new StoredStateFloor(
                material.attemptId(), material.tenantId(), material.environmentId(),
                material.identityFingerprint(), material.startCommandId(),
                material.startCommandFingerprint(), material.observationCommandId(),
                material.attestationFingerprint(), material.observedState(),
                material.attemptRevision(), material.processIdentityFingerprint(),
                material.stateFactFingerprint(), material.acceptedAt(),
                stateFloorFingerprint(material));
    }

    private String stateFloorFingerprint(StoredStateFloor floor) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", STATE_FLOOR_SCHEMA);
        material.put("attemptId", floor.attemptId());
        material.put("tenantId", floor.tenantId());
        material.put("environmentId", floor.environmentId());
        material.put("identityFingerprint", floor.identityFingerprint());
        material.put("startCommandId", floor.startCommandId());
        material.put("startCommandFingerprint", floor.startCommandFingerprint());
        material.put("observationCommandId", floor.observationCommandId());
        material.put("attestationFingerprint", floor.attestationFingerprint());
        material.put("observedState", floor.observedState());
        material.put("attemptRevision", floor.attemptRevision());
        material.put("processIdentityFingerprint", floor.processIdentityFingerprint());
        material.put("stateFactFingerprint", floor.stateFactFingerprint());
        material.put("acceptedAt", floor.acceptedAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String stateFactFingerprint(
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", STATE_FACT_SCHEMA);
        material.put("providerId", receipt.providerId());
        material.put("deploymentId", receipt.deploymentId());
        material.put("attemptId", receipt.attemptId());
        material.put("identityFingerprint", receipt.identityFingerprint());
        material.put("startCommandId", receipt.startCommandId());
        material.put("startCommandFingerprint", receipt.startCommandFingerprint());
        material.put("leaseEpoch", receipt.leaseEpoch());
        material.put("attemptRevision", receipt.attemptRevision());
        material.put("isolationMode", receipt.isolationMode());
        material.put("state", receipt.state());
        material.put("processIdentityFingerprint",
                receipt.processIdentityFingerprint());
        material.put("runtimeStateFingerprint", receipt.runtimeStateFingerprint());
        material.put("terminalDisposition", receipt.terminalDisposition());
        material.put("evidenceFingerprint", receipt.evidenceFingerprint());
        material.put("stateEffectiveAt", receipt.stateEffectiveAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private static StoredStateFloor mapStateFloor(ResultSet rs) throws SQLException {
        return new StoredStateFloor(
                rs.getString("attempt_id"), rs.getString("tenant_id"),
                rs.getString("environment_id"), rs.getString("identity_fingerprint"),
                rs.getString("start_command_id"),
                rs.getString("start_command_fingerprint"),
                rs.getString("observation_command_id"),
                rs.getString("attestation_fingerprint"),
                rs.getString("observed_state"), rs.getLong("attempt_revision"),
                rs.getString("process_identity_fingerprint"),
                rs.getString("state_fact_fingerprint"),
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private void lockAttempt(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        String key = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptObservationLock.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "attemptId", identity.attemptId()));
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_observation_locks (lock_key)
                KEY (lock_key) VALUES (?)
                """, key);
        jdbc.queryForObject("""
                SELECT lock_key FROM rg_test_stability_attempt_observation_locks
                WHERE lock_key = ? FOR UPDATE
                """, String.class, key);
    }

    private void lockProvider(String providerScope) {
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_observation_provider_locks (
                    provider_scope_fingerprint
                ) KEY (provider_scope_fingerprint) VALUES (?)
                """, providerScope);
        jdbc.queryForObject("""
                SELECT provider_scope_fingerprint
                FROM rg_test_stability_attempt_observation_provider_locks
                WHERE provider_scope_fingerprint = ? FOR UPDATE
                """, String.class, providerScope);
    }

    private String providerScope(String providerId, String deploymentId) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptObservationProviderScope.v1",
                "providerId", providerId,
                "deploymentId", deploymentId));
    }

    private ProviderFloor providerFloor(String providerScope) {
        List<ProviderFloor> rows = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_id, deployment_id,
                       provider_sequence, command_id, attestation_fingerprint,
                       updated_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_provider_floors
                WHERE provider_scope_fingerprint = ?
                """, (rs, row) -> new ProviderFloor(
                rs.getString("provider_scope_fingerprint"), rs.getString("provider_id"),
                rs.getString("deployment_id"), rs.getLong("provider_sequence"),
                rs.getString("command_id"), rs.getString("attestation_fingerprint"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint")), providerScope);
        if (rows.size() > 1) {
            throw integrity("provider floor uniqueness");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateProviderFloor(String providerScope, ProviderFloor floor) {
        List<ProviderSequence> latest = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_provider_sequences
                WHERE provider_scope_fingerprint = ?
                ORDER BY provider_sequence DESC
                FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> mapProviderSequence(rs), providerScope);
        if ((floor == null) != latest.isEmpty()) {
            throw integrity("provider floor continuity");
        }
        if (floor == null) {
            return;
        }
        ProviderSequence sequence = latest.getFirst();
        String expectedFloor = providerFloorFingerprint(floor);
        String expectedSequence = providerSequenceFingerprint(
                sequence.providerScope(), sequence.providerSequence(),
                sequence.commandId(), sequence.attestationFingerprint(),
                sequence.acceptedAt());
        if (!floor.providerScope().equals(providerScope)
                || !floor.recordFingerprint().equals(expectedFloor)
                || !sequence.providerScope().equals(providerScope)
                || !sequence.recordFingerprint().equals(expectedSequence)
                || floor.providerSequence() != sequence.providerSequence()
                || !floor.commandId().equals(sequence.commandId())
                || !floor.attestationFingerprint().equals(
                sequence.attestationFingerprint())) {
            throw integrity("provider floor");
        }
    }

    private static ProviderSequence mapProviderSequence(ResultSet rs) throws SQLException {
        return new ProviderSequence(
                rs.getString("provider_scope_fingerprint"),
                rs.getLong("provider_sequence"), rs.getString("command_id"),
                rs.getString("attestation_fingerprint"),
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private void appendProviderSequence(
            String providerScope,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt) {
        String fingerprint = providerSequenceFingerprint(
                providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, acceptedAt);
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_observation_provider_sequences (
                    provider_scope_fingerprint, provider_sequence, command_id,
                    attestation_fingerprint, accepted_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, Timestamp.from(acceptedAt), fingerprint);
    }

    private void persistProviderFloor(
            String providerScope,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant updatedAt) {
        ProviderFloor material = new ProviderFloor(
                providerScope, receipt.providerId(), receipt.deploymentId(),
                receipt.providerSequence(), commandId, attestationFingerprint,
                updatedAt, "");
        ProviderFloor floor = new ProviderFloor(
                material.providerScope(), material.providerId(), material.deploymentId(),
                material.providerSequence(), material.commandId(),
                material.attestationFingerprint(), material.updatedAt(),
                providerFloorFingerprint(material));
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_observation_provider_floors (
                    provider_scope_fingerprint, provider_id, deployment_id,
                    provider_sequence, command_id, attestation_fingerprint,
                    updated_at, record_fingerprint
                ) KEY (provider_scope_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, floor.providerScope(), floor.providerId(), floor.deploymentId(),
                floor.providerSequence(), floor.commandId(),
                floor.attestationFingerprint(), Timestamp.from(floor.updatedAt()),
                floor.recordFingerprint());
    }

    private String providerFloorFingerprint(ProviderFloor floor) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", PROVIDER_FLOOR_SCHEMA,
                "providerScope", floor.providerScope(),
                "providerId", floor.providerId(),
                "deploymentId", floor.deploymentId(),
                "providerSequence", floor.providerSequence(),
                "commandId", floor.commandId(),
                "attestationFingerprint", floor.attestationFingerprint(),
                "updatedAt", floor.updatedAt()));
    }

    private String providerSequenceFingerprint(
            String providerScope,
            long providerSequence,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", PROVIDER_SEQUENCE_SCHEMA,
                "providerScope", providerScope,
                "providerSequence", providerSequence,
                "commandId", commandId,
                "attestationFingerprint", attestationFingerprint,
                "acceptedAt", acceptedAt));
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException(
                    "Physical-attempt observation database time is unavailable");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Physical-attempt observation protocol cannot be serialized");
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw integrity("protocol");
        }
    }

    private static String requireCommandId(String value) {
        String normalized = normalized(value);
        if (!COMMAND_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid physical-attempt observation command id");
        }
        return normalized;
    }

    private static String requireAttemptId(String value) {
        String normalized = normalized(value);
        if (!ATTEMPT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid physical-attempt id");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String field) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requireEnvironment(String value) {
        String normalized = normalized(value);
        if (!Set.of("test", "staging").contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid physical-attempt observation environment");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static ConflictException conflict(ConflictReason reason) {
        return new ConflictException(reason);
    }

    private static IllegalStateException integrity(String subject) {
        return new IllegalStateException(
                "Physical-attempt observation " + subject + " integrity failed");
    }

    private static boolean knownIntegrityFailure(String message) {
        return message != null
                && message.startsWith("Physical-attempt observation ")
                && message.endsWith(" integrity failed");
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        JdbcTemplate required = Objects.requireNonNull(jdbc, "jdbc");
        if (required.getDataSource() == null) {
            throw new IllegalArgumentException("JDBC datasource is required");
        }
        return new DataSourceTransactionManager(required.getDataSource());
    }

    private enum StateTransition {
        RETAIN,
        ADVANCE
    }

    private record StoredEntry(
            String commandId,
            String commandFingerprint,
            String tenantId,
            String environmentId,
            String attemptId,
            String startCommandId,
            long leaseEpoch,
            String providerId,
            String deploymentId,
            String status,
            String observedState,
            long providerSequence,
            long attemptRevision,
            String attestationFingerprint,
            String commandJson,
            String descriptorJson,
            String attestationJson,
            Instant preparedAt,
            Instant updatedAt,
            String recordFingerprint) {
    }

    private record StoredStateFloor(
            String attemptId,
            String tenantId,
            String environmentId,
            String identityFingerprint,
            String startCommandId,
            String startCommandFingerprint,
            String observationCommandId,
            String attestationFingerprint,
            String observedState,
            long attemptRevision,
            String processIdentityFingerprint,
            String stateFactFingerprint,
            Instant acceptedAt,
            String recordFingerprint) {
    }

    private record ProviderFloor(
            String providerScope,
            String providerId,
            String deploymentId,
            long providerSequence,
            String commandId,
            String attestationFingerprint,
            Instant updatedAt,
            String recordFingerprint) {
    }

    private record ProviderSequence(
            String providerScope,
            long providerSequence,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt,
            String recordFingerprint) {
    }
}
