package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiConnectionSchemaReadinessTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:connection-schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void migrationsPassReadinessOnH2PostgresMode() {
        applyMigrations();

        new ApiConnectionSchemaReadiness(jdbc);
    }

    @Test
    void missingRequiredTableOrColumnFailsClosed() {
        applyMigrations();
        jdbc.execute("DROP TABLE rg_api_connection_heads");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");

        jdbc.execute("DROP ALL OBJECTS");
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_revisions DROP COLUMN view_json");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void eachLeaseAndBindingTableIsPartOfTheReadinessProbe() {
        applyMigrations();
        jdbc.execute("DROP TABLE rg_api_connection_pending_secret_leases");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);

        jdbc.execute("DROP ALL OBJECTS");
        applyMigrations();
        jdbc.execute("DROP TABLE rg_api_connection_secret_bindings");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resourceConnectionForeignKeyIsRequired() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP CONSTRAINT rg_api_resource_revisions_connection_fk");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void resourceConnectionForeignKeyMustRetainRestrictDeleteRule() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP CONSTRAINT rg_api_resource_revisions_connection_fk");
        jdbc.execute("ALTER TABLE rg_api_resource_revisions ADD CONSTRAINT rg_api_resource_revisions_connection_fk "
                + "FOREIGN KEY (tenant_id, project_id, environment_id, connection_id) "
                + "REFERENCES rg_api_connection_identities (tenant_id, project_id, environment_id, connection_id) "
                + "ON DELETE CASCADE");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void existingResourceConnectionIsBackfilledAndCannotDeleteItsIdentity() {
        applyMigrationsThroughSecond();
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'legacy-resource', 'k1', 'resource-cmd', ?,
                        'PREPARING', 1, 'attempt-1', CURRENT_TIMESTAMP, 'CREATE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "sha256:" + "a".repeat(64));
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state,
                     spec_json, spec_fingerprint, connection_id, strong_etag, command_id,
                     attempt_no, attempt_token)
                VALUES ('t', 'p', 'e', 'legacy-resource', 1, 'STAGED', '{}', ?, 'legacy-connection',
                        '"etag"', 'resource-cmd', 1, 'attempt-1')
                """, "sha256:" + "b".repeat(64));
        applyMigration("V20260830_003__api_connection_secret_staging.sql");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_identities", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM rg_api_connection_identities WHERE connection_id = 'legacy-connection'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationContainsContractStatusesAndNoSecretStorageColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/postgresql/"
                + "V20260830_003__api_connection_secret_staging.sql")).toLowerCase();
        assertThat(sql).contains("rg_api_connection_identities", "rg_api_connection_revisions",
                "rg_api_connection_heads", "rg_api_connection_pending_secret_leases",
                "rg_api_connection_secret_bindings", "staged", "committed", "pending",
                "activated", "abort_required", "value", "secret_ref", "keep_existing",
                "rg_api_connection_revisions_command_uq", "rg_api_connection_revisions_revision_attempt_uq",
                "rg_api_connection_revisions_etag_uq", "rg_api_connection_revisions_state_etag_uq",
                "rg_api_resource_revisions_connection_fk", "on delete restrict");
        assertThat(sql).doesNotContain("secret_value", "secret_plaintext", "secret_ciphertext",
                "secret_ref_json", "credential_json", "password_value", "token_value");
    }

    @Test
    void baseUrlCheckRejectsEmptyHostCredentialsWhitespaceQueryAndFragment() {
        applyMigrations();
        insertJournalFor("url-command", "url-key", "url-connection", 1, "url-attempt");
        insertIdentity("url-connection");
        for (String baseUrl : List.of("https://", "https:///path", "https://:443", " https://example.com",
                "https://example.com ", "https://user@example.com", "https://example.com?x=1",
                "https://example.com#fragment", "https://example.com\tpath", "https://example.com\npath",
                "https://example.com\rpath", "https://example.com\u000Bpath", "https://example.com\u000Cpath")) {
            assertThatThrownBy(() -> insertRevision("url-connection", "url-command", 1, "url-attempt", "STAGED",
                    "\"url-etag\"", baseUrl))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void oneCommandCannotStageTwoConnectionRevisions() {
        applyMigrations();
        insertJournalFor("cmd-1", "k1", "c1", 1, "attempt-1");
        insertIdentity("c1");
        insertIdentity("c2");
        insertRevision("c1");

        assertThatThrownBy(() -> insertRevision("c2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoCommandsMayStageSameRevisionButHeadRequiresExactCommittedCommand() {
        applyMigrations();
        insertJournalFor("cmd-a", "key-a", "shared", 1, "attempt-a");
        insertJournalFor("cmd-b", "key-b", "shared", 1, "attempt-b");
        insertIdentity("shared");
        insertRevision("shared", "cmd-a", 1, "attempt-a", "STAGED", "\"etag-a\"", "https://example.com/a");
        insertRevision("shared", "cmd-b", 1, "attempt-b", "STAGED", "\"etag-b\"", "https://example.com/b");

        assertThatThrownBy(() -> insertHead("shared", 1, "cmd-a", "\"etag-a\"", "STAGED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE rg_api_connection_revisions SET state = 'COMMITTED' WHERE command_id = 'cmd-b'");
        insertHead("shared", 1, "cmd-b", "\"etag-b\"", "COMMITTED");
        assertThat(jdbc.queryForObject("SELECT command_id FROM rg_api_connection_heads WHERE connection_id = 'shared'",
                String.class)).isEqualTo("cmd-b");
    }

    @Test
    void pendingLeaseRejectsMismatchedAttemptAndToken() {
        applyMigrations();
        insertJournalFor("lease-command", "lease-key", "lease-connection", 1, "lease-attempt");
        insertIdentity("lease-connection");
        insertRevision("lease-connection", "lease-command", 1, "lease-attempt", "STAGED", "\"lease-etag\"",
                "https://example.com/lease");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rg_api_connection_pending_secret_leases
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     attempt_no, attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle,
                     status, lease_until)
                VALUES ('t', 'p', 'e', 'lease-connection', 1, 'lease-command', 2, 'wrong-attempt',
                        'token', 'VALUE', 'provider', 'lease', 'opaque', 'PENDING', CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRevision(String connectionId) {
        insertRevision(connectionId, "cmd-1", 1, "attempt-1", "STAGED", "\"etag\"", "https://example.com");
    }

    private void insertRevision(String connectionId, String commandId, int attemptNo, String attemptToken,
                                String state, String etag, String baseUrl) {
        jdbc.update("""
                INSERT INTO rg_api_connection_revisions
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id, state,
                     attempt_no, attempt_token, view_json, metadata_fingerprint, base_url,
                     defaults_headers_json, timeout_ms, auth_kind, strong_etag)
                VALUES ('t', 'p', 'e', ?, 1, ?, ?, ?, ?, '{}', ?, ?, '{}', 30000, 'NONE', ?)
                """, connectionId, commandId, state, attemptNo, attemptToken,
                "sha256:" + "b".repeat(64), baseUrl, etag);
    }

    private void insertHead(String connectionId, int revision, String commandId, String etag, String state) {
        jdbc.update("""
                INSERT INTO rg_api_connection_heads
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     strong_etag, revision_state)
                VALUES ('t', 'p', 'e', ?, ?, ?, ?, ?)
                """, connectionId, revision, commandId, etag, state);
    }

    private void insertJournalFor(String commandId, String idempotencyKey, String targetId,
                                  int attemptNo, String attemptToken) {
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_CONNECTION_SAVE', ?, ?, ?, ?,
                        'PREPARING', ?, ?, CURRENT_TIMESTAMP, 'CREATE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, targetId, idempotencyKey, commandId, "sha256:" + "a".repeat(64), attemptNo, attemptToken);
    }

    private void insertIdentity(String connectionId) {
        jdbc.update("INSERT INTO rg_api_connection_identities VALUES ('t', 'p', 'e', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                connectionId);
    }

    private void applyMigrations() {
        applyMigrationsThroughSecond();
        applyMigration("V20260830_003__api_connection_secret_staging.sql");
    }

    private void applyMigrationsThroughSecond() {
        applyMigration("V20260830_001__api_resource_authoring.sql");
        applyMigration("V20260830_002__api_resource_concurrent_staging.sql");
    }

    private void applyMigration(String migration) {
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration))
                .execute(dataSource);
    }
}
