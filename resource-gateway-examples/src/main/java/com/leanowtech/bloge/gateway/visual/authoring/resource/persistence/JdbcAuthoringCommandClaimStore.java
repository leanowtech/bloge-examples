package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * JDBC command-claim adapter independent of Resource projection decisions.
 *
 * <p>It owns the journal/immutable-attempt claim transition. Projection-
 * specific abandoned-child cleanup is delegated to {@link
 * JdbcAuthoringAttemptCleanup} while the same transaction still holds the
 * prior journal lock. A caller supplies the JDBC transaction context so a
 * lifecycle-complete module can share one connection and transaction manager
 * with its projection store; this class never constructs or depends on
 * Resource decisions/compiler collaborators.</p>
 */
public final class JdbcAuthoringCommandClaimStore implements AuthoringCommandClaimStore {
    private static final String JOURNAL_COLUMNS = "tenant_id, project_id, environment_id, actor_id, endpoint, target_id, "
            + "idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until, "
            + "expected_mode, expected_revision, receipt_schema, receipt_json, receipt_fingerprint, receipt_etag, "
            + "failure_code, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcAuthoringAttemptCleanup attemptCleanup;
    private final ObjectMapper mapper;
    private final Duration leaseDuration;

    /** Creates the adapter over an existing same-DataSource JDBC transaction context. */
    public JdbcAuthoringCommandClaimStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                          ObjectMapper mapper, Duration leaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.attemptCleanup = new JdbcAuthoringAttemptCleanup(jdbc);
        DataSource jdbcDataSource = jdbc.getDataSource();
        DataSource transactionDataSource = transactions.getTransactionManager()
                instanceof DataSourceTransactionManager manager ? manager.getDataSource() : null;
        if (jdbcDataSource == null || transactionDataSource == null || jdbcDataSource != transactionDataSource) {
            throw new IllegalArgumentException("jdbc and transaction manager must share the same DataSource");
        }
    }

    /** Claims one command coordinate, retrying the only missing-row insert race. */
    @Override
    public ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        requireFingerprint(requestFingerprint);
        try {
            return transactions.execute(status -> claimInTransaction(key, requestFingerprint, expectedRevision));
        } catch (DuplicateKeyException duplicate) {
            try {
                return transactions.execute(status -> claimInTransaction(key, requestFingerprint, expectedRevision));
            } catch (AuthoringCommandClaimStoreException ex) {
                throw ex;
            } catch (DataAccessException ex) {
                throw error(AuthoringCommandClaimStoreException.Code.PERSISTENCE);
            } catch (RuntimeException ex) {
                throw error(AuthoringCommandClaimStoreException.Code.PERSISTENCE);
            }
        } catch (AuthoringCommandClaimStoreException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw error(AuthoringCommandClaimStoreException.Code.PERSISTENCE);
        } catch (RuntimeException ex) {
            throw error(AuthoringCommandClaimStoreException.Code.PERSISTENCE);
        }
    }

    private ClaimResult claimInTransaction(CommandKey key, String fingerprint, ExpectedRevision expected) {
        JournalRow prior = journalForCoordinate(key);
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
        if (prior != null && "PREPARING".equals(prior.status()) && prior.leaseUntil().isAfter(now)) {
            return new ClaimResult.Busy(prior.leaseUntil());
        }

        boolean resumed = prior != null;
        String commandId = prior == null ? java.util.UUID.randomUUID().toString() : prior.commandId();
        int attemptNo = prior == null ? 1 : prior.attemptNo() + 1;
        String attemptToken = java.util.UUID.randomUUID().toString();
        Instant leaseUntil = now.plus(leaseDuration);
        CommandLease incoming = new CommandLease(commandId, attemptNo, attemptToken, key, fingerprint,
                leaseUntil, expected);
        if (prior == null) {
            jdbc.update("""
                    INSERT INTO rg_authoring_command_journal
                        (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                         command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                         expected_mode, expected_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?, ?, ?)
                    """, key.scope().tenantId(), key.scope().projectId(), key.scope().environmentId(), key.actorId(),
                    key.endpoint().name(), key.targetId(), key.idempotencyKey(), commandId, fingerprint, attemptNo,
                    attemptToken, timestamp(leaseUntil), expectedMode(expected), expectedRevision(expected),
                    timestamp(now), timestamp(now));
            insertAttempt(incoming, now);
        } else {
            supersedeAttempt(prior);
            attemptCleanup.deleteAbandonedNestedConnectionStage(prior.commandId(), prior.attemptNo(),
                    prior.attemptToken());
            insertAttempt(incoming, now);
            jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id = ? AND attempt_no = ? "
                            + "AND attempt_token = ? AND state = 'STAGED'", commandId, prior.attemptNo(),
                    prior.attemptToken());
            jdbc.update("""
                    UPDATE rg_authoring_command_journal
                       SET request_fingerprint = ?, status = 'PREPARING', attempt_no = ?, attempt_token = ?,
                           lease_until = ?, expected_mode = ?, expected_revision = ?, receipt_schema = NULL,
                           receipt_json = NULL, receipt_fingerprint = NULL, receipt_etag = NULL, failure_code = NULL,
                           updated_at = ?
                     WHERE command_id = ?
                    """, fingerprint, attemptNo, attemptToken, timestamp(leaseUntil), expectedMode(expected),
                    expectedRevision(expected), timestamp(now), commandId);
        }
        return new ClaimResult.Acquired(incoming, resumed);
    }

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
                lease.attemptNo(), lease.attemptToken(), timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()),
                expectedRevision(lease.expectedRevision()), timestamp(now), timestamp(now)) != 1) {
            throw error(AuthoringCommandClaimStoreException.Code.INTEGRITY);
        }
    }

    private void supersedeAttempt(JournalRow prior) {
        if (!"PREPARING".equals(prior.status())) return;
        if (jdbc.update("UPDATE rg_authoring_command_attempts SET status='SUPERSEDED', updated_at=CURRENT_TIMESTAMP "
                        + "WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PREPARING'",
                prior.commandId(), prior.attemptNo(), prior.attemptToken()) != 1) {
            throw error(AuthoringCommandClaimStoreException.Code.LEASE_FENCED);
        }
    }

    private JournalRow journalForCoordinate(CommandKey key) {
        List<JournalRow> rows = jdbc.query("SELECT " + JOURNAL_COLUMNS + " FROM rg_authoring_command_journal "
                        + "WHERE tenant_id=? AND project_id=? AND environment_id=? AND actor_id=? AND endpoint=? "
                        + "AND target_id=? AND idempotency_key=? FOR UPDATE", journalRowMapper(),
                key.scope().tenantId(), key.scope().projectId(), key.scope().environmentId(), key.actorId(),
                key.endpoint().name(), key.targetId(), key.idempotencyKey());
        if (rows.size() > 1) throw error(AuthoringCommandClaimStoreException.Code.INTEGRITY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CommandReceipt receipt(JournalRow row) {
        try {
            return new CommandReceipt(row.receiptSchema(), mapper.readTree(row.receiptJson()),
                    row.receiptFingerprint(), row.receiptEtag());
        } catch (Exception ex) {
            throw error(AuthoringCommandClaimStoreException.Code.INTEGRITY);
        }
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, n) -> timestamp(rs, 1));
    }

    private static RowMapper<JournalRow> journalRowMapper() {
        return (rs, rowNum) -> new JournalRow(rs.getString("command_id"), rs.getString("request_fingerprint"),
                rs.getString("status"), rs.getInt("attempt_no"), rs.getString("attempt_token"),
                timestamp(rs, "lease_until"), rs.getString("expected_mode"),
                rs.getObject("expected_revision", Long.class), rs.getString("receipt_schema"),
                rs.getString("receipt_json"), rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        return timestampValue(rs.getObject(column));
    }

    private static Instant timestamp(ResultSet rs, int column) throws SQLException {
        return timestampValue(rs.getObject(column));
    }

    private static Instant timestampValue(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value.atOffset(ZoneOffset.UTC).toInstant());
    }

    private static String expectedMode(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Create ? "CREATE" : "MATCH";
    }

    private static Long expectedRevision(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Match match ? match.revision() : null;
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw error(AuthoringCommandClaimStoreException.Code.INTEGRITY);
        }
    }

    private static AuthoringCommandClaimStoreException error(AuthoringCommandClaimStoreException.Code code) {
        return new AuthoringCommandClaimStoreException(code);
    }

    private record JournalRow(String commandId, String requestFingerprint, String status, int attemptNo,
                              String attemptToken, Instant leaseUntil, String expectedMode, Long expectedRevision,
                              String receiptSchema, String receiptJson, String receiptFingerprint, String receiptEtag) { }
}
