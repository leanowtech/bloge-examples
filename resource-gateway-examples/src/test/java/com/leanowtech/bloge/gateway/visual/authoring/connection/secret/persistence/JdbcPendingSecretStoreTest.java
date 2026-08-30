package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused H2/PostgreSQL-mode evidence for the durable pending-secret protocol. */
class JdbcPendingSecretStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:pending-jdbc-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        migrate("db/postgresql/V20260830_001__api_resource_authoring.sql");
        migrate("db/postgresql/V20260830_002__api_resource_concurrent_staging.sql");
        migrate("db/postgresql/V20260830_003__api_connection_secret_staging.sql");
        migrate("db/postgresql/V20260830_004__connection_metadata_authority.sql");
        migrate("db/postgresql/V20260830_005__pending_secret_store_protocol.sql");
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }


    @Test
    void commitRequiresAmbientTransactionAndRollbackKeepsAllRows() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("tx", 1, "tx-token", NOW.plusSeconds(60)), "token", "password");
        seed(batch);
        store.stage(batch);
        List<ActivatedSecretSlot> activated = List.of(activation("token", "tx-token", "active-token"),
                activation("password", "tx-token", "active-password"));
        assertThatThrownBy(() -> store.commitBindings(batch, activated)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.TRANSACTION_REQUIRED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            store.commitBindings(batch, activated);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Long.class)).isZero();
        transactions.executeWithoutResult(status -> store.commitBindings(batch, activated));
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isPresent();
        FinalizedSecretSlots replay = transactions.execute(status -> store.commitBindings(batch, activated));
        assertThat(replay).isNotNull();
    }

    @Test
    void keepExistingReadsExactPriorRevisionAndDoesNotPersistProviderSentinelsAsBinding() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch old = batchAtRevision(leaseAtRevision("old", 1, "old-token", NOW.plusSeconds(60), 2), 2, "token");
        seed(old); store.stage(old);
        transactions.executeWithoutResult(status -> store.commitBindings(old,
                List.of(activation("token", "old-token", "old-active"))));
        CommandLease nextLease = leaseAtRevision("next", 1, "next-token", NOW.plusSeconds(60), 3);
        PendingSecretBatch next = new PendingSecretBatch(new PendingSecretLease(nextLease,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 3), ExpectedRevision.match(2)),
                List.of(new PendingSecretOperation.Retained("token",
                        new ConnectionRevisionCoordinate(SCOPE, "connection", 2))));
        seed(next); store.stage(next);
        transactions.executeWithoutResult(status -> store.commitBindings(next, List.of()));
        assertThat(store.findActive(next.lease().coordinate(), "token")).contains(
                new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding(
                        "provider:one", "old-active", "next"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", Long.class, "next")).isZero();
    }

    @Test
    void recoveryClaimsWholeBatchAndTerminalAbortReplayIsExact() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("recover", 1, "recover-token", NOW.plusSeconds(60)), "token", "password");
        seed(batch); store.stage(batch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=? WHERE command_id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(60)), "recover");
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        assertThat(candidate.batch().operations()).hasSize(2);
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findExact(batch.lease())).isEmpty();
        assertThat(store.claimRecoveryDue(1)).isEmpty();
    }

    @Test
    void stagedOpaqueValuesNeverBecomePlaintextOrJsonPayload() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("opaque", 1, "opaque-token", NOW.plusSeconds(60)), "token");
        seed(batch); store.stage(batch);
        assertThat(jdbc.queryForObject("SELECT opaque_handle FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", String.class, "opaque")).isEqualTo("opaque-locator-token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_outcomes", Long.class)).isZero();
    }

    private JdbcPendingSecretStore store() { return new JdbcPendingSecretStore(dataSource, fixedClock()); }

    private Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private void seed(PendingSecretBatch batch) {
        CommandLease lease = batch.lease().commandLease();
        CommandKey key = lease.key();
        jdbc.update("INSERT INTO rg_authoring_command_journal (tenant_id, project_id, environment_id, actor_id, endpoint,"
                + " target_id, idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token,"
                + " lease_until, expected_mode, expected_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.commandId(), "sha256:" + "a".repeat(64), lease.attemptNo(), lease.attemptToken(),
                java.sql.Timestamp.from(lease.leaseUntil()), lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? (long) match.revision() : null);
        jdbc.update("MERGE INTO rg_api_connection_identities (tenant_id, project_id, environment_id, connection_id) KEY (tenant_id, project_id, environment_id, connection_id) VALUES (?, ?, ?, ?)",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), batch.lease().coordinate().connectionId());
        jdbc.update("INSERT INTO rg_api_connection_revisions (tenant_id, project_id, environment_id, connection_id, revision, command_id,"
                + " state, attempt_no, attempt_token, display_name, secret_slot, view_json, metadata_fingerprint, base_url,"
                + " defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header, strong_etag) VALUES (?, ?, ?, ?, ?, ?,"
                + " 'COMMITTED', ?, ?, 'fixture', 'token', '{}', ?, 'https://example.com', '{}', 1000, 'BEARER', NULL, NULL, '" + "\"etag-" + batch.lease().commandLease().commandId() + "\"' )",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), batch.lease().coordinate().connectionId(),
                batch.lease().coordinate().revision(), lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                "sha256:" + "b".repeat(64));
    }

    private void migrate(String path) { new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource); }

    private PendingSecretBatch batch(CommandLease lease, String... slots) {
        return batchAtRevision(lease, 3, slots);
    }

    private PendingSecretBatch batchAtRevision(CommandLease lease, long revision, String... slots) {
        List<PendingSecretOperation> operations = java.util.Arrays.stream(slots).map(slot ->
                (PendingSecretOperation) new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", lease.attemptToken(), "opaque-locator-" + slot,
                                lease.leaseUntil(), new SecretOperationContext(SCOPE, "actor", "connection-save",
                                lease.key().targetId(), revision,
                                lease.commandId(), lease.attemptNo(), lease.attemptToken(), slot)))).toList();
        String connectionId = lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE ? "connection" : lease.key().targetId();
        return new PendingSecretBatch(new PendingSecretLease(lease,
                new ConnectionRevisionCoordinate(SCOPE, connectionId, revision), lease.expectedRevision()), operations);
    }

    private CommandLease lease(String id, int attempt, String token, Instant until) {
        return leaseAtRevision(id, attempt, token, until, 3);
    }

    private CommandLease leaseAtRevision(String id, int attempt, String token, Instant until, long revision) {
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "connection", "idempotency-" + id);
        ExpectedRevision expected = revision == 1 ? ExpectedRevision.create() : ExpectedRevision.match(revision - 1);
        return new CommandLease(id, attempt, token, key, "sha256:" + "a".repeat(64), until, expected);
    }

    private ActivatedSecretSlot activation(String slot, String leaseId, String locator) {
        return new ActivatedSecretSlot(slot, new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret(
                "provider:one", leaseId, locator));
    }
}
