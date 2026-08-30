package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
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
 * JDBC-backed, transactionally fenced API Resource authoring store.
 *
 * <p>All mutations require the exact persisted PREPARING attempt.  V001 and
 * V002 migrations must be installed before this class is constructed; this
 * repository intentionally never applies or falls back from migrations.</p>
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
                   p.operator_json, p.operator_fingerprint, p.operator_state, p.set_fingerprint,
                   j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag
              FROM rg_api_resource_heads h
              JOIN rg_api_resource_revisions r
                ON r.tenant_id = h.tenant_id AND r.project_id = h.project_id
               AND r.environment_id = h.environment_id AND r.resource_id = h.resource_id
               AND r.revision = h.revision AND r.command_id = h.command_id AND r.strong_etag = h.strong_etag
               AND r.state = h.revision_state AND r.state = 'COMMITTED'
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id AND p.resource_id = r.resource_id
               AND p.revision = r.revision AND p.command_id = r.command_id AND p.descriptor_state = 'READY'
               AND p.design_contract_state = 'READY' AND p.operator_state = 'READY'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.status = 'COMMITTED'
               AND j.tenant_id = r.tenant_id AND j.project_id = r.project_id
               AND j.environment_id = r.environment_id AND j.target_id = r.resource_id
               AND j.endpoint = 'API_RESOURCE_SAVE'
            WHERE h.tenant_id = ? AND h.project_id = ? AND h.environment_id = ? AND h.resource_id = ?
            """;
    private static final String REVISION_READ_JOIN = """
            SELECT r.tenant_id, r.project_id, r.environment_id, r.resource_id, r.revision, r.strong_etag,
                   r.spec_json, r.spec_fingerprint, r.connection_id, r.command_id,
                   p.descriptor_json, p.descriptor_fingerprint, p.descriptor_state,
                   p.design_contract_json, p.design_contract_fingerprint, p.design_contract_state,
                   p.operator_json, p.operator_fingerprint, p.operator_state, p.set_fingerprint,
                   j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag
              FROM rg_api_resource_revisions r
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id AND p.resource_id = r.resource_id
               AND p.revision = r.revision AND p.command_id = r.command_id AND p.descriptor_state = 'READY'
               AND p.design_contract_state = 'READY' AND p.operator_state = 'READY'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.status = 'COMMITTED'
               AND j.tenant_id = r.tenant_id AND j.project_id = r.project_id
               AND j.environment_id = r.environment_id AND j.target_id = r.resource_id
               AND j.endpoint = 'API_RESOURCE_SAVE'
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
        DataSource jdbcDataSource = jdbc.getDataSource();
        DataSource transactionDataSource = transactions.getTransactionManager() instanceof DataSourceTransactionManager manager
                ? manager.getDataSource() : null;
        if (jdbcDataSource == null || transactionDataSource == null || jdbcDataSource != transactionDataSource) {
            throw new IllegalArgumentException("jdbc and transaction manager must share the same DataSource");
        }
    }

    /** Creates a store whose JDBC and transaction collaborators share one DataSource. */
    public JdbcApiResourceCommitStore(DataSource dataSource, ObjectMapper mapper, Clock clock,
                                      Duration leaseDuration, ApiResourceDecisions decisions,
                                      ApiResourceProjectionCompiler compiler) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), mapper, clock,
                leaseDuration, decisions, compiler);
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
        Instant now = databaseNow();
        if (prior != null && !fingerprint.equals(prior.requestFingerprint())) {
            return new ClaimResult.Conflict("idempotency fingerprint conflict");
        }
        if (prior != null && "COMMITTED".equals(prior.status())) {
            return new ClaimResult.Replay(receipt(prior));
        }
        if (prior != null && "PREPARING".equals(prior.status()) && databaseLeaseIsLive(prior.commandId())) {
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

    private boolean databaseLeaseIsLive(String commandId) {
        try {
            Instant expiry = jdbc.queryForObject("SELECT lease_until FROM rg_authoring_command_journal WHERE command_id=?", (rs, n) -> timestamp(rs, "lease_until"), commandId);
            return expiry != null && expiry.isAfter(databaseNow());
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
    }

    /** Reads the database clock so lease decisions do not trust caller clocks. */
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, n) -> timestamp(rs, 1));
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
                    || !ApiResourceSpec.SCHEMA_VERSION.equals(resource.schemaVersion())
                    || !ApiResourceSpec.DRAFT.equals(resource.status())
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
            if (!row.setFingerprint().equals(projectionSetFingerprint(resource, projections))) {
                throw new IllegalArgumentException("projection set fingerprint drift");
            }
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
                rs.getString("operator_fingerprint"), rs.getString("operator_state"), rs.getString("set_fingerprint"),
                rs.getString("receipt_schema"), rs.getString("receipt_json"),
                rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return timestampValue(value);
    }

    private static Instant timestamp(ResultSet rs, int column) throws SQLException {
        return timestampValue(rs.getObject(column));
    }

    private static Instant timestampValue(Object value) {
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

    private record ActiveRow(String commandId, String tenantId, String projectId, String environmentId,
                             String actorId, String endpoint, String targetId, String idempotencyKey,
                             String requestFingerprint, String status, int attemptNo, String attemptToken, Instant leaseUntil,
                             String expectedMode, Long expectedRevision) { }

    private record HeadRow(long revision, String commandId, String strongEtag) { }

    private record StoredRow(String tenantId, String projectId, String environmentId, String resourceId, long revision,
                             String strongEtag, String specJson, String specFingerprint, String connectionId,
                             String commandId, String descriptorJson, String descriptorFingerprint, String descriptorState,
                             String designContractJson, String designContractFingerprint, String designContractState,
                             String operatorJson, String operatorFingerprint, String operatorState, String setFingerprint,
                             String receiptSchema, String receiptJson, String receiptFingerprint, String receiptEtag) { }

    @Override
    public StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command) {
        Objects.requireNonNull(command, "command");
        if (connectionId == null || connectionId.isBlank()) throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "connection id is required");
        try { return transactions.execute(status -> stageInTransaction(lease, connectionId, command)); }
        catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (DataAccessException ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage persistence failed"); }
    }

    private StagedApiResource stageInTransaction(CommandLease lease, String connectionId, ApiResourceCommand command) {
        requireActive(lease);
        StagedRow existing = staged(lease);
        ApiResourceSpec head = committedSpec(lease.key().scope(), lease.key().targetId());
        ApiResourceSpec next;
        try { next = decisions.next(Optional.ofNullable(head), lease.key().targetId(), connectionId, command, lease.expectedRevision()); }
        catch (ApiResourceAuthoringException ex) {
            if (ex.code() == ApiResourceAuthoringException.Code.ALREADY_EXISTS
                    || ex.code() == ApiResourceAuthoringException.Code.NOT_FOUND
                    || ex.code() == ApiResourceAuthoringException.Code.CAS_MISMATCH) {
                throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
            }
            throw ex;
        }
        if (existing != null) {
            verifyStaged(lease, existing, next, connectionId);
            return stagedValue(lease, existing);
        }
        ReadyApiResourceProjections projections;
        try { projections = compiler.compile(lease.key().scope(), next); verifyProjections(next, projections); }
        catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (RuntimeException ex) { throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection compilation failed"); }
        try {
            String etag = opaqueEtag(), specJson = mapper.writeValueAsString(next);
            if (jdbc.update("""
                    INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json, spec_fingerprint,
                     connection_id, strong_etag, command_id, attempt_no, attempt_token)
                    VALUES (?, ?, ?, ?, ?, 'STAGED', ?, ?, ?, ?, ?, ?, ?)""",
                    lease.key().scope().tenantId(), lease.key().scope().projectId(), lease.key().scope().environmentId(),
                    lease.key().targetId(), next.revision(), specJson, next.fingerprint(), connectionId, etag,
                    lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage insert failed");
            }
            insertProjection(lease, next, projections);
            return new StagedApiResource(lease, next, projections, etag);
        } catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage serialization failed"); }
    }

    private void insertProjection(CommandLease lease, ApiResourceSpec resource, ReadyApiResourceProjections p) throws Exception {
        if (jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                (tenant_id, project_id, environment_id, resource_id, revision, command_id, descriptor_json, descriptor_fingerprint, descriptor_state,
                 design_contract_json, design_contract_fingerprint, design_contract_state, operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, 'READY', ?, ?, 'READY', ?)""",
                lease.key().scope().tenantId(), lease.key().scope().projectId(), lease.key().scope().environmentId(), lease.key().targetId(), resource.revision(), lease.commandId(),
                mapper.writeValueAsString(p.descriptor().body()), p.descriptor().fingerprint(), mapper.writeValueAsString(p.designContract().body()), p.designContract().fingerprint(),
                mapper.writeValueAsString(p.operator().body()), p.operator().fingerprint(), projectionSetFingerprint(resource, p)) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "projection insert failed");
        }
    }

    private String projectionSetFingerprint(ApiResourceSpec resource, ReadyApiResourceProjections p) {
        var root = mapper.createObjectNode();
        for (ProjectionDocument d : new ProjectionDocument[]{p.descriptor(), p.designContract(), p.operator()}) {
            var item = mapper.createObjectNode();
            item.put("kind", d.kind().name());
            item.put("subject", d.subject().toString());
            item.put("bodyFingerprint", d.fingerprint());
            root.set(d.kind().name(), item);
        }
        return AuthoringFingerprints.of(root);
    }

    private ApiResourceSpec committedSpec(AuthoringScope scope, String resourceId) {
        List<String> json = jdbc.query("SELECT r.spec_json FROM rg_api_resource_heads h JOIN rg_api_resource_revisions r ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id AND r.environment_id=h.environment_id AND r.resource_id=h.resource_id AND r.revision=h.revision AND r.command_id=h.command_id AND r.strong_etag=h.strong_etag AND r.state='COMMITTED' WHERE h.tenant_id=? AND h.project_id=? AND h.environment_id=? AND h.resource_id=?", (rs, n) -> rs.getString(1), scope.tenantId(), scope.projectId(), scope.environmentId(), resourceId);
        if (json.isEmpty()) return null;
        try { return mapper.readValue(json.getFirst(), ApiResourceSpec.class); } catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stored head is invalid"); }
    }

    private void requireActive(CommandLease lease) {
        if (lease == null) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        List<ActiveRow> rows = jdbc.query("SELECT command_id, tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key, request_fingerprint, status, attempt_no, attempt_token, lease_until, expected_mode, expected_revision FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE", (rs, n) -> new ActiveRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11), rs.getString(12), timestamp(rs, "lease_until"), rs.getString(14), (Long) rs.getObject(15)), lease.commandId());
        if (rows.isEmpty()) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        ActiveRow r = rows.getFirst();
        boolean coordinate = "PREPARING".equals(r.status()) && r.commandId().equals(lease.commandId()) && r.tenantId().equals(lease.key().scope().tenantId())
                && r.projectId().equals(lease.key().scope().projectId()) && r.environmentId().equals(lease.key().scope().environmentId())
                && r.actorId().equals(lease.key().actorId()) && r.endpoint().equals(lease.key().endpoint().name())
                && r.targetId().equals(lease.key().targetId()) && r.idempotencyKey().equals(lease.key().idempotencyKey())
                && r.requestFingerprint().equals(lease.requestFingerprint()) && r.attemptNo() == lease.attemptNo()
                && r.attemptToken().equals(lease.attemptToken()) && r.expectedMode().equals(expectedMode(lease.expectedRevision()))
                && Objects.equals(r.expectedRevision(), expectedRevision(lease.expectedRevision()));
        if (!coordinate) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        if (!databaseLeaseIsLive(r.commandId())) throw error(ApiResourceCommitStoreException.Code.LEASE_EXPIRED, "lease expired");
    }

    private StagedRow staged(CommandLease lease) {
        List<StagedRow> rows = jdbc.query("""
                SELECT r.tenant_id, r.project_id, r.environment_id, r.resource_id, r.revision, r.command_id, r.attempt_no, r.attempt_token, r.spec_json, r.spec_fingerprint, r.connection_id, r.strong_etag,
                       p.descriptor_json, p.descriptor_fingerprint, p.design_contract_json,
                       p.design_contract_fingerprint, p.operator_json, p.operator_fingerprint, p.set_fingerprint
                  FROM rg_api_resource_revisions r JOIN rg_api_resource_projection_revisions p
                   ON p.tenant_id=r.tenant_id AND p.project_id=r.project_id AND p.environment_id=r.environment_id
                   AND p.resource_id=r.resource_id AND p.revision=r.revision AND p.command_id=r.command_id
                   AND p.descriptor_state='READY' AND p.design_contract_state='READY' AND p.operator_state='READY'
                 WHERE r.command_id=? AND r.attempt_no=? AND r.attempt_token=? AND r.state='STAGED' FOR UPDATE""",
                (rs, n) -> new StagedRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getInt(7),rs.getString(8),rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        return rows.stream().findFirst().orElse(null);
    }

    private StagedApiResource stagedValue(CommandLease lease, StagedRow row) {
        try {
            ApiResourceSpec resource = mapper.readValue(row.specJson(), ApiResourceSpec.class);
            return new StagedApiResource(lease, resource,
                    new ReadyApiResourceProjections(projection(ProjectionDocument.Kind.DESCRIPTOR, resource, row.descriptorJson(), row.descriptorFingerprint()), projection(ProjectionDocument.Kind.DESIGN_CONTRACT, resource, row.designJson(), row.designFingerprint()), projection(ProjectionDocument.Kind.OPERATOR, resource, row.operatorJson(), row.operatorFingerprint())), row.etag());
        } catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged resource is invalid"); }
    }

    private record StagedRow(String tenantId,String projectId,String environmentId,String resourceId,long revision,String commandId,int attemptNo,String attemptToken,String specJson, String specFingerprint, String connectionId, String etag,
                             String descriptorJson, String descriptorFingerprint, String designJson, String designFingerprint,
                             String operatorJson, String operatorFingerprint, String setFingerprint) { }

    private void verifyStaged(CommandLease lease, StagedRow r, ApiResourceSpec expected, String connectionId) {
        try {
            ApiResourceSpec actual = mapper.readValue(r.specJson(), ApiResourceSpec.class);
            ReadyApiResourceProjections p = new ReadyApiResourceProjections(
                    projection(ProjectionDocument.Kind.DESCRIPTOR, actual, r.descriptorJson(), r.descriptorFingerprint()),
                    projection(ProjectionDocument.Kind.DESIGN_CONTRACT, actual, r.designJson(), r.designFingerprint()),
                    projection(ProjectionDocument.Kind.OPERATOR, actual, r.operatorJson(), r.operatorFingerprint()));
            if (!r.tenantId().equals(lease.key().scope().tenantId()) || !r.projectId().equals(lease.key().scope().projectId()) || !r.environmentId().equals(lease.key().scope().environmentId())
                    || !r.resourceId().equals(lease.key().targetId()) || r.revision() != expected.revision() || !r.commandId().equals(lease.commandId())
                    || r.attemptNo()!=lease.attemptNo() || !r.attemptToken().equals(lease.attemptToken()) || !r.connectionId().equals(connectionId)
                    || !r.specFingerprint().equals(expected.fingerprint()) || !r.specFingerprint().equals(specFingerprint(actual))
                    || !r.setFingerprint().equals(projectionSetFingerprint(actual, p)))
                throw new IllegalArgumentException("staged integrity drift");
            verifyProjections(actual, p);
        } catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged resource is invalid"); }
    }

    private static String opaqueEtag() { return "\"" + UUID.randomUUID() + "\""; }

    private static void verifyProjections(ApiResourceSpec resource, ReadyApiResourceProjections projections) {
        if (projections == null || !resource.ref().equals(projections.subject())) throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection subject drift");
        for (ProjectionDocument document : new ProjectionDocument[]{projections.descriptor(), projections.designContract(), projections.operator()}) {
            if (document.state() != ProjectionDocument.State.READY || !AuthoringFingerprints.of(document.body()).equals(document.fingerprint())) throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection integrity drift");
        }
    }

    @Override
    public CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt) {
        try { return transactions.execute(status -> commitInTransaction(lease, finalReceipt)); }
        catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (DataAccessException ex) {
            throw error(isCommitRace(ex) ? ApiResourceCommitStoreException.Code.CAS_MISMATCH
                    : ApiResourceCommitStoreException.Code.INTEGRITY, "commit persistence failed");
        }
    }

    /** Maps database lock/unique races to CAS while retaining other failures as integrity errors. */
    private static boolean isCommitRace(DataAccessException failure) {
        if (failure instanceof ConcurrencyFailureException || failure instanceof DuplicateKeyException) return true;
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "HYT00".equals(sql.getSQLState()))) return true;
            cause = cause.getCause();
        }
        String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("lock") || message.contains("deadlock") || message.contains("serialization");
    }

    private CommandReceipt commitInTransaction(CommandLease lease, CommandReceipt receipt) {
        requireActive(lease); StagedRow s = staged(lease);
        if (s == null) throw error(ApiResourceCommitStoreException.Code.STAGE_MISSING, "staged resource is missing");
        ApiResourceSpec resource;
        try { resource = mapper.readValue(s.specJson(), ApiResourceSpec.class); }
        catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged spec is invalid"); }
        validateReceipt(receipt, s.etag());
        try {
            ReadyApiResourceProjections p = new ReadyApiResourceProjections(
                    projection(ProjectionDocument.Kind.DESCRIPTOR, resource, s.descriptorJson(), s.descriptorFingerprint()),
                    projection(ProjectionDocument.Kind.DESIGN_CONTRACT, resource, s.designJson(), s.designFingerprint()),
                    projection(ProjectionDocument.Kind.OPERATOR, resource, s.operatorJson(), s.operatorFingerprint()));
            verifyProjections(resource, p);
            if (!s.setFingerprint().equals(projectionSetFingerprint(resource, p))) throw new IllegalArgumentException();
        } catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "staged projections are invalid"); }
        jdbc.query("SELECT revision FROM rg_api_resource_revisions WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? ORDER BY revision, command_id FOR UPDATE", (rs,n)->rs.getLong(1), lease.key().scope().tenantId(),lease.key().scope().projectId(),lease.key().scope().environmentId(),lease.key().targetId());
        List<HeadRow> heads = jdbc.query("SELECT revision, command_id, strong_etag FROM rg_api_resource_heads WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? FOR UPDATE", (rs,n)->new HeadRow(rs.getLong(1), rs.getString(2), rs.getString(3)), lease.key().scope().tenantId(),lease.key().scope().projectId(),lease.key().scope().environmentId(),lease.key().targetId());
        if ((lease.expectedRevision() instanceof ExpectedRevision.Create && !heads.isEmpty()) || (lease.expectedRevision() instanceof ExpectedRevision.Match m && (heads.isEmpty() || heads.getFirst().revision()!=m.revision()))) throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH,"head revision changed");
        if (jdbc.update("UPDATE rg_api_resource_revisions SET state='COMMITTED' WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? AND revision=? AND command_id=? AND state='STAGED'",s.tenantId(),s.projectId(),s.environmentId(),s.resourceId(),s.revision(),s.commandId()) != 1) throw error(ApiResourceCommitStoreException.Code.INTEGRITY,"stage state changed");
        if (jdbc.update("UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema=?, receipt_json=?, receipt_fingerprint=?, receipt_etag=?, updated_at=CURRENT_TIMESTAMP WHERE command_id=? AND status='PREPARING' AND attempt_no=? AND attempt_token=?",
                receipt.schemaVersion(), json(receipt.body()), receipt.bodyFingerprint(), receipt.strongEtag(), lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "command journal state changed");
        }
        if (heads.isEmpty()) {
            if (jdbc.update("INSERT INTO rg_api_resource_heads (tenant_id,project_id,environment_id,resource_id,revision,command_id,strong_etag) VALUES (?,?,?,?,?,?,?)",
                    s.tenantId(), s.projectId(), s.environmentId(), s.resourceId(), s.revision(), s.commandId(), s.etag()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "head insert failed");
            }
        } else if (jdbc.update("UPDATE rg_api_resource_heads SET revision=?, command_id=?, strong_etag=?, updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? AND revision=? AND revision_state='COMMITTED'",
                s.revision(), s.commandId(), s.etag(), s.tenantId(), s.projectId(), s.environmentId(), s.resourceId(), heads.getFirst().revision()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "head update failed");
        }
        return receipt;
    }

    @Override
    public void fail(CommandLease lease, CommandFailureCode failureCode) {
        if (lease == null) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED,"lease is fenced");
        if (failureCode == null) throw error(ApiResourceCommitStoreException.Code.INTEGRITY,"failure code is required");
        try { transactions.executeWithoutResult(status -> {
            List<JournalRow> rows = jdbc.query("SELECT "+JOURNAL_COLUMNS+" FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",journalRowMapper(),lease.commandId());
            if (rows.isEmpty()) return; JournalRow j=rows.getFirst();
            if (!j.attemptToken().equals(lease.attemptToken()) || j.attemptNo() != lease.attemptNo()) return;
            if ("COMMITTED".equals(j.status())) throw error(ApiResourceCommitStoreException.Code.INTEGRITY,"committed command cannot fail");
            requireActive(lease);
            jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'",lease.commandId(),lease.attemptNo(),lease.attemptToken());
            if (jdbc.update("UPDATE rg_authoring_command_journal SET status='FAILED', receipt_schema=NULL, receipt_json=NULL, receipt_fingerprint=NULL, receipt_etag=NULL, failure_code=?, updated_at=CURRENT_TIMESTAMP WHERE command_id=? AND status='PREPARING' AND attempt_no=? AND attempt_token=?",
                    failureCode.value(), lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "command journal state changed");
            }
        }); } catch (ApiResourceCommitStoreException ex) { throw ex; }
        catch (DataAccessException ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "fail persistence failed"); }
    }

    private String json(JsonNode node) { try { return mapper.writeValueAsString(node); } catch (Exception ex) { throw error(ApiResourceCommitStoreException.Code.INTEGRITY,"receipt serialization failed"); } }
    private static void validateReceipt(CommandReceipt r,String etag) { if(r==null || !etag.equals(r.strongEtag()) || !AuthoringFingerprints.of(r.body()).equals(r.bodyFingerprint())) throw error(ApiResourceCommitStoreException.Code.RECEIPT_INVALID,"receipt is invalid"); }
}
