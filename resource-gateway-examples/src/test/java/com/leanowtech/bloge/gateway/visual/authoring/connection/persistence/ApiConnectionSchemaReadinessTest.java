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
                "rg_api_connection_revisions_command_uq", "rg_api_resource_revisions_connection_fk");
        assertThat(sql).doesNotContain("secret_value", "secret_plaintext", "secret_ciphertext",
                "secret_ref_json", "credential_json", "password_value", "token_value");
    }

    @Test
    void oneCommandCannotStageTwoConnectionRevisions() {
        applyMigrations();
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_CONNECTION_SAVE', 'c1', 'k1', 'cmd-1', ?,
                        'PREPARING', 1, 'attempt-1', CURRENT_TIMESTAMP, 'CREATE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "sha256:" + "a".repeat(64));
        jdbc.update("INSERT INTO rg_api_connection_identities VALUES ('t', 'p', 'e', 'c1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO rg_api_connection_identities VALUES ('t', 'p', 'e', 'c2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        insertRevision("c1");

        assertThatThrownBy(() -> insertRevision("c2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRevision(String connectionId) {
        jdbc.update("""
                INSERT INTO rg_api_connection_revisions
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id, state,
                     attempt_no, attempt_token, view_json, metadata_fingerprint, base_url,
                     defaults_headers_json, timeout_ms, auth_kind, strong_etag)
                VALUES ('t', 'p', 'e', ?, 1, 'cmd-1', 'STAGED', 1, 'attempt-1', '{}', ?,
                        'https://example.com', '{}', 30000, 'NONE', '"etag"')
                """, connectionId, "sha256:" + "b".repeat(64));
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
