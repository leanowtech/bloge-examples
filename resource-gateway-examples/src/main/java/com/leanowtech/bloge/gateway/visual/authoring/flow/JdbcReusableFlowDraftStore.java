package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * JDBC V014 authority for reusable Tool/Solution draft revisions.
 *
 * <p>Compilation happens before this store. One database transaction closes the idempotency
 * command, immutable revision and current head; no uncommitted state is observable. Exact replay
 * is resolved before head CAS so an earlier successful update remains replayable after later
 * revisions commit.</p>
 */
public final class JdbcReusableFlowDraftStore implements ReusableFlowDraftStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Supplier<String> identifiers;

    /** Creates a store using opaque UUID identifiers and the application transaction manager. */
    public JdbcReusableFlowDraftStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      ObjectMapper mapper) {
        this(jdbc, transactions, mapper, () -> UUID.randomUUID().toString());
    }

    JdbcReusableFlowDraftStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                               ObjectMapper mapper, Supplier<String> identifiers) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null || manager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("Flow store and transaction manager must share one DataSource");
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    @Override public ReusableFlowSaveResult save(ReusableFlowSaveIntent intent) {
        Objects.requireNonNull(intent, "intent");
        try {
            return required(transactions.execute(status -> saveInTransaction(intent)));
        } catch (DuplicateKeyException race) {
            try {
                return required(transactions.execute(status -> saveInTransaction(intent)));
            } catch (ReusableFlowFailure failure) {
                throw failure;
            } catch (DataAccessException failure) {
                throw new ReusableFlowFailure(ReusableFlowFailure.Code.PERSISTENCE);
            }
        } catch (ReusableFlowFailure failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.PERSISTENCE);
        } catch (RuntimeException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    @Override public Optional<ReusableFlowDraft> findHead(AuthoringScope scope, String flowId) {
        if (!validRead(scope, flowId)) return Optional.empty();
        try {
            Optional<HeadRow> head = head(scope, flowId, false);
            if (head.isEmpty()) return Optional.empty();
            Row row = revisionRow(scope, flowId, head.get().revision())
                    .orElseThrow(() -> new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY));
            Stored stored = decode(row);
            if (!head.get().matches(stored)) {
                throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
            }
            return Optional.of(stored.draft());
        } catch (ReusableFlowFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    @Override public Optional<ReusableFlowDraft> findRevision(
            AuthoringScope scope, String flowId, int revision) {
        if (!validRead(scope, flowId) || revision < 1) return Optional.empty();
        try {
            List<Row> rows = jdbc.query("""
                    SELECT revision, draft_id, content_fingerprint, draft_json,
                           receipt_json, strong_etag
                      FROM rg_authoring_flow_revisions
                     WHERE tenant_id=? AND project_id=? AND environment_id=?
                       AND flow_id=? AND revision=?
                    """, rowMapper(scope, flowId), scope.tenantId(), scope.projectId(),
                    scope.environmentId(), flowId, revision);
            return exact(rows).map(this::decode).map(Stored::draft);
        } catch (ReusableFlowFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private ReusableFlowSaveResult saveInTransaction(ReusableFlowSaveIntent intent) {
        Optional<CommandRow> command = command(intent, true);
        if (command.isPresent()) return replay(intent, command.get());

        Optional<HeadRow> head = head(intent.scope(), intent.flowId(), true);
        Optional<CommandRow> concurrentCommand = command(intent, false);
        if (concurrentCommand.isPresent()) return replay(intent, concurrentCommand.get());
        checkExpected(head, intent.expectedRevision());
        int revision = head.map(value -> Math.addExact(value.revision(), 1)).orElse(1);
        String draftId = head.map(HeadRow::draftId).orElseGet(() -> "draft-" + nextIdentifier());
        String strongEtag = "\"" + nextIdentifier() + "\"";
        ReusableFlowDraft draft = draft(intent, draftId, revision);
        ReusableFlowSaveReceipt receipt = receipt(draft);

        if (head.isEmpty()) insertIdentity(intent.scope(), intent.flowId(), draftId);
        insertRevision(intent, draft, receipt, strongEtag);
        if (head.isEmpty()) insertHead(intent.scope(), draft, strongEtag);
        else updateHead(intent.scope(), head.get(), draft, strongEtag);
        insertCommand(intent, revision, receipt, strongEtag);
        return new ReusableFlowSaveResult(draft, receipt, strongEtag, false);
    }

    private ReusableFlowSaveResult replay(ReusableFlowSaveIntent intent, CommandRow command) {
        if (!command.requestFingerprint().equals(intent.requestFingerprint())
                || !command.expected().equals(intent.expectedRevision())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.CONFLICT);
        }
        Row row = revisionRow(intent.scope(), intent.flowId(), command.committedRevision())
                .orElseThrow(() -> new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY));
        Stored stored = decode(row);
        ReusableFlowSaveReceipt commandReceipt = decode(command.receiptJson(), ReusableFlowSaveReceipt.class);
        if (!commandReceipt.equals(stored.receipt()) || !command.strongEtag().equals(stored.strongEtag())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return new ReusableFlowSaveResult(stored.draft(), stored.receipt(), stored.strongEtag(), true);
    }

    private Optional<CommandRow> command(ReusableFlowSaveIntent intent, boolean lock) {
        String sql = """
                SELECT request_fingerprint, expected_mode, expected_revision,
                       committed_revision, receipt_json, strong_etag
                  FROM rg_authoring_flow_commands
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND actor_id=? AND flow_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        List<CommandRow> rows = jdbc.query(sql, (rs, row) -> new CommandRow(rs.getString(1),
                expected(rs.getString(2), nullableLong(rs, 3)), Math.toIntExact(rs.getLong(4)),
                rs.getString(5), rs.getString(6)), intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.actorId(), intent.flowId(), intent.idempotencyKey());
        return exact(rows);
    }

    private Optional<HeadRow> head(AuthoringScope scope, String flowId, boolean lock) {
        String sql = """
                SELECT revision, draft_id, content_fingerprint, strong_etag
                  FROM rg_authoring_flow_heads
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<HeadRow> rows = jdbc.query(sql, (rs, row) -> new HeadRow(Math.toIntExact(rs.getLong(1)),
                rs.getString(2), rs.getString(3), rs.getString(4)), scope.tenantId(), scope.projectId(),
                scope.environmentId(), flowId);
        return exact(rows);
    }

    private Optional<Row> revisionRow(AuthoringScope scope, String flowId, int revision) {
        List<Row> rows = jdbc.query("""
                SELECT revision, draft_id, content_fingerprint, draft_json,
                       receipt_json, strong_etag
                  FROM rg_authoring_flow_revisions
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND flow_id=? AND revision=?
                """, rowMapper(scope, flowId), scope.tenantId(), scope.projectId(),
                scope.environmentId(), flowId, revision);
        return exact(rows);
    }

    private org.springframework.jdbc.core.RowMapper<Row> rowMapper(AuthoringScope scope, String flowId) {
        return (rs, row) -> new Row(scope, flowId, Math.toIntExact(rs.getLong(1)), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
    }

    private void insertIdentity(AuthoringScope scope, String flowId, String draftId) {
        jdbc.update("""
                INSERT INTO rg_authoring_flow_identities
                    (tenant_id, project_id, environment_id, flow_id, draft_id)
                VALUES (?, ?, ?, ?, ?)
                """, scope.tenantId(), scope.projectId(), scope.environmentId(), flowId, draftId);
    }

    private void insertRevision(ReusableFlowSaveIntent intent, ReusableFlowDraft draft,
                                ReusableFlowSaveReceipt receipt, String strongEtag) {
        jdbc.update("""
                INSERT INTO rg_authoring_flow_revisions
                    (tenant_id, project_id, environment_id, flow_id, revision, draft_id,
                     content_fingerprint, draft_json, receipt_json, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.flowId(), draft.revision(), draft.draftId(), draft.fingerprint(), encode(draft),
                encode(receipt), strongEtag);
    }

    private void insertHead(AuthoringScope scope, ReusableFlowDraft draft, String strongEtag) {
        jdbc.update("""
                INSERT INTO rg_authoring_flow_heads
                    (tenant_id, project_id, environment_id, flow_id, revision,
                     draft_id, content_fingerprint, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, scope.tenantId(), scope.projectId(), scope.environmentId(), draft.flowId(),
                draft.revision(), draft.draftId(), draft.fingerprint(), strongEtag);
    }

    private void updateHead(AuthoringScope scope, HeadRow prior,
                            ReusableFlowDraft draft, String strongEtag) {
        int updated = jdbc.update("""
                UPDATE rg_authoring_flow_heads
                   SET revision=?, draft_id=?, content_fingerprint=?, strong_etag=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                   AND revision=? AND draft_id=? AND content_fingerprint=? AND strong_etag=?
                """, draft.revision(), draft.draftId(), draft.fingerprint(), strongEtag,
                scope.tenantId(), scope.projectId(), scope.environmentId(), draft.flowId(),
                prior.revision(), prior.draftId(), prior.contentFingerprint(), prior.strongEtag());
        if (updated != 1) throw new ReusableFlowFailure(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    private void insertCommand(ReusableFlowSaveIntent intent, int revision,
                               ReusableFlowSaveReceipt receipt, String strongEtag) {
        ExpectedColumns expected = columns(intent.expectedRevision());
        jdbc.update("""
                INSERT INTO rg_authoring_flow_commands
                    (tenant_id, project_id, environment_id, actor_id, flow_id, idempotency_key,
                     request_fingerprint, expected_mode, expected_revision,
                     committed_revision, receipt_json, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.actorId(), intent.flowId(), intent.idempotencyKey(), intent.requestFingerprint(),
                expected.mode(), expected.revision(), revision, encode(receipt), strongEtag);
    }

    private Stored decode(Row row) {
        ReusableFlowDraft draft = decode(row.draftJson(), ReusableFlowDraft.class);
        ReusableFlowSaveReceipt receipt = decode(row.receiptJson(), ReusableFlowSaveReceipt.class);
        if (!row.flowId().equals(draft.flowId()) || row.revision() != draft.revision()
                || !row.draftId().equals(draft.draftId())
                || !row.contentFingerprint().equals(draft.fingerprint())
                || !receipt.flowId().equals(draft.flowId()) || !receipt.draft().equals(draft.subject())
                || !strongEtag(row.strongEtag())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return new Stored(draft, receipt, row.strongEtag());
    }

    private static ReusableFlowDraft draft(ReusableFlowSaveIntent intent, String draftId, int revision) {
        ReusableFlowCommand.Flow flow = intent.command().flow();
        return new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION, intent.flowId(), draftId,
                revision, intent.contentFingerprint(), flow.displayName(), flow.kind(), flow.description(),
                flow.contract(), flow.graph(), flow.layout(), ReusableFlowDraft.Status.DRAFT);
    }

    private static ReusableFlowSaveReceipt receipt(ReusableFlowDraft draft) {
        return new ReusableFlowSaveReceipt(ReusableFlowSaveReceipt.SCHEMA_VERSION, draft.flowId(),
                draft.subject(), ReusableFlowSaveReceipt.Validation.VALID);
    }

    private static void checkExpected(Optional<HeadRow> head, ExpectedRevision expected) {
        boolean mismatch = expected instanceof ExpectedRevision.Create && head.isPresent()
                || expected instanceof ExpectedRevision.Match match
                && (head.isEmpty() || head.get().revision() != match.revision());
        if (mismatch) throw new ReusableFlowFailure(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    private String nextIdentifier() {
        String value = identifiers.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return value;
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private static ExpectedRevision expected(String mode, Long revision) {
        return switch (mode) {
            case "CREATE" -> revision == null ? ExpectedRevision.create() : invalidExpected();
            case "MATCH" -> revision != null ? ExpectedRevision.match(revision) : invalidExpected();
            default -> invalidExpected();
        };
    }

    private static ExpectedRevision invalidExpected() {
        throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
    }

    private static ExpectedColumns columns(ExpectedRevision expected) {
        if (expected instanceof ExpectedRevision.Create) return new ExpectedColumns("CREATE", null);
        return new ExpectedColumns("MATCH", ((ExpectedRevision.Match) expected).revision());
    }

    private static Long nullableLong(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private static boolean strongEtag(String value) {
        if (value == null || value.length() < 3 || value.length() > 258
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }

    private static boolean validRead(AuthoringScope scope, String flowId) {
        return scope != null && flowId != null && flowId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static <T> Optional<T> exact(List<T> rows) {
        if (rows.size() > 1) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        return rows.stream().findFirst();
    }

    private static <T> T required(T value) {
        if (value == null) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        return value;
    }

    private record ExpectedColumns(String mode, Long revision) { }
    private record HeadRow(int revision, String draftId, String contentFingerprint, String strongEtag) {
        private boolean matches(Stored stored) {
            return revision == stored.draft().revision()
                    && draftId.equals(stored.draft().draftId())
                    && contentFingerprint.equals(stored.draft().fingerprint())
                    && strongEtag.equals(stored.strongEtag());
        }
    }
    private record CommandRow(String requestFingerprint, ExpectedRevision expected,
                              int committedRevision, String receiptJson, String strongEtag) { }
    private record Row(AuthoringScope scope, String flowId, int revision, String draftId,
                       String contentFingerprint, String draftJson, String receiptJson,
                       String strongEtag) { }
    private record Stored(ReusableFlowDraft draft, ReusableFlowSaveReceipt receipt, String strongEtag) { }
}
