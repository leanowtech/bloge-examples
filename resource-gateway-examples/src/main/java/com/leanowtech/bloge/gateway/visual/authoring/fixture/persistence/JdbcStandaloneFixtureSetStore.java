package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** JDBC V016–V019 authority for independently authored Flow and component Fixture revisions. */
public final class JdbcStandaloneFixtureSetStore implements StandaloneFixtureSetStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Supplier<String> identifiers;

    public JdbcStandaloneFixtureSetStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                         ObjectMapper mapper) {
        this(jdbc, transactions, mapper, () -> UUID.randomUUID().toString());
    }

    JdbcStandaloneFixtureSetStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                  ObjectMapper mapper, Supplier<String> identifiers) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null || manager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("Fixture store and transaction manager must share one DataSource");
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    @Override public StandaloneFixtureSetSaveResult save(StandaloneFixtureSetSaveIntent intent) {
        Objects.requireNonNull(intent, "intent");
        try {
            return required(transactions.execute(status -> saveInTransaction(intent)));
        } catch (DuplicateKeyException race) {
            try {
                return required(transactions.execute(status -> saveInTransaction(intent)));
            } catch (StandaloneFixtureSetStoreException failure) {
                throw failure;
            } catch (DataAccessException failure) {
                throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
            }
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public StandaloneFixtureSetShareResult share(
            StandaloneFixtureSetShareIntent intent, FixtureSetShareDeriver deriver) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(deriver, "deriver");
        try {
            return required(transactions.execute(status -> shareInTransaction(intent, deriver)));
        } catch (DuplicateKeyException race) {
            try {
                return required(transactions.execute(status -> shareInTransaction(intent, deriver)));
            } catch (StandaloneFixtureSetStoreException failure) {
                throw failure;
            } catch (DataAccessException failure) {
                throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
            }
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public StandaloneFixtureSetReviewResult review(
            StandaloneFixtureSetReviewIntent intent, FixtureSetReviewDeriver deriver) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(deriver, "deriver");
        try {
            return required(transactions.execute(status -> reviewInTransaction(intent, deriver)));
        } catch (DuplicateKeyException race) {
            try {
                return required(transactions.execute(status -> reviewInTransaction(intent, deriver)));
            } catch (StandaloneFixtureSetStoreException failure) {
                throw failure;
            } catch (DataAccessException failure) {
                throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
            }
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.PERSISTENCE);
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId) {
        if (!valid(scope, fixtureSetId)) return Optional.empty();
        try {
            Optional<HeadRow> head = head(scope, fixtureSetId, false);
            if (head.isEmpty()) return Optional.empty();
            StoredStandaloneFixtureSet stored = revision(scope, fixtureSetId, head.get().revision())
                    .map(this::decode).orElseThrow(() -> failure(
                            StandaloneFixtureSetStoreException.Code.INTEGRITY));
            if (!head.get().matches(stored)) {
                throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
            }
            return Optional.of(stored.stored());
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public Optional<StoredFixtureSet> findRevision(
            AuthoringScope scope, String fixtureSetId, int revision) {
        if (!valid(scope, fixtureSetId) || revision < 1) return Optional.empty();
        try {
            return revision(scope, fixtureSetId, revision).map(this::decode)
                    .map(StoredStandaloneFixtureSet::stored);
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public Optional<StoredStandaloneFixtureSet> findRevisionByStrongEtag(
            AuthoringScope scope, String fixtureSetId, String strongEtag) {
        if (!valid(scope, fixtureSetId) || !FixtureSetStrongEtag.isValid(strongEtag)) {
            return Optional.empty();
        }
        try {
            return exact(jdbc.query("""
                    SELECT revision, fixture_fingerprint, subject_kind, subject_publication_id,
                           subject_revision, subject_member_id, subject_fingerprint,
                           subject_runtime_fingerprint, generated_json, strong_etag
                      FROM rg_authoring_standalone_fixture_revisions
                     WHERE tenant_id=? AND project_id=? AND environment_id=?
                       AND fixture_set_id=? AND strong_etag=?
                    """, rowMapper(scope, fixtureSetId), scope.tenantId(), scope.projectId(),
                    scope.environmentId(), fixtureSetId, strongEtag)).map(this::decode);
        } catch (StandaloneFixtureSetStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    @Override public List<FixtureSetSummary> listSummariesBySubject(
            AuthoringScope scope, FixtureSubjectRef subject) {
        if (scope == null || !SubjectColumns.supported(subject)) return List.of();
        SubjectColumns coordinate = SubjectColumns.of(subject);
        try {
            List<Row> rows = jdbc.query("""
                    SELECT r.fixture_set_id, r.revision, r.fixture_fingerprint, r.subject_kind,
                           r.subject_publication_id, r.subject_revision, r.subject_member_id,
                           r.subject_fingerprint, r.subject_runtime_fingerprint,
                           r.generated_json, r.strong_etag
                      FROM rg_authoring_standalone_fixture_heads h
                      JOIN rg_authoring_standalone_fixture_revisions r
                        ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id
                       AND r.environment_id=h.environment_id AND r.fixture_set_id=h.fixture_set_id
                       AND r.revision=h.revision AND r.fixture_fingerprint=h.fixture_fingerprint
                       AND r.strong_etag=h.strong_etag
                     WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=?
                       AND r.subject_kind=? AND r.subject_publication_id=?
                       AND r.subject_revision=? AND COALESCE(r.subject_member_id, '')=?
                       AND r.subject_fingerprint=?
                       AND COALESCE(r.subject_runtime_fingerprint, '')=?
                     ORDER BY r.fixture_set_id
                    """, (rs, index) -> new Row(scope, rs.getString(1),
                            Math.toIntExact(rs.getLong(2)), rs.getString(3), rs.getString(4),
                            rs.getString(5), Math.toIntExact(rs.getLong(6)), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)),
                    scope.tenantId(), scope.projectId(), scope.environmentId(), coordinate.kind(),
                    coordinate.authorityId(), coordinate.revision(), coordinate.memberIdOrEmpty(),
                    coordinate.fingerprint(), coordinate.runtimeFingerprintOrEmpty());
            return rows.stream().map(this::decode).map(value -> value.stored().generated().summary()).toList();
        } catch (RuntimeException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private StandaloneFixtureSetSaveResult saveInTransaction(StandaloneFixtureSetSaveIntent intent) {
        Optional<CommandRow> command = command(intent, true);
        if (command.isPresent()) return replay(intent, command.get());
        Optional<HeadRow> head = head(intent.scope(), intent.fixtureSetId(), true);
        Optional<CommandRow> concurrent = command(intent, false);
        if (concurrent.isPresent()) return replay(intent, concurrent.get());
        checkExpected(head, intent.expectedRevision());
        if (head.isPresent()) {
            StoredStandaloneFixtureSet current = revision(
                    intent.scope(), intent.fixtureSetId(), head.get().revision())
                    .map(this::decode).orElseThrow(() -> failure(
                            StandaloneFixtureSetStoreException.Code.INTEGRITY));
            if (current.stored().generated().view().status()
                    != FixtureSetView.Status.PRIVATE_DRAFT) {
                throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
            }
        }
        int revision = head.map(value -> Math.addExact(value.revision(), 1)).orElse(1);
        if (intent.generated().view().revision() != revision) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        String strongEtag = "\"" + nextIdentifier() + "\"";
        if (head.isEmpty()) insertIdentity(intent);
        insertRevision(intent, strongEtag);
        if (head.isEmpty()) insertHead(intent, strongEtag);
        else updateHead(intent, head.get(), strongEtag);
        insertCommand(intent, strongEtag);
        return new StandaloneFixtureSetSaveResult(intent.generated().view(),
                intent.generated().receipt(), strongEtag, false);
    }

    private StandaloneFixtureSetShareResult shareInTransaction(
            StandaloneFixtureSetShareIntent intent, FixtureSetShareDeriver deriver) {
        Optional<ShareCommandRow> command = shareCommand(intent, true);
        if (command.isPresent()) return replayShare(intent, command.get());
        HeadRow head = head(intent.scope(), intent.fixtureSetId(), true)
                .orElseThrow(() -> failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH));
        Optional<ShareCommandRow> concurrent = shareCommand(intent, false);
        if (concurrent.isPresent()) return replayShare(intent, concurrent.get());
        StoredStandaloneFixtureSet source = revision(
                intent.scope(), intent.fixtureSetId(), head.revision())
                .map(this::decode).orElseThrow(() -> failure(
                        StandaloneFixtureSetStoreException.Code.INTEGRITY));
        requireExactShareSource(head, source, intent);
        int revision = Math.addExact(head.revision(), 1);
        int statusRevision = Math.addExact(
                source.stored().generated().view().statusRevision(), 1);
        String reviewRequestId = nextIdentifier();
        FixtureShareMaterialization materialization = deriver.derive(
                source.stored(), revision, statusRevision, reviewRequestId);
        requireExactShareMaterialization(source, materialization, revision,
                statusRevision, reviewRequestId);
        String strongEtag = "\"" + nextIdentifier() + "\"";
        insertSharedRevision(intent, materialization, strongEtag);
        updateSharedHead(intent, head, materialization, strongEtag);
        insertShareCommand(intent, materialization, strongEtag);
        insertReviewRequest(intent, materialization, strongEtag);
        return new StandaloneFixtureSetShareResult(
                materialization.generated().view(), materialization.receipt(), strongEtag, false);
    }

    private StandaloneFixtureSetReviewResult reviewInTransaction(
            StandaloneFixtureSetReviewIntent intent, FixtureSetReviewDeriver deriver) {
        Optional<ReviewCommandRow> command = reviewCommand(intent, true);
        if (command.isPresent()) return replayReview(intent, command.get());
        HeadRow head = head(intent.scope(), intent.fixtureSetId(), true)
                .orElseThrow(() -> failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH));
        Optional<ReviewCommandRow> concurrent = reviewCommand(intent, false);
        if (concurrent.isPresent()) return replayReview(intent, concurrent.get());
        StoredStandaloneFixtureSet source = revision(
                intent.scope(), intent.fixtureSetId(), head.revision())
                .map(this::decode).orElseThrow(() -> failure(
                        StandaloneFixtureSetStoreException.Code.INTEGRITY));
        requireExactReviewSource(head, source, intent);
        ReviewRequestRow request = reviewRequest(intent, true)
                .orElseThrow(() -> failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH));
        requireExactReviewRequest(request, intent);
        int revision = Math.addExact(head.revision(), 1);
        int statusRevision = Math.addExact(
                source.stored().generated().view().statusRevision(), 1);
        FixtureReviewMaterialization materialization = deriver.derive(
                source.stored(), revision, statusRevision);
        requireExactReviewMaterialization(source, materialization, revision, statusRevision,
                intent.command().source().reviewRequestId());
        String strongEtag = "\"" + nextIdentifier() + "\"";
        insertReviewedRevision(intent, materialization, strongEtag);
        updateReviewedHead(intent, head, materialization, strongEtag);
        completeReviewRequest(intent, materialization, strongEtag);
        insertReviewCommand(intent, materialization, strongEtag);
        return new StandaloneFixtureSetReviewResult(
                materialization.generated().view(), materialization.receipt(), strongEtag, false);
    }

    private StandaloneFixtureSetSaveResult replay(
            StandaloneFixtureSetSaveIntent intent, CommandRow command) {
        if (!command.requestFingerprint().equals(intent.requestFingerprint())
                || !command.expected().equals(intent.expectedRevision())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
        }
        StoredStandaloneFixtureSet stored = revision(
                        intent.scope(), intent.fixtureSetId(), command.committedRevision())
                .map(this::decode).orElseThrow(() -> failure(
                        StandaloneFixtureSetStoreException.Code.INTEGRITY));
        FixtureSetSaveReceipt receipt = decode(command.receiptJson(), FixtureSetSaveReceipt.class);
        if (!receipt.equals(stored.stored().generated().receipt())
                || !command.strongEtag().equals(stored.strongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return new StandaloneFixtureSetSaveResult(stored.stored().generated().view(),
                receipt, stored.strongEtag(), true);
    }

    private StandaloneFixtureSetShareResult replayShare(
            StandaloneFixtureSetShareIntent intent, ShareCommandRow command) {
        if (!command.requestFingerprint().equals(intent.requestFingerprint())
                || !command.sourceStrongEtag().equals(intent.sourceStrongEtag())
                || command.sourceRevision() != intent.command().source().revision()
                || !command.sourceFingerprint().equals(intent.command().source().fingerprint())
                || command.sourceStatusRevision() != intent.command().source().statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
        }
        StoredStandaloneFixtureSet stored = revision(
                        intent.scope(), intent.fixtureSetId(), command.committedRevision())
                .map(this::decode).orElseThrow(() -> failure(
                        StandaloneFixtureSetStoreException.Code.INTEGRITY));
        FixtureShareReceipt receipt = decode(command.receiptJson(), FixtureShareReceipt.class);
        if (!command.strongEtag().equals(stored.strongEtag())
                || !receipt.fixtureSetId().equals(stored.stored().generated().view().fixtureSetId())
                || receipt.revision() != stored.stored().generated().view().revision()
                || !receipt.fingerprint().equals(stored.stored().generated().view().fingerprint())
                || receipt.status() != stored.stored().generated().view().status()
                || receipt.statusRevision() != stored.stored().generated().view().statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return new StandaloneFixtureSetShareResult(
                stored.stored().generated().view(), receipt, stored.strongEtag(), true);
    }

    private StandaloneFixtureSetReviewResult replayReview(
            StandaloneFixtureSetReviewIntent intent, ReviewCommandRow command) {
        var source = intent.command().source();
        if (!command.requestFingerprint().equals(intent.requestFingerprint())
                || !command.reviewRequestId().equals(source.reviewRequestId())
                || command.sourceRevision() != source.revision()
                || !command.sourceFingerprint().equals(source.fingerprint())
                || command.sourceStatusRevision() != source.statusRevision()
                || !command.sourceStrongEtag().equals(intent.sourceStrongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CONFLICT);
        }
        StoredStandaloneFixtureSet stored = revision(
                        intent.scope(), intent.fixtureSetId(), command.committedRevision())
                .map(this::decode).orElseThrow(() -> failure(
                        StandaloneFixtureSetStoreException.Code.INTEGRITY));
        FixtureReviewReceipt receipt = decode(command.receiptJson(), FixtureReviewReceipt.class);
        if (!command.strongEtag().equals(stored.strongEtag())
                || !receipt.equals(new FixtureReviewReceipt(receipt.schemaVersion(),
                receipt.reviewRequestId(), stored.stored().generated().view().fixtureSetId(),
                receipt.derivedFromRevision(), stored.stored().generated().view().revision(),
                stored.stored().generated().view().fingerprint(),
                stored.stored().generated().view().status(),
                stored.stored().generated().view().statusRevision(),
                receipt.activatedAssetCount()))) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return new StandaloneFixtureSetReviewResult(
                stored.stored().generated().view(), receipt, stored.strongEtag(), true);
    }

    private Optional<CommandRow> command(StandaloneFixtureSetSaveIntent intent, boolean lock) {
        String sql = """
                SELECT request_fingerprint, expected_mode, expected_revision,
                       committed_revision, receipt_json, strong_etag
                  FROM rg_authoring_standalone_fixture_commands
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND actor_id=? AND fixture_set_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, index) -> new CommandRow(rs.getString(1),
                        expected(rs.getString(2), nullableLong(rs, 3)), Math.toIntExact(rs.getLong(4)),
                        rs.getString(5), rs.getString(6)),
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.actorId(), intent.fixtureSetId(), intent.idempotencyKey()));
    }

    private Optional<ShareCommandRow> shareCommand(
            StandaloneFixtureSetShareIntent intent, boolean lock) {
        String sql = """
                SELECT request_fingerprint, source_revision, source_fingerprint,
                       source_status_revision, source_strong_etag, committed_revision,
                       receipt_json, strong_etag
                  FROM rg_authoring_fixture_share_commands
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND actor_id=? AND fixture_set_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, index) -> new ShareCommandRow(
                        rs.getString(1), Math.toIntExact(rs.getLong(2)), rs.getString(3),
                        Math.toIntExact(rs.getLong(4)), rs.getString(5),
                        Math.toIntExact(rs.getLong(6)), rs.getString(7), rs.getString(8)),
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.actorId(), intent.fixtureSetId(), intent.idempotencyKey()));
    }

    private Optional<ReviewCommandRow> reviewCommand(
            StandaloneFixtureSetReviewIntent intent, boolean lock) {
        String sql = """
                SELECT request_fingerprint, review_request_id, source_revision,
                       source_fingerprint, source_status_revision, source_strong_etag,
                       committed_revision, receipt_json, strong_etag
                  FROM rg_authoring_fixture_review_commands
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND actor_id=? AND fixture_set_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, index) -> new ReviewCommandRow(
                        rs.getString(1), rs.getString(2), Math.toIntExact(rs.getLong(3)),
                        rs.getString(4), Math.toIntExact(rs.getLong(5)), rs.getString(6),
                        Math.toIntExact(rs.getLong(7)), rs.getString(8), rs.getString(9)),
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.actorId(), intent.fixtureSetId(), intent.idempotencyKey()));
    }

    private Optional<ReviewRequestRow> reviewRequest(
            StandaloneFixtureSetReviewIntent intent, boolean lock) {
        String sql = """
                SELECT fixture_set_id, derived_revision, derived_fingerprint,
                       derived_status_revision, derived_strong_etag, status, created_by
                  FROM rg_authoring_fixture_review_requests
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND review_request_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, index) -> new ReviewRequestRow(
                        rs.getString(1), Math.toIntExact(rs.getLong(2)), rs.getString(3),
                        Math.toIntExact(rs.getLong(4)), rs.getString(5), rs.getString(6),
                        rs.getString(7)), intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.command().source().reviewRequestId()));
    }

    private Optional<HeadRow> head(AuthoringScope scope, String fixtureSetId, boolean lock) {
        String sql = """
                SELECT revision, fixture_fingerprint, strong_etag
                  FROM rg_authoring_standalone_fixture_heads
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return exact(jdbc.query(sql, (rs, index) -> new HeadRow(
                        Math.toIntExact(rs.getLong(1)), rs.getString(2), rs.getString(3)),
                scope.tenantId(), scope.projectId(), scope.environmentId(), fixtureSetId));
    }

    private Optional<Row> revision(AuthoringScope scope, String fixtureSetId, int revision) {
        return exact(jdbc.query("""
                SELECT revision, fixture_fingerprint, subject_kind, subject_publication_id,
                       subject_revision, subject_member_id, subject_fingerprint,
                       subject_runtime_fingerprint, generated_json, strong_etag
                  FROM rg_authoring_standalone_fixture_revisions
                 WHERE tenant_id=? AND project_id=? AND environment_id=?
                   AND fixture_set_id=? AND revision=?
                """, rowMapper(scope, fixtureSetId), scope.tenantId(), scope.projectId(),
                scope.environmentId(), fixtureSetId, revision));
    }

    private org.springframework.jdbc.core.RowMapper<Row> rowMapper(
            AuthoringScope scope, String fixtureSetId) {
        return (rs, index) -> row(scope, fixtureSetId, rs);
    }

    private static Row row(AuthoringScope scope, String fixtureSetId, ResultSet rs) throws SQLException {
        return new Row(scope, fixtureSetId, Math.toIntExact(rs.getLong(1)), rs.getString(2),
                rs.getString(3), rs.getString(4), Math.toIntExact(rs.getLong(5)),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10));
    }

    private void insertIdentity(StandaloneFixtureSetSaveIntent intent) {
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_identities
                    (tenant_id, project_id, environment_id, fixture_set_id)
                VALUES (?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.fixtureSetId());
    }

    private void insertRevision(StandaloneFixtureSetSaveIntent intent, String strongEtag) {
        SubjectColumns subject = SubjectColumns.of(intent.generated().view().subject());
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_revisions
                    (tenant_id, project_id, environment_id, fixture_set_id, revision,
                     fixture_fingerprint, subject_kind, subject_publication_id, subject_revision,
                     subject_member_id, subject_fingerprint, subject_runtime_fingerprint,
                     generated_json, strong_etag, committed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.fixtureSetId(), intent.generated().view().revision(),
                intent.generated().view().fingerprint(), subject.kind(), subject.authorityId(),
                subject.revision(), subject.memberId(), subject.fingerprint(),
                subject.runtimeFingerprint(), encode(intent.generated()), strongEtag, intent.actorId());
    }

    private void insertSharedRevision(
            StandaloneFixtureSetShareIntent intent,
            FixtureShareMaterialization materialization,
            String strongEtag) {
        SubjectColumns subject = SubjectColumns.of(materialization.generated().view().subject());
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_revisions
                    (tenant_id, project_id, environment_id, fixture_set_id, revision,
                     fixture_fingerprint, subject_kind, subject_publication_id, subject_revision,
                     subject_member_id, subject_fingerprint, subject_runtime_fingerprint,
                     generated_json, strong_etag, committed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.fixtureSetId(),
                materialization.generated().view().revision(),
                materialization.generated().view().fingerprint(), subject.kind(), subject.authorityId(),
                subject.revision(), subject.memberId(), subject.fingerprint(), subject.runtimeFingerprint(),
                encode(materialization.generated()), strongEtag, intent.actorId());
    }

    private void insertReviewedRevision(
            StandaloneFixtureSetReviewIntent intent,
            FixtureReviewMaterialization materialization,
            String strongEtag) {
        SubjectColumns subject = SubjectColumns.of(materialization.generated().view().subject());
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_revisions
                    (tenant_id, project_id, environment_id, fixture_set_id, revision,
                     fixture_fingerprint, subject_kind, subject_publication_id, subject_revision,
                     subject_member_id, subject_fingerprint, subject_runtime_fingerprint,
                     generated_json, strong_etag, committed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.fixtureSetId(),
                materialization.generated().view().revision(),
                materialization.generated().view().fingerprint(), subject.kind(), subject.authorityId(),
                subject.revision(), subject.memberId(), subject.fingerprint(), subject.runtimeFingerprint(),
                encode(materialization.generated()), strongEtag, intent.actorId());
    }

    private void insertHead(StandaloneFixtureSetSaveIntent intent, String strongEtag) {
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_heads
                    (tenant_id, project_id, environment_id, fixture_set_id,
                     revision, fixture_fingerprint, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.fixtureSetId(), intent.generated().view().revision(),
                intent.generated().view().fingerprint(), strongEtag);
    }

    private void updateHead(StandaloneFixtureSetSaveIntent intent, HeadRow prior, String strongEtag) {
        int updated = jdbc.update("""
                UPDATE rg_authoring_standalone_fixture_heads
                   SET revision=?, fixture_fingerprint=?, strong_etag=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                   AND revision=? AND fixture_fingerprint=? AND strong_etag=?
                """, intent.generated().view().revision(), intent.generated().view().fingerprint(), strongEtag,
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.fixtureSetId(), prior.revision(), prior.fingerprint(), prior.strongEtag());
        if (updated != 1) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private void updateSharedHead(
            StandaloneFixtureSetShareIntent intent, HeadRow prior,
            FixtureShareMaterialization materialization, String strongEtag) {
        int updated = jdbc.update("""
                UPDATE rg_authoring_standalone_fixture_heads
                   SET revision=?, fixture_fingerprint=?, strong_etag=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                   AND revision=? AND fixture_fingerprint=? AND strong_etag=?
                """, materialization.generated().view().revision(),
                materialization.generated().view().fingerprint(), strongEtag,
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.fixtureSetId(), prior.revision(), prior.fingerprint(), prior.strongEtag());
        if (updated != 1) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private void updateReviewedHead(
            StandaloneFixtureSetReviewIntent intent, HeadRow prior,
            FixtureReviewMaterialization materialization, String strongEtag) {
        int updated = jdbc.update("""
                UPDATE rg_authoring_standalone_fixture_heads
                   SET revision=?, fixture_fingerprint=?, strong_etag=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                   AND revision=? AND fixture_fingerprint=? AND strong_etag=?
                """, materialization.generated().view().revision(),
                materialization.generated().view().fingerprint(), strongEtag,
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.fixtureSetId(), prior.revision(), prior.fingerprint(), prior.strongEtag());
        if (updated != 1) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private void insertCommand(StandaloneFixtureSetSaveIntent intent, String strongEtag) {
        ExpectedColumns expected = columns(intent.expectedRevision());
        jdbc.update("""
                INSERT INTO rg_authoring_standalone_fixture_commands
                    (tenant_id, project_id, environment_id, actor_id, fixture_set_id,
                     idempotency_key, request_fingerprint, expected_mode, expected_revision,
                     committed_revision, receipt_json, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.actorId(), intent.fixtureSetId(),
                intent.idempotencyKey(), intent.requestFingerprint(), expected.mode(), expected.revision(),
                intent.generated().view().revision(), encode(intent.generated().receipt()), strongEtag);
    }

    private void insertShareCommand(
            StandaloneFixtureSetShareIntent intent,
            FixtureShareMaterialization materialization,
            String strongEtag) {
        var source = intent.command().source();
        jdbc.update("""
                INSERT INTO rg_authoring_fixture_share_commands
                    (tenant_id, project_id, environment_id, actor_id, fixture_set_id,
                     idempotency_key, request_fingerprint, source_revision,
                     source_fingerprint, source_status_revision, source_strong_etag,
                     committed_revision, receipt_json, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.actorId(), intent.fixtureSetId(),
                intent.idempotencyKey(), intent.requestFingerprint(), source.revision(),
                source.fingerprint(), source.statusRevision(), intent.sourceStrongEtag(),
                materialization.generated().view().revision(), encode(materialization.receipt()),
                strongEtag);
    }

    private void insertReviewRequest(
            StandaloneFixtureSetShareIntent intent,
            FixtureShareMaterialization materialization,
            String strongEtag) {
        var source = intent.command().source();
        var receipt = materialization.receipt();
        jdbc.update("""
                INSERT INTO rg_authoring_fixture_review_requests
                    (tenant_id, project_id, environment_id, review_request_id, fixture_set_id,
                     source_revision, source_fingerprint, source_status_revision,
                     source_strong_etag, derived_revision, derived_fingerprint,
                     derived_status_revision, derived_strong_etag, policy_json,
                     status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), receipt.reviewRequestId(), intent.fixtureSetId(),
                source.revision(), source.fingerprint(), source.statusRevision(),
                intent.sourceStrongEtag(), receipt.revision(), receipt.fingerprint(),
                receipt.statusRevision(), strongEtag, encode(intent.command().policy()),
                intent.actorId());
    }

    private void completeReviewRequest(
            StandaloneFixtureSetReviewIntent intent,
            FixtureReviewMaterialization materialization,
            String strongEtag) {
        int updated = jdbc.update("""
                UPDATE rg_authoring_fixture_review_requests
                   SET status='COMPLETED', completed_revision=?, completed_fingerprint=?,
                       completed_strong_etag=?, completed_by=?, completed_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND review_request_id=?
                   AND fixture_set_id=? AND derived_revision=? AND derived_fingerprint=?
                   AND derived_status_revision=? AND derived_strong_etag=? AND status='PENDING'
                """, materialization.generated().view().revision(),
                materialization.generated().view().fingerprint(), strongEtag, intent.actorId(),
                intent.scope().tenantId(), intent.scope().projectId(), intent.scope().environmentId(),
                intent.command().source().reviewRequestId(), intent.fixtureSetId(),
                intent.command().source().revision(), intent.command().source().fingerprint(),
                intent.command().source().statusRevision(), intent.sourceStrongEtag());
        if (updated != 1) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private void insertReviewCommand(
            StandaloneFixtureSetReviewIntent intent,
            FixtureReviewMaterialization materialization,
            String strongEtag) {
        var source = intent.command().source();
        jdbc.update("""
                INSERT INTO rg_authoring_fixture_review_commands
                    (tenant_id, project_id, environment_id, actor_id, fixture_set_id,
                     idempotency_key, request_fingerprint, review_request_id,
                     source_revision, source_fingerprint, source_status_revision,
                     source_strong_etag, committed_revision, receipt_json, strong_etag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.scope().tenantId(), intent.scope().projectId(),
                intent.scope().environmentId(), intent.actorId(), intent.fixtureSetId(),
                intent.idempotencyKey(), intent.requestFingerprint(), source.reviewRequestId(),
                source.revision(), source.fingerprint(), source.statusRevision(),
                intent.sourceStrongEtag(), materialization.generated().view().revision(),
                encode(materialization.receipt()), strongEtag);
    }

    private StoredStandaloneFixtureSet decode(Row row) {
        GeneratedDefaultFixture generated = decode(row.generatedJson(), GeneratedDefaultFixture.class);
        FixtureSubjectRef subject = SubjectColumns.subject(row);
        if (!generated.view().fixtureSetId().equals(row.fixtureSetId())
                || generated.view().revision() != row.revision()
                || !generated.view().fingerprint().equals(row.fixtureFingerprint())
                || !generated.view().subject().equals(subject)) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return new StoredStandaloneFixtureSet(
                new StoredFixtureSet(row.scope(), generated, row.strongEtag()), row.strongEtag());
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private static void checkExpected(Optional<HeadRow> head, ExpectedRevision expected) {
        boolean mismatch = expected instanceof ExpectedRevision.Create && head.isPresent()
                || expected instanceof ExpectedRevision.Match match
                && (head.isEmpty() || head.get().revision() != match.revision());
        if (mismatch) throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
    }

    private static void requireExactShareSource(
            HeadRow head, StoredStandaloneFixtureSet source,
            StandaloneFixtureSetShareIntent intent) {
        var view = source.stored().generated().view();
        var expected = intent.command().source();
        if (!head.matches(source) || !head.strongEtag().equals(intent.sourceStrongEtag())
                || view.status() != FixtureSetView.Status.PRIVATE_DRAFT
                || view.revision() != expected.revision()
                || !view.fingerprint().equals(expected.fingerprint())
                || view.statusRevision() != expected.statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
    }

    private static void requireExactShareMaterialization(
            StoredStandaloneFixtureSet source, FixtureShareMaterialization materialization,
            int revision, int statusRevision, String reviewRequestId) {
        if (materialization == null
                || materialization.receipt().derivedFromRevision()
                != source.stored().generated().view().revision()
                || materialization.receipt().revision() != revision
                || materialization.receipt().statusRevision() != statusRevision
                || !materialization.receipt().reviewRequestId().equals(reviewRequestId)) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private static void requireExactReviewSource(
            HeadRow head, StoredStandaloneFixtureSet source,
            StandaloneFixtureSetReviewIntent intent) {
        var view = source.stored().generated().view();
        var expected = intent.command().source();
        if (!head.matches(source) || !head.strongEtag().equals(intent.sourceStrongEtag())
                || view.status() != FixtureSetView.Status.SHARING_PENDING
                || view.revision() != expected.revision()
                || !view.fingerprint().equals(expected.fingerprint())
                || view.statusRevision() != expected.statusRevision()) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
    }

    private static void requireExactReviewRequest(
            ReviewRequestRow request, StandaloneFixtureSetReviewIntent intent) {
        var source = intent.command().source();
        if (!"PENDING".equals(request.status()) || request.createdBy().equals(intent.actorId())
                || !request.fixtureSetId().equals(intent.fixtureSetId())
                || request.derivedRevision() != source.revision()
                || !request.derivedFingerprint().equals(source.fingerprint())
                || request.derivedStatusRevision() != source.statusRevision()
                || !request.derivedStrongEtag().equals(intent.sourceStrongEtag())) {
            throw failure(StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        }
    }

    private static void requireExactReviewMaterialization(
            StoredStandaloneFixtureSet source, FixtureReviewMaterialization materialization,
            int revision, int statusRevision, String reviewRequestId) {
        if (materialization == null
                || materialization.receipt().derivedFromRevision()
                != source.stored().generated().view().revision()
                || materialization.receipt().revision() != revision
                || materialization.receipt().statusRevision() != statusRevision
                || !materialization.receipt().reviewRequestId().equals(reviewRequestId)) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
    }

    private String nextIdentifier() {
        String value = identifiers.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }
        return value;
    }

    private static ExpectedRevision expected(String mode, Long revision) {
        if ("CREATE".equals(mode) && revision == null) return ExpectedRevision.create();
        if ("MATCH".equals(mode) && revision != null && revision > 0) {
            return ExpectedRevision.match(revision);
        }
        throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
    }

    private static ExpectedColumns columns(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Create
                ? new ExpectedColumns("CREATE", null)
                : new ExpectedColumns("MATCH", ((ExpectedRevision.Match) expected).revision());
    }

    private static Long nullableLong(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? null : value;
    }

    private static boolean valid(AuthoringScope scope, String fixtureSetId) {
        return scope != null && fixtureSetId != null
                && fixtureSetId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static <T> Optional<T> exact(List<T> values) {
        if (values.size() > 1) throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        return values.stream().findFirst();
    }

    private static <T> T required(T value) {
        if (value == null) throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        return value;
    }

    private static StandaloneFixtureSetStoreException failure(StandaloneFixtureSetStoreException.Code code) {
        return new StandaloneFixtureSetStoreException(code);
    }

    private record Row(AuthoringScope scope, String fixtureSetId, int revision,
                       String fixtureFingerprint, String subjectKind, String subjectAuthorityId,
                       int subjectRevision, String subjectMemberId, String subjectFingerprint,
                       String subjectRuntimeFingerprint, String generatedJson, String strongEtag) { }

    private record SubjectColumns(
            String kind, String authorityId, int revision, String memberId,
            String fingerprint, String runtimeFingerprint) {
        static boolean supported(FixtureSubjectRef subject) {
            return subject instanceof FixtureSubjectRef.FlowDraft
                    || subject instanceof FixtureSubjectRef.FlowVersion
                    || subject instanceof FixtureSubjectRef.OperatorVersion
                    || subject instanceof FixtureSubjectRef.BuiltinFunctionVersion;
        }

        static SubjectColumns of(FixtureSubjectRef subject) {
            if (subject instanceof FixtureSubjectRef.FlowDraft value) {
                return new SubjectColumns(value.kind(), value.draftId(), value.revision(), null,
                        value.fingerprint(), null);
            }
            if (subject instanceof FixtureSubjectRef.FlowVersion value) {
                return new SubjectColumns(value.kind(), value.publicationId(), value.revision(), null,
                        value.fingerprint(), null);
            }
            if (subject instanceof FixtureSubjectRef.OperatorVersion value) {
                return new SubjectColumns(value.kind(), value.libraryId(), value.libraryRevision(),
                        value.operatorRef(), value.contractFingerprint(), null);
            }
            if (subject instanceof FixtureSubjectRef.BuiltinFunctionVersion value) {
                return new SubjectColumns(value.kind(), value.catalogId(), value.catalogRevision(),
                        value.functionName(), value.signatureFingerprint(), value.runtimeFingerprint());
            }
            throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
        }

        static FixtureSubjectRef subject(Row row) {
            return switch (row.subjectKind()) {
                case "FLOW_DRAFT" -> new FixtureSubjectRef.FlowDraft(
                        row.subjectAuthorityId(), row.subjectRevision(), row.subjectFingerprint());
                case "FLOW_VERSION" -> new FixtureSubjectRef.FlowVersion(
                        row.subjectAuthorityId(), row.subjectRevision(), row.subjectFingerprint());
                case "OPERATOR_VERSION" -> new FixtureSubjectRef.OperatorVersion(
                        row.subjectAuthorityId(), row.subjectRevision(), row.subjectMemberId(),
                        row.subjectFingerprint());
                case "BUILTIN_FUNCTION_VERSION" -> new FixtureSubjectRef.BuiltinFunctionVersion(
                        row.subjectAuthorityId(), row.subjectRevision(), row.subjectMemberId(),
                        row.subjectFingerprint(), row.subjectRuntimeFingerprint());
                default -> throw failure(StandaloneFixtureSetStoreException.Code.INTEGRITY);
            };
        }

        String memberIdOrEmpty() { return memberId == null ? "" : memberId; }
        String runtimeFingerprintOrEmpty() {
            return runtimeFingerprint == null ? "" : runtimeFingerprint;
        }
    }
    private record HeadRow(int revision, String fingerprint, String strongEtag) {
        boolean matches(StoredStandaloneFixtureSet value) {
            return revision == value.stored().generated().view().revision()
                    && fingerprint.equals(value.stored().generated().view().fingerprint())
                    && strongEtag.equals(value.strongEtag());
        }
    }
    private record CommandRow(String requestFingerprint, ExpectedRevision expected,
                              int committedRevision, String receiptJson, String strongEtag) { }
    private record ShareCommandRow(
            String requestFingerprint, int sourceRevision, String sourceFingerprint,
            int sourceStatusRevision, String sourceStrongEtag, int committedRevision,
            String receiptJson, String strongEtag) { }
    private record ReviewCommandRow(
            String requestFingerprint, String reviewRequestId, int sourceRevision,
            String sourceFingerprint, int sourceStatusRevision, String sourceStrongEtag,
            int committedRevision, String receiptJson, String strongEtag) { }
    private record ReviewRequestRow(
            String fixtureSetId, int derivedRevision, String derivedFingerprint,
            int derivedStatusRevision, String derivedStrongEtag, String status,
            String createdBy) { }
    private record ExpectedColumns(String mode, Long revision) { }
}
