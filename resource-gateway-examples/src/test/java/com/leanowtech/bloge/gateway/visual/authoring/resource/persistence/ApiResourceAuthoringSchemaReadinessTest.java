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
                .hasMessageContaining("V20260830_002");
    }

    @Test
    void missingTableOrColumnFailsClosed() {
        applyMigration();
        jdbc.execute("DROP TABLE rg_api_resource_heads");
        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_002");

        jdbc.execute("DROP ALL OBJECTS");
        applyMigration();
        jdbc.execute("DROP INDEX rg_api_resource_revisions_connection_visibility_idx");
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP COLUMN connection_id");
        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_002");
    }

    @Test
    void readinessFailsWhenCoordinateUniqueIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_authoring_command_journal DROP CONSTRAINT rg_authoring_command_journal_coordinate_uq");
        jdbc.execute("ALTER TABLE rg_authoring_command_journal ADD CONSTRAINT rg_authoring_command_journal_coordinate_uq "
                + "UNIQUE (tenant_id, project_id)");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenLeaseRecoveryIndexIsDropped() {
        applyMigration();
        jdbc.execute("DROP INDEX rg_authoring_command_journal_lease_recovery_idx");
        jdbc.execute("CREATE INDEX rg_authoring_command_journal_lease_recovery_idx "
                + "ON rg_authoring_command_journal (status, updated_at, lease_until)");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenRequiredForeignKeyIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_api_resource_revisions DROP CONSTRAINT rg_api_resource_revisions_command_fk");
        jdbc.execute("ALTER TABLE rg_api_resource_revisions ADD CONSTRAINT rg_api_resource_revisions_command_fk "
                + "FOREIGN KEY (command_id) REFERENCES rg_authoring_command_journal (command_id)");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenHeadProjectionForeignKeyIsDropped() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_api_resource_heads DROP CONSTRAINT rg_api_resource_heads_projection_fk");
        jdbc.execute("ALTER TABLE rg_api_resource_heads ADD CONSTRAINT rg_api_resource_heads_projection_fk "
                + "FOREIGN KEY (tenant_id, project_id, environment_id, resource_id, revision, command_id) "
                + "REFERENCES rg_api_resource_revisions (tenant_id, project_id, environment_id, resource_id, revision, command_id)");

        assertThatThrownBy(() -> new ApiResourceAuthoringSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readinessFailsWhenHeadRevisionForeignKeyOmitsState() {
        applyMigration();
        jdbc.execute("ALTER TABLE rg_api_resource_heads DROP CONSTRAINT rg_api_resource_heads_revision_fk");
        jdbc.execute("ALTER TABLE rg_api_resource_heads ADD CONSTRAINT rg_api_resource_heads_revision_fk "
                + "FOREIGN KEY (tenant_id, project_id, environment_id, resource_id, revision, command_id) "
                + "REFERENCES rg_api_resource_revisions (tenant_id, project_id, environment_id, resource_id, revision, command_id)");

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
    void attemptFencingForeignKeyRejectsWrongAttemptAndToken() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);

        assertThatThrownBy(() -> insertRevision("STAGED", "cmd-1", 2, "token-1", FP_C))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertRevision("STAGED", "cmd-1", 1, "token-2", FP_C))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void takeoverCannotUpdateJournalWhileOldStagedAttemptRemains() {
        applyMigration();
        insertJournal("PREPARING", null, null, null);
        insertRevision("STAGED", "cmd-1");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE rg_authoring_command_journal
                   SET attempt_no = 2, attempt_token = 'token-2', updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = 'cmd-1'
                """))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fingerprintsRejectInvalidHexAndUppercaseDigest() {
        applyMigration();
        assertThatThrownBy(() -> insertJournalWithFingerprints(
                "sha256:" + "g".repeat(64), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertJournalWithFingerprints(
                "sha256:" + "A".repeat(64), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertJournalWithFingerprints(
                "sha256:" + " ".repeat(64), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertJournalWithFingerprints(
                "sha256:a" + " ".repeat(63), null))
                .isInstanceOf(RuntimeException.class);

        insertJournal("PREPARING", null, null, null);
        assertThatThrownBy(() -> insertRevision("STAGED", "cmd-1", 1, "token-1",
                "sha256:" + "G".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        insertRevision("STAGED", "cmd-1");
        assertThatThrownBy(() -> insertProjection("READY", "sha256:" + "G".repeat(64)))
                .isInstanceOf(RuntimeException.class);

        jdbc.execute("DELETE FROM rg_api_resource_revisions");
        jdbc.execute("DELETE FROM rg_authoring_command_journal");
        assertThatThrownBy(() -> insertJournalWithFingerprints(
                FP_A, "COMMITTED", "bloge.receipt.v1", "{}", null,
                "sha256:" + "G".repeat(64)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void failureCodeMatchesCommandFailureCodeAndRejectsInvalidValues() {
        applyMigration();
        insertJournal("FAILED", null, null, CommandFailureCode.INTERNAL.value());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_authoring_command_journal", Integer.class))
                .isEqualTo(1);

        jdbc.execute("DELETE FROM rg_authoring_command_journal");
        assertThatThrownBy(() -> insertJournal("FAILED", null, null, "internal"))
                .isInstanceOf(RuntimeException.class);
        jdbc.execute("DELETE FROM rg_authoring_command_journal");
        assertThatThrownBy(() -> insertJournal("FAILED", null, null, "INTERNAL/NOPE"))
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

    /**
     * Proves the migration does not serialize staging on the logical resource/revision alone.
     * Two commands may stage revision one, while the head foreign keys accept only the exact
     * command whose revision has reached COMMITTED and has a READY projection.
     */
    @Test
    void concurrentCommandsMayStageSameRevisionButHeadRequiresExactCommittedReadyCommand() {
        applyMigration();
        insertJournalFor("cmd-1", "k1");
        insertJournalFor("cmd-2", "k2");
        insertRevisionFor("STAGED", "cmd-1");
        insertRevisionFor("STAGED", "cmd-2");
        insertProjectionFor("cmd-1");
        insertProjectionFor("cmd-2");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE revision = 1", Integer.class))
                .isEqualTo(2);
        jdbc.update("UPDATE rg_api_resource_revisions SET state = 'COMMITTED' WHERE command_id = 'cmd-2'");
        insertHeadFor("cmd-2");
        assertThat(jdbc.queryForObject(
                "SELECT command_id FROM rg_api_resource_heads WHERE resource_id = 'r'", String.class))
                .isEqualTo("cmd-2");

        assertThatThrownBy(() -> insertHeadFor("cmd-1"))
                .isInstanceOf(RuntimeException.class);
    }

    private void applyMigration() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260830_001__api_resource_authoring.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260830_002__api_resource_concurrent_staging.sql")).execute(dataSource);
    }

    private void insertJournal(String status, String receiptSchema, String receiptJson, String failureCode) {
        insertJournalWithFingerprints(FP_A, status, receiptSchema, receiptJson, failureCode,
                receiptSchema == null ? null : FP_B);
    }

    private void insertJournalWithFingerprints(String requestFingerprint, String receiptFingerprint) {
        insertJournalWithFingerprints(requestFingerprint, "PREPARING", null, null, null, null);
    }

    private void insertJournalWithFingerprints(String requestFingerprint, String status,
                                               String receiptSchema,
                                               String receiptJson, String failureCode,
                                               String receiptFingerprint) {
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
                """, requestFingerprint, status, receiptSchema, receiptJson,
                receiptFingerprint,
                receiptSchema == null ? null : "\"receipt-etag\"", failureCode);
    }

    private void insertRevision(String state, String commandId) {
        insertRevision(state, commandId, 1, "token-1", FP_C);
    }

    private void insertRevision(String state, String commandId, int attemptNo,
                                String attemptToken, String specFingerprint) {
        insertRevisionFor(state, commandId, attemptNo, attemptToken, specFingerprint);
    }

    private void insertRevisionFor(String state, String commandId) {
        insertRevisionFor(state, commandId, 1, "token-1", FP_C);
    }

    private void insertRevisionFor(String state, String commandId, int attemptNo,
                                   String attemptToken, String specFingerprint) {
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state,
                     spec_json, spec_fingerprint, connection_id, strong_etag, command_id,
                     attempt_no, attempt_token, created_at, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, ?, '{}', ?, 'connection', '"etag"', ?,
                        ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, state, specFingerprint, commandId, attemptNo, attemptToken);
    }

    private void insertProjection(String state) {
        insertProjection(state, FP_A);
    }

    private void insertProjection(String state, String descriptorFingerprint) {
        insertProjectionFor("cmd-1", state, descriptorFingerprint);
    }

    private void insertProjectionFor(String commandId) {
        insertProjectionFor(commandId, "READY", FP_A);
    }

    private void insertProjectionFor(String commandId, String state, String descriptorFingerprint) {
        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('t', 'p', 'e', 'r', 1, ?, '{}', ?, ?, '{}', ?, ?, '{}', ?, ?, ?)
                """, commandId, descriptorFingerprint, state, FP_B, state, FP_C, state, FP_D);
    }

    private void insertHead() {
        insertHeadFor("cmd-1");
    }

    private void insertHeadFor(String commandId) {
        jdbc.update("""
                INSERT INTO rg_api_resource_heads
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id,
                     strong_etag, revision_state, updated_at)
                VALUES ('t', 'p', 'e', 'r', 1, ?, '"etag"', 'COMMITTED', CURRENT_TIMESTAMP)
                """, commandId);
    }

    private void insertJournalFor(String commandId, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, expected_revision,
                     created_at, updated_at)
                VALUES ('t', 'p', 'e', 'a', 'API_RESOURCE_SAVE', 'r', ?, ?, ?,
                        'PREPARING', 1, ?, CURRENT_TIMESTAMP, 'CREATE', NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, idempotencyKey, commandId, FP_A, "token-1");
    }
}
