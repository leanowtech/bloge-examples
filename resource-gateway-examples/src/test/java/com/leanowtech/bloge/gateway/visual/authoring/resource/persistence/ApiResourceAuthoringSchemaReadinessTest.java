package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResourceAuthoringSchemaReadinessTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:authoring-schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void migrationPassesReadinessOnH2PostgresMode() {
        applyMigration();

        new ApiResourceAuthoringSchemaReadiness(jdbc);
    }

    @Test
    void emptyDatabaseFailsClosedWithMigrationHint() {
        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_001");
    }

    @Test
    void missingTableOrColumnFailsClosed() {
        applyMigration();
        jdbc.execute("DROP TABLE rg_api_resource_heads");
        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_001");

        applyMigration();
        jdbc.execute("DROP INDEX rg_api_resource_revisions_connection_visibility_idx");
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP COLUMN connection_id");
        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_001");
    }

    @Test
    void coordinateAndCommandConstraintsRejectDuplicates() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        assertThatThrownBy(() -> insertJournal("PREPARING", null, null, null))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, expected_revision,
                     created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'r', 'k2', 'cmd-1',
                        'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'PREPARING', 1, 'token-1', CURRENT_TIMESTAMP, 'CREATE', NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void journalStateReceiptInvariantsAreDatabaseChecked() {
        applyMigration();
        assertThatThrownBy(() -> insertJournal("COMMITTED", null, null, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertJournal("FAILED", null, null, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertJournal("PREPARING", "bloge.receipt.v1", "{}", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void projectionRequiresReadyDocumentsAndMatchingSetFingerprint() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        insertRevision("STAGED", "cmd-1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('t', 'p', 'e', 'r', 1, '{}', 'fp-descriptor', 'STAGED', '{}', 'fp-contract', 'READY',
                        '{}', 'fp-operator', 'READY', 'fp-set')
                """))
                .isInstanceOf(RuntimeException.class);

        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('t', 'p', 'e', 'r', 1, '{}', 'fp-descriptor', 'READY', '{}', 'fp-contract', 'READY',
                        '{}', 'fp-operator', 'READY', 'fp-set')
                """);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_projection_revisions", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id = 'cmd-1'");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_projection_revisions", Integer.class)).isEqualTo(0);
    }

    @Test
    void headForeignKeyCannotExpressRevisionStateAndReadinessJoinIsReadOnly() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        insertRevision("STAGED", "cmd-1");
        jdbc.update("""
                INSERT INTO rg_api_resource_heads
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     strong_etag, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, '"etag"', CURRENT_TIMESTAMP)
                """);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM rg_api_resource_revisions WHERE command_id = 'cmd-1'", String.class))
                .isEqualTo("STAGED");
        // A state-aware writer must prevent this; the portable FK only checks identity.
    }

    private void applyMigration() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260830_001__api_resource_authoring.sql")).execute(dataSource);
    }

    private void insertJournal(String status, String receiptSchema, String receiptJson, String failureCode) {
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, expected_revision,
                     receipt_schema, receipt_json, receipt_fingerprint, receipt_etag,
                     failure_code, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'r', 'k1', 'cmd-1',
                        'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        ?, 1, 'token-1', CURRENT_TIMESTAMP, 'CREATE', NULL, ?, ?, NULL, NULL,
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, status, receiptSchema, receiptJson, failureCode);
    }

    private void insertRevision(String state, String commandId) {
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state,
                     spec_json, spec_fingerprint, connection_id, strong_etag, command_id,
                     attempt_no, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, ?, '{}',
                        'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'connection', '"etag"', ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, state, commandId);
    }
}
