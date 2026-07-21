package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationVerifier;
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
import java.util.regex.Pattern;

/**
 * Database-time authority for attempt-cancellation commands and provider receipts.
 *
 * <p>One attempt/lease lock serializes command preparation and terminal receipt acceptance. A
 * separate provider/deployment lock serializes its monotonic receipt sequence. Acceptance verifies
 * the detached attestation inside the transaction using database time, appends an immutable
 * sequence journal, advances the floor, and updates the command record atomically.</p>
 *
 * <p>The store is payload-free: it retains command/provider protocol JSON and opaque commitments,
 * never fixture values, node input/output, credentials, process ids, or provider diagnostics.</p>
 */
public final class DatabaseTestSuiteStabilityAttemptCancellationJournal
        implements TestSuiteStabilityAttemptCancellationJournal {

    private static final String ENTRY_SCHEMA =
            "bloge.testSuiteStabilityAttemptCancellationStoredEntry.v1";
    private static final String FLOOR_SCHEMA =
            "bloge.testSuiteStabilityAttemptCancellationProviderFloor.v1";
    private static final String SEQUENCE_SCHEMA =
            "bloge.testSuiteStabilityAttemptCancellationProviderSequence.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-cancel-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");
    private static final Duration MAXIMUM_CALLER_CLOCK_SKEW = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAttemptCancellationVerifier verifier;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a journal using a local transaction manager for the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param verifier pinned provider trust verifier
     */
    public DatabaseTestSuiteStabilityAttemptCancellationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttemptCancellationVerifier verifier) {
        this(jdbc, objectMapper, verifier, localTransactionManager(jdbc));
    }

    /**
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param verifier pinned provider trust verifier
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityAttemptCancellationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttemptCancellationVerifier verifier,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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

    /** Creates payload-free journal, lock, sequence-floor, and append-only sequence tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_cancel_locks (
                    lock_key VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_cancel_provider_locks (
                    provider_scope_fingerprint VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_cancel_entries (
                    command_id VARCHAR(96) PRIMARY KEY,
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
                    CONSTRAINT uq_rg_test_stability_attempt_cancel_fence
                        UNIQUE (tenant_id, environment_id, attempt_id, lease_epoch)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_stability_attempt_cancel_scope
                ON rg_test_stability_attempt_cancel_entries (
                    tenant_id, environment_id, prepared_at, command_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_cancel_provider_floors (
                    provider_scope_fingerprint VARCHAR(71) PRIMARY KEY,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    command_id VARCHAR(96) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_attempt_cancel_provider_sequences (
                    provider_scope_fingerprint VARCHAR(71) NOT NULL,
                    provider_sequence BIGINT NOT NULL,
                    command_id VARCHAR(96) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (provider_scope_fingerprint, provider_sequence),
                    CONSTRAINT uq_rg_test_stability_attempt_cancel_provider_command
                        UNIQUE (provider_scope_fingerprint, command_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Preparation prepare(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor) {
        TestSuiteStabilityAttemptCancellationCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        Preparation result = mutations.execute(status -> {
            Instant now = currentTime();
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
            validatePreparationTime(requiredCommand, now);
            validateProviderCompatibility(requiredCommand, requiredDescriptor);

            StoredEntry prepared = stored(
                    requiredCommand, requiredDescriptor, Status.PREPARED, null,
                    now, now);
            insertEntry(prepared);
            return new Preparation(PreparationStatus.PREPARED,
                    validateEntry(requireEntry(requiredCommand.commandId())));
        });
        return Objects.requireNonNull(result, "attempt cancellation preparation");
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(
            String commandId,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation) {
        String requiredCommandId = requireCommandId(commandId);
        TestSuiteStabilityAttemptCancellationReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Acceptance result = mutations.execute(status -> {
            StoredEntry initial = entry(requiredCommandId);
            if (initial == null) {
                throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
            }
            Entry initialEntry = validateEntry(initial);
            lockAttempt(initialEntry.command());
            StoredEntry current = requireEntry(requiredCommandId);
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
            if (!now.isBefore(prepared.command().confirmationDeadlineAt())) {
                throw conflict(ConflictReason.COMMAND_EXPIRED);
            }
            TestSuiteStabilityAttemptCancellationReceipt receipt = verifier.verify(
                    prepared.command(), prepared.descriptor(), requiredAttestation, now);
            String providerScope = providerScope(receipt.providerId(), receipt.deploymentId());
            lockProvider(providerScope);
            ProviderFloor floor = providerFloor(providerScope);
            validateProviderFloor(providerScope, floor);
            if (floor != null && receipt.providerSequence() <= floor.providerSequence()) {
                throw conflict(ConflictReason.PROVIDER_SEQUENCE_ROLLBACK);
            }

            appendProviderSequence(providerScope, receipt, requiredCommandId,
                    attestationFingerprint, now);
            persistProviderFloor(providerScope, receipt, requiredCommandId,
                    attestationFingerprint, now);
            Status terminalStatus = receipt.terminationConfirmed()
                    ? Status.CONFIRMED : Status.UNCONFIRMED;
            StoredEntry terminal = stored(
                    prepared.command(), prepared.descriptor(), terminalStatus,
                    requiredAttestation, prepared.preparedAt(), now);
            updateEntry(current, terminal);
            Entry retained = validateEntry(requireEntry(requiredCommandId));
            return new Acceptance(
                    terminalStatus == Status.CONFIRMED
                            ? AcceptanceStatus.CONFIRMED
                            : AcceptanceStatus.UNCONFIRMED,
                    retained);
        });
        return Objects.requireNonNull(result, "attempt cancellation acceptance");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String commandId) {
        String tenant = requireIdentifier(tenantId, "tenantId");
        String environment = normalized(environmentId);
        String exactCommandId = requireCommandId(commandId);
        if (!java.util.Set.of("test", "staging").contains(environment)) {
            throw new IllegalArgumentException("Invalid cancellation journal environment");
        }
        Optional<Entry> result = reads.execute(status -> {
            StoredEntry stored = entry(exactCommandId);
            if (stored == null) {
                return Optional.empty();
            }
            Entry validated = validateEntry(stored);
            if (!validated.command().tenantId().equals(tenant)
                    || !validated.command().environmentId().equals(environment)) {
                return Optional.empty();
            }
            return Optional.of(validated);
        });
        return result == null ? Optional.empty() : result;
    }

    private void validateCommandIdentity(
            TestSuiteStabilityAttemptCancellationCommand command) {
        String derived = ProtocolFingerprint.of(objectMapper, command.canonicalMaterial());
        if (!derived.equals(command.commandFingerprint())
                || !command.commandId().equals("stability-attempt-cancel-"
                + derived.substring("sha256:".length()))) {
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private void validateProviderCompatibility(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor) {
        Duration commandWindow = Duration.between(
                command.requestedAt(), command.confirmationDeadlineAt());
        if (!descriptor.available()
                || descriptor.maximumConfirmationLatency().compareTo(commandWindow) > 0) {
            throw conflict(ConflictReason.PROVIDER_INCOMPATIBLE);
        }
    }

    private void validatePreparationTime(
            TestSuiteStabilityAttemptCancellationCommand command,
            Instant now) {
        if (command.requestedAt().isAfter(now.plus(MAXIMUM_CALLER_CLOCK_SKEW))
                || !now.isBefore(command.confirmationDeadlineAt())) {
            throw conflict(ConflictReason.COMMAND_EXPIRED);
        }
    }

    private StoredEntry stored(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor,
            Status status,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation,
            Instant preparedAt,
            Instant updatedAt) {
        String attestationFingerprint = attestation == null
                ? "" : ProtocolFingerprint.of(objectMapper, attestation);
        long providerSequence = attestation == null
                ? 0 : attestation.receipt().providerSequence();
        String commandJson = encode(command);
        String descriptorJson = encode(descriptor);
        String attestationJson = attestation == null ? "" : encode(attestation);
        String fingerprint = entryFingerprint(
                command, descriptor, status, providerSequence, attestationFingerprint,
                preparedAt, updatedAt);
        return new StoredEntry(
                command.commandId(), command.commandFingerprint(), command.tenantId(),
                command.environmentId(), command.attemptId(), command.leaseEpoch(),
                descriptor.providerId(), descriptor.deploymentId(), status.name(),
                providerSequence, attestationFingerprint, commandJson, descriptorJson,
                attestationJson, preparedAt, updatedAt, fingerprint);
    }

    private Entry validateEntry(StoredEntry stored) {
        try {
            TestSuiteStabilityAttemptCancellationCommand command = decode(
                    stored.commandJson(), TestSuiteStabilityAttemptCancellationCommand.class);
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor = decode(
                    stored.descriptorJson(),
                    TestSuiteStabilityAttemptCancellationAuthority.Descriptor.class);
            Status status = Status.valueOf(stored.status());
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation =
                    stored.attestationJson().isBlank() ? null : decode(
                            stored.attestationJson(),
                            TestSuiteStabilityAttemptCancellationReceipt.Attestation.class);
            String expected = entryFingerprint(
                    command, descriptor, status, stored.providerSequence(),
                    stored.attestationFingerprint(), stored.preparedAt(), stored.updatedAt());
            if (!stored.commandId().equals(command.commandId())
                    || !stored.commandFingerprint().equals(command.commandFingerprint())
                    || !stored.tenantId().equals(command.tenantId())
                    || !stored.environmentId().equals(command.environmentId())
                    || !stored.attemptId().equals(command.attemptId())
                    || stored.leaseEpoch() != command.leaseEpoch()
                    || !stored.providerId().equals(descriptor.providerId())
                    || !stored.deploymentId().equals(descriptor.deploymentId())
                    || !stored.recordFingerprint().equals(expected)
                    || (attestation == null) != stored.attestationFingerprint().isBlank()
                    || attestation != null && (!stored.attestationFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, attestation))
                    || stored.providerSequence()
                    != attestation.receipt().providerSequence())) {
                throw new IllegalStateException(
                        "Attempt cancellation journal entry integrity failed");
            }
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
                    "Attempt cancellation journal entry integrity failed");
        }
    }

    private static boolean knownIntegrityFailure(String message) {
        return java.util.Set.of(
                "Attempt cancellation journal entry integrity failed",
                "Attempt cancellation provider sequence continuity failed",
                "Attempt cancellation provider sequence integrity failed",
                "Attempt cancellation provider floor continuity failed",
                "Attempt cancellation provider floor integrity failed")
                .contains(message);
    }

    private void validateRetainedProviderState(
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation,
            String attestationFingerprint) {
        TestSuiteStabilityAttemptCancellationReceipt receipt = attestation.receipt();
        String providerScope = providerScope(receipt.providerId(), receipt.deploymentId());
        List<ProviderSequence> retained = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_cancel_provider_sequences
                WHERE provider_scope_fingerprint = ? AND command_id = ?
                """, (rs, row) -> new ProviderSequence(
                rs.getString("provider_scope_fingerprint"),
                rs.getLong("provider_sequence"), rs.getString("command_id"),
                rs.getString("attestation_fingerprint"),
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getString("record_fingerprint")), providerScope, receipt.commandId());
        if (retained.size() != 1) {
            throw new IllegalStateException(
                    "Attempt cancellation provider sequence continuity failed");
        }
        ProviderSequence sequence = retained.getFirst();
        String expected = sequenceFingerprint(
                sequence.providerScope(), sequence.providerSequence(), sequence.commandId(),
                sequence.attestationFingerprint(), sequence.acceptedAt());
        if (!sequence.recordFingerprint().equals(expected)
                || sequence.providerSequence() != receipt.providerSequence()
                || !sequence.attestationFingerprint().equals(attestationFingerprint)) {
            throw new IllegalStateException(
                    "Attempt cancellation provider sequence integrity failed");
        }
        validateProviderFloor(providerScope, providerFloor(providerScope));
    }

    private String entryFingerprint(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor,
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
                INSERT INTO rg_test_stability_attempt_cancel_entries (
                    command_id, command_fingerprint, tenant_id, environment_id,
                    attempt_id, lease_epoch, provider_id, deployment_id, status,
                    provider_sequence, attestation_fingerprint, command_json,
                    descriptor_json, attestation_json, prepared_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.commandId(), value.commandFingerprint(), value.tenantId(),
                value.environmentId(), value.attemptId(), value.leaseEpoch(),
                value.providerId(), value.deploymentId(), value.status(),
                value.providerSequence(), value.attestationFingerprint(), value.commandJson(),
                value.descriptorJson(), value.attestationJson(),
                Timestamp.from(value.preparedAt()), Timestamp.from(value.updatedAt()),
                value.recordFingerprint());
    }

    private void updateEntry(StoredEntry previous, StoredEntry value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_cancel_entries
                SET status = ?, provider_sequence = ?, attestation_fingerprint = ?,
                    attestation_json = ?, updated_at = ?, record_fingerprint = ?
                WHERE command_id = ? AND record_fingerprint = ?
                """,
                value.status(), value.providerSequence(), value.attestationFingerprint(),
                value.attestationJson(), Timestamp.from(value.updatedAt()),
                value.recordFingerprint(), previous.commandId(),
                previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Attempt cancellation journal concurrent update failed");
        }
    }

    private StoredEntry entry(String commandId) {
        List<StoredEntry> rows = jdbc.query("""
                SELECT command_id, command_fingerprint, tenant_id, environment_id,
                       attempt_id, lease_epoch, provider_id, deployment_id, status,
                       provider_sequence, attestation_fingerprint, command_json,
                       descriptor_json, attestation_json, prepared_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_cancel_entries
                WHERE command_id = ?
                """, this::mapEntry, commandId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Attempt cancellation command identity is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredEntry entryByFence(
            TestSuiteStabilityAttemptCancellationCommand command) {
        List<StoredEntry> rows = jdbc.query("""
                SELECT command_id, command_fingerprint, tenant_id, environment_id,
                       attempt_id, lease_epoch, provider_id, deployment_id, status,
                       provider_sequence, attestation_fingerprint, command_json,
                       descriptor_json, attestation_json, prepared_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_cancel_entries
                WHERE tenant_id = ? AND environment_id = ?
                  AND attempt_id = ? AND lease_epoch = ?
                """, this::mapEntry, command.tenantId(), command.environmentId(),
                command.attemptId(), command.leaseEpoch());
        if (rows.size() > 1) {
            throw new IllegalStateException("Attempt cancellation fence is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredEntry requireEntry(String commandId) {
        StoredEntry stored = entry(commandId);
        if (stored == null) {
            throw conflict(ConflictReason.COMMAND_NOT_PREPARED);
        }
        return stored;
    }

    private StoredEntry mapEntry(ResultSet resultSet, int row) throws SQLException {
        return new StoredEntry(
                resultSet.getString("command_id"),
                resultSet.getString("command_fingerprint"),
                resultSet.getString("tenant_id"),
                resultSet.getString("environment_id"),
                resultSet.getString("attempt_id"),
                resultSet.getLong("lease_epoch"),
                resultSet.getString("provider_id"),
                resultSet.getString("deployment_id"),
                resultSet.getString("status"),
                resultSet.getLong("provider_sequence"),
                resultSet.getString("attestation_fingerprint"),
                resultSet.getString("command_json"),
                resultSet.getString("descriptor_json"),
                resultSet.getString("attestation_json"),
                resultSet.getTimestamp("prepared_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getString("record_fingerprint"));
    }

    private void lockAttempt(TestSuiteStabilityAttemptCancellationCommand command) {
        String key = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityAttemptCancellationLock.v1",
                "tenantId", command.tenantId(),
                "environmentId", command.environmentId(),
                "attemptId", command.attemptId(),
                "leaseEpoch", command.leaseEpoch()));
        jdbc.update("MERGE INTO rg_test_stability_attempt_cancel_locks (lock_key) KEY (lock_key) VALUES (?)",
                key);
        jdbc.queryForObject("""
                SELECT lock_key FROM rg_test_stability_attempt_cancel_locks
                WHERE lock_key = ? FOR UPDATE
                """, String.class, key);
    }

    private void lockProvider(String providerScope) {
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_cancel_provider_locks (
                    provider_scope_fingerprint
                ) KEY (provider_scope_fingerprint) VALUES (?)
                """, providerScope);
        jdbc.queryForObject("""
                SELECT provider_scope_fingerprint
                FROM rg_test_stability_attempt_cancel_provider_locks
                WHERE provider_scope_fingerprint = ? FOR UPDATE
                """, String.class, providerScope);
    }

    private String providerScope(String providerId, String deploymentId) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityAttemptCancellationProviderScope.v1",
                "providerId", providerId,
                "deploymentId", deploymentId));
    }

    private ProviderFloor providerFloor(String providerScope) {
        List<ProviderFloor> rows = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_id, deployment_id,
                       provider_sequence, command_id, attestation_fingerprint,
                       updated_at, record_fingerprint
                FROM rg_test_stability_attempt_cancel_provider_floors
                WHERE provider_scope_fingerprint = ?
                """, (rs, row) -> new ProviderFloor(
                rs.getString("provider_scope_fingerprint"), rs.getString("provider_id"),
                rs.getString("deployment_id"), rs.getLong("provider_sequence"),
                rs.getString("command_id"), rs.getString("attestation_fingerprint"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint")), providerScope);
        if (rows.size() > 1) {
            throw new IllegalStateException("Attempt cancellation provider floor is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateProviderFloor(String providerScope, ProviderFloor floor) {
        List<ProviderSequence> latest = jdbc.query("""
                SELECT provider_scope_fingerprint, provider_sequence, command_id,
                       attestation_fingerprint, accepted_at, record_fingerprint
                FROM rg_test_stability_attempt_cancel_provider_sequences
                WHERE provider_scope_fingerprint = ?
                ORDER BY provider_sequence DESC
                FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> new ProviderSequence(
                rs.getString("provider_scope_fingerprint"),
                rs.getLong("provider_sequence"), rs.getString("command_id"),
                rs.getString("attestation_fingerprint"),
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getString("record_fingerprint")), providerScope);
        if ((floor == null) != latest.isEmpty()) {
            throw new IllegalStateException(
                    "Attempt cancellation provider floor continuity failed");
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
                    "Attempt cancellation provider floor integrity failed");
        }
    }

    private void appendProviderSequence(
            String providerScope,
            TestSuiteStabilityAttemptCancellationReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant acceptedAt) {
        String fingerprint = sequenceFingerprint(
                providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, acceptedAt);
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_cancel_provider_sequences (
                    provider_scope_fingerprint, provider_sequence, command_id,
                    attestation_fingerprint, accepted_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, providerScope, receipt.providerSequence(), commandId,
                attestationFingerprint, Timestamp.from(acceptedAt), fingerprint);
    }

    private void persistProviderFloor(
            String providerScope,
            TestSuiteStabilityAttemptCancellationReceipt receipt,
            String commandId,
            String attestationFingerprint,
            Instant updatedAt) {
        String fingerprint = floorFingerprint(
                providerScope, receipt.providerId(), receipt.deploymentId(),
                receipt.providerSequence(), commandId, attestationFingerprint, updatedAt);
        jdbc.update("""
                MERGE INTO rg_test_stability_attempt_cancel_provider_floors (
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
        Timestamp value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Attempt cancellation database time is unavailable");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Attempt cancellation protocol cannot be serialized");
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Attempt cancellation protocol cannot be deserialized");
        }
    }

    private static String requireCommandId(String value) {
        String normalized = normalized(value);
        if (!COMMAND_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid cancellation command id");
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
