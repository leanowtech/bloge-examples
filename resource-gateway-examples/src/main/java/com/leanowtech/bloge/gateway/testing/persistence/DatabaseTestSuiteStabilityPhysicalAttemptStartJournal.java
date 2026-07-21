package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartVerifier;
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
 * Database-time authority for physical-attempt start commands and provider attestations.
 *
 * <p>One attempt/lease lock serializes preparation and receipt acceptance. Initial preparation
 * additionally locks the exact queue row and verifies the complete reservation and queue record.
 * Invocation authorization repeats that live fence check immediately before external I/O. A
 * separate provider/deployment lock serializes the monotonic start sequence floor.</p>
 *
 * <p>Acceptance deliberately does not require the queue lease to remain active: once dispatch may
 * have occurred, a valid provider-signed fact must remain visible to terminal and orphan
 * reconciliation even when cancellation or lease expiry won the queue race.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptStartJournal
        implements TestSuiteStabilityPhysicalAttemptStartJournal {

    private static final String ENTRY_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptStartStoredEntry.v1";
    private static final String RESERVATION_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptStoredEntry.v1";
    private static final String FLOOR_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptStartProviderFloor.v1";
    private static final String SEQUENCE_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptStartProviderSequence.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-start-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");
    private static final Duration MAXIMUM_CALLER_CLOCK_SKEW = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityJobRepository jobs;
    private final TestSuiteStabilityPhysicalAttemptStartVerifier verifier;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a journal using a local transaction manager for the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param jobs integrity-verifying queue repository over the same datasource
     * @param verifier pinned provider start-attestation verifier
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityPhysicalAttemptStartVerifier verifier) {
        this(jdbc, objectMapper, jobs, verifier, localTransactionManager(jdbc));
    }

    /**
     * Creates a journal with a caller-supplied transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param jobs integrity-verifying queue repository over the same datasource
     * @param verifier pinned provider start-attestation verifier
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityPhysicalAttemptStartVerifier verifier,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
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

    /** Creates payload-free command, provider-floor, sequence, and lock tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_start_locks (
                    lock_key VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_start_provider_locks (
                    provider_scope_fingerprint VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_start_entries (
                    command_id VARCHAR(128) PRIMARY KEY,
                    command_fingerprint VARCHAR(71) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    attempt_id VARCHAR(96) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    command_json CLOB NOT NULL,
                    descriptor_json CLOB NOT NULL,
                    attestation_json CLOB NOT NULL,
                    prepared_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_attempt_start_fence
                        UNIQUE (tenant_id, environment_id, attempt_id, lease_epoch)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_stability_attempt_start_scope
                ON rg_test_stability_attempt_start_entries (
                    tenant_id, environment_id, prepared_at, command_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_start_provider_floors (
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
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_start_provider_sequences (
                    provider_scope_fingerprint VARCHAR(71) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    command_id VARCHAR(128) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (provider_scope_fingerprint, provider_sequence),
                    CONSTRAINT uq_rg_test_stability_attempt_start_provider_command
                        UNIQUE (provider_scope_fingerprint, command_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Preparation prepare(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor) {
        TestSuiteStabilityPhysicalAttemptStartCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        Preparation result = mutations.execute(status -> {
            validateCommandIdentity(requiredCommand);
            lockAttempt(requiredCommand);

            StoredEntry byId = entry(requiredCommand.commandId());
            if (byId != null) {
                Entry retained = validateEntry(byId);
                if (retained.command().equals(requiredCommand)
                        && retained.descriptor().equals(requiredDescriptor)) {
                    return new Preparation(PreparationStatus.REPLAYED, retained);
                }
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }
            StoredEntry byFence = entryByFence(requiredCommand);
            if (byFence != null) {
                validateEntry(byFence);
                throw conflict(ConflictReason.ATTEMPT_COMMAND_CONFLICT);
            }

            lockQueueJob(requiredCommand.identity());
            Instant now = currentTime();
            validateReservedLiveFence(requiredCommand.identity(), now);
            validatePreparationTime(requiredCommand, now);
            validateProviderCompatibility(requiredCommand, requiredDescriptor);

            StoredEntry prepared = stored(
                    requiredCommand, requiredDescriptor, Status.PREPARED, null, now, now);
            insertEntry(prepared);
            return new Preparation(PreparationStatus.PREPARED,
                    validateEntry(requireEntry(requiredCommand.commandId())));
        });
        return Objects.requireNonNull(result, "physical-attempt start preparation");
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
            lockAttempt(initialEntry.command());
            Entry prepared = validateEntry(requireEntry(exactCommandId));
            if (prepared.status() != Status.PREPARED) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            lockQueueJob(prepared.command().identity());
            Instant now = currentTime();
            validateReservedLiveFence(prepared.command().identity(), now);
            validatePreparationTime(prepared.command(), now);
            validateInvocationWindow(prepared.command(), prepared.descriptor(), now);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(authorized)) {
            throw new IllegalStateException(
                    "Physical-attempt start invocation authorization returned no result");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(
            String commandId,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation) {
        String exactCommandId = requireCommandId(commandId);
        TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Acceptance result = mutations.execute(status -> {
            StoredEntry initial = entry(exactCommandId);
            if (initial == null) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            Entry initialEntry = validateEntry(initial);
            lockAttempt(initialEntry.command());
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

            Instant now = currentTime();
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt = verifier.verify(
                    prepared.command(), prepared.descriptor(), requiredAttestation, now);
            if (receipt.confirmedAt().isBefore(prepared.preparedAt())) {
                throw conflict(ConflictReason.START_PRECEDES_PREPARATION);
            }
            String providerScope = providerScope(
                    receipt.providerId(), receipt.deploymentId());
            lockProvider(providerScope);
            ProviderFloor floor = providerFloor(providerScope);
            validateProviderFloor(providerScope, floor);
            if (floor != null && receipt.providerSequence() <= floor.providerSequence()) {
                throw conflict(ConflictReason.PROVIDER_SEQUENCE_ROLLBACK);
            }

            appendProviderSequence(providerScope, receipt, exactCommandId,
                    attestationFingerprint, now);
            persistProviderFloor(providerScope, receipt, exactCommandId,
                    attestationFingerprint, now);
            Status terminalStatus = receipt.startConfirmed()
                    ? Status.CONFIRMED : Status.UNCONFIRMED;
            StoredEntry terminal = stored(
                    prepared.command(), prepared.descriptor(), terminalStatus,
                    requiredAttestation, prepared.preparedAt(), now);
            updateEntry(current, terminal);
            Entry retained = validateEntry(requireEntry(exactCommandId));
            return new Acceptance(
                    terminalStatus == Status.CONFIRMED
                            ? AcceptanceStatus.CONFIRMED
                            : AcceptanceStatus.UNCONFIRMED,
                    retained);
        });
        return Objects.requireNonNull(result, "physical-attempt start acceptance");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String commandId) {
        String tenant = requireIdentifier(tenantId, "tenantId");
        String environment = normalized(environmentId);
        String exactCommandId = requireCommandId(commandId);
        if (!Set.of("test", "staging").contains(environment)) {
            throw new IllegalArgumentException("Invalid physical-attempt start environment");
        }
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

    private void validateCommandIdentity(
            TestSuiteStabilityPhysicalAttemptStartCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        String derivedIdentity = ProtocolFingerprint.of(
                objectMapper, identity.canonicalMaterial());
        String derivedCommand = ProtocolFingerprint.of(
                objectMapper, command.canonicalMaterial());
        if (!derivedIdentity.equals(identity.identityFingerprint())
                || !identity.attemptId().equals("stability-attempt-"
                + derivedIdentity.substring("sha256:".length()))
                || !derivedCommand.equals(command.commandFingerprint())
                || !command.commandId().equals("stability-attempt-start-"
                + derivedCommand.substring("sha256:".length()))) {
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private void validateReservedLiveFence(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            Instant now) {
        ReservedAttempt reservation = reservation(identity.attemptId());
        if (reservation == null) {
            throw conflict(ConflictReason.RESERVATION_NOT_ACTIVE);
        }
        validateReservation(reservation, identity);
        TestSuiteStabilityJobRecord job = jobs.find(
                identity.tenantId(), identity.environmentId(), identity.jobId())
                .orElseThrow(() -> conflict(ConflictReason.RESERVATION_NOT_ACTIVE));
        List<QueueFence> rows = jdbc.query("""
                SELECT request_fingerprint, status, owner_id, lease_epoch, lease_expires_at
                FROM rg_test_suite_stability_jobs
                WHERE tenant_id = ? AND environment_id = ? AND job_id = ?
                """, (rs, row) -> new QueueFence(
                rs.getString("request_fingerprint"), rs.getString("status"),
                rs.getString("owner_id"), rs.getLong("lease_epoch"),
                rs.getTimestamp("lease_expires_at") == null
                        ? null : rs.getTimestamp("lease_expires_at").toInstant()),
                identity.tenantId(), identity.environmentId(), identity.jobId());
        if (rows.size() != 1) {
            throw conflict(ConflictReason.RESERVATION_NOT_ACTIVE);
        }
        QueueFence fence = rows.getFirst();
        if (job.status() != TestSuiteStabilityJobRecord.Status.RUNNING
                || !job.deadlineAt().isAfter(now)
                || !job.requestFingerprint().equals(identity.requestFingerprint())
                || !fence.status().equals(TestSuiteStabilityJobRecord.Status.RUNNING.name())
                || !fence.requestFingerprint().equals(identity.requestFingerprint())
                || !fence.ownerId().equals(identity.ownerId())
                || fence.leaseEpoch() != identity.leaseEpoch()
                || fence.leaseExpiresAt() == null
                || !fence.leaseExpiresAt().isAfter(now)) {
            throw conflict(ConflictReason.RESERVATION_NOT_ACTIVE);
        }
    }

    private void validateReservation(
            ReservedAttempt stored,
            TestSuiteStabilityPhysicalAttemptIdentity expectedIdentity) {
        try {
            TestSuiteStabilityPhysicalAttemptIdentity identity = decode(
                    stored.identityJson(), TestSuiteStabilityPhysicalAttemptIdentity.class);
            String derived = ProtocolFingerprint.of(objectMapper, identity.canonicalMaterial());
            String expectedRecord = ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion", RESERVATION_SCHEMA,
                    "identityFingerprint", identity.identityFingerprint(),
                    "reservedAt", stored.reservedAt()));
            if (!identity.equals(expectedIdentity)
                    || !derived.equals(identity.identityFingerprint())
                    || !identity.attemptId().equals("stability-attempt-"
                    + derived.substring("sha256:".length()))
                    || !stored.attemptId().equals(identity.attemptId())
                    || !stored.identityFingerprint().equals(identity.identityFingerprint())
                    || !stored.tenantId().equals(identity.tenantId())
                    || !stored.environmentId().equals(identity.environmentId())
                    || !stored.jobId().equals(identity.jobId())
                    || !stored.requestFingerprint().equals(identity.requestFingerprint())
                    || !stored.ownerId().equals(identity.ownerId())
                    || stored.leaseEpoch() != identity.leaseEpoch()
                    || !stored.runtimeBindingFingerprint().equals(
                    identity.runtimeBindingFingerprint())
                    || !stored.providerId().equals(identity.providerId())
                    || !stored.deploymentId().equals(identity.deploymentId())
                    || !stored.isolationMode().equals(identity.isolationMode().name())
                    || !stored.recordFingerprint().equals(expectedRecord)) {
                throw new IllegalStateException(
                        "Physical-attempt start reservation integrity failed");
            }
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException state
                    && "Physical-attempt start reservation integrity failed"
                    .equals(state.getMessage())) {
                throw state;
            }
            throw new IllegalStateException(
                    "Physical-attempt start reservation integrity failed");
        }
    }

    private void validateProviderCompatibility(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        Duration commandWindow = Duration.between(
                command.requestedAt(), command.confirmationDeadlineAt());
        if (!descriptor.available()
                || !descriptor.providerId().equals(identity.providerId())
                || !descriptor.deploymentId().equals(identity.deploymentId())
                || !descriptor.isolationModes().contains(identity.isolationMode())
                || descriptor.maximumStartLatency().compareTo(commandWindow) > 0) {
            throw conflict(ConflictReason.PROVIDER_INCOMPATIBLE);
        }
    }

    private void validateInvocationWindow(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            Instant now) {
        Duration remaining = Duration.between(now, command.confirmationDeadlineAt());
        if (!descriptor.available()
                || descriptor.maximumStartLatency().compareTo(remaining) > 0) {
            throw conflict(ConflictReason.PROVIDER_INCOMPATIBLE);
        }
    }

    private void validatePreparationTime(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            Instant now) {
        if (command.requestedAt().isAfter(now.plus(MAXIMUM_CALLER_CLOCK_SKEW))
                || !now.isBefore(command.confirmationDeadlineAt())) {
            throw conflict(ConflictReason.COMMAND_EXPIRED);
        }
    }

    private StoredEntry stored(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            Status status,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation,
            Instant preparedAt,
            Instant updatedAt) {
        String attestationFingerprint = attestation == null
                ? "" : ProtocolFingerprint.of(objectMapper, attestation);
        long providerSequence = attestation == null
                ? 0 : attestation.receipt().providerSequence();
        String fingerprint = entryFingerprint(
                command, descriptor, status, providerSequence, attestationFingerprint,
                preparedAt, updatedAt);
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        return new StoredEntry(
                command.commandId(), command.commandFingerprint(), identity.tenantId(),
                identity.environmentId(), identity.attemptId(), identity.leaseEpoch(),
                descriptor.providerId(), descriptor.deploymentId(), status.name(),
                providerSequence, attestationFingerprint, encode(command), encode(descriptor),
                attestation == null ? "" : encode(attestation), preparedAt, updatedAt,
                fingerprint);
    }

    private Entry validateEntry(StoredEntry stored) {
        try {
            TestSuiteStabilityPhysicalAttemptStartCommand command = decode(
                    stored.commandJson(), TestSuiteStabilityPhysicalAttemptStartCommand.class);
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor = decode(
                    stored.descriptorJson(),
                    TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.class);
            Status status = Status.valueOf(stored.status());
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation =
                    stored.attestationJson().isBlank() ? null : decode(
                            stored.attestationJson(),
                            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.class);
            TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
            String expected = entryFingerprint(
                    command, descriptor, status, stored.providerSequence(),
                    stored.attestationFingerprint(), stored.preparedAt(), stored.updatedAt());
            if (!stored.commandId().equals(command.commandId())
                    || !stored.commandFingerprint().equals(command.commandFingerprint())
                    || !stored.tenantId().equals(identity.tenantId())
                    || !stored.environmentId().equals(identity.environmentId())
                    || !stored.attemptId().equals(identity.attemptId())
                    || stored.leaseEpoch() != identity.leaseEpoch()
                    || !stored.providerId().equals(descriptor.providerId())
                    || !stored.deploymentId().equals(descriptor.deploymentId())
                    || !stored.recordFingerprint().equals(expected)
                    || (attestation == null) != stored.attestationFingerprint().isBlank()
                    || attestation != null && (!stored.attestationFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, attestation))
                    || stored.providerSequence()
                    != attestation.receipt().providerSequence())) {
                throw new IllegalStateException(
                        "Physical-attempt start journal entry integrity failed");
            }
            validateCommandIdentity(command);
            if (attestation != null) {
                validateRetainedProviderState(attestation, stored.attestationFingerprint());
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
            throw new IllegalStateException(
                    "Physical-attempt start journal entry integrity failed");
        }
    }

    private static boolean knownIntegrityFailure(String message) {
        return Set.of(
                "Physical-attempt start journal entry integrity failed",
                "Physical-attempt start provider sequence continuity failed",
                "Physical-attempt start provider sequence integrity failed",
                "Physical-attempt start provider floor continuity failed",
                "Physical-attempt start provider floor integrity failed")
                .contains(message);
    }

    private void validateRetainedProviderState(
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation,
            String attestationFingerprint) {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = attestation.receipt();
        String providerScope = providerScope(receipt.providerId(), receipt.deploymentId());
        List<ProviderSequence> retained = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_start_provider_sequences
                WHERE provider_scope_fingerprint = ? AND command_id = ?
                """, (rs, row) -> mapProviderSequence(rs),
                providerScope, receipt.commandId());
        if (retained.size() != 1) {
            throw new IllegalStateException(
                    "Physical-attempt start provider sequence continuity failed");
        }
        ProviderSequence sequence = retained.getFirst();
        String expected = sequenceFingerprint(
                sequence.providerScope(), sequence.providerSequence(), sequence.commandId(),
                sequence.attestationFingerprint(), sequence.acceptedAt());
        if (!sequence.recordFingerprint().equals(expected)
                || sequence.providerSequence() != receipt.providerSequence()
                || !sequence.attestationFingerprint().equals(attestationFingerprint)) {
            throw new IllegalStateException(
                    "Physical-attempt start provider sequence integrity failed");
        }
        validateProviderFloor(providerScope, providerFloor(providerScope));
    }

    private String entryFingerprint(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            Status status,
            long providerSequence,
            String attestationFingerprint,
            Instant preparedAt,
            Instant updatedAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", ENTRY_SCHEMA);
        material.put("commandFingerprint", command.commandFingerprint());
        material.put("descriptorFingerprint", ProtocolFingerprint.of(objectMapper, descriptor));
        material.put("status", status);
        material.put("providerSequence", providerSequence);
        material.put("attestationFingerprint", attestationFingerprint);
        material.put("preparedAt", preparedAt);
        material.put("updatedAt", updatedAt);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private void insertEntry(StoredEntry value) {
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_start_entries (
                    command_id, command_fingerprint, tenant_id, environment_id,
                    attempt_id, lease_epoch, provider_id, deployment_id, status,
                    provider_sequence, attestation_fingerprint, command_json,
                    descriptor_json, attestation_json, prepared_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.commandId(), value.commandFingerprint(), value.tenantId(),
                value.environmentId(), value.attemptId(), value.leaseEpoch(),
                value.providerId(), value.deploymentId(), value.status(),
                value.providerSequence(), value.attestationFingerprint(), value.commandJson(),
                value.descriptorJson(), value.attestationJson(),
                Timestamp.from(value.preparedAt()), Timestamp.from(value.updatedAt()),
                value.recordFingerprint());
    }

    private void updateEntry(StoredEntry previous, StoredEntry value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_start_entries
                SET status = ?, provider_sequence = ?, attestation_fingerprint = ?,
                    attestation_json = ?, updated_at = ?, record_fingerprint = ?
                WHERE command_id = ? AND record_fingerprint = ?
                """, value.status(), value.providerSequence(),
                value.attestationFingerprint(), value.attestationJson(),
                Timestamp.from(value.updatedAt()), value.recordFingerprint(),
                previous.commandId(), previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Physical-attempt start journal concurrent update failed");
        }
    }

    private StoredEntry entry(String commandId) {
        List<StoredEntry> rows = jdbc.query(entrySelect() + " WHERE command_id = ?",
                this::mapEntry, commandId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Physical-attempt start command identity is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredEntry entryByFence(TestSuiteStabilityPhysicalAttemptStartCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        List<StoredEntry> rows = jdbc.query(entrySelect() + """
                 WHERE tenant_id = ? AND environment_id = ?
                   AND attempt_id = ? AND lease_epoch = ?
                """, this::mapEntry, identity.tenantId(), identity.environmentId(),
                identity.attemptId(), identity.leaseEpoch());
        if (rows.size() > 1) {
            throw new IllegalStateException("Physical-attempt start fence is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String entrySelect() {
        return """
                SELECT command_id, command_fingerprint, tenant_id, environment_id,
                       attempt_id, lease_epoch, provider_id, deployment_id, status,
                       provider_sequence, attestation_fingerprint, command_json,
                       descriptor_json, attestation_json, prepared_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_start_entries
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
                rs.getString("attempt_id"), rs.getLong("lease_epoch"),
                rs.getString("provider_id"), rs.getString("deployment_id"),
                rs.getString("status"), rs.getLong("provider_sequence"),
                rs.getString("attestation_fingerprint"), rs.getString("command_json"),
                rs.getString("descriptor_json"), rs.getString("attestation_json"),
                rs.getTimestamp("prepared_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private ReservedAttempt reservation(String attemptId) {
        List<ReservedAttempt> rows = jdbc.query("""
                SELECT attempt_id, identity_fingerprint, tenant_id, environment_id, job_id,
                       request_fingerprint, owner_id, lease_epoch,
                       runtime_binding_fingerprint, provider_id, deployment_id,
                       isolation_mode, identity_json, reserved_at, record_fingerprint
                FROM rg_test_stability_physical_attempts
                WHERE attempt_id = ?
                """, (rs, row) -> new ReservedAttempt(
                rs.getString("attempt_id"), rs.getString("identity_fingerprint"),
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("job_id"), rs.getString("request_fingerprint"),
                rs.getString("owner_id"), rs.getLong("lease_epoch"),
                rs.getString("runtime_binding_fingerprint"), rs.getString("provider_id"),
                rs.getString("deployment_id"), rs.getString("isolation_mode"),
                rs.getString("identity_json"), rs.getTimestamp("reserved_at").toInstant(),
                rs.getString("record_fingerprint")), attemptId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Physical-attempt start reservation is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void lockAttempt(TestSuiteStabilityPhysicalAttemptStartCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        String key = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityPhysicalAttemptStartLock.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "attemptId", identity.attemptId(),
                "leaseEpoch", identity.leaseEpoch()));
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_start_locks (lock_key)
                KEY (lock_key) VALUES (?)
                """, key);
        jdbc.queryForObject("""
                SELECT lock_key FROM rg_test_stability_attempt_start_locks
                WHERE lock_key = ? FOR UPDATE
                """, String.class, key);
    }

    private void lockQueueJob(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        List<String> rows = jdbc.queryForList("""
                SELECT job_id FROM rg_test_suite_stability_jobs
                WHERE tenant_id = ? AND environment_id = ? AND job_id = ?
                FOR UPDATE
                """, String.class, identity.tenantId(), identity.environmentId(),
                identity.jobId());
        if (rows.size() != 1) {
            throw conflict(ConflictReason.RESERVATION_NOT_ACTIVE);
        }
    }

    private void lockProvider(String providerScope) {
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_start_provider_locks (
                    provider_scope_fingerprint
                ) KEY (provider_scope_fingerprint) VALUES (?)
                """, providerScope);
        jdbc.queryForObject("""
                SELECT provider_scope_fingerprint
                FROM rg_test_stability_attempt_start_provider_locks
                WHERE provider_scope_fingerprint = ? FOR UPDATE
                """, String.class, providerScope);
    }

    private String providerScope(String providerId, String deploymentId) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptStartProviderScope.v1",
                "providerId", providerId,
                "deploymentId", deploymentId));
    }

    private ProviderFloor providerFloor(String providerScope) {
        List<ProviderFloor> rows = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_id, deployment_id,
                       provider_sequence, command_id, attestation_fingerprint,
                       updated_at, record_fingerprint
                FROM rg_test_stability_attempt_start_provider_floors
                WHERE provider_scope_fingerprint = ?
                """, (rs, row) -> new ProviderFloor(
                rs.getString("provider_scope_fingerprint"), rs.getString("provider_id"),
                rs.getString("deployment_id"), rs.getLong("provider_sequence"),
                rs.getString("command_id"), rs.getString("attestation_fingerprint"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint")), providerScope);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Physical-attempt start provider floor is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateProviderFloor(String providerScope, ProviderFloor floor) {
        List<ProviderSequence> latest = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_start_provider_sequences
                WHERE provider_scope_fingerprint = ?
                ORDER BY provider_sequence DESC
                FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> mapProviderSequence(rs), providerScope);
        if ((floor == null) != latest.isEmpty()) {
            throw new IllegalStateException(
                    "Physical-attempt start provider floor continuity failed");
        }
        if (floor == null) {
            return;
        }
        ProviderSequence sequence = latest.getFirst();
        String expectedFloor = floorFingerprint(
                floor.providerScope(), floor.providerId(), floor.deploymentId(),
                floor.providerSequence(), floor.commandId(), floor.attestationFingerprint(),
                floor.updatedAt());
        String expectedSequence = sequenceFingerprint(
                sequence.providerScope(), sequence.providerSequence(), sequence.commandId(),
                sequence.attestationFingerprint(), sequence.acceptedAt());
        if (!floor.providerScope().equals(providerScope)
                || !floor.recordFingerprint().equals(expectedFloor)
                || !sequence.providerScope().equals(providerScope)
                || !sequence.recordFingerprint().equals(expectedSequence)
                || floor.providerSequence() != sequence.providerSequence()
                || !floor.commandId().equals(sequence.commandId())
                || !floor.attestationFingerprint().equals(
                sequence.attestationFingerprint())) {
            throw new IllegalStateException(
                    "Physical-attempt start provider floor integrity failed");
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
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt) {
        String fingerprint = sequenceFingerprint(
                providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, acceptedAt);
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_start_provider_sequences (
                    provider_scope_fingerprint, provider_sequence, command_id,
                    attestation_fingerprint, accepted_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, Timestamp.from(acceptedAt), fingerprint);
    }

    private void persistProviderFloor(
            String providerScope,
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant updatedAt) {
        String fingerprint = floorFingerprint(
                providerScope, receipt.providerId(), receipt.deploymentId(),
                receipt.providerSequence(), commandId, attestationFingerprint, updatedAt);
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_start_provider_floors (
                    provider_scope_fingerprint, provider_id, deployment_id,
                    provider_sequence, command_id, attestation_fingerprint,
                    updated_at, record_fingerprint
                ) KEY (provider_scope_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, providerScope, receipt.providerId(), receipt.deploymentId(),
                receipt.providerSequence(), commandId, attestationFingerprint,
                Timestamp.from(updatedAt), fingerprint);
    }

    private String floorFingerprint(
            String providerScope,
            String providerId,
            String deploymentId,
            long providerSequence,
            String commandId,
            String attestationFingerprint,
            Instant updatedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", FLOOR_SCHEMA,
                "providerScope", providerScope,
                "providerId", providerId,
                "deploymentId", deploymentId,
                "providerSequence", providerSequence,
                "commandId", commandId,
                "attestationFingerprint", attestationFingerprint,
                "updatedAt", updatedAt));
    }

    private String sequenceFingerprint(
            String providerScope,
            long providerSequence,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", SEQUENCE_SCHEMA,
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
                    "Physical-attempt start database time is unavailable");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Physical-attempt start protocol cannot be serialized");
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Physical-attempt start protocol cannot be deserialized");
        }
    }

    private static String requireCommandId(String value) {
        String normalized = normalized(value);
        if (!COMMAND_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid physical-attempt start command id");
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

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static ConflictException conflict(ConflictReason reason) {
        return new ConflictException(reason);
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        JdbcTemplate required = Objects.requireNonNull(jdbc, "jdbc");
        if (required.getDataSource() == null) {
            throw new IllegalArgumentException("JDBC datasource is required");
        }
        return new DataSourceTransactionManager(required.getDataSource());
    }

    private record StoredEntry(
            String commandId,
            String commandFingerprint,
            String tenantId,
            String environmentId,
            String attemptId,
            long leaseEpoch,
            String providerId,
            String deploymentId,
            String status,
            long providerSequence,
            String attestationFingerprint,
            String commandJson,
            String descriptorJson,
            String attestationJson,
            Instant preparedAt,
            Instant updatedAt,
            String recordFingerprint) {
    }

    private record ReservedAttempt(
            String attemptId,
            String identityFingerprint,
            String tenantId,
            String environmentId,
            String jobId,
            String requestFingerprint,
            String ownerId,
            long leaseEpoch,
            String runtimeBindingFingerprint,
            String providerId,
            String deploymentId,
            String isolationMode,
            String identityJson,
            Instant reservedAt,
            String recordFingerprint) {
    }

    private record QueueFence(
            String requestFingerprint,
            String status,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt) {
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
