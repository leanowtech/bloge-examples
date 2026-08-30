package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Readiness and fail-closed evidence for the forward-only pending-secret
 * V007–V010 closure. The fixture deliberately starts at V006 so this test
 * exercises upgrade boundaries rather than only a clean current schema.
 */
class PendingSecretStoreSchemaReadinessTest {
    private static final Instant JOURNAL_DEADLINE = Instant.parse("2026-01-01T00:00:10Z");
    private static final Instant PROVIDER_DEADLINE = Instant.parse("2026-01-01T00:00:20Z");
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:pending-schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        applyMigrationsThroughV006();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void v007AppliesToTheV006ShapeAndClampsEffectiveDeadline() {
        insertFixture("deadline", "MATCH", 2L, PROVIDER_DEADLINE);

        applyV007();

        assertThat(jdbc.queryForObject("SELECT provider_lease_until FROM "
                        + "rg_api_connection_pending_secret_leases WHERE command_id=?", Timestamp.class, "deadline")
                .toInstant()).isEqualTo(PROVIDER_DEADLINE);
        assertThat(jdbc.queryForObject("SELECT lease_until FROM rg_api_connection_pending_secret_leases"
                        + " WHERE command_id=?", Timestamp.class, "deadline").toInstant())
                .isEqualTo(JOURNAL_DEADLINE);
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME='RG_API_CONNECTION_PENDING_SECRET_LEASES'"
                        + " AND COLUMN_NAME='CHILD_EXPECTED_MODE'", String.class)).isEqualTo("NO");
    }

    @Test
    void providerDeadlineWinsWhenItPrecedesTheJournalDeadline() {
        insertFixture("provider-first", "MATCH", 2L, JOURNAL_DEADLINE.minusSeconds(5));

        applyV007();

        assertThat(jdbc.queryForObject("SELECT provider_lease_until FROM "
                        + "rg_api_connection_pending_secret_leases WHERE command_id=?", Timestamp.class, "provider-first")
                .toInstant()).isEqualTo(JOURNAL_DEADLINE.minusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT lease_until FROM rg_api_connection_pending_secret_leases"
                        + " WHERE command_id=?", Timestamp.class, "provider-first").toInstant())
                .isEqualTo(JOURNAL_DEADLINE.minusSeconds(5));
    }

    @Test
    void nullChildExpectationFailsTheUpgradeInsteadOfInventingCas() {
        insertFixture("legacy-null", null, null, PROVIDER_DEADLINE);

        assertThatThrownBy(this::applyV007)
                .isInstanceOf(ScriptStatementFailedException.class);
    }

    @Test
    void childExpectationForTheWrongRevisionFailsTheUpgrade() {
        insertFixture("legacy-mismatch", "MATCH", 1L, PROVIDER_DEADLINE);

        assertThatThrownBy(this::applyV007)
                .isInstanceOf(ScriptStatementFailedException.class);
    }

    @Test
    void v008RejectsMatchWithNullChildCasAtDatabaseBoundary() {
        insertFixture("v008-valid", "MATCH", 2L, PROVIDER_DEADLINE);

        applyV007();
        applyV008();

        assertThatThrownBy(() -> jdbc.update("UPDATE rg_api_connection_pending_secret_leases"
                + " SET child_expected_revision=NULL WHERE command_id=?", "v008-valid"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET child_expected_revision=2"
                + " WHERE command_id=?", "v008-valid");
    }

    @Test
    void v008FailsClosedWhenLegacyMatchNullCannotBeProvenSafe() {
        insertFixture("v008-legacy-null", "MATCH", null, PROVIDER_DEADLINE);

        applyV007();

        assertThatThrownBy(this::applyV008)
                .isInstanceOf(ScriptStatementFailedException.class);
    }

    @Test
    void v009CreatesImmutableAttemptAuthorityAndRebindsAllCompositeReferences() {
        insertFixture("v009-authority", "MATCH", 2L, PROVIDER_DEADLINE);

        applyV007();
        applyV008();
        applyV009();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_authoring_command_attempts"
                + " WHERE command_id='v009-authority' AND attempt_no=1 AND attempt_token='v009-authority-attempt'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_attempts"
                + " WHERE command_id='v009-authority' AND attempt_no=1 AND attempt_token='v009-authority-attempt'",
                String.class)).isEqualTo("PREPARING");
        assertThat(foreignKeyTarget("RG_API_CONNECTION_PENDING_SECRET_LEASES",
                "RG_API_CONNECTION_PENDING_SECRET_LEASES_COMMAND_FK")).isEqualTo("RG_AUTH");
        assertThat(foreignKeyTarget("RG_API_CONNECTION_PENDING_SECRET_OUTCOMES",
                "RG_API_CONNECTION_PENDING_SECRET_OUTCOMES_COMMAND_FK")).isEqualTo("RG_AUTH");
        assertThat(foreignKeyTarget("RG_API_CONNECTION_REVISIONS",
                "RG_API_CONNECTION_REVISIONS_COMMAND_FK")).isEqualTo("RG_AUTH");
        assertThat(foreignKeyTarget("RG_API_RESOURCE_REVISIONS",
                "RG_API_RESOURCE_REVISIONS_COMMAND_FK")).isEqualTo("RG_AUTH");
    }

    @Test
    void v010BackfillsExactHeadAndBindingAttemptAndAllowsReplacementProvenance() {
        insertFixture("v010-authority", "MATCH", 2L, PROVIDER_DEADLINE);

        applyV007();
        applyV008();
        applyV009();
        jdbc.update("INSERT INTO rg_api_connection_heads"
                + " (tenant_id, project_id, environment_id, connection_id, revision, command_id,"
                + " strong_etag, revision_state) VALUES ('t', 'p', 'e', 'connection', 3,"
                + " 'v010-authority', '\"etag-v010-authority\"', 'COMMITTED')");
        jdbc.update("INSERT INTO rg_api_connection_secret_bindings"
                + " (tenant_id, project_id, environment_id, connection_id, revision, revision_state,"
                + " slot, provider_id, active_locator, command_id) VALUES ('t', 'p', 'e', 'connection', 3,"
                + " 'COMMITTED', 'token', 'provider:one', 'active', 'v010-authority')");

        applyV010();

        assertThat(jdbc.queryForObject("SELECT attempt_no FROM rg_api_connection_heads"
                        + " WHERE command_id='v010-authority'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_token FROM rg_api_connection_heads"
                        + " WHERE command_id='v010-authority'", String.class)).isEqualTo("v010-authority-attempt");
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM rg_api_connection_secret_bindings"
                        + " WHERE command_id='v010-authority'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_token FROM rg_api_connection_secret_bindings"
                        + " WHERE command_id='v010-authority'", String.class)).isEqualTo("v010-authority-attempt");
        assertThat(foreignKeyTarget("RG_API_CONNECTION_HEADS", "RG_API_CONNECTION_HEADS_REVISION_FK"))
                .isEqualTo("RG_API_CONNECTION_REVISIONS");
        assertThat(foreignKeyTarget("RG_API_CONNECTION_SECRET_BINDINGS",
                "RG_API_CONNECTION_SECRET_BINDINGS_REVISION_FK")).isEqualTo("RG_API_CONNECTION_REVISIONS");
        assertThat(foreignKeyColumns("RG_API_CONNECTION_HEADS_REVISION_FK"))
                .contains("ATTEMPT_NO", "ATTEMPT_TOKEN");
        assertThat(foreignKeyColumns("RG_API_CONNECTION_SECRET_BINDINGS_REVISION_FK"))
                .contains("ATTEMPT_NO", "ATTEMPT_TOKEN");

        jdbc.update("UPDATE rg_authoring_command_attempts SET status='SUPERSEDED'"
                + " WHERE command_id='v010-authority' AND attempt_no=1");
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_attempts"
                + " WHERE command_id='v010-authority' AND attempt_no=1", String.class)).isEqualTo("SUPERSEDED");
        insertReplacementRevision();
    }

    private String foreignKeyTarget(String table, String constraint) {
        String referenced = jdbc.query("SELECT tc.TABLE_NAME"
                        + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc"
                        + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " ON tc.CONSTRAINT_SCHEMA=rc.UNIQUE_CONSTRAINT_SCHEMA"
                        + " AND tc.CONSTRAINT_NAME=rc.UNIQUE_CONSTRAINT_NAME"
                        + " WHERE rc.CONSTRAINT_NAME=? AND rc.CONSTRAINT_SCHEMA=SCHEMA()"
                        , (row, ignored) -> row.getString(1), constraint).stream().findFirst().orElse(null);
        return "RG_AUTHORING_COMMAND_ATTEMPTS".equals(referenced) ? "RG_AUTH" : referenced;
    }

    private void applyMigrationsThroughV006() {
        applyMigration("V20260830_001__api_resource_authoring.sql");
        applyMigration("V20260830_002__api_resource_concurrent_staging.sql");
        applyMigration("V20260830_003__api_connection_secret_staging.sql");
        applyMigration("V20260830_004__connection_metadata_authority.sql");
        applyMigration("V20260830_005__pending_secret_store_protocol.sql");
        applyMigration("V20260830_006__pending_secret_store_hardening.sql");
    }

    private void applyV007() {
        applyMigration("V20260831_007__pending_secret_store_protocol_closure.sql");
    }

    private void applyV008() {
        applyMigration("V20260831_008__pending_secret_store_child_cas_closure.sql");
    }

    private void applyV009() {
        applyMigration("V20260831_009__authoring_command_attempt_authority.sql");
    }

    private void applyV010() {
        applyMigration("V20260831_010__attempt_provenance_closure.sql");
    }

    private java.util.List<String> foreignKeyColumns(String constraint) {
        return jdbc.query("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE"
                        + " WHERE CONSTRAINT_SCHEMA=SCHEMA() AND CONSTRAINT_NAME=?"
                        + " ORDER BY ORDINAL_POSITION", (row, ignored) -> row.getString(1), constraint);
    }

    private void insertReplacementRevision() {
        String token = "v010-replacement-attempt";
        jdbc.update("INSERT INTO rg_authoring_command_attempts"
                + " (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,"
                + " command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,"
                + " expected_mode, expected_revision) VALUES ('t', 'p', 'e', 'actor', 'API_CONNECTION_SAVE',"
                + " 'connection', 'idempotency-v010-authority', 'v010-authority', ?, 'PREPARING', 2, ?, ?,"
                + " 'MATCH', 2)", "sha256:" + "a".repeat(64), token,
                Timestamp.from(JOURNAL_DEADLINE.plusSeconds(30)));
        jdbc.update("INSERT INTO rg_api_connection_revisions"
                + " (tenant_id, project_id, environment_id, connection_id, revision, command_id, state,"
                + " attempt_no, attempt_token, display_name, secret_slot, view_json, metadata_fingerprint, base_url,"
                + " defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header, strong_etag)"
                + " VALUES ('t', 'p', 'e', 'connection', 3, 'v010-authority', 'STAGED', 2, ?, 'replacement',"
                + " 'token', '{}', ?, 'https://example.com', '{}', 1000, 'BEARER', NULL, NULL, '\"replacement\"')",
                token, "sha256:" + "c".repeat(64));
    }

    private void applyMigration(String name) {
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + name)).execute(dataSource);
    }

    private void insertFixture(String commandId, String childMode, Long childRevision, Instant providerDeadline) {
        String token = commandId + "-attempt";
        String fingerprint = "sha256:" + "a".repeat(64);
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                     idempotency_key, command_id, request_fingerprint, status, attempt_no,
                     attempt_token, lease_until, expected_mode, expected_revision)
                VALUES ('t', 'p', 'e', 'actor', 'API_CONNECTION_SAVE', 'connection', ?, ?, ?,
                        'PREPARING', 1, ?, ?, 'MATCH', 2)
                """, "idempotency-" + commandId, commandId, fingerprint, token,
                Timestamp.from(JOURNAL_DEADLINE));
        jdbc.update("INSERT INTO rg_api_connection_identities"
                + " (tenant_id, project_id, environment_id, connection_id) VALUES ('t', 'p', 'e', 'connection')");
        jdbc.update("""
                INSERT INTO rg_api_connection_revisions
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     state, attempt_no, attempt_token, display_name, secret_slot, view_json,
                     metadata_fingerprint, base_url, defaults_headers_json, timeout_ms, auth_kind,
                     basic_username, api_key_header, strong_etag)
                VALUES ('t', 'p', 'e', 'connection', 3, ?, 'COMMITTED', 1, ?, 'fixture',
                        'token', '{}', ?, 'https://example.com', '{}', 1000, 'BEARER', NULL, NULL, ?)
                """, commandId, token, "sha256:" + "b".repeat(64), "\"etag-" + commandId + "\"");
        jdbc.update("""
                INSERT INTO rg_api_connection_pending_secret_leases
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     attempt_no, attempt_token, slot, source_mode, provider_id, lease_id,
                     opaque_handle, status, lease_until, provider_lease_until,
                     source_tenant_id, source_project_id, source_environment_id, source_connection_id,
                     source_revision, child_expected_mode, child_expected_revision,
                     context_tenant_id, context_project_id, context_environment_id, context_actor_id,
                     context_purpose, context_connection_id, context_revision, context_command_id,
                     context_attempt_no, context_attempt_token)
                VALUES ('t', 'p', 'e', 'connection', 3, ?, 1, ?, 'token', 'VALUE',
                        'provider:one', ?, 'opaque', 'PENDING', ?, ?, NULL, NULL, NULL, NULL, NULL,
                        ?, ?, 't', 'p', 'e', 'actor', 'connection-save', 'connection', 3, ?, 1, ?)
                """, commandId, token, token, Timestamp.from(Instant.parse("2025-12-31T23:59:00Z")),
                Timestamp.from(providerDeadline), childMode, childRevision, commandId, token);
    }
}
