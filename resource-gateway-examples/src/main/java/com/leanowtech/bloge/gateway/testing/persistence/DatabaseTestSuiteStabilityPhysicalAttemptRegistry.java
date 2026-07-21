package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRegistry;
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
 * Database-time physical-attempt reservation authority bound to the durable job queue.
 *
 * <p>Reservation locks the exact queue row, asks the queue repository to integrity-verify the
 * complete retained job, validates the live owner/epoch/deadline fence, and then serializes one
 * physical identity per lease epoch. The supplied queue repository and transaction manager must
 * use the same datasource as the JDBC facade so the validation and insert share one transaction.</p>
 *
 * <p>The table contains no fixture, business payload, credential, process id, or provider
 * diagnostic. A retained reservation authorizes a later isolated dispatch but does not claim that
 * dispatch occurred.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptRegistry
        implements TestSuiteStabilityPhysicalAttemptRegistry {

    private static final String STORED_ENTRY_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptStoredEntry.v1";
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityJobRepository jobs;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a registry using a local transaction manager for the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param jobs integrity-verifying queue repository over the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptRegistry(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRepository jobs) {
        this(jdbc, objectMapper, jobs, localTransactionManager(jdbc));
    }

    /**
     * Creates a registry with a caller-supplied transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param jobs integrity-verifying queue repository over the same datasource
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptRegistry(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRepository jobs,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
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

    /** Creates payload-free fence-lock and physical-attempt reservation tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_physical_attempt_locks (
                    fence_fingerprint VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_stability_physical_attempts (
                    attempt_id VARCHAR(96) PRIMARY KEY,
                    identity_fingerprint VARCHAR(71) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    job_id VARCHAR(96) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    runtime_binding_fingerprint VARCHAR(71) NOT NULL,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    isolation_mode VARCHAR(32) NOT NULL,
                    identity_json CLOB NOT NULL,
                    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_physical_attempt_fence
                        UNIQUE (tenant_id, environment_id, job_id, lease_epoch)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_stability_physical_attempt_scope
                ON rg_test_stability_physical_attempts (
                    tenant_id, environment_id, reserved_at, attempt_id
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Reservation reserve(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        TestSuiteStabilityPhysicalAttemptIdentity requiredIdentity =
                Objects.requireNonNull(identity, "identity");
        Reservation result = mutations.execute(status -> {
            validateIdentity(requiredIdentity);
            lockQueueJob(requiredIdentity);
            Instant now = currentTime();
            validateLiveQueueFence(requiredIdentity, now);
            lockAttemptFence(requiredIdentity);

            StoredEntry retainedById = entry(requiredIdentity.attemptId());
            if (retainedById != null) {
                Entry retained = validateEntry(retainedById);
                if (retained.identity().equals(requiredIdentity)) {
                    return new Reservation(ReservationStatus.REPLAYED, retained);
                }
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }
            StoredEntry retainedByFence = entryByFence(requiredIdentity);
            if (retainedByFence != null) {
                validateEntry(retainedByFence);
                throw conflict(ConflictReason.FENCE_CONFLICT);
            }

            StoredEntry reserved = stored(requiredIdentity, now);
            insert(reserved);
            return new Reservation(ReservationStatus.RESERVED,
                    validateEntry(requireEntry(requiredIdentity.attemptId())));
        });
        return Objects.requireNonNull(result, "physical-attempt reservation");
    }

    /** {@inheritDoc} */
    @Override
    public void authorizeDispatch(String attemptId) {
        String exactAttemptId = requireAttemptId(attemptId);
        Boolean authorized = mutations.execute(status -> {
            StoredEntry initial = entry(exactAttemptId);
            if (initial == null) {
                throw conflict(ConflictReason.ATTEMPT_NOT_RESERVED);
            }
            Entry retained = validateEntry(initial);
            lockQueueJob(retained.identity());
            validateLiveQueueFence(retained.identity(), currentTime());
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(authorized)) {
            throw new IllegalStateException(
                    "Physical-attempt dispatch authorization returned no result");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String attemptId) {
        String tenant = requireIdentifier(tenantId, "tenantId");
        String environment = normalized(environmentId);
        String exactAttemptId = requireAttemptId(attemptId);
        if (!Set.of("test", "staging").contains(environment)) {
            throw new IllegalArgumentException("Invalid physical-attempt environment");
        }
        Optional<Entry> result = reads.execute(status -> {
            StoredEntry stored = entry(exactAttemptId);
            if (stored == null) {
                return Optional.empty();
            }
            Entry validated = validateEntry(stored);
            if (!validated.identity().tenantId().equals(tenant)
                    || !validated.identity().environmentId().equals(environment)) {
                return Optional.empty();
            }
            return Optional.of(validated);
        });
        return result == null ? Optional.empty() : result;
    }

    private void validateIdentity(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        String derived = ProtocolFingerprint.of(objectMapper, identity.canonicalMaterial());
        if (!derived.equals(identity.identityFingerprint())
                || !identity.attemptId().equals("stability-attempt-"
                + derived.substring("sha256:".length()))) {
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private void validateLiveQueueFence(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            Instant now) {
        TestSuiteStabilityJobRecord job = jobs.find(
                identity.tenantId(), identity.environmentId(), identity.jobId())
                .orElseThrow(() -> conflict(ConflictReason.LEASE_NOT_ACTIVE));
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
            throw conflict(ConflictReason.LEASE_NOT_ACTIVE);
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
            throw conflict(ConflictReason.LEASE_NOT_ACTIVE);
        }
    }

    private void lockQueueJob(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        List<String> rows = jdbc.queryForList("""
                SELECT job_id FROM rg_test_suite_stability_jobs
                WHERE tenant_id = ? AND environment_id = ? AND job_id = ?
                FOR UPDATE
                """, String.class, identity.tenantId(), identity.environmentId(),
                identity.jobId());
        if (rows.size() != 1) {
            throw conflict(ConflictReason.LEASE_NOT_ACTIVE);
        }
    }

    private void lockAttemptFence(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        String fence = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityPhysicalAttemptFence.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "jobId", identity.jobId(),
                "leaseEpoch", identity.leaseEpoch()));
        jdbc.update("""
                MERGE INTO rg_test_stability_physical_attempt_locks (
                    fence_fingerprint
                ) KEY (fence_fingerprint) VALUES (?)
                """, fence);
        jdbc.queryForObject("""
                SELECT fence_fingerprint FROM rg_test_stability_physical_attempt_locks
                WHERE fence_fingerprint = ? FOR UPDATE
                """, String.class, fence);
    }

    private StoredEntry stored(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            Instant reservedAt) {
        String identityJson = encode(identity);
        String fingerprint = entryFingerprint(identity, reservedAt);
        return new StoredEntry(
                identity.attemptId(), identity.identityFingerprint(), identity.tenantId(),
                identity.environmentId(), identity.jobId(), identity.requestFingerprint(),
                identity.ownerId(), identity.leaseEpoch(),
                identity.runtimeBindingFingerprint(), identity.providerId(),
                identity.deploymentId(), identity.isolationMode().name(), identityJson,
                reservedAt, fingerprint);
    }

    private Entry validateEntry(StoredEntry stored) {
        try {
            TestSuiteStabilityPhysicalAttemptIdentity identity = decode(
                    stored.identityJson(), TestSuiteStabilityPhysicalAttemptIdentity.class);
            validateIdentity(identity);
            String expected = entryFingerprint(identity, stored.reservedAt());
            if (!stored.attemptId().equals(identity.attemptId())
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
                    || !stored.recordFingerprint().equals(expected)) {
                throw new IllegalStateException(
                        "Physical-attempt reservation integrity failed");
            }
            return new Entry(Entry.SCHEMA_VERSION, identity, stored.reservedAt(),
                    stored.recordFingerprint());
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException state
                    && "Physical-attempt reservation integrity failed"
                    .equals(state.getMessage())) {
                throw state;
            }
            throw new IllegalStateException(
                    "Physical-attempt reservation integrity failed");
        }
    }

    private String entryFingerprint(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            Instant reservedAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", STORED_ENTRY_SCHEMA);
        material.put("identityFingerprint", identity.identityFingerprint());
        material.put("reservedAt", reservedAt);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private void insert(StoredEntry value) {
        jdbc.update("""
                INSERT INTO rg_test_stability_physical_attempts (
                    attempt_id, identity_fingerprint, tenant_id, environment_id, job_id,
                    request_fingerprint, owner_id, lease_epoch,
                    runtime_binding_fingerprint, provider_id, deployment_id, isolation_mode,
                    identity_json, reserved_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.attemptId(), value.identityFingerprint(), value.tenantId(),
                value.environmentId(), value.jobId(), value.requestFingerprint(),
                value.ownerId(), value.leaseEpoch(), value.runtimeBindingFingerprint(),
                value.providerId(), value.deploymentId(), value.isolationMode(),
                value.identityJson(), Timestamp.from(value.reservedAt()),
                value.recordFingerprint());
    }

    private StoredEntry entry(String attemptId) {
        List<StoredEntry> rows = jdbc.query("""
                SELECT attempt_id, identity_fingerprint, tenant_id, environment_id, job_id,
                       request_fingerprint, owner_id, lease_epoch,
                       runtime_binding_fingerprint, provider_id, deployment_id,
                       isolation_mode, identity_json, reserved_at, record_fingerprint
                FROM rg_test_stability_physical_attempts
                WHERE attempt_id = ?
                """, this::mapEntry, attemptId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Physical-attempt identity is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredEntry entryByFence(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        List<StoredEntry> rows = jdbc.query("""
                SELECT attempt_id, identity_fingerprint, tenant_id, environment_id, job_id,
                       request_fingerprint, owner_id, lease_epoch,
                       runtime_binding_fingerprint, provider_id, deployment_id,
                       isolation_mode, identity_json, reserved_at, record_fingerprint
                FROM rg_test_stability_physical_attempts
                WHERE tenant_id = ? AND environment_id = ? AND job_id = ? AND lease_epoch = ?
                """, this::mapEntry, identity.tenantId(), identity.environmentId(),
                identity.jobId(), identity.leaseEpoch());
        if (rows.size() > 1) {
            throw new IllegalStateException("Physical-attempt fence is not unique");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredEntry requireEntry(String attemptId) {
        StoredEntry stored = entry(attemptId);
        if (stored == null) {
            throw new IllegalStateException("Physical-attempt reservation disappeared");
        }
        return stored;
    }

    private StoredEntry mapEntry(ResultSet resultSet, int row) throws SQLException {
        return new StoredEntry(
                resultSet.getString("attempt_id"),
                resultSet.getString("identity_fingerprint"),
                resultSet.getString("tenant_id"),
                resultSet.getString("environment_id"),
                resultSet.getString("job_id"),
                resultSet.getString("request_fingerprint"),
                resultSet.getString("owner_id"),
                resultSet.getLong("lease_epoch"),
                resultSet.getString("runtime_binding_fingerprint"),
                resultSet.getString("provider_id"),
                resultSet.getString("deployment_id"),
                resultSet.getString("isolation_mode"),
                resultSet.getString("identity_json"),
                resultSet.getTimestamp("reserved_at").toInstant(),
                resultSet.getString("record_fingerprint"));
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Physical-attempt database time is unavailable");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Physical-attempt identity cannot be serialized");
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Physical-attempt identity cannot be deserialized");
        }
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

    private record QueueFence(
            String requestFingerprint,
            String status,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt) {
    }

    private record StoredEntry(
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
}
