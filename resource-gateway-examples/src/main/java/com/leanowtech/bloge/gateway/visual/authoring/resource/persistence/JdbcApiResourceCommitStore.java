package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed claim and committed-read seam for API Resource authoring.
 *
 * <p>This J1 slice deliberately leaves stage, commit and fail for the J2
 * transaction implementation. The repository never applies DDL; a later
 * runtime configuration supplies the migration and readiness gate.</p>
 */
public final class JdbcApiResourceCommitStore implements ApiResourceCommitStore {
    private static final String JOURNAL_COLUMNS = "tenant_id, project_id, environment_id, actor_id, endpoint, target_id, "
            + "idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until, "
            + "expected_mode, expected_revision, receipt_schema, receipt_json, receipt_fingerprint, receipt_etag, "
            + "failure_code, created_at, updated_at";
    private static final String READ_JOIN = """
            SELECT h.tenant_id, h.project_id, h.environment_id, h.resource_id, h.revision, h.strong_etag,
                   r.spec_json, r.spec_fingerprint, r.connection_id, r.command_id,
                   p.descriptor_json, p.descriptor_fingerprint, p.descriptor_state,
                   p.design_contract_json, p.design_contract_fingerprint, p.design_contract_state,
                   p.operator_json, p.operator_fingerprint, p.operator_state,
                   j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag
              FROM rg_api_resource_heads h
              JOIN rg_api_resource_revisions r
                ON r.tenant_id = h.tenant_id AND r.project_id = h.project_id
               AND r.environment_id = h.environment_id AND r.resource_id = h.resource_id
               AND r.revision = h.revision AND r.strong_etag = h.strong_etag
               AND r.state = h.revision_state AND r.state = 'COMMITTED'
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id AND p.resource_id = r.resource_id
               AND p.revision = r.revision AND p.descriptor_state = 'READY'
               AND p.design_contract_state = 'READY' AND p.operator_state = 'READY'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.status = 'COMMITTED'
            WHERE h.tenant_id = ? AND h.project_id = ? AND h.environment_id = ? AND h.resource_id = ?
            """;
    private static final String REVISION_READ_JOIN = """
            SELECT r.tenant_id, r.project_id, r.environment_id, r.resource_id, r.revision, r.strong_etag,
                   r.spec_json, r.spec_fingerprint, r.connection_id, r.command_id,
                   p.descriptor_json, p.descriptor_fingerprint, p.descriptor_state,
                   p.design_contract_json, p.design_contract_fingerprint, p.design_contract_state,
                   p.operator_json, p.operator_fingerprint, p.operator_state,
                   j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag
              FROM rg_api_resource_revisions r
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id AND p.resource_id = r.resource_id
               AND p.revision = r.revision AND p.descriptor_state = 'READY'
               AND p.design_contract_state = 'READY' AND p.operator_state = 'READY'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.status = 'COMMITTED'
             WHERE r.tenant_id = ? AND r.project_id = ? AND r.environment_id = ?
               AND r.resource_id = ? AND r.revision = ? AND r.state = 'COMMITTED'
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration leaseDuration;
    private final ApiResourceDecisions decisions;
    @SuppressWarnings("unused")
    private final ApiResourceProjectionCompiler compiler;

    /** Creates the seam with all collaborators needed by the complete store. */
    public JdbcApiResourceCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      ObjectMapper mapper, Clock clock, Duration leaseDuration,
                                      ApiResourceDecisions decisions,
                                      ApiResourceProjectionCompiler compiler) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.clock = Objects.requireNonNull(clock, "clock");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /** Claims a scoped idempotency coordinate, including lease recovery fencing. */
    @Override
    public ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        requireFingerprint(requestFingerprint);
        try {
            return transactions.execute(status -> claimInTransaction(key, requestFingerprint, expectedRevision));
        } catch (DuplicateKeyException duplicate) {
            // A concurrent first insert is the only race not covered by FOR UPDATE on a missing row.
            try {
                return transactions.execute(status -> claimInTransaction(key, requestFingerprint, expectedRevision));
            } catch (ApiResourceCommitStoreException ex) {
                throw ex;
            } catch (DataAccessException ex) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "claim persistence failed");
            }
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "claim persistence failed");
        }
    }

    private ClaimResult claimInTransaction(CommandKey key, String fingerprint, ExpectedRevision expected) {
        JournalRow prior = journalForCoordinate(key, true);
        Instant now = clock.instant();
        if (prior != null && !fingerprint.equals(prior.requestFingerprint())) {
            return new ClaimResult.Conflict("idempotency fingerprint conflict");
        }
        if (prior != null && "COMMITTED".equals(prior.status())) {
            return new ClaimResult.Replay(receipt(prior));
        }
        if (prior != null && "PREPARING".equals(prior.status()) && prior.leaseUntil().isAfter(now)) {
            return new ClaimResult.Busy(prior.leaseUntil());
        }

        boolean resumed = prior != null;
        String commandId = prior == null ? UUID.randomUUID().toString() : prior.commandId();
        int attemptNo = prior == null ? 1 : prior.attemptNo() + 1;
        String attemptToken = UUID.randomUUID().toString();
        OffsetDateTime leaseUntil = OffsetDateTime.ofInstant(now.plus(leaseDuration), ZoneOffset.UTC);
        if (prior == null) {
            jdbc.update("""
                    INSERT INTO rg_authoring_command_journal
                        (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                         command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                         expected_mode, expected_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?, ?, ?)
                    """, key.scope().tenantId(), key.scope().projectId(), key.scope().environmentId(), key.actorId(),
                    key.endpoint().name(), key.targetId(), key.idempotencyKey(), commandId, fingerprint, attemptNo,
                    attemptToken, leaseUntil, expectedMode(expected), expectedRevision(expected),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        } else {
            jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id = ? AND state = 'STAGED'", commandId);
            jdbc.update("""
                    UPDATE rg_authoring_command_journal
                       SET request_fingerprint = ?, status = 'PREPARING', attempt_no = ?, attempt_token = ?,
                           lease_until = ?, expected_mode = ?, expected_revision = ?, receipt_schema = NULL,
                           receipt_json = NULL, receipt_fingerprint = NULL, receipt_etag = NULL, failure_code = NULL,
                           updated_at = ?
                     WHERE command_id = ?
                    """, fingerprint, attemptNo, attemptToken, leaseUntil, expectedMode(expected), expectedRevision(expected),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC), commandId);
        }
        return new ClaimResult.Acquired(new CommandLease(commandId, attemptNo, attemptToken, key, fingerprint,
                now.plus(leaseDuration), expected), resumed);
    }

    /** Reads a visible committed head and all three READY projections. */
    @Override
    public Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceId, "resourceId");
        return read(scope, resourceId, null);
    }

    /** Reads a visible committed exact revision and all three READY projections. */
    @Override
    public Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceId, "resourceId");
        if (revision < 1) return Optional.empty();
        return read(scope, resourceId, revision);
    }

    private Optional<StoredApiResource> read(AuthoringScope scope, String resourceId, Long revision) {
        String sql = revision == null ? READ_JOIN : REVISION_READ_JOIN;
        Object[] args = revision == null
                ? new Object[]{scope.tenantId(), scope.projectId(), scope.environmentId(), resourceId}
                : new Object[]{scope.tenantId(), scope.projectId(), scope.environmentId(), resourceId, revision};
        try {
            List<StoredRow> rows = jdbc.query(sql, storedRowMapper(), args);
            return rows.stream().findFirst().map(this::stored);
        } catch (DataAccessException ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "read persistence failed");
        }
    }

    private JournalRow journalForCoordinate(CommandKey key, boolean forUpdate) {
        String sql = "SELECT " + JOURNAL_COLUMNS + " FROM rg_authoring_command_journal "
                + "WHERE tenant_id = ? AND project_id = ? AND environment_id = ? AND actor_id = ? "
                + "AND endpoint = ? AND target_id = ? AND idempotency_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try {
            return jdbc.query(sql, journalRowMapper(), key.scope().tenantId(), key.scope().projectId(),
                    key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                    key.idempotencyKey()).stream().findFirst().orElse(null);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private StoredApiResource stored(StoredRow row) {
        try {
            ApiResourceSpec resource = mapper.readValue(row.specJson(), ApiResourceSpec.class);
            if (!row.resourceId().equals(resource.resourceId()) || row.revision() != resource.revision()
                    || !row.specFingerprint().equals(resource.fingerprint())
                    || !row.specFingerprint().equals(specFingerprint(resource))
                    || !row.connectionId().equals(resource.connectionId())) {
                throw new IllegalArgumentException("stored spec integrity drift");
            }
            ProjectionDocument descriptor = projection(ProjectionDocument.Kind.DESCRIPTOR, resource,
                    row.descriptorJson(), row.descriptorFingerprint());
            ProjectionDocument design = projection(ProjectionDocument.Kind.DESIGN_CONTRACT, resource,
                    row.designContractJson(), row.designContractFingerprint());
            ProjectionDocument operator = projection(ProjectionDocument.Kind.OPERATOR, resource,
                    row.operatorJson(), row.operatorFingerprint());
            ReadyApiResourceProjections projections = new ReadyApiResourceProjections(descriptor, design, operator);
            JsonNode body = mapper.readTree(row.receiptJson());
            if (!row.strongEtag().equals(row.receiptEtag())) throw new IllegalArgumentException("receipt etag drift");
            return new StoredApiResource(new AuthoringScope(row.tenantId(), row.projectId(), row.environmentId()),
                    resource, projections, new CommandReceipt(row.receiptSchema(), body,
                    row.receiptFingerprint(), row.receiptEtag()));
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stored authoring data is invalid");
        }
    }

    private ProjectionDocument projection(ProjectionDocument.Kind kind, ApiResourceSpec resource,
                                          String json, String fingerprint) throws Exception {
        JsonNode body = mapper.readTree(json);
        if (!fingerprint.equals(AuthoringFingerprints.of(body))) throw new IllegalArgumentException("projection fingerprint drift");
        return new ProjectionDocument(kind, resource.ref(), body, fingerprint, ProjectionDocument.State.READY);
    }

    private String specFingerprint(ApiResourceSpec resource) {
        try {
            ApiResourceCommand command = new ApiResourceCommand(resource.displayName(), resource.description(),
                    resource.operation(), resource.contract(), resource.response(), resource.effect(), resource.examples());
            return decisions.next(Optional.empty(), resource.resourceId(), resource.connectionId(), command,
                    ExpectedRevision.create()).fingerprint();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("stored spec fingerprint cannot be verified", ex);
        }
    }

    private CommandReceipt receipt(JournalRow row) {
        try {
            return new CommandReceipt(row.receiptSchema(), mapper.readTree(row.receiptJson()),
                    row.receiptFingerprint(), row.receiptEtag());
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stored receipt is invalid");
        }
    }

    private static String expectedMode(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Create ? "CREATE" : "MATCH";
    }

    private static Long expectedRevision(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Match match ? match.revision() : null;
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "fingerprint is invalid");
        }
    }

    private static RowMapper<JournalRow> journalRowMapper() {
        return (rs, rowNum) -> new JournalRow(rs.getString("command_id"), rs.getString("request_fingerprint"),
                rs.getString("status"), rs.getInt("attempt_no"), rs.getString("attempt_token"),
                timestamp(rs, "lease_until"), rs.getString("receipt_schema"), rs.getString("receipt_json"),
                rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
    }

    private static RowMapper<StoredRow> storedRowMapper() {
        return (rs, rowNum) -> new StoredRow(rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("resource_id"), rs.getLong("revision"),
                rs.getString("strong_etag"), rs.getString("spec_json"), rs.getString("spec_fingerprint"),
                rs.getString("connection_id"), rs.getString("command_id"), rs.getString("descriptor_json"),
                rs.getString("descriptor_fingerprint"), rs.getString("descriptor_state"),
                rs.getString("design_contract_json"), rs.getString("design_contract_fingerprint"),
                rs.getString("design_contract_state"), rs.getString("operator_json"),
                rs.getString("operator_fingerprint"), rs.getString("operator_state"),
                rs.getString("receipt_schema"), rs.getString("receipt_json"),
                rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private static ApiResourceCommitStoreException error(ApiResourceCommitStoreException.Code code, String message) {
        return new ApiResourceCommitStoreException(code, message);
    }

    private record JournalRow(String commandId, String requestFingerprint, String status, int attemptNo,
                              String attemptToken, Instant leaseUntil, String receiptSchema, String receiptJson,
                              String receiptFingerprint, String receiptEtag) { }

    private record StoredRow(String tenantId, String projectId, String environmentId, String resourceId, long revision,
                             String strongEtag, String specJson, String specFingerprint, String connectionId,
                             String commandId, String descriptorJson, String descriptorFingerprint, String descriptorState,
                             String designContractJson, String designContractFingerprint, String designContractState,
                             String operatorJson, String operatorFingerprint, String operatorState,
                             String receiptSchema, String receiptJson, String receiptFingerprint, String receiptEtag) { }

    @Override
    public StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command) {
        throw new UnsupportedOperationException("stage is implemented by the J2 JDBC store slice");
    }

    @Override
    public CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt) {
        throw new UnsupportedOperationException("commit is implemented by the J2 JDBC store slice");
    }

    @Override
    public void fail(CommandLease lease, CommandFailureCode failureCode) {
        throw new UnsupportedOperationException("fail is implemented by the J2 JDBC store slice");
    }
}
