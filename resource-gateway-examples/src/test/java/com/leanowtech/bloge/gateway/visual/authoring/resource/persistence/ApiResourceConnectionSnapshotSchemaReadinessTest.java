package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResourceConnectionSnapshotSchemaReadinessTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-snapshot-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void v011PassesReadinessButV010DoesNot() {
        applyThrough(10);
        assertThatThrownBy(() -> new ApiResourceConnectionSnapshotSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_011");

        applyMigration("V20260831_011__api_resource_connection_snapshot.sql");
        new ApiResourceConnectionSnapshotSchemaReadiness(jdbc);
    }

    @Test
    void missingOrMisorderedSnapshotIndexFailsClosed() {
        applyThrough(11);
        jdbc.execute("DROP INDEX rg_api_resource_revisions_connection_snapshot_idx");
        jdbc.execute("CREATE INDEX rg_api_resource_revisions_connection_snapshot_idx "
                + "ON rg_api_resource_revisions (state, connection_id)");

        assertThatThrownBy(() -> new ApiResourceConnectionSnapshotSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_011");
    }

    @Test
    void nullableSnapshotAuthorityFailsClosed() {
        applyThrough(11);
        jdbc.execute("ALTER TABLE rg_api_resource_revisions "
                + "ALTER COLUMN connection_revision DROP NOT NULL");

        assertThatThrownBy(() -> new ApiResourceConnectionSnapshotSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_011");
    }

    @Test
    void legacyResourceWithoutProvableSnapshotFailsMigration() {
        applyThrough(10);
        jdbc.update("""
                INSERT INTO rg_api_connection_identities
                    (tenant_id, project_id, environment_id, connection_id)
                VALUES ('tenant', 'project', 'dev', 'connection')
                """);
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode)
                VALUES ('tenant', 'project', 'dev', 'actor', 'API_RESOURCE_SAVE', 'resource',
                        'key', 'command', ?, 'PREPARING', 1, 'token',
                        CURRENT_TIMESTAMP + INTERVAL '1' HOUR, 'CREATE')
                """, "sha256:" + "1".repeat(64));
        jdbc.update("""
                INSERT INTO rg_authoring_command_attempts
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode)
                SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                       idempotency_key, command_id, request_fingerprint, status, attempt_no,
                       attempt_token, lease_until, expected_mode
                  FROM rg_authoring_command_journal
                 WHERE command_id = 'command'
                """);
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state,
                     spec_json, spec_fingerprint, connection_id, strong_etag, command_id,
                     attempt_no, attempt_token)
                VALUES ('tenant', 'project', 'dev', 'resource', 1, 'STAGED', '{}', ?,
                        'connection', '"etag"', 'command', 1, 'token')
                """, "sha256:" + "2".repeat(64));

        assertThatThrownBy(() -> applyMigration(
                "V20260831_011__api_resource_connection_snapshot.sql"))
                .isInstanceOf(RuntimeException.class);
    }

    private void applyThrough(int version) {
        String[] migrations = {
                "V20260830_001__api_resource_authoring.sql",
                "V20260830_002__api_resource_concurrent_staging.sql",
                "V20260830_003__api_connection_secret_staging.sql",
                "V20260830_004__connection_metadata_authority.sql",
                "V20260830_005__pending_secret_store_protocol.sql",
                "V20260830_006__pending_secret_store_hardening.sql",
                "V20260831_007__pending_secret_store_protocol_closure.sql",
                "V20260831_008__pending_secret_store_child_cas_closure.sql",
                "V20260831_009__authoring_command_attempt_authority.sql",
                "V20260831_010__attempt_provenance_closure.sql",
                "V20260831_011__api_resource_connection_snapshot.sql"
        };
        for (int index = 0; index < version; index++) applyMigration(migrations[index]);
    }

    private void applyMigration(String name) {
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + name))
                .execute(dataSource);
    }
}
