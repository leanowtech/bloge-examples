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
    private static final String FP_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FP_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String FP_C = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String FP_D = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

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
    void readinessFailsWhenCoordinateUniqueIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_authoring_command_journal DROP CONSTRAINT rg_authoring_command_journal_coordinate_uq");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenLeaseRecoveryIndexIsDropped() {
        applyMigration();
        jdbc.execute("DROP INDEX rg_authoring_command_journal_lease_recovery_idx");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenRequiredForeignKeyIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP CONSTRAINT rg_api_resource_revisions_command_fk");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenHeadProjectionForeignKeyIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_api_resource_heads DROP CONSTRAINT rg_api_resource_heads_projection_fk");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
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
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'r', 'k2', 'cmd-1', ?,
                        'PREPARING', 1, 'token-1', CURRENT_TIMESTAMP, 'CREATE', NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FP_A))
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
        assertThatThrownBy(() -> insertJournal("FAILED", null, null, "failure code"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void projectionRequiresReadyDocumentsAndSupportsCascadeCleanup() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        insertRevision("STAGED", "cmd-1");

        assertThatThrownBy(() -> insertProjection("STAGED"))
                .isInstanceOf(RuntimeException.class);

        insertProjection("READY");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_projection_revisions", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM rg_api_resource_revisions WHERE command_id = 'cmd-1'");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_projection_revisions", Integer.class)).isEqualTo(0);
    }

    @Test
    void stagedRevisionCannotBeHead() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        insertRevision("STAGED", "cmd-1");

        assertThatThrownBy(this::insertHead)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void committedRevisionWithoutProjectionCannotBeHead() {
        applyMigration();
        insertJournal("COMMITTED", "bloge.receipt.v1", "{}", null);
        insertRevision("COMMITTED", "cmd-1");

        assertThatThrownBy(this::insertHead)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onlyCommittedRevisionWithReadyProjectionCanBeHead() {
        applyMigration();
        insertJournal("COMMITTED", "bloge.receipt.v1", "{}", null);
        insertRevision("COMMITTED", "cmd-1");
        insertProjection("READY");

        insertHead();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_heads", Integer.class))
                .isEqualTo(1);
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
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'r', 'k1', 'cmd-1', ?,
                        ?, 1, 'token-1', CURRENT_TIMESTAMP, 'CREATE', NULL, ?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FP_A, status, receiptSchema, receiptJson,
                receiptSchema == null ? null : FP_B,
                receiptSchema == null ? null : "\"receipt-etag\"", failureCode);
    }

    private void insertRevision(String state, String commandId) {
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state,
                     spec_json, spec_fingerprint, connection_id, strong_etag, command_id,
                     attempt_no, attempt_token, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, ?, '{}', ?, 'connection', '"etag"', ?,
                        1, 'token-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, state, FP_C, commandId);
    }

    private void insertProjection(String state) {
        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('t', 'p', 'e', 'r', 1, '{}', ?, ?, '{}', ?, ?, '{}', ?, ?, ?)
                """, FP_A, state, FP_B, state, FP_C, state, FP_D);
    }

    private void insertHead() {
        jdbc.update("""
                INSERT INTO rg_api_resource_heads
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     strong_etag, revision_state, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, '"etag"', 'COMMITTED', CURRENT_TIMESTAMP)
                """);
    }
}
