package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * JDBC-backed, transactionally fenced API Resource authoring store.
 *
 * <p>V001 through V011 migrations must be installed before this class is
 * constructed; this repository intentionally never applies or falls back from
 * migrations. V009 stores immutable command-attempt authority, V010 binds
 * committed projections to exact attempts, and V011 records the Connection
 * snapshot used by every Resource projection. Current stage, commit, and
 * failure mutations require the exact current {@code PREPARING} attempt;
 * recovery may inspect a historical {@code PREPARING} or {@code SUPERSEDED}
 * attempt under the journal-to-attempt lock order.</p>
 */
public final class JdbcApiResourceCommitStore implements ApiResourceCommitStore {
    private static final String JOURNAL_COLUMNS = "tenant_id, project_id, environment_id, actor_id, endpoint, target_id, "
            + "idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until, "
            + "expected_mode, expected_revision, receipt_schema, receipt_json, receipt_fingerprint, receipt_etag, "
            + "failure_code, created_at, updated_at";
    private static final String READ_JOIN = """
            SELECT h.tenant_id, h.project_id, h.environment_id, h.resource_id, h.revision, h.strong_etag,
                   r.spec_json, r.spec_fingerprint, r.connection_id, r.connection_revision,
                   r.connection_metadata_fingerprint, r.command_id,
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
              JOIN rg_authoring_command_attempts a
                ON a.command_id = r.command_id AND a.attempt_no = r.attempt_no
               AND a.attempt_token = r.attempt_token AND a.status = 'COMMITTED'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.attempt_no = r.attempt_no
               AND j.attempt_token = r.attempt_token AND j.status = 'COMMITTED'
               AND j.tenant_id = r.tenant_id AND j.project_id = r.project_id
               AND j.environment_id = r.environment_id AND j.target_id = r.resource_id
               AND j.endpoint = 'API_RESOURCE_SAVE'
            WHERE h.tenant_id = ? AND h.project_id = ? AND h.environment_id = ? AND h.resource_id = ?
            """;
    private static final String REVISION_READ_JOIN = """
            SELECT r.tenant_id, r.project_id, r.environment_id, r.resource_id, r.revision, r.strong_etag,
                   r.spec_json, r.spec_fingerprint, r.connection_id, r.connection_revision,
                   r.connection_metadata_fingerprint, r.command_id,
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
              JOIN rg_authoring_command_attempts a
                ON a.command_id = r.command_id AND a.attempt_no = r.attempt_no
               AND a.attempt_token = r.attempt_token AND a.status = 'COMMITTED'
              JOIN rg_authoring_command_journal j
                ON j.command_id = r.command_id AND j.attempt_no = r.attempt_no
               AND j.attempt_token = r.attempt_token AND j.status = 'COMMITTED'
               AND j.tenant_id = r.tenant_id AND j.project_id = r.project_id
               AND j.environment_id = r.environment_id AND j.target_id = r.resource_id
               AND j.endpoint = 'API_RESOURCE_SAVE'
             WHERE r.tenant_id = ? AND r.project_id = ? AND r.environment_id = ?
               AND r.resource_id = ? AND r.revision = ? AND r.state = 'COMMITTED'
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Duration leaseDuration;
    private final ApiResourceDecisions decisions;
    private final ApiResourceProjectionCompiler compiler;
    private final JdbcAuthoringAttemptCleanup attemptCleanup;
    /** Package-private test seam; production construction uses a no-op observer. */
    private final Consumer<String> failAfterJournalLockObserver;

    /** Creates the seam with all collaborators needed by the complete store. */
    public JdbcApiResourceCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      ObjectMapper mapper, Duration leaseDuration,
                                      ApiResourceDecisions decisions,
                                      ApiResourceProjectionCompiler compiler) {
        this(jdbc, transactions, mapper, leaseDuration, decisions, compiler, ignored -> { });
    }

    /**
     * Package-private constructor for deterministic lock-order tests.  The
     * observer runs after the journal and immutable attempt have been locked,
     * before pending-secret inspection; it is never part of the public
     * persistence protocol.
     */
    JdbcApiResourceCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                               ObjectMapper mapper, Duration leaseDuration,
                               ApiResourceDecisions decisions,
                               ApiResourceProjectionCompiler compiler,
                               Consumer<String> failAfterJournalLockObserver) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.attemptCleanup = new JdbcAuthoringAttemptCleanup(jdbc);
        this.failAfterJournalLockObserver = Objects.requireNonNull(failAfterJournalLockObserver,
                "failAfterJournalLockObserver");
        DataSource jdbcDataSource = jdbc.getDataSource();
        DataSource transactionDataSource = transactions.getTransactionManager() instanceof DataSourceTransactionManager manager
                ? manager.getDataSource() : null;
        if (jdbcDataSource == null || transactionDataSource == null || jdbcDataSource != transactionDataSource) {
            throw new IllegalArgumentException("jdbc and transaction manager must share the same DataSource");
        }
    }

    /** Creates a store whose JDBC and transaction collaborators share one DataSource. */
    public JdbcApiResourceCommitStore(DataSource dataSource, ObjectMapper mapper,
                                      Duration leaseDuration, ApiResourceDecisions decisions,
                                      ApiResourceProjectionCompiler compiler) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), mapper,
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
        if (prior != null && (!prior.expectedMode().equals(expectedMode(expected))
                || !Objects.equals(prior.expectedRevision(), expectedRevision(expected)))) {
            return new ClaimResult.Conflict("expected revision conflict");
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
        CommandLease incoming = new CommandLease(commandId, attemptNo, attemptToken, key, fingerprint,
                now.plus(leaseDuration), expected);
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
            insertAttempt(incoming, now);
        } else {
            // Keep the expired attempt immutable. Its pending-secret rows may
            // still need recovery after this current-journal pointer advances.
            supersedeAttempt(prior);
            attemptCleanup.deleteAbandonedNestedConnectionStage(prior.commandId(), prior.attemptNo(),
                    prior.attemptToken());
            insertAttempt(incoming, now);
            jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id = ? AND attempt_no = ? "
                    + "AND attempt_token = ? AND state = 'STAGED'", commandId, prior.attemptNo(), prior.attemptToken());
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
        return new ClaimResult.Acquired(incoming, resumed);
    }

    /** Closes an expired current attempt before the mutable journal points at its replacement. */
    private void supersedeAttempt(JournalRow prior) {
        if (!"PREPARING".equals(prior.status())) return;
        if (jdbc.update("UPDATE rg_authoring_command_attempts SET status='SUPERSEDED',"
                        + " updated_at=CURRENT_TIMESTAMP WHERE command_id=? AND attempt_no=?"
                        + " AND attempt_token=? AND status='PREPARING'", prior.commandId(), prior.attemptNo(),
                prior.attemptToken()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "command attempt state changed");
        }
    }

    /** Inserts one immutable command-attempt authority row before moving the journal pointer. */
    private void insertAttempt(CommandLease lease, Instant now) {
        if (jdbc.update("""
                INSERT INTO rg_authoring_command_attempts
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, expected_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?, ?, ?)
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().actorId(), lease.key().endpoint().name(),
                lease.key().targetId(), lease.key().idempotencyKey(), lease.commandId(), lease.requestFingerprint(),
                lease.attemptNo(), lease.attemptToken(), OffsetDateTime.ofInstant(lease.leaseUntil(), ZoneOffset.UTC),
                expectedMode(lease.expectedRevision()), expectedRevision(lease.expectedRevision()),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(now, ZoneOffset.UTC)) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "command attempt insert failed");
        }
    }

    private boolean databaseLeaseIsLive(String commandId) {
        try {
            Instant expiry = jdbc.queryForObject(
                    "SELECT lease_until FROM rg_authoring_command_journal WHERE command_id = ?",
                    (rs, n) -> timestamp(rs, "lease_until"), commandId);
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

    /** Resolves one committed historical revision by its opaque strong validator. */
    @Override
    public Optional<StoredApiResource> findRevisionByStrongEtag(AuthoringScope scope, String resourceId,
                                                               String strongEtag) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceId, "resourceId");
        if (!StrongEtag.isValid(strongEtag)) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "strong ETag is invalid");
        }
        try {
            List<Long> revisions = jdbc.query("""
                    SELECT revision
                      FROM rg_api_resource_revisions
                     WHERE tenant_id=? AND project_id=? AND environment_id=?
                       AND resource_id=? AND strong_etag=? AND state='COMMITTED'
                    """, (rs, row) -> rs.getLong(1), scope.tenantId(), scope.projectId(),
                    scope.environmentId(), resourceId, strongEtag);
            if (revisions.isEmpty()) return Optional.empty();
            if (revisions.size() != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                        "committed resource ETag provenance is ambiguous");
            }
            return findRevision(scope, resourceId, revisions.getFirst());
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "read persistence failed");
        }
    }

    private Optional<StoredApiResource> read(AuthoringScope scope, String resourceId, Long revision) {
        String sql = revision == null ? READ_JOIN : REVISION_READ_JOIN;
        Object[] args = revision == null
                ? new Object[]{scope.tenantId(), scope.projectId(), scope.environmentId(), resourceId}
                : new Object[]{scope.tenantId(), scope.projectId(), scope.environmentId(), resourceId, revision};
        try {
            List<StoredRow> rows = jdbc.query(sql, storedRowMapper(), args);
            if (rows.isEmpty()) return Optional.empty();
            if (rows.size() != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                        "committed resource provenance is ambiguous");
            }
            return Optional.of(stored(rows.getFirst()));
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
            List<JournalRow> rows = jdbc.query(sql, journalRowMapper(), key.scope().tenantId(), key.scope().projectId(),
                    key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                    key.idempotencyKey());
            if (rows.size() > 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                        "authoring coordinate has ambiguous journal provenance");
            }
            return rows.isEmpty() ? null : rows.getFirst();
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
            ReadyApiResourceProjections projections = new ReadyApiResourceProjections(descriptor, design, operator,
                    new ApiResourceConnectionSnapshot(row.connectionId(), row.connectionRevision(),
                            row.connectionMetadataFingerprint()));
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

    /** Computes the immutable content digest without invoking CREATE revision semantics. */
    private String specFingerprint(ApiResourceSpec resource) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("resourceId", resource.resourceId());
        payload.put("connectionId", resource.connectionId());
        payload.set("command", mapper.valueToTree(new ApiResourceCommand(
                resource.displayName(), resource.description(), resource.operation(), resource.contract(),
                resource.response(), resource.effect(), resource.examples())));
        try {
            byte[] bytes = canonicalize(payload).toString().getBytes(StandardCharsets.UTF_8);
            return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException("stored spec fingerprint cannot be verified", ex);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                result.set(key, canonicalize(value.get(key)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
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
        return (rs, rowNum) -> new JournalRow(
                rs.getString("command_id"), rs.getString("request_fingerprint"),
                rs.getString("status"), rs.getInt("attempt_no"),
                rs.getString("attempt_token"), timestamp(rs, "lease_until"),
                rs.getString("expected_mode"), rs.getObject("expected_revision", Long.class),
                rs.getString("receipt_schema"), rs.getString("receipt_json"),
                rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
    }

    private static RowMapper<StoredRow> storedRowMapper() {
        return (rs, rowNum) -> new StoredRow(
                rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("resource_id"),
                rs.getLong("revision"), rs.getString("strong_etag"),
                rs.getString("spec_json"), rs.getString("spec_fingerprint"),
                rs.getString("connection_id"), rs.getLong("connection_revision"),
                rs.getString("connection_metadata_fingerprint"), rs.getString("command_id"),
                rs.getString("descriptor_json"), rs.getString("descriptor_fingerprint"),
                rs.getString("descriptor_state"), rs.getString("design_contract_json"),
                rs.getString("design_contract_fingerprint"), rs.getString("design_contract_state"),
                rs.getString("operator_json"), rs.getString("operator_fingerprint"),
                rs.getString("operator_state"), rs.getString("set_fingerprint"),
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
                              String attemptToken, Instant leaseUntil, String expectedMode, Long expectedRevision,
                              String receiptSchema, String receiptJson,
                              String receiptFingerprint, String receiptEtag) { }

    private record AttemptAuthority(String tenantId, String projectId, String environmentId, String actorId,
                                    String endpoint, String targetId, String idempotencyKey,
                                    String requestFingerprint, String status, int attemptNo, String attemptToken,
                                    Instant leaseUntil, String expectedMode, Long expectedRevision) { }

    private record ActiveRow(String commandId, String tenantId, String projectId, String environmentId,
                             String actorId, String endpoint, String targetId, String idempotencyKey,
                             String requestFingerprint, String status, int attemptNo, String attemptToken, Instant leaseUntil,
                             String expectedMode, Long expectedRevision) { }

    private record HeadRow(long revision, String commandId, String strongEtag) { }

    private record StoredRow(String tenantId, String projectId, String environmentId, String resourceId, long revision,
                             String strongEtag, String specJson, String specFingerprint, String connectionId,
                             long connectionRevision, String connectionMetadataFingerprint,
                             String commandId, String descriptorJson, String descriptorFingerprint, String descriptorState,
                             String designContractJson, String designContractFingerprint, String designContractState,
                             String operatorJson, String operatorFingerprint, String operatorState, String setFingerprint,
                             String receiptSchema, String receiptJson, String receiptFingerprint, String receiptEtag) { }

    @Override
    public StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command) {
        Objects.requireNonNull(command, "command");
        if (connectionId == null || connectionId.isBlank()) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "connection id is required");
        }
        try {
            return transactions.execute(status -> stageInTransaction(lease, connectionId, command));
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage persistence failed");
        }
    }

    private StagedApiResource stageInTransaction(CommandLease lease, String connectionId, ApiResourceCommand command) {
        requireActive(lease);
        StagedRow existing = staged(lease);
        ApiResourceSpec head = committedSpec(lease.key().scope(), lease.key().targetId());
        ApiResourceSpec next;
        try {
            next = decisions.next(Optional.ofNullable(head), lease.key().targetId(), connectionId, command,
                    lease.expectedRevision());
        } catch (ApiResourceAuthoringException ex) {
            if (ex.code() == ApiResourceAuthoringException.Code.ALREADY_EXISTS
                    || ex.code() == ApiResourceAuthoringException.Code.NOT_FOUND
                    || ex.code() == ApiResourceAuthoringException.Code.CAS_MISMATCH) {
                throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
            }
            throw ex;
        }
        if (existing != null) {
            verifiedStage(lease, existing, next, connectionId);
            return stagedValue(lease, existing);
        }
        ReadyApiResourceProjections projections;
        try {
            projections = compiler.compile(lease.key().scope(), next);
            verifyProjections(next, projections);
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID,
                    "projection compilation failed");
        }
        try {
            String etag = opaqueEtag(), specJson = mapper.writeValueAsString(next);
            if (jdbc.update("""
                    INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json, spec_fingerprint,
                     connection_id, connection_revision, connection_metadata_fingerprint,
                     strong_etag, command_id, attempt_no, attempt_token)
                    VALUES (?, ?, ?, ?, ?, 'STAGED', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    lease.key().scope().tenantId(), lease.key().scope().projectId(),
                    lease.key().scope().environmentId(), lease.key().targetId(), next.revision(),
                    specJson, next.fingerprint(), connectionId,
                    projections.connectionSnapshot().revision(),
                    projections.connectionSnapshot().metadataFingerprint(), etag,
                    lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage insert failed");
            }
            insertProjection(lease, next, projections);
            return new StagedApiResource(lease, next, projections, etag);
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage serialization failed");
        }
    }

    private void insertProjection(CommandLease lease, ApiResourceSpec resource,
                                  ReadyApiResourceProjections projections) throws Exception {
        if (jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, 'READY', ?, ?, 'READY', ?)""",
                lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().targetId(), resource.revision(),
                lease.commandId(), mapper.writeValueAsString(projections.descriptor().body()),
                projections.descriptor().fingerprint(),
                mapper.writeValueAsString(projections.designContract().body()),
                projections.designContract().fingerprint(),
                mapper.writeValueAsString(projections.operator().body()),
                projections.operator().fingerprint(),
                projectionSetFingerprint(resource, projections)) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "projection insert failed");
        }
    }

    private String projectionSetFingerprint(ApiResourceSpec resource,
                                            ReadyApiResourceProjections projections) {
        var root = mapper.createObjectNode();
        for (ProjectionDocument document : new ProjectionDocument[]{
                projections.descriptor(), projections.designContract(), projections.operator()}) {
            var fingerprint = mapper.createObjectNode();
            fingerprint.put("kind", document.kind().name());
            fingerprint.put("subject", document.subject().toString());
            fingerprint.put("bodyFingerprint", document.fingerprint());
            root.set(document.kind().name(), fingerprint);
        }
        var connection = mapper.createObjectNode();
        connection.put("connectionId", projections.connectionSnapshot().connectionId());
        connection.put("revision", projections.connectionSnapshot().revision());
        connection.put("metadataFingerprint", projections.connectionSnapshot().metadataFingerprint());
        root.set("connectionSnapshot", connection);
        return AuthoringFingerprints.of(root);
    }

    private ApiResourceSpec committedSpec(AuthoringScope scope, String resourceId) {
        List<String> json = jdbc.query(
                "SELECT r.spec_json FROM rg_api_resource_heads h "
                        + "JOIN rg_api_resource_revisions r ON r.tenant_id=h.tenant_id "
                        + "AND r.project_id=h.project_id AND r.environment_id=h.environment_id "
                        + "AND r.resource_id=h.resource_id AND r.revision=h.revision "
                        + "AND r.command_id=h.command_id AND r.strong_etag=h.strong_etag "
                        + "AND r.state='COMMITTED' WHERE h.tenant_id=? AND h.project_id=? "
                        + "AND h.environment_id=? AND h.resource_id=?",
                (rs, n) -> rs.getString(1), scope.tenantId(), scope.projectId(),
                scope.environmentId(), resourceId);
        if (json.isEmpty()) {
            return null;
        }
        if (json.size() != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                    "committed resource head provenance is ambiguous");
        }
        try {
            return mapper.readValue(json.getFirst(), ApiResourceSpec.class);
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stored head is invalid");
        }
    }

    private void requireActive(CommandLease lease) {
        if (lease == null) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        List<ActiveRow> rows = jdbc.query(
                "SELECT command_id, tenant_id, project_id, environment_id, actor_id, endpoint, "
                        + "target_id, idempotency_key, request_fingerprint, status, attempt_no, "
                        + "attempt_token, lease_until, expected_mode, expected_revision "
                        + "FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",
                (rs, n) -> new ActiveRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getInt(11), rs.getString(12),
                        timestamp(rs, "lease_until"), rs.getString(14), (Long) rs.getObject(15)),
                lease.commandId());
        if (rows.isEmpty() || rows.size() > 1) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        ActiveRow r = rows.getFirst();
        boolean coordinate = "PREPARING".equals(r.status()) && r.commandId().equals(lease.commandId()) && r.tenantId().equals(lease.key().scope().tenantId())
                && r.projectId().equals(lease.key().scope().projectId()) && r.environmentId().equals(lease.key().scope().environmentId())
                && r.actorId().equals(lease.key().actorId()) && r.endpoint().equals(lease.key().endpoint().name())
                && r.targetId().equals(lease.key().targetId()) && r.idempotencyKey().equals(lease.key().idempotencyKey())
                && r.requestFingerprint().equals(lease.requestFingerprint()) && r.attemptNo() == lease.attemptNo()
                && r.attemptToken().equals(lease.attemptToken()) && r.expectedMode().equals(expectedMode(lease.expectedRevision()))
                && Objects.equals(r.expectedRevision(), expectedRevision(lease.expectedRevision()));
        if (!coordinate) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        requireAttempt(lease);
        if (!databaseLeaseIsLive(r.commandId())) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_EXPIRED, "lease expired");
        }
    }

    /** Checks the immutable V009 authority, independently of the mutable journal pointer. */
    private void requireAttempt(CommandLease lease) {
        List<AttemptAuthority> rows = jdbc.query("""
                SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                       idempotency_key, request_fingerprint, status, attempt_no, attempt_token,
                       lease_until, expected_mode, expected_revision
                  FROM rg_authoring_command_attempts
                 WHERE command_id=? AND attempt_no=? AND attempt_token=?
                 FOR UPDATE""", (rs, n) -> new AttemptAuthority(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getString(11), timestamp(rs, 12), rs.getString(13), (Long) rs.getObject(14)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (rows.size() != 1) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        AttemptAuthority a = rows.getFirst();
        if (!"PREPARING".equals(a.status())
                || !a.tenantId().equals(lease.key().scope().tenantId())
                || !a.projectId().equals(lease.key().scope().projectId())
                || !a.environmentId().equals(lease.key().scope().environmentId())
                || !a.actorId().equals(lease.key().actorId())
                || !a.endpoint().equals(lease.key().endpoint().name())
                || !a.targetId().equals(lease.key().targetId())
                || !a.idempotencyKey().equals(lease.key().idempotencyKey())
                || !a.requestFingerprint().equals(lease.requestFingerprint())
                || a.attemptNo() != lease.attemptNo()
                || !a.attemptToken().equals(lease.attemptToken())
                || !a.leaseUntil().equals(lease.leaseUntil())
                || !a.expectedMode().equals(expectedMode(lease.expectedRevision()))
                || !Objects.equals(a.expectedRevision(), expectedRevision(lease.expectedRevision()))) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
    }

    /**
     * Revalidates the complete immutable authority for compensation that has
     * already terminalized an exact attempt. This deliberately locks the
     * attempt after the caller's journal lock and rejects any changed lease,
     * scope, endpoint, key, fingerprint, or expected revision.
     */
    private void requireExactFailedAttempt(CommandLease lease) {
        List<ActiveRow> journals = jdbc.query(
                "SELECT command_id, tenant_id, project_id, environment_id, actor_id, endpoint, "
                        + "target_id, idempotency_key, request_fingerprint, status, attempt_no, "
                        + "attempt_token, lease_until, expected_mode, expected_revision "
                        + "FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",
                (rs, n) -> new ActiveRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getInt(11), rs.getString(12),
                        timestamp(rs, "lease_until"), rs.getString(14), (Long) rs.getObject(15)),
                lease.commandId());
        if (journals.size() != 1 || !matchesAuthority(journals.getFirst(), lease, "FAILED")) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }

        List<AttemptAuthority> attempts = jdbc.query("""
                SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                       idempotency_key, request_fingerprint, status, attempt_no, attempt_token,
                       lease_until, expected_mode, expected_revision
                  FROM rg_authoring_command_attempts
                 WHERE command_id=? AND attempt_no=? AND attempt_token=?
                 FOR UPDATE
                """, (rs, n) -> new AttemptAuthority(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getString(11), timestamp(rs, 12), rs.getString(13), (Long) rs.getObject(14)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (attempts.size() != 1 || !matchesAuthority(attempts.getFirst(), lease, "FAILED")) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
    }

    private static boolean matchesAuthority(ActiveRow row, CommandLease lease, String status) {
        return status.equals(row.status()) && row.commandId().equals(lease.commandId())
                && row.tenantId().equals(lease.key().scope().tenantId())
                && row.projectId().equals(lease.key().scope().projectId())
                && row.environmentId().equals(lease.key().scope().environmentId())
                && row.actorId().equals(lease.key().actorId())
                && row.endpoint().equals(lease.key().endpoint().name())
                && row.targetId().equals(lease.key().targetId())
                && row.idempotencyKey().equals(lease.key().idempotencyKey())
                && row.requestFingerprint().equals(lease.requestFingerprint())
                && row.attemptNo() == lease.attemptNo()
                && row.attemptToken().equals(lease.attemptToken())
                && row.leaseUntil().equals(lease.leaseUntil())
                && row.expectedMode().equals(expectedMode(lease.expectedRevision()))
                && Objects.equals(row.expectedRevision(), expectedRevision(lease.expectedRevision()));
    }

    private static boolean matchesAuthority(AttemptAuthority row, CommandLease lease, String status) {
        return status.equals(row.status())
                && row.tenantId().equals(lease.key().scope().tenantId())
                && row.projectId().equals(lease.key().scope().projectId())
                && row.environmentId().equals(lease.key().scope().environmentId())
                && row.actorId().equals(lease.key().actorId())
                && row.endpoint().equals(lease.key().endpoint().name())
                && row.targetId().equals(lease.key().targetId())
                && row.idempotencyKey().equals(lease.key().idempotencyKey())
                && row.requestFingerprint().equals(lease.requestFingerprint())
                && row.attemptNo() == lease.attemptNo()
                && row.attemptToken().equals(lease.attemptToken())
                && row.leaseUntil().equals(lease.leaseUntil())
                && row.expectedMode().equals(expectedMode(lease.expectedRevision()))
                && Objects.equals(row.expectedRevision(), expectedRevision(lease.expectedRevision()));
    }

    private StagedRow staged(CommandLease lease) {
        List<StagedRow> rows = jdbc.query("""
                SELECT r.tenant_id, r.project_id, r.environment_id, r.resource_id,
                       r.revision, r.command_id, r.attempt_no, r.attempt_token,
                       r.spec_json, r.spec_fingerprint, r.connection_id, r.connection_revision,
                       r.connection_metadata_fingerprint, r.strong_etag,
                       p.descriptor_json, p.descriptor_fingerprint, p.descriptor_state,
                       p.design_contract_json, p.design_contract_fingerprint, p.design_contract_state,
                       p.operator_json, p.operator_fingerprint, p.operator_state, p.set_fingerprint
                  FROM rg_api_resource_revisions r
                  JOIN rg_api_resource_projection_revisions p
                    ON p.tenant_id = r.tenant_id
                   AND p.project_id = r.project_id
                   AND p.environment_id = r.environment_id
                   AND p.resource_id = r.resource_id
                   AND p.revision = r.revision
                   AND p.command_id = r.command_id
                 WHERE r.command_id = ?
                   AND r.attempt_no = ?
                   AND r.attempt_token = ?
                   AND r.state = 'STAGED'
                 FOR UPDATE""",
                (rs, n) -> new StagedRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), rs.getInt(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getLong(12),
                        rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
                        rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20),
                        rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(24)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (rows.size() > 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                    "staged resource provenance is ambiguous");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StagedApiResource stagedValue(CommandLease lease, StagedRow row) {
        try {
            ApiResourceSpec resource = mapper.readValue(row.specJson(), ApiResourceSpec.class);
            ReadyApiResourceProjections projections = new ReadyApiResourceProjections(
                    projection(ProjectionDocument.Kind.DESCRIPTOR, resource,
                            row.descriptorJson(), row.descriptorFingerprint()),
                    projection(ProjectionDocument.Kind.DESIGN_CONTRACT, resource,
                            row.designJson(), row.designFingerprint()),
                    projection(ProjectionDocument.Kind.OPERATOR, resource,
                            row.operatorJson(), row.operatorFingerprint()),
                    new ApiResourceConnectionSnapshot(row.connectionId(), row.connectionRevision(),
                            row.connectionMetadataFingerprint()));
            return new StagedApiResource(lease, resource, projections, row.etag());
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged resource is invalid");
        }
    }

    private record StagedRow(
            String tenantId,
            String projectId,
            String environmentId,
            String resourceId,
            long revision,
            String commandId,
            int attemptNo,
            String attemptToken,
            String specJson,
            String specFingerprint,
            String connectionId,
            long connectionRevision,
            String connectionMetadataFingerprint,
            String etag,
            String descriptorJson,
            String descriptorFingerprint,
            String descriptorState,
            String designJson,
            String designFingerprint,
            String designState,
            String operatorJson,
            String operatorFingerprint,
            String operatorState,
            String setFingerprint) {
    }

    /**
     * Verifies every persisted staged relationship before it can be returned or
     * committed. The expected resource is produced by the decision engine, so
     * an update never trusts a fingerprint recomputed as a CREATE operation.
     */
    private ApiResourceSpec verifiedStage(CommandLease lease, StagedRow r,
                                          ApiResourceSpec expected, String connectionId) {
        try {
            ApiResourceSpec actual = mapper.readValue(r.specJson(), ApiResourceSpec.class);
            ReadyApiResourceProjections p = new ReadyApiResourceProjections(
                    projection(ProjectionDocument.Kind.DESCRIPTOR, actual, r.descriptorJson(), r.descriptorFingerprint()),
                    projection(ProjectionDocument.Kind.DESIGN_CONTRACT, actual, r.designJson(), r.designFingerprint()),
                    projection(ProjectionDocument.Kind.OPERATOR, actual, r.operatorJson(), r.operatorFingerprint()),
                    new ApiResourceConnectionSnapshot(r.connectionId(), r.connectionRevision(),
                            r.connectionMetadataFingerprint()));
            if (!r.tenantId().equals(lease.key().scope().tenantId())
                    || !r.projectId().equals(lease.key().scope().projectId())
                    || !r.environmentId().equals(lease.key().scope().environmentId())
                    || !r.resourceId().equals(lease.key().targetId())
                    || r.revision() != expected.revision()
                    || !r.commandId().equals(lease.commandId())
                    || r.attemptNo() != lease.attemptNo()
                    || !r.attemptToken().equals(lease.attemptToken())
                    || !r.connectionId().equals(connectionId)
                    || r.connectionRevision() != p.connectionSnapshot().revision()
                    || !r.connectionMetadataFingerprint().equals(
                            p.connectionSnapshot().metadataFingerprint())
                    || !actual.equals(expected)
                    || !r.specFingerprint().equals(expected.fingerprint())
                    || !r.setFingerprint().equals(projectionSetFingerprint(actual, p))) {
                throw new IllegalArgumentException("staged integrity drift");
            }
            if (!ProjectionDocument.State.READY.name().equals(r.descriptorState())
                    || !ProjectionDocument.State.READY.name().equals(r.designState())
                    || !ProjectionDocument.State.READY.name().equals(r.operatorState())) {
                throw new IllegalArgumentException("staged projection state drift");
            }
            if (r.etag() == null || r.etag().isBlank()) {
                throw new IllegalArgumentException("staged etag drift");
            }
            verifyProjections(actual, p);
            return actual;
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged resource is invalid");
        }
    }

    private static String opaqueEtag() { return "\"" + UUID.randomUUID() + "\""; }

    private static void verifyProjections(ApiResourceSpec resource, ReadyApiResourceProjections projections) {
        if (projections == null || !resource.ref().equals(projections.subject())
                || !resource.connectionId().equals(projections.connectionSnapshot().connectionId())) {
            throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection subject drift");
        }
        for (ProjectionDocument document : new ProjectionDocument[]{projections.descriptor(), projections.designContract(), projections.operator()}) {
            if (document.state() != ProjectionDocument.State.READY
                    || !AuthoringFingerprints.of(document.body()).equals(document.fingerprint())) {
                throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID,
                        "projection integrity drift");
            }
        }
    }

    @Override
    public CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt) {
        try {
            return transactions.execute(status -> commitInTransaction(lease, finalReceipt));
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        }
        catch (DataAccessException ex) {
            throw error(isCommitRace(ex) ? ApiResourceCommitStoreException.Code.CAS_MISMATCH
                    : ApiResourceCommitStoreException.Code.INTEGRITY, "commit persistence failed");
        }
    }

    /** Maps database lock/unique races to CAS while retaining other failures as integrity errors. */
    private static boolean isCommitRace(DataAccessException failure) {
        if (failure instanceof ConcurrencyFailureException || failure instanceof DuplicateKeyException) {
            return true;
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "HYT00".equals(sql.getSQLState()))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Replays the decision seam for a stored command without using CREATE fingerprinting. */
    private ApiResourceSpec expectedStageSpec(CommandLease lease, ApiResourceSpec actual) {
        ApiResourceCommand command = new ApiResourceCommand(actual.displayName(), actual.description(),
                actual.operation(), actual.contract(), actual.response(), actual.effect(), actual.examples());
        try {
            return decisions.next(Optional.ofNullable(committedSpec(lease.key().scope(), lease.key().targetId())),
                    lease.key().targetId(), actual.connectionId(), command, lease.expectedRevision());
        } catch (ApiResourceAuthoringException ex) {
            if (ex.code() == ApiResourceAuthoringException.Code.ALREADY_EXISTS
                    || ex.code() == ApiResourceAuthoringException.Code.NOT_FOUND
                    || ex.code() == ApiResourceAuthoringException.Code.CAS_MISMATCH) {
                throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
            }
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged spec is invalid");
        }
    }

    private CommandReceipt commitInTransaction(CommandLease lease, CommandReceipt receipt) {
        requireActive(lease);
        StagedRow staged = staged(lease);
        if (staged == null) {
            throw error(ApiResourceCommitStoreException.Code.STAGE_MISSING, "staged resource is missing");
        }
        ApiResourceSpec resource;
        try {
            resource = mapper.readValue(staged.specJson(), ApiResourceSpec.class);
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged spec is invalid");
        }
        validateReceipt(receipt, staged.etag());
        jdbc.query(
                "SELECT revision FROM rg_api_resource_revisions "
                        + "WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? "
                        + "ORDER BY revision, command_id FOR UPDATE",
                (rs, n) -> rs.getLong(1), lease.key().scope().tenantId(),
                lease.key().scope().projectId(), lease.key().scope().environmentId(),
                lease.key().targetId());
        List<HeadRow> heads = jdbc.query(
                "SELECT revision, command_id, strong_etag FROM rg_api_resource_heads "
                        + "WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? FOR UPDATE",
                (rs, n) -> new HeadRow(rs.getLong(1), rs.getString(2), rs.getString(3)),
                lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().targetId());
        if ((lease.expectedRevision() instanceof ExpectedRevision.Create && !heads.isEmpty())
                || (lease.expectedRevision() instanceof ExpectedRevision.Match match
                && (heads.isEmpty() || heads.getFirst().revision() != match.revision()))) {
            throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
        }
        ApiResourceSpec expected = expectedStageSpec(lease, resource);
        verifiedStage(lease, staged, expected, resource.connectionId());
        if (jdbc.update(
                "UPDATE rg_api_resource_revisions SET state='COMMITTED' "
                        + "WHERE tenant_id=? AND project_id=? AND environment_id=? AND resource_id=? "
                        + "AND revision=? AND command_id=? AND attempt_no=? AND attempt_token=? "
                        + "AND state='STAGED'",
                staged.tenantId(), staged.projectId(), staged.environmentId(), staged.resourceId(),
                staged.revision(), staged.commandId(), staged.attemptNo(), staged.attemptToken()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "stage state changed");
        }
        if (jdbc.update("""
                UPDATE rg_authoring_command_attempts
                   SET status='COMMITTED', updated_at=CURRENT_TIMESTAMP
                 WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PREPARING'
                """, lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "command attempt state changed");
        }
        if (jdbc.update("""
                UPDATE rg_authoring_command_journal
                   SET status = 'COMMITTED', receipt_schema = ?, receipt_json = ?,
                       receipt_fingerprint = ?, receipt_etag = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ? AND status = 'PREPARING'
                   AND attempt_no = ? AND attempt_token = ?""",
                receipt.schemaVersion(), json(receipt.body()), receipt.bodyFingerprint(), receipt.strongEtag(),
                lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "command journal state changed");
        }
        if (heads.isEmpty()) {
            if (jdbc.update("""
                    INSERT INTO rg_api_resource_heads
                        (tenant_id, project_id, environment_id, resource_id, revision, command_id, strong_etag)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    staged.tenantId(), staged.projectId(), staged.environmentId(), staged.resourceId(),
                    staged.revision(), staged.commandId(), staged.etag()) != 1) {
                throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "head insert failed");
            }
        } else if (jdbc.update("""
                UPDATE rg_api_resource_heads
                   SET revision = ?, command_id = ?, strong_etag = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND project_id = ? AND environment_id = ? AND resource_id = ?
                   AND revision = ? AND revision_state = 'COMMITTED'""",
                staged.revision(), staged.commandId(), staged.etag(), staged.tenantId(), staged.projectId(),
                staged.environmentId(), staged.resourceId(), heads.getFirst().revision()) != 1) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "head update failed");
        }
        return receipt;
    }

    @Override
    public void fail(CommandLease lease, CommandFailureCode failureCode) {
        if (lease == null) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        if (failureCode == null) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "failure code is required");
        }
        try {
            transactions.executeWithoutResult(status -> {
                List<JournalRow> rows = jdbc.query(
                        "SELECT " + JOURNAL_COLUMNS + " FROM rg_authoring_command_journal "
                                + "WHERE command_id=? FOR UPDATE",
                        journalRowMapper(), lease.commandId());
                if (rows.isEmpty()) {
                    return;
                }
                JournalRow journal = rows.getFirst();
                if (!journal.attemptToken().equals(lease.attemptToken())
                        || journal.attemptNo() != lease.attemptNo()) {
                    return;
                }
                if ("COMMITTED".equals(journal.status())) {
                    throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                            "committed command cannot fail");
                }
                if ("FAILED".equals(journal.status())) {
                    // Pending-secret compensation may have terminalized this
                    // exact attempt first.  Remove only its staged resource
                    // row; never reopen or rewrite a newer journal attempt.
                    requireExactFailedAttempt(lease);
                    int removed = jdbc.update("DELETE FROM rg_api_resource_revisions "
                                    + "WHERE command_id=? AND attempt_no=? AND attempt_token=? "
                                    + "AND state='STAGED'",
                            lease.commandId(), lease.attemptNo(), lease.attemptToken());
                    if (removed == 0) {
                        throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED,
                                "failed command has no pending staged resource");
                    }
                    return;
                }
                requireActive(lease);
                failAfterJournalLockObserver.accept(lease.commandId());
                Long pending = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases "
                                + "WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                        Long.class, lease.commandId(), lease.attemptNo(), lease.attemptToken());
                if (pending != null && pending > 0) {
                    throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                            "pending secret compensation is required");
                }
                jdbc.update(
                        "DELETE FROM rg_api_resource_revisions "
                                + "WHERE command_id=? AND attempt_no=? AND attempt_token=? "
                                + "AND state='STAGED'",
                        lease.commandId(), lease.attemptNo(), lease.attemptToken());
                if (jdbc.update("""
                        UPDATE rg_authoring_command_attempts
                           SET status='FAILED', updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PREPARING'
                        """, lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
                    throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "command attempt state changed");
                }
                if (jdbc.update(
                        "UPDATE rg_authoring_command_journal SET status='FAILED', "
                                + "receipt_schema=NULL, receipt_json=NULL, receipt_fingerprint=NULL, "
                                + "receipt_etag=NULL, failure_code=?, updated_at=CURRENT_TIMESTAMP "
                                + "WHERE command_id=? AND status='PREPARING' AND attempt_no=? "
                                + "AND attempt_token=?",
                        failureCode.value(), lease.commandId(), lease.attemptNo(),
                        lease.attemptToken()) != 1) {
                    throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "command journal state changed");
                }
            });
        } catch (ApiResourceCommitStoreException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "fail persistence failed");
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "receipt serialization failed");
        }
    }

    private static void validateReceipt(CommandReceipt receipt, String etag) {
        if (receipt == null
                || !etag.equals(receipt.strongEtag())
                || !AuthoringFingerprints.of(receipt.body()).equals(receipt.bodyFingerprint())) {
            throw error(ApiResourceCommitStoreException.Code.RECEIPT_INVALID, "receipt is invalid");
        }
    }
}
