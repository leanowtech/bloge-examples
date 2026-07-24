package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Database-clock Scenario aggregate coordinator with append-only payload-free case progress.
 *
 * <p>The request row is the serialization authority. Claim, checkpoint, release, takeover, and
 * terminal commit lock that row and sample an independent database connection after lock
 * acquisition, avoiding H2's transaction-frozen {@code CURRENT_TIMESTAMP}. Progress JSON is
 * limited to content-addressed {@link ScenarioCaseRehearsalResult}; schema columns deliberately
 * exclude TestSuite input, fixture data, node input/output, and replay payload.</p>
 */
public class DatabaseScenarioRehearsalRunRepository
        implements ScenarioRehearsalRunRepository {
    private static final String CREATE_REQUEST_TABLE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_run_requests (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                compiled_plan_id VARCHAR(512) NOT NULL,
                compiled_plan_revision BIGINT NOT NULL,
                compiled_plan_fingerprint VARCHAR(71) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                total_cases INTEGER NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at VARCHAR(64) NOT NULL,
                next_case_index INTEGER NOT NULL,
                evidence_bundle_fingerprint VARCHAR(71) NOT NULL,
                last_failure_code VARCHAR(256) NOT NULL,
                started_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                retain_until VARCHAR(64) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id
                )
            )
            """;
    private static final String CREATE_PROGRESS_TABLE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_case_progress (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                case_index INTEGER NOT NULL,
                child_request_id VARCHAR(512) NOT NULL,
                result_fingerprint VARCHAR(71) NOT NULL,
                completed_at VARCHAR(64) NOT NULL,
                result_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id, case_index
                ),
                FOREIGN KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id
                ) REFERENCES scenario_rehearsal_run_requests (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id
                )
            )
            """;
    private static final String INSERT_REQUEST = """
            INSERT INTO scenario_rehearsal_run_requests (
                tenant_id, organization_id, project_id, environment_id, region,
                request_id, request_fingerprint, compiled_plan_id,
                compiled_plan_revision, compiled_plan_fingerprint, run_id, total_cases,
                status, lease_owner, lease_epoch, lease_expires_at, next_case_index,
                evidence_bundle_fingerprint, last_failure_code,
                started_at, updated_at, retain_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      'ACTIVE', ?, 1, ?, 0, '', '', ?, ?, ?)
            """;
    private static final String SELECT_REQUEST = """
            SELECT request_id, request_fingerprint, compiled_plan_id,
                   compiled_plan_revision, compiled_plan_fingerprint, run_id,
                   total_cases, status, lease_owner, lease_epoch, lease_expires_at,
                   next_case_index, evidence_bundle_fingerprint, last_failure_code,
                   started_at, updated_at, retain_until
            FROM scenario_rehearsal_run_requests
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
            """;
    private static final String SELECT_REQUEST_FOR_UPDATE =
            SELECT_REQUEST + " FOR UPDATE";
    private static final String TAKE_OVER = """
            UPDATE scenario_rehearsal_run_requests
            SET lease_owner = ?, lease_epoch = ?, lease_expires_at = ?,
                last_failure_code = '', updated_at = ?, retain_until = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_epoch = ?
            """;
    private static final String SELECT_PROGRESS = """
            SELECT case_index, child_request_id, result_fingerprint,
                   completed_at, result_json
            FROM scenario_rehearsal_case_progress
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
            ORDER BY case_index
            """;
    private static final String INSERT_PROGRESS = """
            INSERT INTO scenario_rehearsal_case_progress (
                tenant_id, organization_id, project_id, environment_id, region,
                request_id, case_index, child_request_id, result_fingerprint,
                completed_at, result_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String ADVANCE = """
            UPDATE scenario_rehearsal_run_requests
            SET next_case_index = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ? AND next_case_index = ?
            """;
    private static final String COMPLETE = """
            UPDATE scenario_rehearsal_run_requests
            SET status = 'COMPLETED', evidence_bundle_fingerprint = ?,
                last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ? AND next_case_index = total_cases
            """;
    private static final String RELEASE = """
            UPDATE scenario_rehearsal_run_requests
            SET lease_expires_at = ?, last_failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ScenarioRehearsalLifecycleAuditRepository lifecycleAudit;
    private final Supplier<Instant> coordinationClock;

    /**
     * Creates the production repository using the application database clock.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param lifecycleAudit mandatory payload-free transition audit
     */
    public DatabaseScenarioRehearsalRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ScenarioRehearsalLifecycleAuditRepository lifecycleAudit) {
        this(jdbc, mapper, lifecycleAudit, null);
    }

    /** Package-private deterministic database-clock seam for concurrency tests. */
    DatabaseScenarioRehearsalRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ScenarioRehearsalLifecycleAuditRepository lifecycleAudit,
            Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.lifecycleAudit = Objects.requireNonNull(
                lifecycleAudit, "lifecycleAudit");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
    }

    /** Creates request authority before its progress table. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_REQUEST_TABLE);
        jdbc.execute(CREATE_PROGRESS_TABLE);
    }

    @Override
    @Transactional
    public Claim claim(
            Registration registration,
            String leaseOwner,
            Duration leaseDuration) {
        Objects.requireNonNull(registration, "registration");
        String owner = required(leaseOwner, "leaseOwner");
        Duration duration = positive(
                leaseDuration, "leaseDuration");
        Optional<State> existing = locked(
                registration.scope(), registration.requestId());
        Instant observedAt = coordinationNow();
        requireFutureRetention(registration, observedAt);
        Instant expiresAt = observedAt.plus(duration);
        if (existing.isEmpty()) {
            try {
                insert(registration, owner, observedAt, expiresAt);
                State created = locked(
                        registration.scope(),
                        registration.requestId()).orElseThrow();
                appendLifecycle(
                        created,
                        ScenarioRehearsalLifecycleAuditEvent
                                .Transition.CLAIMED,
                        -1, "", "", "");
                return acquired(created);
            } catch (DuplicateKeyException concurrentInsert) {
                existing = locked(
                        registration.scope(), registration.requestId());
                if (existing.isEmpty()) {
                    throw concurrentInsert;
                }
                observedAt = coordinationNow();
                requireFutureRetention(registration, observedAt);
                expiresAt = observedAt.plus(duration);
            }
        }

        State state = existing.orElseThrow();
        requireSameRegistration(state.registration(), registration);
        if (state.status() == Status.COMPLETED) {
            return new Claim(Outcome.COMPLETED, state, null, 0);
        }
        if (state.leaseExpiresAt().isAfter(observedAt)) {
            return new Claim(
                    Outcome.IN_PROGRESS, state, null,
                    retryAfterSeconds(
                            observedAt, state.leaseExpiresAt()));
        }
        long nextEpoch = Math.addExact(state.leaseEpoch(), 1);
        CapabilitySnapshot.Scope scope = registration.scope();
        int updated = jdbc.update(
                TAKE_OVER,
                owner,
                nextEpoch,
                expiresAt.toString(),
                observedAt.toString(),
                later(
                        state.registration().retainUntil(),
                        registration.retainUntil()).toString(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                registration.requestId(),
                state.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario rehearsal lease changed while row was locked");
        }
        State acquired = locked(
                scope, registration.requestId()).orElseThrow();
        appendLifecycle(
                acquired,
                ScenarioRehearsalLifecycleAuditEvent
                        .Transition.TAKEN_OVER,
                -1, "", "", "");
        return acquired(acquired);
    }

    @Override
    @Transactional
    public List<ScenarioCaseRehearsalResult> progress(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        State state = requireLiveLocked(lease);
        List<ScenarioCaseRehearsalResult> results =
                readProgress(lease.scope(), lease.requestId());
        if (results.size() != state.nextCaseIndex()) {
            throw new IllegalStateException(
                    "Scenario rehearsal progress differs from its durable cursor");
        }
        return results;
    }

    @Override
    @Transactional
    public void checkpoint(
            Lease lease, ScenarioCaseRehearsalResult result) {
        Objects.requireNonNull(lease, "lease");
        ScenarioCaseRehearsalResult exact =
                Objects.requireNonNull(result, "result");
        ScenarioRehearsalResultIntegrity.verifyCase(mapper, exact);
        State state = requireLiveLocked(lease);
        int caseIndex = state.nextCaseIndex();
        if (caseIndex >= state.registration().totalCases()
                || exact.caseIndex() != caseIndex
                || !childRequestId(
                lease.requestId(), caseIndex).equals(
                exact.childRequestId())) {
            throw new IllegalArgumentException(
                    "Scenario checkpoint is not the exact next aggregate case");
        }
        CapabilitySnapshot.Scope scope = lease.scope();
        try {
            jdbc.update(
                    INSERT_PROGRESS,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    lease.requestId(),
                    caseIndex,
                    exact.childRequestId(),
                    exact.resultFingerprint(),
                    exact.completedAt().toString(),
                    serialize(exact));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "Scenario progress already contains the next case",
                    duplicate);
        }
        Instant at = coordinationNow();
        if (!at.isBefore(state.leaseExpiresAt())) {
            throw new ScenarioRehearsalLeaseLostException();
        }
        int advanced = jdbc.update(
                ADVANCE,
                caseIndex + 1,
                at.toString(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                lease.requestId(),
                lease.leaseOwner(),
                lease.leaseEpoch(),
                state.leaseExpiresAt().toString(),
                caseIndex);
        if (advanced != 1) {
            throw new ScenarioRehearsalLeaseLostException();
        }
        appendLifecycle(
                state,
                ScenarioRehearsalLifecycleAuditEvent
                        .Transition.CHECKPOINTED,
                caseIndex, exact.resultFingerprint(), "", "");
    }

    @Override
    @Transactional
    public boolean complete(
            Lease lease, String evidenceBundleFingerprint) {
        Objects.requireNonNull(lease, "lease");
        String fingerprint = required(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        State state = locked(
                lease.scope(), lease.requestId()).orElse(null);
        Instant at = coordinationNow();
        if (state == null
                || state.status() != Status.ACTIVE
                || !state.leaseOwner().equals(lease.leaseOwner())
                || state.leaseEpoch() != lease.leaseEpoch()
                || state.nextCaseIndex()
                != state.registration().totalCases()
                || !at.isBefore(state.leaseExpiresAt())) {
            return false;
        }
        CapabilitySnapshot.Scope scope = lease.scope();
        int completed = jdbc.update(
                COMPLETE,
                fingerprint,
                at.toString(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                lease.requestId(),
                lease.leaseOwner(),
                lease.leaseEpoch(),
                state.leaseExpiresAt().toString());
        if (completed == 1) {
            appendLifecycle(
                    state,
                    ScenarioRehearsalLifecycleAuditEvent
                            .Transition.COMPLETED,
                    -1, "", fingerprint, "");
        }
        return completed == 1;
    }

    @Override
    @Transactional
    public boolean release(Lease lease, String failureCode) {
        Objects.requireNonNull(lease, "lease");
        State state = locked(
                lease.scope(), lease.requestId()).orElse(null);
        if (state == null
                || state.status() != Status.ACTIVE
                || !state.leaseOwner().equals(lease.leaseOwner())
                || state.leaseEpoch() != lease.leaseEpoch()) {
            return false;
        }
        Instant at = coordinationNow();
        CapabilitySnapshot.Scope scope = lease.scope();
        String reasonCode = bounded(failureCode, 256);
        int released = jdbc.update(
                RELEASE,
                at.toString(),
                reasonCode,
                at.toString(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                lease.requestId(),
                lease.leaseOwner(),
                lease.leaseEpoch(),
                state.leaseExpiresAt().toString());
        if (released == 1) {
            appendLifecycle(
                    state,
                    ScenarioRehearsalLifecycleAuditEvent
                            .Transition.RELEASED,
                    -1, "", "", reasonCode);
        }
        return released == 1;
    }

    @Override
    public Optional<State> find(
            CapabilitySnapshot.Scope scope, String requestId) {
        Objects.requireNonNull(scope, "scope");
        return query(
                SELECT_REQUEST, scope,
                required(requestId, "requestId"));
    }

    private State requireLiveLocked(Lease lease) {
        State state = locked(
                lease.scope(), lease.requestId()).orElseThrow(
                ScenarioRehearsalLeaseLostException::new);
        Instant at = coordinationNow();
        if (state.status() != Status.ACTIVE
                || !state.leaseOwner().equals(lease.leaseOwner())
                || state.leaseEpoch() != lease.leaseEpoch()
                || !at.isBefore(state.leaseExpiresAt())) {
            throw new ScenarioRehearsalLeaseLostException();
        }
        return state;
    }

    private Optional<State> locked(
            CapabilitySnapshot.Scope scope, String requestId) {
        return query(SELECT_REQUEST_FOR_UPDATE, scope, requestId);
    }

    private Optional<State> query(
            String sql,
            CapabilitySnapshot.Scope scope,
            String requestId) {
        List<State> states = jdbc.query(
                sql,
                (rs, rowNumber) -> state(rs, scope),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                requestId);
        return states.stream().findFirst();
    }

    private static State state(
            ResultSet rs,
            CapabilitySnapshot.Scope scope) throws SQLException {
        MirrorArtifactRef plan = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                rs.getString("compiled_plan_id"),
                rs.getLong("compiled_plan_revision"),
                rs.getString("compiled_plan_fingerprint"));
        Registration registration = new Registration(
                scope,
                rs.getString("request_id"),
                rs.getString("request_fingerprint"),
                plan,
                rs.getString("run_id"),
                rs.getInt("total_cases"),
                Instant.parse(rs.getString("retain_until")));
        return new State(
                registration,
                Status.valueOf(rs.getString("status")),
                rs.getString("lease_owner"),
                rs.getLong("lease_epoch"),
                Instant.parse(rs.getString("lease_expires_at")),
                rs.getInt("next_case_index"),
                rs.getString("evidence_bundle_fingerprint"),
                rs.getString("last_failure_code"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private void insert(
            Registration registration,
            String owner,
            Instant now,
            Instant expiresAt) {
        CapabilitySnapshot.Scope scope = registration.scope();
        MirrorArtifactRef plan = registration.compiledPlanRef();
        jdbc.update(
                INSERT_REQUEST,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                registration.requestId(),
                registration.requestFingerprint(),
                plan.id(),
                plan.revision(),
                plan.fingerprint(),
                registration.runId(),
                registration.totalCases(),
                owner,
                expiresAt.toString(),
                now.toString(),
                now.toString(),
                registration.retainUntil().toString());
    }

    private void appendLifecycle(
            State state,
            ScenarioRehearsalLifecycleAuditEvent.Transition transition,
            int caseIndex,
            String resultFingerprint,
            String evidenceBundleFingerprint,
            String reasonCode) {
        Registration registration = state.registration();
        int nextCaseIndex =
                transition
                        == ScenarioRehearsalLifecycleAuditEvent
                        .Transition.CHECKPOINTED
                        ? caseIndex + 1
                        : state.nextCaseIndex();
        lifecycleAudit.append(
                new ScenarioRehearsalLifecycleAuditEvent(
                        0, null, registration.scope(),
                        registration.requestId(),
                        registration.compiledPlanRef(),
                        registration.runId(), transition,
                        state.leaseOwner(), state.leaseEpoch(),
                        registration.totalCases(), caseIndex,
                        nextCaseIndex, resultFingerprint,
                        evidenceBundleFingerprint, reasonCode));
    }

    private List<ScenarioCaseRehearsalResult> readProgress(
            CapabilitySnapshot.Scope scope, String requestId) {
        List<ScenarioCaseRehearsalResult> values = jdbc.query(
                SELECT_PROGRESS,
                (rs, rowNumber) -> deserialize(
                        rs.getString("result_json"),
                        rowNumber,
                        requestId,
                        rs.getInt("case_index"),
                        rs.getString("child_request_id"),
                        rs.getString("result_fingerprint"),
                        rs.getString("completed_at")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                requestId);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).caseIndex() != index) {
                throw new IllegalStateException(
                        "Scenario rehearsal progress is not a contiguous prefix");
            }
        }
        return List.copyOf(values);
    }

    private ScenarioCaseRehearsalResult deserialize(
            String json,
            int rowNumber,
            String requestId,
            int expectedIndex,
            String expectedChildRequestId,
            String expectedFingerprint,
            String expectedCompletedAt) {
        try {
            ScenarioCaseRehearsalResult result = mapper.readValue(
                    json, ScenarioCaseRehearsalResult.class);
            ScenarioRehearsalResultIntegrity.verifyCase(mapper, result);
            if (rowNumber != expectedIndex
                    || result.caseIndex() != expectedIndex
                    || !childRequestId(requestId, expectedIndex).equals(
                    result.childRequestId())
                    || !expectedChildRequestId.equals(
                    result.childRequestId())
                    || !expectedFingerprint.equals(
                    result.resultFingerprint())
                    || !expectedCompletedAt.equals(
                    result.completedAt().toString())) {
                throw new IllegalArgumentException(
                        "Scenario progress index differs from content-addressed JSON");
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Stored Scenario rehearsal progress failed integrity validation",
                    invalid);
        }
    }

    private String serialize(ScenarioCaseRehearsalResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Scenario progress cannot be serialized", failure);
        }
    }

    private static Claim acquired(State state) {
        return new Claim(
                Outcome.ACQUIRED,
                state,
                new Lease(
                        state.registration().scope(),
                        state.registration().requestId(),
                        state.leaseOwner(),
                        state.leaseEpoch()),
                0);
    }

    private static void requireSameRegistration(
            Registration existing, Registration requested) {
        if (!existing.scope().equals(requested.scope())
                || !existing.requestId().equals(requested.requestId())
                || !existing.requestFingerprint().equals(
                requested.requestFingerprint())
                || !existing.compiledPlanRef().equals(
                requested.compiledPlanRef())
                || !existing.runId().equals(requested.runId())
                || existing.totalCases() != requested.totalCases()) {
            throw new ScenarioRehearsalRunRequestConflictException();
        }
    }

    private static void requireFutureRetention(
            Registration registration, Instant observedAt) {
        if (!registration.retainUntil().isAfter(observedAt)) {
            throw new IllegalArgumentException(
                    "retention boundary must be later than database coordination time");
        }
    }

    private static Duration positive(
            Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
        return duration;
    }

    private static long retryAfterSeconds(
            Instant observedAt, Instant leaseExpiresAt) {
        Duration remaining = Duration.between(
                observedAt, leaseExpiresAt);
        long wholeSeconds = remaining.getSeconds();
        return remaining.getNano() == 0
                ? Math.max(1, wholeSeconds)
                : Math.addExact(wholeSeconds, 1);
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static String childRequestId(
            String aggregateRequestId, int caseIndex) {
        return aggregateRequestId + ":case:"
                + String.format("%03d", caseIndex);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum
                ? normalized
                : normalized.substring(0, maximum);
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "Scenario rehearsal database clock returned null");
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        DataSource dataSource = independentDataSource(
                Objects.requireNonNull(
                        jdbc.getDataSource(),
                        "Scenario rehearsal datasource is unavailable"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT CURRENT_TIMESTAMP")) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "Scenario rehearsal database clock returned no row");
            }
            Timestamp value = result.getTimestamp(1);
            if (value == null) {
                throw new IllegalStateException(
                        "Scenario rehearsal database clock returned null");
            }
            return value.toInstant();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Scenario rehearsal database clock is unavailable",
                    unavailable);
        }
    }

    private static DataSource independentDataSource(DataSource source) {
        DataSource current = source;
        while (current instanceof DelegatingDataSource delegating) {
            DataSource target = delegating.getTargetDataSource();
            if (target == null || target == current) {
                break;
            }
            current = target;
        }
        return current;
    }
}
