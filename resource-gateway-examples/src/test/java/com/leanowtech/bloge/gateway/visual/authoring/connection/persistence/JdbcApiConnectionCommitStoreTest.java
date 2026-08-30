package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ActivatedSecretSlot;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ConnectionRevisionCoordinate;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.JdbcPendingSecretStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretBatch;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretLease;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretOperation;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.SecretSourceMode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL-compatible JDBC contract evidence using an isolated H2 database. */
class JdbcApiConnectionCommitStoreTest extends ApiConnectionCommitStoreContractTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String BASE_URL = "https://customer.example.com";
    private static final Instant TEST_NOW = Instant.now();

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @AfterEach
    void dropDatabaseObjects() {
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Override
    protected ApiConnectionCommitStore createStore(Clock clock) {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:connection-jdbc-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        migrate("db/postgresql/V20260830_001__api_resource_authoring.sql");
        migrate("db/postgresql/V20260830_002__api_resource_concurrent_staging.sql");
        migrate("db/postgresql/V20260830_003__api_connection_secret_staging.sql");
        migrate("db/postgresql/V20260830_004__connection_metadata_authority.sql");
        migrate("db/postgresql/V20260830_005__pending_secret_store_protocol.sql");
        migrate("db/postgresql/V20260830_006__pending_secret_store_hardening.sql");
        migrate("db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql");
        migrate("db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql");
        migrate("db/postgresql/V20260831_009__authoring_command_attempt_authority.sql");
        migrate("db/postgresql/V20260831_010__attempt_provenance_closure.sql");
        return new JdbcApiConnectionCommitStore(dataSource, new ObjectMapper(),
                new ApiConnectionDecisions(), clock);
    }

    @Override
    protected void prepareChildStage(ApiConnectionCommitStore store, CommandLease lease) {
        seedOuterJournal(lease);
    }

    @Override
    protected void prepareOuterReceipt(ApiConnectionCommitStore store, CommandLease lease,
                                       CommandReceipt receipt, StoredApiConnection child) {
        jdbc.update("""
                UPDATE rg_authoring_command_journal
                   SET status='COMMITTED', receipt_schema=?, receipt_json=?, receipt_fingerprint=?, receipt_etag=?
                 WHERE command_id=? AND attempt_no=? AND attempt_token=?
                """, receipt.schemaVersion(), receipt.body().toString(),
                receipt.bodyFingerprint(), receipt.strongEtag(), lease.commandId(), lease.attemptNo(),
                lease.attemptToken());
        insertCommittedResourceAuthority(lease, lease.key().targetId(), 1, "sha256:" + "b".repeat(64));
    }

    @Override
    protected StoredApiConnection commitChild(ApiConnectionCommitStore store, CommandLease lease) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> {
                    StoredApiConnection child = store.commitChild(lease);
                    markOuterCommitted(lease, child);
                    return child;
                });
    }

    @Override
    protected boolean childIsVisibleAfterOuterCommit() {
        return true;
    }

    private void markOuterCommitted(CommandLease lease, StoredApiConnection child) {
        String viewJson = jdbc.queryForObject("SELECT view_json FROM rg_api_connection_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                String.class, lease.commandId(), lease.attemptNo(), lease.attemptToken());
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode view = mapper.readTree(viewJson);
            jdbc.update("UPDATE rg_authoring_command_attempts SET status='COMMITTED'"
                            + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                    lease.commandId(), lease.attemptNo(), lease.attemptToken());
            if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
                ObjectNode body = new ObjectMapper().createObjectNode();
                body.put("schemaVersion", "bloge.apiResourceSaveReceipt.v1");
                body.putObject("connection").put("connectionId", child.view().connectionId())
                        .put("revision", child.view().revision());
                body.putObject("resource").put("kind", "API_RESOURCE")
                        .put("resourceId", lease.key().targetId()).put("revision", 1)
                        .put("fingerprint", "sha256:" + "b".repeat(64));
                body.putObject("projections").put("descriptor", "READY")
                        .put("designContract", "READY").put("operator", "READY");
                jdbc.update("UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema=?"
                                + ", receipt_json=?, receipt_fingerprint=?, receipt_etag=?"
                                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                        "bloge.apiResourceSaveReceipt.v1", body.toString(), AuthoringFingerprints.of(body),
                        child.strongEtag(), lease.commandId(), lease.attemptNo(), lease.attemptToken());
                insertCommittedResourceAuthority(lease, lease.key().targetId(), 1,
                        "sha256:" + "b".repeat(64));
            } else {
                jdbc.update("UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema=?"
                                + ", receipt_json=?, receipt_fingerprint=?, receipt_etag=?"
                                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                        "bloge.apiConnectionView.v1", viewJson, AuthoringFingerprints.of(view), child.strongEtag(),
                        lease.commandId(), lease.attemptNo(), lease.attemptToken());
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void constructorRequiresAnExactDataSourceTransactionManager() {
        DataSource jdbcSource = new DriverManagerDataSource(
                "jdbc:h2:mem:constructor-jdbc-" + System.nanoTime() + ";MODE=PostgreSQL", "sa", "");
        DataSource otherSource = new DriverManagerDataSource(
                "jdbc:h2:mem:constructor-other-" + System.nanoTime() + ";MODE=PostgreSQL", "sa", "");
        ObjectMapper mapper = new ObjectMapper();
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new JdbcApiConnectionCommitStore(
                new JdbcTemplate(jdbcSource),
                new TransactionTemplate(new DataSourceTransactionManager(otherSource)),
                mapper, decisions, clock))
                .isInstanceOf(IllegalArgumentException.class);
        PlatformTransactionManager nonJdbcManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                throw new UnsupportedOperationException();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
                throw new UnsupportedOperationException();
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> new JdbcApiConnectionCommitStore(
                new JdbcTemplate(jdbcSource), new TransactionTemplate(nonJdbcManager),
                mapper, decisions, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcApiConnectionCommitStore(
                new JdbcTemplate(), new TransactionTemplate(new DataSourceTransactionManager(jdbcSource)),
                mapper, decisions, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcApiConnectionCommitStore(
                (DataSource) null, mapper, decisions, clock))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void directChildCommitOutsideAnAmbientMatchingTransactionIsRejected() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease resourceLease = new CommandLease("child-outside-tx", 1, "child-outside-tx-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile",
                        "key-child-outside-tx"), "sha256:" + "a".repeat(64), databaseNow().plusSeconds(30),
                ExpectedRevision.match(7));
        assertThatThrownBy(() -> store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        seedOuterJournal(resourceLease);
        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());

        assertThatThrownBy(() -> store.commitChild(resourceLease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions WHERE command_id=? AND state='STAGED'",
                Integer.class, resourceLease.commandId())).isEqualTo(1);
        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
    }

    @Test
    void childOnlyRetriesUnbindTheirFenceBeforeTheNextTransaction() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = new CommandLease("child-fence-retry", 1, "child-fence-retry-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile",
                        "key-child-fence-retry"), "sha256:" + "a".repeat(64), databaseNow().plusSeconds(30),
                ExpectedRevision.match(7));
        seedOuterJournal(lease);
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        for (int retry = 0; retry < 2; retry++) {
            assertThatThrownBy(() -> transaction.execute(status -> {
                store.commitChild(lease);
                return null;
            })).isInstanceOf(ApiConnectionCommitStoreException.class)
                    .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
            assertThat(store.childCommitFenceBoundForCurrentTransaction()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions"
                    + " WHERE command_id=? AND state='STAGED'", Integer.class, lease.commandId())).isOne();
        }

        StoredApiConnection committed = transaction.execute(status -> {
            StoredApiConnection child = store.commitChild(lease);
            markOuterCommitted(lease, child);
            return child;
        });

        assertThat(committed).isNotNull();
        assertThat(store.childCommitFenceBoundForCurrentTransaction()).isFalse();
        assertThat(store.findHead(SCOPE, "customer")).contains(committed);
    }

    @Test
    void databaseClockDoesNotLetAnAheadJvmClockExpireALiveLease() {
        Clock ahead = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneId.of("UTC"));
        JdbcApiConnectionCommitStore store = (JdbcApiConnectionCommitStore) createStore(ahead);
        Instant databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (row, ignored) -> row.getTimestamp(1).toInstant());
        CommandLease lease = new CommandLease("database-clock", 1, "database-clock-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        "customer", "key-database-clock"), "sha256:" + "a".repeat(64),
                databaseNow.plusSeconds(30), ExpectedRevision.create());

        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());

        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("PREPARING");
    }

    /** Uses SQL to advance H2's observable clock; production expiry remains DB-clock-only. */
    @Test
    @Override
    void expiredLeaseCannotStageOrCommitAndFailIsAStaleNoOp() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        Instant databaseNow = databaseNow();
        CommandLease live = leaseWithUntil("expiry", "expiry-token", databaseNow.plusSeconds(10));
        store.stage(live, "customer", ExpectedRevision.create(), noneCommand());
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE command_id=?", live.commandId());
        Instant expiredUntil = databaseLeaseUntil(live.commandId());
        jdbc.update("UPDATE rg_authoring_command_attempts SET lease_until = ? "
                + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", OffsetDateTime.ofInstant(expiredUntil, ZoneOffset.UTC),
                live.commandId(), live.attemptNo(), live.attemptToken());
        CommandLease expired = withLeaseUntil(live, expiredUntil);
        store.fail(expired);
        assertThatThrownBy(() -> store.commit(expired)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_EXPIRED);
    }

    /** Uses SQL to expire the staged attempt so the JDBC contract is deterministic without a JVM clock. */
    @Test
    @Override
    void newerAttemptTakesOverOldStageAndFencesOldLease() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        Instant databaseNow = databaseNow();
        CommandLease old = leaseWithUntil("takeover", "old-token", databaseNow.plusSeconds(10));
        CommandLease current = new CommandLease("takeover", 2, "new-token", old.key(), old.requestFingerprint(),
                databaseNow.plusSeconds(30), ExpectedRevision.create());
        store.stage(old, "customer", ExpectedRevision.create(), noneCommand());
        CommandLease driftedOuterCas = new CommandLease("takeover", 2, "drifted-token", old.key(),
                old.requestFingerprint(), databaseNow.plusSeconds(30), ExpectedRevision.match(3));
        assertThatThrownBy(() -> store.stage(driftedOuterCas, "customer", ExpectedRevision.match(3),
                renamedCommand("Drifted"))).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThatThrownBy(() -> store.stage(current, "customer", ExpectedRevision.create(),
                renamedCommand("Current"))).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE command_id=?", old.commandId());
        Instant expiredUntil = databaseLeaseUntil(old.commandId());
        jdbc.update("UPDATE rg_authoring_command_attempts SET lease_until = ? "
                + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", OffsetDateTime.ofInstant(expiredUntil, ZoneOffset.UTC),
                old.commandId(), old.attemptNo(), old.attemptToken());
        StagedApiConnection replacement = store.stage(current, "customer", ExpectedRevision.create(),
                renamedCommand("Current"));
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_attempts"
                + " WHERE command_id=? AND attempt_no=1 AND attempt_token=?", String.class,
                old.commandId(), old.attemptToken())).isEqualTo("SUPERSEDED");
        store.fail(withLeaseUntil(old, expiredUntil));
        assertThatThrownBy(() -> store.commit(old)).isInstanceOf(ApiConnectionCommitStoreException.class);
        assertThat(store.commit(current)).isEqualTo(new StoredApiConnection(SCOPE, replacement.view(),
                replacement.metadataFingerprint(), replacement.strongEtag(), current.commandId()));
    }

    @Test
    void replacementCommitUpdatesOnlyItsAttemptWhenRecoveryRetainsTheOldStage() {
        Clock clock = Clock.fixed(TEST_NOW, ZoneId.of("UTC"));
        JdbcApiConnectionCommitStore store = (JdbcApiConnectionCommitStore) createStore(clock);
        JdbcPendingSecretStore pending = new JdbcPendingSecretStore(dataSource, clock);
        ApiConnectionCommand command = new ApiConnectionCommand("Secret API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
        PreparedSecretBinding oldPrepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        CommandLease old = lease("retained-stage", 1, "old-token", "customer", ExpectedRevision.create(),
                TEST_NOW.plusSeconds(30));
        StagedApiConnection oldStage = store.stage(old, "customer", ExpectedRevision.create(), command, oldPrepared);
        PendingSecretBatch oldBatch = pendingBatch(old, "old-provider-lease", "old-opaque");
        pending.stage(oldBatch);

        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                        + " WHERE command_id=?", old.commandId());
        jdbc.update("UPDATE rg_authoring_command_attempts SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                old.commandId(), old.attemptNo(), old.attemptToken());
        Instant replacementUntil = databaseNow().plusSeconds(30);
        CommandLease replacement = lease("retained-stage", 2, "new-token", "customer", ExpectedRevision.create(),
                replacementUntil);
        PreparedSecretBinding replacementPrepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        StagedApiConnection replacementStage = store.stage(replacement, "customer", ExpectedRevision.create(),
                command, replacementPrepared);
        PendingSecretBatch replacementBatch = pendingBatch(replacement, "new-provider-lease", "new-opaque");
        pending.stage(replacementBatch);
        var proof = pending.prepareFinalization(replacementBatch, List.of(new ActivatedSecretSlot("token",
                new ActivatedExternalSecret("provider:test", "new-provider-lease", "new-active"))));

        StoredApiConnection committed = store.commit(replacement, proof);

        assertThat(committed.strongEtag()).isEqualTo(replacementStage.strongEtag());
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_connection_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", String.class,
                old.commandId(), old.attemptNo(), old.attemptToken())).isEqualTo("STAGED");
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_connection_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", String.class,
                replacement.commandId(), replacement.attemptNo(), replacement.attemptToken())).isEqualTo("COMMITTED");
        assertThat(oldStage.strongEtag()).isNotEqualTo(committed.strongEtag());

        // Both attempts own logical revision 1.  The committed read must join
        // the exact current attempt, rather than finding the first committed
        // journal row for this command and accidentally exposing old history.
        assertThat(store.findRevision(SCOPE, "customer", 1)).contains(committed);
    }

    @Test
    void childOnlyAmbientTransactionRollsBackBeforeOuterCommit() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease resourceLease = new CommandLease("nested-jdbc", 1, "nested-jdbc-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "key-nested-jdbc"),
                "sha256:" + "a".repeat(64), TEST_NOW.plusSeconds(30), ExpectedRevision.match(7));

        seedOuterJournal(resourceLease);
        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        assertThatThrownBy(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> store.commitChild(resourceLease)))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_connection_revisions WHERE command_id=?",
                String.class, resourceLease.commandId())).isEqualTo("STAGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_heads WHERE connection_id=?",
                Integer.class, "customer")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, resourceLease.commandId())).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT receipt_json FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, resourceLease.commandId())).isNull();
        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
    }

    @Test
    void committedOuterResourceReceiptMakesChildReadableWithoutReusingOuterEtag() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease resourceLease = new CommandLease("nested-receipt", 1, "nested-receipt-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "key-nested-receipt"),
                "sha256:" + "a".repeat(64), TEST_NOW.plusSeconds(30), ExpectedRevision.match(7));

        seedOuterJournal(resourceLease);
        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection child = commitChild(store, resourceLease);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fakeBody = mapper.createObjectNode().put("connectionId", "customer").put("revision", 1);
        jdbc.update("""
                UPDATE rg_authoring_command_journal
                   SET status='COMMITTED', receipt_schema=?, receipt_json=?, receipt_fingerprint=?, receipt_etag=?
                 WHERE command_id=? AND status='PREPARING'
                """, "bloge.apiResourceSaveReceipt.v1", mapper.writeValueAsString(fakeBody),
                AuthoringFingerprints.of(fakeBody), "\"outer-resource-etag\"", resourceLease.commandId());
        assertThatThrownBy(() -> store.publishChild(resourceLease, new CommandReceipt(
                "bloge.apiResourceSaveReceipt.v1", fakeBody, AuthoringFingerprints.of(fakeBody),
                "\"outer-resource-etag\"")))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        ObjectNode body = mapper.createObjectNode();
        body.put("schemaVersion", "bloge.apiResourceSaveReceipt.v1");
        body.putObject("connection").put("connectionId", "customer").put("revision", 1);
        body.putObject("resource").put("kind", "API_RESOURCE").put("resourceId", "profile")
                .put("revision", 1).put("fingerprint", "sha256:" + "b".repeat(64));
        body.putObject("projections").put("descriptor", "READY")
                .put("designContract", "READY").put("operator", "READY");
        jdbc.update("""
                UPDATE rg_authoring_command_journal
                   SET receipt_schema=?, receipt_json=?, receipt_fingerprint=?, receipt_etag=?
                 WHERE command_id=? AND status='COMMITTED'
                """, "bloge.apiResourceSaveReceipt.v1", mapper.writeValueAsString(body),
                AuthoringFingerprints.of(body), "\"outer-resource-etag\"", resourceLease.commandId());
        insertCommittedResourceAuthority(resourceLease, "profile", 1, "sha256:" + "b".repeat(64));
        CommandReceipt validReceipt = new CommandReceipt("bloge.apiResourceSaveReceipt.v1", body,
                AuthoringFingerprints.of(body), "\"outer-resource-etag\"");

        jdbc.update("""
                MERGE INTO rg_api_connection_identities AS target
                USING (VALUES (?, ?, ?, ?)) AS source(tenant_id, project_id, environment_id, connection_id)
                  ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                 AND target.environment_id=source.environment_id AND target.connection_id=source.connection_id
                WHEN NOT MATCHED THEN INSERT (tenant_id, project_id, environment_id, connection_id)
                VALUES (source.tenant_id, source.project_id, source.environment_id, source.connection_id)
                """, SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), "other");
        jdbc.update("UPDATE rg_api_resource_revisions SET connection_id=? WHERE command_id=?", "other",
                resourceLease.commandId());
        assertThatThrownBy(() -> store.publishChild(resourceLease, validReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        jdbc.update("UPDATE rg_api_resource_revisions SET connection_id=? WHERE command_id=?", "customer",
                resourceLease.commandId());

        ObjectNode alteredRevision = body.deepCopy();
        alteredRevision.with("resource").put("revision", 2);
        CommandReceipt alteredRevisionReceipt = new CommandReceipt("bloge.apiResourceSaveReceipt.v1", alteredRevision,
                AuthoringFingerprints.of(alteredRevision), "\"outer-resource-etag\"");
        assertThatThrownBy(() -> store.publishChild(resourceLease, alteredRevisionReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        ObjectNode alteredFingerprint = body.deepCopy();
        alteredFingerprint.with("resource").put("fingerprint", "sha256:" + "c".repeat(64));
        CommandReceipt alteredFingerprintReceipt = new CommandReceipt("bloge.apiResourceSaveReceipt.v1", alteredFingerprint,
                AuthoringFingerprints.of(alteredFingerprint), "\"outer-resource-etag\"");
        assertThatThrownBy(() -> store.publishChild(resourceLease, alteredFingerprintReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        store.publishChild(resourceLease, validReceipt);
        assertThat(store.publishChild(resourceLease, validReceipt)).isEqualTo(new StoredApiConnection(
                SCOPE, child.view(), child.metadataFingerprint(), child.strongEtag(), child.commandId()));

        ObjectNode alteredReplay = body.deepCopy();
        alteredReplay.with("projections").put("operator", "PENDING");
        CommandReceipt alteredReplayReceipt = new CommandReceipt("bloge.apiResourceSaveReceipt.v1", alteredReplay,
                AuthoringFingerprints.of(alteredReplay), "\"outer-resource-etag\"");
        assertThatThrownBy(() -> store.publishChild(resourceLease, alteredReplayReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        CommandLease alteredLease = new CommandLease(resourceLease.commandId(), resourceLease.attemptNo(),
                resourceLease.attemptToken(), resourceLease.key(), resourceLease.requestFingerprint(),
                resourceLease.leaseUntil().plusSeconds(1), resourceLease.expectedRevision());
        assertThatThrownBy(() -> store.publishChild(alteredLease, validReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);

        assertThat(store.findHead(SCOPE, "customer")).contains(new StoredApiConnection(
                SCOPE, child.view(), child.metadataFingerprint(), child.strongEtag(), child.commandId()));

        jdbc.update("UPDATE rg_api_resource_revisions SET connection_id=? WHERE command_id=?", "other",
                resourceLease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id=?", resourceLease.commandId());
        assertReadIntegrityFailure(store);

        assertThat(child.strongEtag()).isNotEqualTo("\"outer-resource-etag\"");
    }

    @Test
    void resourceSaveChildReadRejectsAConnectionViewReceipt() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease resourceLease = new CommandLease("resource-receipt-endpoint", 1,
                "resource-receipt-endpoint-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile",
                        "key-resource-receipt-endpoint"), "sha256:" + "a".repeat(64),
                TEST_NOW.plusSeconds(30), ExpectedRevision.match(7));
        seedOuterJournal(resourceLease);
        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection child = commitChild(store, resourceLease);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode view = mapper.valueToTree(child.view());

        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_schema=?, receipt_json=?,"
                        + " receipt_fingerprint=?, receipt_etag=? WHERE command_id=?",
                "bloge.apiConnectionView.v1", view.toString(), AuthoringFingerprints.of(view), child.strongEtag(),
                resourceLease.commandId());

        assertThatThrownBy(() -> store.findHead(SCOPE, "customer"))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void childHeadAndRevisionRollbackWithTheOuterResourceTransaction() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CommandLease resourceLease = new CommandLease("nested-rollback", 1, "nested-rollback-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "key-nested-rollback"),
                "sha256:" + "a".repeat(64), TEST_NOW.plusSeconds(30), ExpectedRevision.match(7));

        seedOuterJournal(resourceLease);
        outer.executeWithoutResult(status -> {
        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        commitChild(store, resourceLease);
            status.setRollbackOnly();
        });

        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isEmpty();
        assertThat(revisionCount()).isZero();
    }

    @Test
    void failChildCleansOnlyExactRowsAndLeavesTheOuterJournalToResourceStore() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = new CommandLease("nested-fail-child", 1, "nested-fail-child-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile",
                        "key-nested-fail-child"), "sha256:" + "a".repeat(64), TEST_NOW.plusSeconds(30),
                ExpectedRevision.match(7));
        seedOuterJournal(lease);
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        CommandLease altered = new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                lease.key(), "sha256:" + "b".repeat(64), lease.leaseUntil(), lease.expectedRevision());
        assertThatThrownBy(() -> store.failChild(altered)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThat(revisionCount()).isEqualTo(1);
        store.failChild(lease);
        assertThat(revisionCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_heads WHERE command_id=?",
                Integer.class, lease.commandId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("PREPARING");
    }

    @Test
    void standaloneCommitClosesJournalAndWritesConnectionReceipt() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("standalone-receipt", "standalone-receipt-token", "customer",
                ExpectedRevision.create());

        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection committed = store.commit(lease);

        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT receipt_schema FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("bloge.apiConnectionView.v1");
        assertThat(jdbc.queryForObject(
                "SELECT receipt_json FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).contains("Customer API");
        String receiptJson = jdbc.queryForObject(
                "SELECT receipt_json FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId());
        assertThat(new ObjectMapper().readTree(receiptJson))
                .isEqualTo(new ObjectMapper().valueToTree(committed.view()));
        assertThat(jdbc.queryForObject(
                "SELECT receipt_etag FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo(committed.strongEtag());
        assertThat(store.findHead(SCOPE, "customer")).contains(committed);
    }

    @Test
    void committedReadRejectsAViewAndReceiptThatDriftFromCanonicalAuthority() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("canonical-view", "canonical-view-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(lease);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tampered = (ObjectNode) mapper.readTree(jdbc.queryForObject(
                "SELECT view_json FROM rg_api_connection_revisions WHERE command_id=?",
                String.class, lease.commandId()));
        ((ObjectNode) tampered.get("auth")).put("configured", true);
        String tamperedJson = mapper.writeValueAsString(tampered);
        jdbc.update("UPDATE rg_api_connection_revisions SET view_json=? WHERE command_id=?",
                tamperedJson, lease.commandId());
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=?, receipt_fingerprint=? "
                        + "WHERE command_id=?", tamperedJson,
                AuthoringFingerprints.of(mapper.readTree(tamperedJson)), lease.commandId());

        assertThatThrownBy(() -> store.findHead(SCOPE, "customer"))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void committedReadRejectsDisplayNameBaseUrlDefaultsReceiptAndFingerprintTampering() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("canonical-fields", "canonical-fields-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(lease);

        String viewJson = jdbc.queryForObject(
                "SELECT view_json FROM rg_api_connection_revisions WHERE command_id=?",
                String.class, lease.commandId());
        String receiptJson = jdbc.queryForObject(
                "SELECT receipt_json FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId());
        String metadataFingerprint = jdbc.queryForObject(
                "SELECT metadata_fingerprint FROM rg_api_connection_revisions WHERE command_id=?",
                String.class, lease.commandId());

        jdbc.update("UPDATE rg_api_connection_revisions SET view_json=? WHERE command_id=?",
                viewJson.replace("Customer API", "Tampered API"), lease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("UPDATE rg_api_connection_revisions SET view_json=? WHERE command_id=?",
                viewJson, lease.commandId());

        jdbc.update("UPDATE rg_api_connection_revisions SET base_url=? WHERE command_id=?",
                "https://tampered.example.com", lease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("UPDATE rg_api_connection_revisions SET base_url=? WHERE command_id=?",
                BASE_URL, lease.commandId());

        jdbc.update("UPDATE rg_api_connection_revisions SET view_json=? WHERE command_id=?",
                viewJson.replace("\"timeoutMs\":5000", "\"timeoutMs\":6000"), lease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("UPDATE rg_api_connection_revisions SET view_json=? WHERE command_id=?",
                viewJson, lease.commandId());

        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=? WHERE command_id=?",
                receiptJson.replace("Customer API", "Tampered API"), lease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=? WHERE command_id=?",
                receiptJson, lease.commandId());

        jdbc.update("UPDATE rg_api_connection_revisions SET metadata_fingerprint=? WHERE command_id=?",
                "sha256:" + "b".repeat(64), lease.commandId());
        assertReadIntegrityFailure(store);
        jdbc.update("UPDATE rg_api_connection_revisions SET metadata_fingerprint=? WHERE command_id=?",
                metadataFingerprint, lease.commandId());
    }

    @Test
    void committedReadRequiresJournalAndImmutableAttemptAuthorityToAgree() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("read-authority", "read-authority-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(lease);

        assertThat(store.findHead(SCOPE, "customer")).isPresent();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isPresent();

        jdbc.update("UPDATE rg_authoring_command_journal SET target_id=? WHERE command_id=?",
                "other-connection", lease.commandId());
        assertReadIntegrityFailureForHeadAndRevision(store);
        jdbc.update("UPDATE rg_authoring_command_journal SET target_id=? WHERE command_id=?",
                "customer", lease.commandId());
        assertThat(store.findHead(SCOPE, "customer")).isPresent();

        jdbc.update("UPDATE rg_authoring_command_attempts SET target_id=? WHERE command_id=?"
                        + " AND attempt_no=? AND attempt_token=?", "other-connection", lease.commandId(),
                lease.attemptNo(), lease.attemptToken());
        assertReadIntegrityFailureForHeadAndRevision(store);
        jdbc.update("UPDATE rg_authoring_command_attempts SET target_id=? WHERE command_id=?"
                        + " AND attempt_no=? AND attempt_token=?", "customer", lease.commandId(),
                lease.attemptNo(), lease.attemptToken());
        assertThat(store.findHead(SCOPE, "customer")).isPresent();

        jdbc.update("UPDATE rg_authoring_command_attempts SET endpoint=? WHERE command_id=?"
                        + " AND attempt_no=? AND attempt_token=?", AuthoringEndpoint.API_RESOURCE_SAVE.name(),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        assertReadIntegrityFailureForHeadAndRevision(store);
        jdbc.update("UPDATE rg_authoring_command_attempts SET endpoint=? WHERE command_id=?"
                        + " AND attempt_no=? AND attempt_token=?", AuthoringEndpoint.API_CONNECTION_SAVE.name(),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());

        assertThat(store.findHead(SCOPE, "customer")).isPresent();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isPresent();
    }

    @Test
    void concurrentCommitsSerializeTheHeadCas() throws Exception {
        ApiConnectionCommitStore store = jdbcStore();
        CommandLease first = lease("race-a", "race-a-token", "customer", ExpectedRevision.create());
        CommandLease second = lease("race-b", "race-b-token", "customer", ExpectedRevision.create());
        store.stage(first, "customer", ExpectedRevision.create(), noneCommand());
        store.stage(second, "customer", ExpectedRevision.create(), noneCommand());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ApiConnectionCommitStoreException> commitFirst = () -> {
                try {
                    store.commit(first);
                    return null;
                } catch (ApiConnectionCommitStoreException ex) {
                    return ex;
                }
            };
            Callable<ApiConnectionCommitStoreException> commitSecond = () -> {
                try {
                    store.commit(second);
                    return null;
                } catch (ApiConnectionCommitStoreException ex) {
                    return ex;
                }
            };
            var results = executor.invokeAll(java.util.List.of(commitFirst, commitSecond), 10,
                    TimeUnit.SECONDS).stream().map(result -> {
                        try {
                            return result.get(1, TimeUnit.SECONDS);
                        } catch (Exception ex) {
                            throw new IllegalStateException("concurrent test failed", ex);
                        }
                    }).toList();
            assertThat(results).containsNull();
            assertThat(results).anySatisfy(error -> assertThat(error).isNotNull()
                    .extracting(ApiConnectionCommitStoreException::code)
                    .isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH));
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.findHead(SCOPE, "customer")).isPresent();
        assertThat(revisionCount()).isEqualTo(2);
        assertThat(committedCount()).isOne();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isPresent();
    }

    @Test
    void committedHistoryAndReadsStayScopeExact() {
        ApiConnectionCommitStore store = jdbcStore();
        CommandLease create = lease("history", "history-token", "customer", ExpectedRevision.create());
        store.stage(create, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(create);
        CommandLease update = lease("history-update", "history-update-token", "customer",
                ExpectedRevision.match(1));
        ApiConnectionCommand renamed = new ApiConnectionCommand("Customer v2", BASE_URL,
                ApiConnectionCommand.Auth.none(), new ApiConnectionCommand.Defaults(5_000, Map.of()));
        store.stage(update, "customer", ExpectedRevision.match(1), renamed);
        store.commit(update);

        assertThat(store.findRevision(SCOPE, "customer", 1).orElseThrow().view().displayName())
                .isEqualTo("Customer API");
        assertThat(store.findRevision(new AuthoringScope("other", "project", "dev"), "customer", 1))
                .isEmpty();
        assertThat(store.findHead(new AuthoringScope("other", "project", "dev"), "customer")).isEmpty();
    }

    @Test
    void duplicateCommittedProvenanceForOneLogicalRevisionFailsClosed() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease base = lease("duplicate-base", "duplicate-base-token", "customer",
                ExpectedRevision.create());
        store.stage(base, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(base);

        CommandLease first = lease("duplicate-first", "duplicate-first-token", "customer",
                ExpectedRevision.match(1));
        CommandLease second = lease("duplicate-second", "duplicate-second-token", "customer",
                ExpectedRevision.match(1));
        ApiConnectionCommand update = renamedCommand("Customer duplicate");
        store.stage(first, "customer", ExpectedRevision.match(1), update);
        store.stage(second, "customer", ExpectedRevision.match(1), update);
        store.commit(first);

        CommandLease probe = lease("duplicate-probe", "duplicate-probe-token", "customer",
                ExpectedRevision.match(2));
        store.stage(probe, "customer", ExpectedRevision.match(2), renamedCommand("Customer probe"));

        String secondView = jdbc.queryForObject("SELECT view_json FROM rg_api_connection_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", String.class,
                second.commandId(), second.attemptNo(), second.attemptToken());
        jdbc.update("UPDATE rg_api_connection_revisions SET state='COMMITTED'"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'",
                second.commandId(), second.attemptNo(), second.attemptToken());
        jdbc.update("UPDATE rg_authoring_command_attempts SET status='COMMITTED'"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                second.commandId(), second.attemptNo(), second.attemptToken());
        jdbc.update("UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema=?,"
                        + " receipt_json=?, receipt_fingerprint=?, receipt_etag=?"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                "bloge.apiConnectionView.v1", secondView,
                AuthoringFingerprints.of(new ObjectMapper().readTree(secondView)),
                jdbc.queryForObject("SELECT strong_etag FROM rg_api_connection_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", String.class,
                        second.commandId(), second.attemptNo(), second.attemptToken()),
                second.commandId(), second.attemptNo(), second.attemptToken());

        assertThatThrownBy(() -> store.stage(probe, "customer", ExpectedRevision.match(2),
                renamedCommand("Customer probe")))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void committedReadRejectsAnUnknownReceiptSchema() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("receipt-schema", "receipt-schema-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(lease);
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_schema=? WHERE command_id=?",
                "unknown.receipt.v0", lease.commandId());

        assertThatThrownBy(() -> store.findHead(SCOPE, "customer"))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void lateFailureAfterCommitIsAStaleNoOp() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease lease = lease("late-failure", "late-failure-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(lease);

        store.fail(lease);

        assertThat(store.findHead(SCOPE, "customer")).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("COMMITTED");
    }

    private JdbcApiConnectionCommitStore jdbcStore() {
        return (JdbcApiConnectionCommitStore) createStore(Clock.fixed(TEST_NOW, ZoneId.of("UTC")));
    }

    private void migrate(String path) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource);
    }

    private static CommandLease lease(String commandId, String token, String connectionId,
                                      ExpectedRevision expected) {
        return new CommandLease(commandId, 1, token, new CommandKey(SCOPE, "actor",
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE,
                connectionId, "key-" + commandId), "sha256:" + "a".repeat(64),
                TEST_NOW.plusSeconds(30), expected);
    }

    private static CommandLease lease(String commandId, int attemptNo, String token, String connectionId,
                                      ExpectedRevision expected, Instant until) {
        return new CommandLease(commandId, attemptNo, token, new CommandKey(SCOPE, "actor",
                AuthoringEndpoint.API_CONNECTION_SAVE, connectionId, "key-" + commandId),
                "sha256:" + "a".repeat(64), until, expected);
    }

    private static PendingSecretBatch pendingBatch(CommandLease lease, String providerLeaseId,
                                                    String opaqueLocator) {
        ConnectionRevisionCoordinate coordinate = new ConnectionRevisionCoordinate(SCOPE, "customer", 1);
        PendingSecretLease pendingLease = new PendingSecretLease(lease, coordinate, ExpectedRevision.create());
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "customer", 1, lease.commandId(), lease.attemptNo(), lease.attemptToken(), "token");
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:test", providerLeaseId,
                opaqueLocator, lease.leaseUntil(), context);
        return new PendingSecretBatch(pendingLease, List.of(new PendingSecretOperation.Prepared("token",
                SecretSourceMode.VALUE, prepared)));
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (row, ignored) -> row.getTimestamp(1).toInstant());
    }

    private Instant databaseLeaseUntil(String commandId) {
        return jdbc.queryForObject("SELECT lease_until FROM rg_authoring_command_journal WHERE command_id=?",
                (row, ignored) -> row.getTimestamp(1).toInstant(), commandId);
    }

    private static CommandLease leaseWithUntil(String commandId, String token, Instant until) {
        return new CommandLease(commandId, 1, token,
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        "customer", "key-" + commandId), "sha256:" + "a".repeat(64), until,
                ExpectedRevision.create());
    }

    private static ApiConnectionCommand renamedCommand(String displayName) {
        return new ApiConnectionCommand(displayName, BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
    }

    private static CommandLease withLeaseUntil(CommandLease lease, Instant until) {
        return new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(), lease.key(),
                lease.requestFingerprint(), until, lease.expectedRevision());
    }

    private static ApiConnectionCommand noneCommand() {
        return new ApiConnectionCommand("Customer API", BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5_000, Map.of("Accept", "application/json")));
    }

    private void insertCommittedResourceAuthority(CommandLease lease, String resourceId, long revision,
                                                  String specFingerprint) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", Integer.class,
                lease.commandId(), lease.attemptNo(), lease.attemptToken()) > 0) return;
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json,
                     spec_fingerprint, connection_id, strong_etag, command_id, attempt_no, attempt_token)
                VALUES (?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?, ?, ?, ?, ?)
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), resourceId, revision, "{}", specFingerprint,
                "customer", "\"resource-etag\"", lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private void seedOuterJournal(CommandLease lease) {
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                     command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                     expected_mode, expected_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().actorId(), lease.key().endpoint().name(),
                lease.key().targetId(), lease.key().idempotencyKey(), lease.commandId(), lease.requestFingerprint(),
                lease.attemptNo(), lease.attemptToken(), OffsetDateTime.ofInstant(lease.leaseUntil(), ZoneOffset.UTC),
                lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? match.revision() : null);
        jdbc.update("""
                INSERT INTO rg_authoring_command_attempts
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                     command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                     expected_mode, expected_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)
                """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().actorId(), lease.key().endpoint().name(),
                lease.key().targetId(), lease.key().idempotencyKey(), lease.commandId(), lease.requestFingerprint(),
                lease.attemptNo(), lease.attemptToken(), OffsetDateTime.ofInstant(lease.leaseUntil(), ZoneOffset.UTC),
                lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? match.revision() : null);
    }

    private int revisionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions", Integer.class);
    }

    private int committedCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_connection_revisions WHERE state='COMMITTED'", Integer.class);
    }

    private void assertReadIntegrityFailure(JdbcApiConnectionCommitStore store) {
        assertThatThrownBy(() -> store.findHead(SCOPE, "customer"))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    private void assertReadIntegrityFailureForHeadAndRevision(JdbcApiConnectionCommitStore store) {
        assertReadIntegrityFailure(store);
        assertThatThrownBy(() -> store.findRevision(SCOPE, "customer", 1))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }
}
