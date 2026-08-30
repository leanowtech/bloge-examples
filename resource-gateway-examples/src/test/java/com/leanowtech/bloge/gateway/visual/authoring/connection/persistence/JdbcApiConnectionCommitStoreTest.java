package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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
    private static final Instant TEST_NOW = Instant.parse("2026-08-30T00:00:00Z");

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

    @Test
    void pendingSecretLeaseIsInvisibleRedactedAndAtomicallyActivated() throws Exception {
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

        store.commit(lease);

        assertThat(bindingCount()).isOne();
        assertThat(pendingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT active_locator FROM rg_api_connection_secret_bindings",
                String.class)).isEqualTo("vault://team/customer-token");
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().auth().configured()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(store.findHead(SCOPE, "customer").orElseThrow().view()))
                .doesNotContain("vault://team/customer-token", "one-time-secret");
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
}
