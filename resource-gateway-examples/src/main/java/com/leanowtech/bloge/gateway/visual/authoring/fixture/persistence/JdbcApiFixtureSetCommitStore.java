package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException.Code;

/** JDBC V012 adapter for private Fixture children of an API Resource save. */
public final class JdbcApiFixtureSetCommitStore implements ApiFixtureSetCommitStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DataSource dataSource;
    private final ApiResourceDecisions resourceDecisions;

    /** Convenience constructor for isolated tests over one DataSource. */
    public JdbcApiFixtureSetCommitStore(DataSource dataSource, ObjectMapper mapper) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), mapper);
    }

    /** Creates an adapter using the application's exact transaction manager. */
    public JdbcApiFixtureSetCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.resourceDecisions = new ApiResourceDecisions(this.mapper);
        DataSource jdbcDataSource = jdbc.getDataSource();
        DataSource transactionDataSource = transactions.getTransactionManager()
                instanceof DataSourceTransactionManager manager ? manager.getDataSource() : null;
        if (jdbcDataSource == null || transactionDataSource == null
                || jdbcDataSource != transactionDataSource) {
            throw new IllegalArgumentException("JdbcTemplate and transaction manager must share one DataSource");
        }
        this.dataSource = jdbcDataSource;
    }

    @Override
    public StagedFixtureSet stage(CommandLease lease, GeneratedDefaultFixture generated) {
        try {
            return transactions.execute(status -> stageInTransaction(lease, generated));
        } catch (ApiFixtureSetCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(Code.INTEGRITY);
        }
    }

    private StagedFixtureSet stageInTransaction(CommandLease lease, GeneratedDefaultFixture generated) {
        requireAuthority(lease, "PREPARING", true);
        StagedFixtureSet staged;
        try {
            staged = new StagedFixtureSet(lease, generated);
        } catch (IllegalArgumentException ex) {
            throw failure(Code.INTEGRITY);
        }
        insertIdentity(lease.key().scope(), generated.view().fixtureSetId());
        lockIdentity(lease.key().scope(), generated.view().fixtureSetId());
        deleteSupersededStages(lease);
        if (headExists(lease.key().scope(), generated.view().fixtureSetId())) {
            throw failure(Code.CAS_MISMATCH);
        }
        List<StagedFixtureSet> prior = stagedByAttempt(lease, false);
        if (prior.size() > 1) throw failure(Code.INTEGRITY);
        if (prior.size() == 1) {
            if (!prior.getFirst().equals(staged)) throw failure(Code.INTEGRITY);
            return prior.getFirst();
        }
        FixtureSubjectRef.ApiResource subject = (FixtureSubjectRef.ApiResource) generated.view().subject();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_api_fixture_set_revisions
                        (tenant_id, project_id, environment_id, fixture_set_id, revision, state,
                         fingerprint, status, status_revision, subject_kind, subject_id,
                         subject_revision, subject_fingerprint, generated_json, command_id,
                         attempt_no, attempt_token)
                    VALUES (?, ?, ?, ?, ?, 'STAGED', ?, 'PRIVATE_DRAFT', ?, 'API_RESOURCE', ?, ?, ?, ?, ?, ?, ?)
                    """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                    lease.key().scope().environmentId(), generated.view().fixtureSetId(),
                    generated.view().revision(), generated.view().fingerprint(),
                    generated.view().statusRevision(), subject.resourceId(), subject.revision(),
                    subject.fingerprint(), mapper.writeValueAsString(generated), lease.commandId(),
                    lease.attemptNo(), lease.attemptToken());
            if (inserted != 1) throw failure(Code.INTEGRITY);
        } catch (JsonProcessingException ex) {
            throw failure(Code.INTEGRITY);
        }
        return staged;
    }

    @Override
    public StoredFixtureSet commitChild(CommandLease lease) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw failure(Code.INTEGRITY);
        }
        requireAuthority(lease, "PREPARING", true);
        List<StagedFixtureSet> rows = stagedByAttempt(lease, true);
        if (rows.size() != 1) throw failure(rows.isEmpty() ? Code.STAGE_MISSING : Code.INTEGRITY);
        StagedFixtureSet staged = rows.getFirst();
        lockIdentity(lease.key().scope(), staged.generated().view().fixtureSetId());
        if (headExists(lease.key().scope(), staged.generated().view().fixtureSetId())) {
            throw failure(Code.CAS_MISMATCH);
        }
        int updated = jdbc.update("""
                UPDATE rg_api_fixture_set_revisions
                   SET state='COMMITTED', committed_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                   AND revision=? AND command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), staged.generated().view().fixtureSetId(),
                staged.generated().view().revision(), lease.commandId(), lease.attemptNo(),
                lease.attemptToken());
        if (updated != 1) throw failure(Code.CAS_MISMATCH);
        int inserted = jdbc.update("""
                INSERT INTO rg_api_fixture_set_heads
                    (tenant_id, project_id, environment_id, fixture_set_id, revision,
                     command_id, attempt_no, attempt_token, revision_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED')
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), staged.generated().view().fixtureSetId(),
                staged.generated().view().revision(), lease.commandId(), lease.attemptNo(),
                lease.attemptToken());
        if (inserted != 1) throw failure(Code.INTEGRITY);
        registerOuterFence(lease);
        return new StoredFixtureSet(lease.key().scope(), staged.generated());
    }

    @Override
    public StoredFixtureSet publishChild(CommandLease lease, CommandReceipt outerReceipt) {
        if (lease == null || outerReceipt == null) throw failure(Code.RECEIPT_INVALID);
        List<PublishedRow> rows = publishedByAttempt(lease);
        if (rows.size() != 1) throw failure(rows.isEmpty() ? Code.STAGE_MISSING : Code.INTEGRITY);
        PublishedRow row = rows.getFirst();
        if (!row.outerReceipt().equals(outerReceipt)) throw failure(Code.RECEIPT_INVALID);
        try {
            ApiResourceSaveReceiptClosure.requireDefaultFixture(outerReceipt, row.stored().generated());
        } catch (IllegalArgumentException ex) {
            throw failure(Code.RECEIPT_INVALID);
        }
        return row.stored();
    }

    @Override
    public void failChild(CommandLease lease) {
        if (lease == null) return;
        try {
            transactions.executeWithoutResult(status -> {
                lockJournal(lease.commandId());
                int committed = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_api_fixture_set_revisions
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='COMMITTED'
                        """, Integer.class, lease.commandId(), lease.attemptNo(), lease.attemptToken());
                if (committed > 0) return;
                jdbc.update("""
                        DELETE FROM rg_api_fixture_set_revisions
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'
                        """, lease.commandId(), lease.attemptNo(), lease.attemptToken());
            });
        } catch (ApiFixtureSetCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(Code.INTEGRITY);
        }
    }

    @Override
    public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId) {
        List<StoredFixtureSet> rows = jdbc.query("""
                SELECT r.fixture_set_id, r.revision, r.fingerprint, r.status, r.status_revision,
                       r.subject_kind, r.subject_id, r.subject_revision, r.subject_fingerprint,
                       r.generated_json, j.receipt_schema, j.receipt_json,
                       j.receipt_fingerprint, j.receipt_etag,
                       j.command_id AS journal_authority, a.command_id AS attempt_authority,
                       sr.command_id AS subject_authority, sr.spec_json AS subject_spec_json,
                       sr.resource_id AS subject_resource_id, sr.revision AS subject_resource_revision,
                       sr.spec_fingerprint AS subject_resource_fingerprint,
                       sr.connection_id AS subject_connection_id,
                       sr.connection_revision AS subject_connection_revision
                  FROM rg_api_fixture_set_heads h
                  JOIN rg_api_fixture_set_revisions r
                    ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id
                   AND r.environment_id=h.environment_id AND r.fixture_set_id=h.fixture_set_id
                   AND r.revision=h.revision AND r.command_id=h.command_id
                   AND r.attempt_no=h.attempt_no AND r.attempt_token=h.attempt_token
                   AND r.state=h.revision_state
                  LEFT JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no
                   AND j.attempt_token=r.attempt_token AND j.tenant_id=r.tenant_id
                   AND j.project_id=r.project_id AND j.environment_id=r.environment_id
                   AND j.endpoint='API_RESOURCE_SAVE' AND j.target_id=r.subject_id
                   AND j.status='COMMITTED'
                  LEFT JOIN rg_authoring_command_attempts a
                    ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no
                   AND a.attempt_token=r.attempt_token AND a.tenant_id=r.tenant_id
                   AND a.project_id=r.project_id AND a.environment_id=r.environment_id
                   AND a.endpoint='API_RESOURCE_SAVE' AND a.target_id=r.subject_id
                   AND a.status='COMMITTED'
                  LEFT JOIN rg_api_resource_revisions sr
                    ON sr.tenant_id=r.tenant_id AND sr.project_id=r.project_id
                   AND sr.environment_id=r.environment_id AND sr.resource_id=r.subject_id
                   AND sr.revision=r.subject_revision AND sr.spec_fingerprint=r.subject_fingerprint
                   AND sr.command_id=r.command_id AND sr.attempt_no=r.attempt_no
                   AND sr.attempt_token=r.attempt_token AND sr.state='COMMITTED'
                 WHERE h.tenant_id=? AND h.project_id=? AND h.environment_id=?
                   AND h.fixture_set_id=?
                """, storedMapper(scope), scope.tenantId(), scope.projectId(), scope.environmentId(), fixtureSetId);
        return exact(rows);
    }

    @Override
    public Optional<StoredFixtureSet> findRevision(AuthoringScope scope, String fixtureSetId, int revision) {
        if (revision < 1) throw failure(Code.INTEGRITY);
        List<StoredFixtureSet> rows = jdbc.query("""
                SELECT r.fixture_set_id, r.revision, r.fingerprint, r.status, r.status_revision,
                       r.subject_kind, r.subject_id, r.subject_revision, r.subject_fingerprint,
                       r.generated_json, j.receipt_schema, j.receipt_json,
                       j.receipt_fingerprint, j.receipt_etag,
                       j.command_id AS journal_authority, a.command_id AS attempt_authority,
                       sr.command_id AS subject_authority, sr.spec_json AS subject_spec_json,
                       sr.resource_id AS subject_resource_id, sr.revision AS subject_resource_revision,
                       sr.spec_fingerprint AS subject_resource_fingerprint,
                       sr.connection_id AS subject_connection_id,
                       sr.connection_revision AS subject_connection_revision
                  FROM rg_api_fixture_set_revisions r
                  LEFT JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no
                   AND j.attempt_token=r.attempt_token AND j.tenant_id=r.tenant_id
                   AND j.project_id=r.project_id AND j.environment_id=r.environment_id
                   AND j.endpoint='API_RESOURCE_SAVE' AND j.target_id=r.subject_id
                   AND j.status='COMMITTED'
                  LEFT JOIN rg_authoring_command_attempts a
                    ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no
                   AND a.attempt_token=r.attempt_token AND a.tenant_id=r.tenant_id
                   AND a.project_id=r.project_id AND a.environment_id=r.environment_id
                   AND a.endpoint='API_RESOURCE_SAVE' AND a.target_id=r.subject_id
                   AND a.status='COMMITTED'
                  LEFT JOIN rg_api_resource_revisions sr
                    ON sr.tenant_id=r.tenant_id AND sr.project_id=r.project_id
                   AND sr.environment_id=r.environment_id AND sr.resource_id=r.subject_id
                   AND sr.revision=r.subject_revision AND sr.spec_fingerprint=r.subject_fingerprint
                   AND sr.command_id=r.command_id AND sr.attempt_no=r.attempt_no
                   AND sr.attempt_token=r.attempt_token AND sr.state='COMMITTED'
                 WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=?
                   AND r.fixture_set_id=? AND r.revision=? AND r.state='COMMITTED'
                """, storedMapper(scope), scope.tenantId(), scope.projectId(), scope.environmentId(),
                fixtureSetId, revision);
        return exact(rows);
    }

    @Override
    public List<FixtureSetSummary> listSummariesBySubject(AuthoringScope scope, FixtureSubjectRef subject) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(subject, "subject");
        return jdbc.query("""
                SELECT r.fixture_set_id, r.revision, r.fingerprint, r.status, r.status_revision,
                       r.subject_kind, r.subject_id, r.subject_revision, r.subject_fingerprint,
                       r.generated_json, j.receipt_schema, j.receipt_json,
                       j.receipt_fingerprint, j.receipt_etag,
                       j.command_id AS journal_authority, a.command_id AS attempt_authority,
                       sr.command_id AS subject_authority, sr.spec_json AS subject_spec_json,
                       sr.resource_id AS subject_resource_id, sr.revision AS subject_resource_revision,
                       sr.spec_fingerprint AS subject_resource_fingerprint,
                       sr.connection_id AS subject_connection_id,
                       sr.connection_revision AS subject_connection_revision
                  FROM rg_api_fixture_set_heads h
                  JOIN rg_api_fixture_set_revisions r
                    ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id
                   AND r.environment_id=h.environment_id AND r.fixture_set_id=h.fixture_set_id
                   AND r.revision=h.revision AND r.command_id=h.command_id
                   AND r.attempt_no=h.attempt_no AND r.attempt_token=h.attempt_token
                   AND r.state=h.revision_state
                  LEFT JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no
                   AND j.attempt_token=r.attempt_token AND j.tenant_id=r.tenant_id
                   AND j.project_id=r.project_id AND j.environment_id=r.environment_id
                   AND j.endpoint='API_RESOURCE_SAVE' AND j.target_id=r.subject_id
                   AND j.status='COMMITTED'
                  LEFT JOIN rg_authoring_command_attempts a
                    ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no
                   AND a.attempt_token=r.attempt_token AND a.tenant_id=r.tenant_id
                   AND a.project_id=r.project_id AND a.environment_id=r.environment_id
                   AND a.endpoint='API_RESOURCE_SAVE' AND a.target_id=r.subject_id
                   AND a.status='COMMITTED'
                  LEFT JOIN rg_api_resource_revisions sr
                    ON sr.tenant_id=r.tenant_id AND sr.project_id=r.project_id
                   AND sr.environment_id=r.environment_id AND sr.resource_id=r.subject_id
                   AND sr.revision=r.subject_revision AND sr.spec_fingerprint=r.subject_fingerprint
                   AND sr.command_id=r.command_id AND sr.attempt_no=r.attempt_no
                   AND sr.attempt_token=r.attempt_token AND sr.state='COMMITTED'
                 WHERE h.tenant_id=? AND h.project_id=? AND h.environment_id=?
                 ORDER BY r.fixture_set_id
                """, storedMapper(scope), scope.tenantId(), scope.projectId(), scope.environmentId()).stream()
                .filter(stored -> stored.generated().view().subject().equals(subject))
                .map(stored -> stored.generated().summary())
                .toList();
    }

    private void insertIdentity(AuthoringScope scope, String fixtureSetId) {
        jdbc.update("""
                MERGE INTO rg_api_fixture_set_identities AS target
                USING (VALUES (?, ?, ?, ?)) AS source(tenant_id, project_id, environment_id, fixture_set_id)
                  ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                 AND target.environment_id=source.environment_id AND target.fixture_set_id=source.fixture_set_id
                WHEN NOT MATCHED THEN INSERT (tenant_id, project_id, environment_id, fixture_set_id)
                VALUES (source.tenant_id, source.project_id, source.environment_id, source.fixture_set_id)
                """, scope.tenantId(), scope.projectId(), scope.environmentId(), fixtureSetId);
    }

    private void deleteSupersededStages(CommandLease lease) {
        jdbc.update("""
                DELETE FROM rg_api_fixture_set_revisions
                 WHERE command_id=? AND state='STAGED'
                   AND NOT (attempt_no=? AND attempt_token=?)
                """, lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private void lockIdentity(AuthoringScope scope, String fixtureSetId) {
        List<String> rows = jdbc.queryForList("""
                SELECT fixture_set_id FROM rg_api_fixture_set_identities
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                 FOR UPDATE
                """, String.class, scope.tenantId(), scope.projectId(), scope.environmentId(), fixtureSetId);
        if (rows.size() != 1) throw failure(Code.INTEGRITY);
    }

    private boolean headExists(AuthoringScope scope, String fixtureSetId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_api_fixture_set_heads
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND fixture_set_id=?
                """, Integer.class, scope.tenantId(), scope.projectId(), scope.environmentId(), fixtureSetId) > 0;
    }

    private List<StagedFixtureSet> stagedByAttempt(CommandLease lease, boolean lock) {
        String sql = """
                SELECT generated_json FROM rg_api_fixture_set_revisions
                 WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new StagedFixtureSet(lease, generated(rs.getString(1))),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private List<PublishedRow> publishedByAttempt(CommandLease lease) {
        return jdbc.query("""
                SELECT r.fixture_set_id, r.revision, r.fingerprint, r.status, r.status_revision,
                       r.subject_kind, r.subject_id, r.subject_revision, r.subject_fingerprint,
                       r.generated_json, j.receipt_schema, j.receipt_json,
                       j.receipt_fingerprint, j.receipt_etag,
                       j.command_id AS journal_authority, a.command_id AS attempt_authority,
                       sr.command_id AS subject_authority, sr.spec_json AS subject_spec_json,
                       sr.resource_id AS subject_resource_id, sr.revision AS subject_resource_revision,
                       sr.spec_fingerprint AS subject_resource_fingerprint,
                       sr.connection_id AS subject_connection_id,
                       sr.connection_revision AS subject_connection_revision
                  FROM rg_api_fixture_set_revisions r
                  JOIN rg_api_fixture_set_heads h
                    ON h.tenant_id=r.tenant_id AND h.project_id=r.project_id
                   AND h.environment_id=r.environment_id AND h.fixture_set_id=r.fixture_set_id
                   AND h.revision=r.revision AND h.command_id=r.command_id
                   AND h.attempt_no=r.attempt_no AND h.attempt_token=r.attempt_token
                   AND h.revision_state=r.state
                  LEFT JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no
                   AND j.attempt_token=r.attempt_token AND j.tenant_id=r.tenant_id
                   AND j.project_id=r.project_id AND j.environment_id=r.environment_id
                   AND j.endpoint='API_RESOURCE_SAVE' AND j.target_id=r.subject_id
                   AND j.status='COMMITTED'
                  LEFT JOIN rg_authoring_command_attempts a
                    ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no
                   AND a.attempt_token=r.attempt_token AND a.tenant_id=r.tenant_id
                   AND a.project_id=r.project_id AND a.environment_id=r.environment_id
                   AND a.endpoint='API_RESOURCE_SAVE' AND a.target_id=r.subject_id
                   AND a.status='COMMITTED'
                  LEFT JOIN rg_api_resource_revisions sr
                    ON sr.tenant_id=r.tenant_id AND sr.project_id=r.project_id
                   AND sr.environment_id=r.environment_id AND sr.resource_id=r.subject_id
                   AND sr.revision=r.subject_revision AND sr.spec_fingerprint=r.subject_fingerprint
                   AND sr.command_id=r.command_id AND sr.attempt_no=r.attempt_no
                   AND sr.attempt_token=r.attempt_token AND sr.state='COMMITTED'
                 WHERE r.command_id=? AND r.attempt_no=? AND r.attempt_token=?
                   AND r.state='COMMITTED'
                """, (rs, row) -> new PublishedRow(validatedStored(lease.key().scope(), rs), receipt(rs)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private RowMapper<StoredFixtureSet> storedMapper(AuthoringScope scope) {
        return (rs, row) -> validatedStored(scope, rs);
    }

    private StoredFixtureSet validatedStored(AuthoringScope scope, ResultSet rs) throws SQLException {
        if (rs.getString("journal_authority") == null || rs.getString("attempt_authority") == null
                || rs.getString("subject_authority") == null) {
            throw failure(Code.INTEGRITY);
        }
        GeneratedDefaultFixture value = generated(rs.getString("generated_json"));
        SubjectCoordinate subject = subject(value.view().subject());
        ResourceAuthority resource = resourceAuthority(rs, subject);
        if (!value.view().fixtureSetId().equals(rs.getString("fixture_set_id"))
                || value.view().revision() != rs.getInt("revision")
                || !value.view().fingerprint().equals(rs.getString("fingerprint"))
                || !value.view().status().name().equals(rs.getString("status"))
                || value.view().statusRevision() != rs.getInt("status_revision")
                || !subject.kind().equals(rs.getString("subject_kind"))
                || !subject.id().equals(rs.getString("subject_id"))
                || subject.revision() != rs.getInt("subject_revision")
                || !subject.fingerprint().equals(rs.getString("subject_fingerprint"))) {
            throw failure(Code.INTEGRITY);
        }
        CommandReceipt outerReceipt = receipt(rs);
        try {
            ApiResourceSaveReceiptClosure.require(outerReceipt, subject.id(),
                    resource.connectionId(), resource.connectionRevision());
            ApiResourceSaveReceiptClosure.requireDefaultFixture(outerReceipt, value);
        } catch (IllegalArgumentException ex) {
            throw failure(Code.INTEGRITY);
        }
        return new StoredFixtureSet(scope, value);
    }

    private ResourceAuthority resourceAuthority(ResultSet rs, SubjectCoordinate subject) throws SQLException {
        try {
            ApiResourceSpec resource = mapper.readValue(rs.getString("subject_spec_json"), ApiResourceSpec.class);
            resourceDecisions.validateStoredSpec(resource);
            ResourceAuthority authority = new ResourceAuthority(rs.getString("subject_resource_id"),
                    rs.getLong("subject_resource_revision"), rs.getString("subject_resource_fingerprint"),
                    rs.getString("subject_connection_id"), rs.getLong("subject_connection_revision"));
            if (!"API_RESOURCE".equals(subject.kind()) || !authority.resourceId().equals(subject.id())
                    || authority.revision() != subject.revision()
                    || !authority.fingerprint().equals(subject.fingerprint())
                    || !resource.resourceId().equals(authority.resourceId())
                    || resource.revision() != authority.revision()
                    || !resource.fingerprint().equals(authority.fingerprint())
                    || !resource.connectionId().equals(authority.connectionId())
                    || authority.connectionRevision() < 1) {
                throw new IllegalArgumentException("Fixture subject Resource authority drift");
            }
            return authority;
        } catch (Exception ex) {
            throw failure(Code.INTEGRITY);
        }
    }

    private GeneratedDefaultFixture generated(String json) {
        try {
            return mapper.readValue(json, GeneratedDefaultFixture.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw failure(Code.INTEGRITY);
        }
    }

    private CommandReceipt receipt(ResultSet rs) throws SQLException {
        try {
            return new CommandReceipt(rs.getString("receipt_schema"),
                    mapper.readTree(rs.getString("receipt_json")),
                    rs.getString("receipt_fingerprint"), rs.getString("receipt_etag"));
        } catch (JsonProcessingException ex) {
            throw failure(Code.INTEGRITY);
        }
    }

    private Optional<StoredFixtureSet> exact(List<StoredFixtureSet> rows) {
        if (rows.size() > 1) throw failure(Code.INTEGRITY);
        return rows.stream().findFirst();
    }

    private void requireAuthority(CommandLease lease, String requiredStatus, boolean requireLive) {
        if (lease == null) throw failure(Code.LEASE_FENCED);
        JournalAuthority journal = lockJournal(lease.commandId());
        JournalAuthority attempt = lockAttempt(lease);
        if (!journal.matches(lease) || !attempt.matches(lease)
                || !requiredStatus.equals(journal.status()) || !requiredStatus.equals(attempt.status())) {
            throw failure(Code.LEASE_FENCED);
        }
        if (requireLive && (!journal.live() || !attempt.live())) throw failure(Code.LEASE_EXPIRED);
    }

    private JournalAuthority lockJournal(String commandId) {
        List<JournalAuthority> rows = jdbc.query(authoritySql("rg_authoring_command_journal", "command_id=?"),
                AUTHORITY_MAPPER, commandId);
        if (rows.size() != 1) throw failure(Code.LEASE_FENCED);
        return rows.getFirst();
    }

    private JournalAuthority lockAttempt(CommandLease lease) {
        List<JournalAuthority> rows = jdbc.query(authoritySql("rg_authoring_command_attempts",
                        "command_id=? AND attempt_no=? AND attempt_token=?"), AUTHORITY_MAPPER,
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (rows.size() != 1) throw failure(Code.LEASE_FENCED);
        return rows.getFirst();
    }

    private static String authoritySql(String table, String predicate) {
        return "SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id, "
                + "idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token, "
                + "lease_until, expected_mode, expected_revision, "
                + "CASE WHEN CURRENT_TIMESTAMP < lease_until THEN TRUE ELSE FALSE END "
                + "FROM " + table + " WHERE " + predicate + " FOR UPDATE";
    }

    private void registerOuterFence(CommandLease lease) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (readOnly) throw failure(Code.INTEGRITY);
                requireAuthority(lease, "COMMITTED", false);
            }
        });
    }

    private static final RowMapper<JournalAuthority> AUTHORITY_MAPPER = (rs, row) -> {
        ExpectedRevision expected = "CREATE".equals(rs.getString("expected_mode"))
                ? ExpectedRevision.create() : ExpectedRevision.match(rs.getLong("expected_revision"));
        return new JournalAuthority(new AuthoringScope(rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("environment_id")), rs.getString("actor_id"), rs.getString("endpoint"),
                rs.getString("target_id"), rs.getString("idempotency_key"), rs.getString("command_id"),
                rs.getString("request_fingerprint"), rs.getString("status"), rs.getInt("attempt_no"),
                rs.getString("attempt_token"), rs.getTimestamp("lease_until").toInstant(), expected,
                rs.getBoolean(16));
    };

    private static SubjectCoordinate subject(FixtureSubjectRef subject) {
        if (subject instanceof FixtureSubjectRef.ApiResource value) {
            return new SubjectCoordinate("API_RESOURCE", value.resourceId(), value.revision(), value.fingerprint());
        }
        if (subject instanceof FixtureSubjectRef.FlowDraft value) {
            return new SubjectCoordinate("FLOW_DRAFT", value.draftId(), value.revision(), value.fingerprint());
        }
        FixtureSubjectRef.FlowVersion value = (FixtureSubjectRef.FlowVersion) subject;
        return new SubjectCoordinate("FLOW_VERSION", value.publicationId(), value.revision(), value.fingerprint());
    }

    private static ApiFixtureSetCommitStoreException failure(Code code) {
        return new ApiFixtureSetCommitStoreException(code);
    }

    private record JournalAuthority(AuthoringScope scope, String actorId, String endpoint, String targetId,
                                    String idempotencyKey, String commandId, String requestFingerprint,
                                    String status, int attemptNo, String attemptToken, Instant leaseUntil,
                                    ExpectedRevision expectedRevision, boolean live) {
        boolean matches(CommandLease lease) {
            CommandKey key = lease.key();
            return scope.equals(key.scope()) && actorId.equals(key.actorId()) && endpoint.equals(key.endpoint().name())
                    && targetId.equals(key.targetId()) && idempotencyKey.equals(key.idempotencyKey())
                    && commandId.equals(lease.commandId()) && requestFingerprint.equals(lease.requestFingerprint())
                    && attemptNo == lease.attemptNo() && attemptToken.equals(lease.attemptToken())
                    && expectedRevision.equals(lease.expectedRevision()) && leaseUntil.equals(lease.leaseUntil());
        }
    }

    private record PublishedRow(StoredFixtureSet stored, CommandReceipt outerReceipt) { }
    private record SubjectCoordinate(String kind, String id, int revision, String fingerprint) { }

    private record ResourceAuthority(String resourceId, long revision, String fingerprint,
                                     String connectionId, long connectionRevision) { }
}
