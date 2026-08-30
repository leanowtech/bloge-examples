package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
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
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        return new JdbcApiConnectionCommitStore(dataSource, new ObjectMapper(),
                new ApiConnectionDecisions(), clock);
    }

    @Override
    protected String activationLeaseId(PreparedSecretBinding prepared) {
        return jdbc.queryForObject("SELECT lease_id FROM rg_api_connection_pending_secret_leases "
                        + "WHERE opaque_handle=? AND status='PENDING'", String.class, prepared.reference().ref());
    }

    @Test
    void pendingSecretLeaseIsInvisibleAndPreparedHandleIsNotPublishedAsBinding() throws Exception {
        JdbcApiConnectionCommitStore store = jdbcStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        ApiConnectionCommand bearer = new ApiConnectionCommand("Secret API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
        CommandLease lease = lease("atomic-secret", "atomic-secret-token", "customer",
                ExpectedRevision.create());

        store.stage(lease, "customer", ExpectedRevision.create(), bearer, prepared);

        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        assertThat(pendingCount()).isOne();
        assertThat(bindingCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT view_json FROM rg_api_connection_revisions WHERE state='STAGED'", String.class))
                .doesNotContain("vault://team/customer-token", "one-time-secret");
        assertThat(jdbc.queryForObject(
                "SELECT opaque_handle FROM rg_api_connection_pending_secret_leases", String.class))
                .doesNotContain("one-time-secret");

        assertThatThrownBy(() -> store.commit(lease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        assertThat(bindingCount()).isZero();
        assertThat(pendingCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM rg_api_connection_pending_secret_leases",
                String.class)).isEqualTo("ABORT_REQUIRED");
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

    @Test
    void expiredTakeoverNeverDeletesAbortRequiredSecretRowsAndCleanupRemovesOnlyItsStage() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/takeover-token"));
        ApiConnectionCommand bearer = new ApiConnectionCommand("Secret API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
        CommandLease original = lease("abort-required-takeover", "abort-required-token", "customer",
                ExpectedRevision.create());
        store.stage(original, "customer", ExpectedRevision.create(), bearer, prepared);
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE command_id=?", original.commandId());
        Instant expiredUntil = jdbc.queryForObject(
                "SELECT lease_until FROM rg_authoring_command_journal WHERE command_id=?",
                (row, ignored) -> row.getTimestamp(1).toInstant(), original.commandId());
        CommandLease expired = new CommandLease(original.commandId(), original.attemptNo(), original.attemptToken(),
                original.key(), original.requestFingerprint(), expiredUntil, original.expectedRevision());
        CommandLease takeover = new CommandLease(original.commandId(), 2, "takeover-token", original.key(),
                original.requestFingerprint(), TEST_NOW.plusSeconds(60), original.expectedRevision());

        assertThatThrownBy(() -> store.stage(takeover, "customer", ExpectedRevision.create(), bearer, prepared))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThat(pendingCount()).isOne();
        store.failSecretLease(expired, "token");
        assertThatThrownBy(() -> store.stage(takeover, "customer", ExpectedRevision.create(), bearer, prepared))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_api_connection_pending_secret_leases",
                String.class)).isEqualTo("ABORT_REQUIRED");
        store.cleanupSecretLease(expired, "token");

        assertThat(pendingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions "
                + "WHERE command_id=? AND state='STAGED'", Integer.class, original.commandId())).isZero();
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
        assertThatThrownBy(() -> store.stage(current, "customer", ExpectedRevision.create(),
                renamedCommand("Current"))).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE command_id=?", old.commandId());
        StagedApiConnection replacement = store.stage(current, "customer", ExpectedRevision.create(),
                renamedCommand("Current"));
        store.fail(withLeaseUntil(old, databaseLeaseUntil(old.commandId())));
        assertThatThrownBy(() -> store.commit(old)).isInstanceOf(ApiConnectionCommitStoreException.class);
        assertThat(store.commit(current)).isEqualTo(new StoredApiConnection(SCOPE, replacement.view(),
                replacement.metadataFingerprint(), replacement.strongEtag(), current.commandId()));
    }

    @Test
    void secretLeaseAbortAndCleanupTargetTheExactPendingRow() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("password",
                new SecretReference(SCOPE, "vault://team/customer-password"));
        ApiConnectionCommand basic = new ApiConnectionCommand("Basic API", BASE_URL,
                ApiConnectionCommand.Auth.basic("user", ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
        CommandLease lease = lease("lease-cleanup", "lease-cleanup-token", "customer",
                ExpectedRevision.create());
        store.stage(lease, "customer", ExpectedRevision.create(), basic, prepared);

        store.failSecretLease(lease, "password");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rg_api_connection_pending_secret_leases", String.class))
                .isEqualTo("ABORT_REQUIRED");

        store.cleanupSecretLease(lease, "password");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void nestedChildPromotesConnectionHeadWithoutClosingOuterJournal() {
        JdbcApiConnectionCommitStore store = jdbcStore();
        CommandLease resourceLease = new CommandLease("nested-jdbc", 1, "nested-jdbc-token",
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "key-nested-jdbc"),
                "sha256:" + "a".repeat(64), TEST_NOW.plusSeconds(30), ExpectedRevision.match(7));

        store.stage(resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection committed = store.commitChild(resourceLease);

        assertThat(committed.view().revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_connection_revisions WHERE command_id=?",
                String.class, resourceLease.commandId())).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT revision FROM rg_api_connection_heads WHERE connection_id=?",
                Long.class, "customer")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, resourceLease.commandId())).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT receipt_json FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, resourceLease.commandId())).isNull();
        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
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

    private int pendingCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases", Integer.class);
    }

    private int bindingCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Integer.class);
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
}
