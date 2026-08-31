package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * JDBC V015 authority for immutable reusable Flow versions.
 *
 * <p>Publication locks the V014 Flow identity, so identity creation, monotonic version allocation,
 * immutable snapshot persistence, and idempotency receipt commit happen in one local transaction.
 * Exact catalog reads never consult a mutable Draft or a latest-version alias.</p>
 */
public final class JdbcReusableFlowPublicationStore implements ReusableFlowPublicationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Supplier<String> identifiers;
    private final Clock clock;

    /** Creates a publication authority with opaque UUID identities and UTC timestamps. */
    public JdbcReusableFlowPublicationStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                             ObjectMapper mapper) {
        this(jdbc, transactions, mapper, () -> "publication-" + UUID.randomUUID(), Clock.systemUTC());
    }

    JdbcReusableFlowPublicationStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      ObjectMapper mapper, Supplier<String> identifiers, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null || manager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("Flow publication store and transaction manager must share one DataSource");
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public ReusableFlowPublishResult publish(ReusableFlowPublishIntent intent) {
        Objects.requireNonNull(intent, "intent");
        try {
            ReusableFlowPublishResult result = transactions.execute(status -> publishInTransaction(intent));
            if (result == null) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
            return result;
        } catch (ReusableFlowFailure failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.PERSISTENCE);
        } catch (RuntimeException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    @Override public Optional<ReusableFlowVersion> findVersion(
            AuthoringScope scope, String publicationId, int revision) {
        if (scope == null || publicationId == null || revision < 1) return Optional.empty();
        try {
            return exact(jdbc.query("""
                    SELECT flow_id, version_fingerprint, source_draft_id, source_revision,
                           source_fingerprint, version_json, receipt_json
                      FROM rg_authoring_flow_versions
                     WHERE tenant_id=? AND project_id=? AND environment_id=?
                       AND publication_id=? AND revision=? AND status='PUBLISHED'
                    """, (rs, row) -> new VersionRow(scope, publicationId, revision,
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            Math.toIntExact(rs.getLong(4)), rs.getString(5), rs.getString(6),
                            rs.getString(7)), scope.tenantId(), scope.projectId(), scope.environmentId(),
                    publicationId, revision)).map(this::decode).map(StoredVersion::version);
        } catch (ReusableFlowFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private ReusableFlowPublishResult publishInTransaction(ReusableFlowPublishIntent intent) {
        Optional<CommandRow> prior = command(intent, true);
        if (prior.isPresent()) return replay(intent, prior.get());
        lockFlowIdentity(intent);
        Optional<CommandRow> concurrent = command(intent, false);
        if (concurrent.isPresent()) return replay(intent, concurrent.get());
        requireExactSource(intent);
        String publicationId = publicationIdentity(intent).orElseGet(() -> insertIdentity(intent));
        int revision = nextRevision(intent.scope(), publicationId);
        ReusableFlowVersion version = version(intent, publicationId, revision);
        ReusableFlowPublishReceipt receipt = receipt(version, intent.draft().subject());
        insertVersion(intent, version, receipt);
        insertCommand(intent, version, receipt);
        return new ReusableFlowPublishResult(version, receipt, false);
    }

    private ReusableFlowPublishResult replay(ReusableFlowPublishIntent intent, CommandRow prior) {
        if (!prior.requestFingerprint().equals(intent.requestFingerprint())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.CONFLICT);
        }
        StoredVersion stored = versionRow(intent.scope(), prior.publicationId(), prior.revision())
                .map(this::decode).orElseThrow(() -> new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY));
        ReusableFlowPublishReceipt commandReceipt = decode(prior.receiptJson(), ReusableFlowPublishReceipt.class);
        if (!commandReceipt.equals(stored.receipt())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return new ReusableFlowPublishResult(stored.version(), stored.receipt(), true);
    }

    private Optional<CommandRow> command(ReusableFlowPublishIntent intent, boolean lock) {
        String sql = """
                SELECT request_fingerprint, publication_id, committed_revision, receipt_json
                  FROM rg_authoring_flow_publish_commands
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND actor_id=? AND flow_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, row) -> new CommandRow(rs.getString(1), rs.getString(2),
                Math.toIntExact(rs.getLong(3)), rs.getString(4)), intent.scope().tenantId(),
                intent.scope().projectId(), intent.scope().environmentId(), intent.actorId(),
                intent.flowId(), intent.idempotencyKey()));
    }

    private void lockFlowIdentity(ReusableFlowPublishIntent intent) {
        List<String> rows = jdbc.query("""
                SELECT draft_id FROM rg_authoring_flow_identities
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                 FOR UPDATE
                """, (rs, row) -> rs.getString(1), intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.flowId());
        if (rows.size() != 1 || !rows.getFirst().equals(intent.draft().draftId())) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private void requireExactSource(ReusableFlowPublishIntent intent) {
        List<Integer> rows = jdbc.query("""
                SELECT 1 FROM rg_authoring_flow_revisions
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                   AND revision=? AND draft_id=? AND content_fingerprint=?
                """, (rs, row) -> rs.getInt(1), intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.flowId(), intent.draft().revision(),
                intent.draft().draftId(), intent.draft().fingerprint());
        if (rows.size() != 1) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
    }

    private Optional<String> publicationIdentity(ReusableFlowPublishIntent intent) {
        return exact(jdbc.query("""
                SELECT publication_id FROM rg_authoring_flow_publication_identities
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND flow_id=?
                """, (rs, row) -> rs.getString(1), intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.flowId()));
    }

    private String insertIdentity(ReusableFlowPublishIntent intent) {
        String publicationId = nextIdentifier();
        jdbc.update("""
                INSERT INTO rg_authoring_flow_publication_identities
                    (tenant_id, project_id, environment_id, flow_id, publication_id)
                VALUES (?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.flowId(), publicationId);
        return publicationId;
    }

    private int nextRevision(AuthoringScope scope, String publicationId) {
        Integer revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1 FROM rg_authoring_flow_versions
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND publication_id=?
                """, Integer.class, scope.tenantId(), scope.projectId(), scope.environmentId(), publicationId);
        if (revision == null || revision < 1) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        return revision;
    }

    private void insertVersion(ReusableFlowPublishIntent intent, ReusableFlowVersion version,
                               ReusableFlowPublishReceipt receipt) {
        jdbc.update("""
                INSERT INTO rg_authoring_flow_versions
                    (tenant_id, project_id, environment_id, publication_id, revision, flow_id,
                     version_fingerprint, source_draft_id, source_revision, source_fingerprint,
                     version_json, receipt_json, published_at, published_by, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLISHED')
                """, intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                version.publicationId(), version.revision(), intent.flowId(), version.fingerprint(),
                version.source().draftId(), version.source().revision(), version.source().fingerprint(),
                encode(version), encode(receipt), java.sql.Timestamp.from(version.publishedAt()),
                version.publishedBy());
    }

    private void insertCommand(ReusableFlowPublishIntent intent, ReusableFlowVersion version,
                               ReusableFlowPublishReceipt receipt) {
        jdbc.update("""
                INSERT INTO rg_authoring_flow_publish_commands
                    (tenant_id, project_id, environment_id, actor_id, flow_id, idempotency_key,
                     request_fingerprint, publication_id, committed_revision, receipt_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.actorId(), intent.flowId(), intent.idempotencyKey(), intent.requestFingerprint(),
                version.publicationId(), version.revision(), encode(receipt));
    }

    private Optional<VersionRow> versionRow(AuthoringScope scope, String publicationId, int revision) {
        return exact(jdbc.query("""
                SELECT flow_id, version_fingerprint, source_draft_id, source_revision,
                       source_fingerprint, version_json, receipt_json
                  FROM rg_authoring_flow_versions
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND publication_id=? AND revision=? AND status='PUBLISHED'
                """, (rs, row) -> new VersionRow(scope, publicationId, revision, rs.getString(1),
                        rs.getString(2), rs.getString(3), Math.toIntExact(rs.getLong(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7)), scope.tenantId(),
                scope.projectId(), scope.environmentId(), publicationId, revision));
    }

    private StoredVersion decode(VersionRow row) {
        ReusableFlowVersion version = decode(row.versionJson(), ReusableFlowVersion.class);
        ReusableFlowPublishReceipt receipt = decode(row.receiptJson(), ReusableFlowPublishReceipt.class);
        if (!row.publicationId().equals(version.publicationId()) || row.revision() != version.revision()
                || !row.flowId().equals(version.flowId()) || !row.versionFingerprint().equals(version.fingerprint())
                || !row.sourceDraftId().equals(version.source().draftId())
                || row.sourceRevision() != version.source().revision()
                || !row.sourceFingerprint().equals(version.source().fingerprint())
                || !receipt.source().equals(new FixtureSubjectRef.FlowDraft(row.sourceDraftId(),
                        row.sourceRevision(), row.sourceFingerprint()))
                || !receipt.version().equals(version.subject())
                || receipt.catalog() != ReusableFlowPublishReceipt.Catalog.AVAILABLE) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return new StoredVersion(version, receipt);
    }

    private ReusableFlowVersion version(ReusableFlowPublishIntent intent, String publicationId, int revision) {
        ReusableFlowDraft draft = intent.draft();
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, publicationId, revision,
                intent.versionFingerprint(), new ReusableFlowVersion.Source(draft.draftId(),
                draft.revision(), draft.fingerprint()), draft.flowId(), draft.displayName(), draft.kind(),
                draft.description(), draft.contract(), draft.graph(), clock.instant(), intent.actorId(),
                ReusableFlowVersion.Status.PUBLISHED);
    }

    private static ReusableFlowPublishReceipt receipt(
            ReusableFlowVersion version, FixtureSubjectRef.FlowDraft source) {
        return new ReusableFlowPublishReceipt(ReusableFlowPublishReceipt.SCHEMA_VERSION, source,
                version.subject(), ReusableFlowPublishReceipt.Catalog.AVAILABLE);
    }

    private String nextIdentifier() {
        String value = identifiers.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return value;
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception failure) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
    }

    private static <T> Optional<T> exact(List<T> rows) {
        if (rows.size() > 1) throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        return rows.stream().findFirst();
    }

    private record CommandRow(String requestFingerprint, String publicationId,
                              int revision, String receiptJson) { }
    private record VersionRow(AuthoringScope scope, String publicationId, int revision, String flowId,
                              String versionFingerprint, String sourceDraftId, int sourceRevision,
                              String sourceFingerprint, String versionJson, String receiptJson) { }
    private record StoredVersion(ReusableFlowVersion version, ReusableFlowPublishReceipt receipt) { }
}
