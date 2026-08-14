package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JDBC authorization-consumption and execution journal for runtime certification.
 *
 * <p>All ownership decisions use database time. A stable nonce lock serializes claims across
 * replicas, while an epoch-fenced run row persists each terminal scenario before another fault is
 * allowed. The canonical stored-state fingerprint makes direct column or JSON mutation fail
 * closed on read and restart.</p>
 */
public final class DatabaseRuntimeCertificationExecutionJournal
        implements RuntimeCertificationExecutionJournal {
    private static final int MAXIMUM_STATE_BYTES = 4 * 1024 * 1024;
    private static final String CONSUMPTION_KIND =
            "RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION";
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_runtime_certification_authorization_locks (
                authorization_nonce_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (authorization_nonce_fingerprint)
            )
            """;
    private static final String CREATE_RUNS = """
            CREATE TABLE IF NOT EXISTS mirror_runtime_certification_runs (
                run_id VARCHAR(512) NOT NULL,
                manifest_fingerprint VARCHAR(71) NOT NULL,
                authorization_fingerprint VARCHAR(71) NOT NULL,
                authorization_nonce_fingerprint VARCHAR(71) NOT NULL,
                environment_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                owner_id VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                scenario_count INTEGER NOT NULL,
                state_fingerprint VARCHAR(71) NOT NULL,
                state_json TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (run_id),
                UNIQUE (authorization_fingerprint),
                UNIQUE (authorization_nonce_fingerprint)
            )
            """;
    private static final String CREATE_STATUS_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_runtime_certification_status
            ON mirror_runtime_certification_runs (status, lease_expires_at, run_id)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;
    private final TransactionTemplate lockInitialization;

    /** Creates a production journal using the deployment database clock. */
    public DatabaseRuntimeCertificationExecutionJournal(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, transactionManager, null);
    }

    /** Deterministic database-clock seam for lease tests. */
    DatabaseRuntimeCertificationExecutionJournal(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.coordinationClock = coordinationClock;
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = transaction(manager, TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        reads = transaction(manager, TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        lockInitialization = transaction(manager, TransactionDefinition.PROPAGATION_NESTED,
                TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    /** Creates additive tables and verifies all previously persisted run state. */
    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_RUNS);
        jdbc.execute(CREATE_STATUS_INDEX);
        List<Stored> runs = requireResult(reads.execute(ignored -> jdbc.query(
                "SELECT * FROM mirror_runtime_certification_runs",
                (rs, row) -> decode(rs))), "runtime certification scan returned null");
        runs.forEach(this::verifyStored);
    }

    @Override
    public Claim claimOrResume(
            RunIdentity identity,
            String ownerId,
            Duration leaseDuration,
            Instant ignoredCallerTime) {
        RunIdentity exact = Objects.requireNonNull(identity, "identity");
        String owner = RegionalDataPlaneDeploymentContract.identifier(ownerId, "ownerId");
        Duration duration = leaseDuration(leaseDuration);
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            lockAuthorization(exact.authorizationNonceFingerprint());
            Optional<Stored> byNonce = findByNonce(
                    exact.authorizationNonceFingerprint(), true);
            Optional<Stored> byAuthorization = findByAuthorization(
                    exact.authorizationRef().fingerprint(), true);
            Optional<Stored> byRun = findByRun(exact.runId(), true);
            Optional<Stored> existing = first(byNonce, byAuthorization, byRun);
            if (existing.isEmpty()) {
                MirrorArtifactRef consumption = consumptionRef(exact);
                Instant expiresAt = now.plus(duration);
                StoredState state = new StoredState(
                        exact, consumption, List.of(), null, now, now);
                insert(state, Status.RUNNING, owner, 1, expiresAt);
                return activeClaim(ClaimStatus.ACQUIRED, exact.runId(), owner, 1,
                        expiresAt, state, "ACQUIRED");
            }
            Stored stored = existing.orElseThrow();
            verifyStored(stored);
            if (!stored.state().identity().equals(exact)
                    || byNonce.filter(value -> !value.runId().equals(stored.runId())).isPresent()
                    || byAuthorization.filter(value -> !value.runId()
                    .equals(stored.runId())).isPresent()
                    || byRun.filter(value -> !value.runId().equals(stored.runId())).isPresent()) {
                return conflict("IDENTITY_CONFLICT");
            }
            if (stored.status() == Status.COMPLETED) {
                return new Claim(ClaimStatus.COMPLETED, null,
                        stored.state().authorizationConsumptionRef(),
                        stored.state().scenarioResults(), stored.state().completedReport(),
                        "EXACT_REPLAY");
            }
            if (now.isBefore(stored.leaseExpiresAt())) {
                return conflict("LEASE_HELD");
            }
            long epoch = Math.addExact(stored.leaseEpoch(), 1);
            Instant expiresAt = now.plus(duration);
            StoredState resumed = stored.state().touch(now);
            update(stored, resumed, Status.RUNNING, owner, epoch, expiresAt);
            return activeClaim(ClaimStatus.RESUMED, exact.runId(), owner, epoch,
                    expiresAt, resumed, "RESUMED");
        }), "runtime certification claim returned null");
    }

    @Override
    public Lease heartbeat(Lease lease, Duration leaseDuration, Instant ignoredCallerTime) {
        Lease exact = Objects.requireNonNull(lease, "lease");
        Duration duration = leaseDuration(leaseDuration);
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored stored = requireLease(exact, now);
            Instant expiresAt = now.plus(duration);
            StoredState touched = stored.state().touch(now);
            update(stored, touched, Status.RUNNING, exact.ownerId(), exact.epoch(), expiresAt);
            return new Lease(exact.runId(), exact.ownerId(), exact.epoch(), expiresAt);
        }), "runtime certification heartbeat returned null");
    }

    @Override
    public void appendScenario(Lease lease, RuntimeCertificationReport.ScenarioResult result) {
        Lease exact = Objects.requireNonNull(lease, "lease");
        RuntimeCertificationReport.ScenarioResult terminal = Objects.requireNonNull(
                result, "result");
        mutations.executeWithoutResult(ignored -> {
            Instant now = dbNow();
            Stored stored = requireLease(exact, now);
            List<RuntimeCertificationReport.ScenarioResult> previous =
                    stored.state().scenarioResults();
            if (previous.size() >= RuntimeCertificationManifest.Scenario.values().length
                    || terminal.scenario()
                    != RuntimeCertificationManifest.Scenario.values()[previous.size()]) {
                throw new IllegalStateException(
                        "runtime certification scenario journal is not an exact prefix");
            }
            List<RuntimeCertificationReport.ScenarioResult> appended =
                    new ArrayList<>(previous);
            appended.add(terminal);
            StoredState next = stored.state().withResults(appended, now);
            update(stored, next, Status.RUNNING, exact.ownerId(), exact.epoch(),
                    exact.expiresAt());
        });
    }

    @Override
    public void complete(Lease lease, RuntimeCertificationReport report) {
        Lease exact = Objects.requireNonNull(lease, "lease");
        RuntimeCertificationReport completed = Objects.requireNonNull(report, "report");
        mutations.executeWithoutResult(ignored -> {
            Instant now = dbNow();
            Stored stored = requireLease(exact, now);
            StoredState state = stored.state();
            RunIdentity identity = state.identity();
            if (state.scenarioResults().size()
                    != RuntimeCertificationManifest.Scenario.values().length
                    || !state.scenarioResults().equals(completed.scenarioResults())
                    || !identity.runId().equals(completed.reportId())
                    || !identity.manifestRef().equals(completed.manifestRef())
                    || !identity.authorizationRef().equals(completed.authorizationRef())
                    || !identity.environmentFingerprint().equals(
                    completed.environmentFingerprint())
                    || !state.authorizationConsumptionRef().equals(
                    completed.authorizationConsumptionRef())) {
                throw new IllegalStateException(
                        "runtime certification report does not close the journal");
            }
            StoredState terminal = state.complete(completed, now);
            update(stored, terminal, Status.COMPLETED, "", exact.epoch(), Instant.EPOCH);
        });
    }

    private Stored requireLease(Lease lease, Instant now) {
        Stored stored = findByRun(lease.runId(), true)
                .orElseThrow(() -> new LeaseLostException("runtime certification run not found"));
        verifyStored(stored);
        if (stored.status() != Status.RUNNING
                || !stored.ownerId().equals(lease.ownerId())
                || stored.leaseEpoch() != lease.epoch()
                || !stored.leaseExpiresAt().equals(lease.expiresAt())
                || !now.isBefore(stored.leaseExpiresAt())) {
            throw new LeaseLostException("runtime certification lease lost");
        }
        return stored;
    }

    private void lockAuthorization(String nonceFingerprint) {
        try {
            lockInitialization.executeWithoutResult(ignored -> jdbc.update("""
                    INSERT INTO mirror_runtime_certification_authorization_locks (
                        authorization_nonce_fingerprint
                    ) VALUES (?)
                    """, nonceFingerprint));
        } catch (DuplicateKeyException ignored) {
            // A concurrent replica already established the stable nonce lock row.
        }
        jdbc.queryForObject("""
                SELECT authorization_nonce_fingerprint
                FROM mirror_runtime_certification_authorization_locks
                WHERE authorization_nonce_fingerprint = ? FOR UPDATE
                """, String.class, nonceFingerprint);
    }

    private Optional<Stored> findByRun(String runId, boolean forUpdate) {
        return find("run_id = ?", runId, forUpdate);
    }

    private Optional<Stored> findByNonce(String fingerprint, boolean forUpdate) {
        return find("authorization_nonce_fingerprint = ?", fingerprint, forUpdate);
    }

    private Optional<Stored> findByAuthorization(String fingerprint, boolean forUpdate) {
        return find("authorization_fingerprint = ?", fingerprint, forUpdate);
    }

    private Optional<Stored> find(String predicate, String value, boolean forUpdate) {
        List<Stored> values = jdbc.query("SELECT * FROM mirror_runtime_certification_runs WHERE "
                        + predicate + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> decode(rs), value);
        return values.stream().findFirst();
    }

    private void insert(
            StoredState state,
            Status status,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt) {
        Encoded encoded = encodeState(state);
        RunIdentity identity = state.identity();
        jdbc.update("""
                INSERT INTO mirror_runtime_certification_runs (
                    run_id, manifest_fingerprint, authorization_fingerprint,
                    authorization_nonce_fingerprint, environment_fingerprint,
                    status, owner_id, lease_epoch, lease_expires_at, scenario_count,
                    state_fingerprint, state_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, identity.runId(), identity.manifestRef().fingerprint(),
                identity.authorizationRef().fingerprint(),
                identity.authorizationNonceFingerprint(), identity.environmentFingerprint(),
                status.name(), ownerId, leaseEpoch, Timestamp.from(leaseExpiresAt),
                state.scenarioResults().size(), encoded.fingerprint(), encoded.json(),
                Timestamp.from(state.createdAt()), Timestamp.from(state.updatedAt()));
    }

    private void update(
            Stored previous,
            StoredState state,
            Status status,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt) {
        Encoded encoded = encodeState(state);
        int updated = jdbc.update("""
                UPDATE mirror_runtime_certification_runs
                SET status = ?, owner_id = ?, lease_epoch = ?, lease_expires_at = ?,
                    scenario_count = ?, state_fingerprint = ?, state_json = ?, updated_at = ?
                WHERE run_id = ? AND status = ? AND owner_id = ? AND lease_epoch = ?
                  AND lease_expires_at = ? AND state_fingerprint = ?
                """, status.name(), ownerId, leaseEpoch, Timestamp.from(leaseExpiresAt),
                state.scenarioResults().size(), encoded.fingerprint(), encoded.json(),
                Timestamp.from(state.updatedAt()), previous.runId(), previous.status().name(),
                previous.ownerId(), previous.leaseEpoch(),
                Timestamp.from(previous.leaseExpiresAt()), previous.stateFingerprint());
        if (updated != 1) {
            throw new LeaseLostException("runtime certification journal compare-and-set failed");
        }
    }

    private Stored decode(ResultSet rs) throws SQLException {
        StoredState state = decode(rs.getString("state_json"), StoredState.class);
        return new Stored(
                rs.getString("run_id"), rs.getString("manifest_fingerprint"),
                rs.getString("authorization_fingerprint"),
                rs.getString("authorization_nonce_fingerprint"),
                rs.getString("environment_fingerprint"),
                Status.valueOf(rs.getString("status")), rs.getString("owner_id"),
                rs.getLong("lease_epoch"), rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getInt("scenario_count"), rs.getString("state_fingerprint"), state,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private void verifyStored(Stored stored) {
        StoredState state = stored.state();
        RunIdentity identity = state.identity();
        boolean completed = stored.status() == Status.COMPLETED;
        if (!stored.runId().equals(identity.runId())
                || !stored.manifestFingerprint().equals(identity.manifestRef().fingerprint())
                || !stored.authorizationFingerprint().equals(
                identity.authorizationRef().fingerprint())
                || !stored.authorizationNonceFingerprint().equals(
                identity.authorizationNonceFingerprint())
                || !stored.environmentFingerprint().equals(identity.environmentFingerprint())
                || stored.scenarioCount() != state.scenarioResults().size()
                || !stored.stateFingerprint().equals(encodeState(state).fingerprint())
                || !stored.createdAt().equals(state.createdAt())
                || !stored.updatedAt().equals(state.updatedAt())
                || completed != (state.completedReport() != null)
                || completed && (!stored.ownerId().isEmpty()
                || !Instant.EPOCH.equals(stored.leaseExpiresAt()))
                || !completed && (stored.ownerId().isEmpty()
                || stored.leaseEpoch() < 1)) {
            throw new IllegalStateException("runtime certification journal storage is invalid");
        }
    }

    private MirrorArtifactRef consumptionRef(RunIdentity identity) {
        ConsumptionMaterial material = new ConsumptionMaterial(
                identity.runId(), identity.manifestRef(), identity.authorizationRef(),
                identity.authorizationNonceFingerprint(), identity.environmentFingerprint());
        return new MirrorArtifactRef(CONSUMPTION_KIND,
                identity.authorizationRef().id() + ":" + identity.runId(), 1,
                ProtocolFingerprint.of(mapper, material));
    }

    private Encoded encodeState(StoredState state) {
        String json = encode(state);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_STATE_BYTES) {
            throw new IllegalStateException("runtime certification journal state is too large");
        }
        return new Encoded(ProtocolFingerprint.of(mapper, state), json);
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "runtime certification journal cannot encode state", failure);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "runtime certification journal cannot decode state", failure);
        }
    }

    private Instant dbNow() {
        if (coordinationClock != null) {
            return Objects.requireNonNull(coordinationClock.get(), "coordinationClock");
        }
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private static Claim activeClaim(
            ClaimStatus status,
            String runId,
            String ownerId,
            long epoch,
            Instant expiresAt,
            StoredState state,
            String reason) {
        return new Claim(status, new Lease(runId, ownerId, epoch, expiresAt),
                state.authorizationConsumptionRef(), state.scenarioResults(), null, reason);
    }

    private static Claim conflict(String reason) {
        return new Claim(ClaimStatus.CONFLICT, null, null, List.of(), null, reason);
    }

    @SafeVarargs
    private static Optional<Stored> first(Optional<Stored>... values) {
        for (Optional<Stored> value : values) {
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Duration leaseDuration(Duration value) {
        Duration exact = Objects.requireNonNull(value, "leaseDuration");
        if (exact.isNegative() || exact.isZero() || exact.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "runtime certification lease must be within (0, 24h]");
        }
        return exact;
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager,
            int propagation,
            int isolation,
            boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(propagation);
        template.setIsolationLevel(isolation);
        template.setReadOnly(readOnly);
        return template;
    }

    private static <T> T requireResult(T value, String message) {
        return Objects.requireNonNull(value, message);
    }

    private enum Status {
        RUNNING,
        COMPLETED
    }

    private record ConsumptionMaterial(
            String runId,
            MirrorArtifactRef manifestRef,
            MirrorArtifactRef authorizationRef,
            String authorizationNonceFingerprint,
            String environmentFingerprint
    ) {
    }

    private record StoredState(
            RunIdentity identity,
            MirrorArtifactRef authorizationConsumptionRef,
            List<RuntimeCertificationReport.ScenarioResult> scenarioResults,
            RuntimeCertificationReport completedReport,
            Instant createdAt,
            Instant updatedAt
    ) {
        private StoredState {
            identity = Objects.requireNonNull(identity, "identity");
            authorizationConsumptionRef =
                    RuntimeCertificationExecutionAuthorization.requireKind(
                            authorizationConsumptionRef, CONSUMPTION_KIND,
                            "authorizationConsumptionRef");
            scenarioResults = scenarioResults == null
                    ? List.of() : List.copyOf(scenarioResults);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)
                    || scenarioResults.size()
                    > RuntimeCertificationManifest.Scenario.values().length) {
                throw new IllegalArgumentException(
                        "runtime certification stored state is invalid");
            }
        }

        private StoredState touch(Instant now) {
            return new StoredState(identity, authorizationConsumptionRef,
                    scenarioResults, completedReport, createdAt, now);
        }

        private StoredState withResults(
                List<RuntimeCertificationReport.ScenarioResult> results,
                Instant now) {
            return new StoredState(identity, authorizationConsumptionRef,
                    results, null, createdAt, now);
        }

        private StoredState complete(RuntimeCertificationReport report, Instant now) {
            return new StoredState(identity, authorizationConsumptionRef,
                    scenarioResults, report, createdAt, now);
        }
    }

    private record Stored(
            String runId,
            String manifestFingerprint,
            String authorizationFingerprint,
            String authorizationNonceFingerprint,
            String environmentFingerprint,
            Status status,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            int scenarioCount,
            String stateFingerprint,
            StoredState state,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record Encoded(String fingerprint, String json) {
    }
}
