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
import java.util.Set;

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
    void revisionDisplayNameAndSecretSlotColumnsRequireExactShape() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_revisions DROP COLUMN display_name");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");

        jdbc.execute("DROP ALL OBJECTS");
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_revisions DROP COLUMN display_name");
        jdbc.execute("ALTER TABLE rg_api_connection_revisions ADD display_name VARCHAR(201) NOT NULL");
        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");

        jdbc.execute("DROP ALL OBJECTS");
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_revisions ALTER COLUMN secret_slot SET NOT NULL");
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
    void bindingRevisionStateColumnIsPartOfTheReadinessProbe() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP CONSTRAINT "
                + "rg_api_connection_secret_bindings_revision_fk");
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP CONSTRAINT "
                + "rg_api_connection_secret_bindings_state_ck");
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP COLUMN revision_state");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void readinessFailsWhenAuthClosureCheckIsTampered() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_revisions DROP CONSTRAINT rg_api_connection_revisions_auth_ck");
        jdbc.execute("ALTER TABLE rg_api_connection_revisions ADD CONSTRAINT rg_api_connection_revisions_auth_ck "
                + "CHECK (auth_kind IN ('NONE', 'BEARER', 'BASIC', 'API_KEY'))");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
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
                "abort_required", "value", "secret_ref", "keep_existing",
                "rg_api_connection_revisions_command_uq", "rg_api_connection_revisions_revision_attempt_uq",
                "rg_api_connection_revisions_etag_uq", "rg_api_connection_revisions_state_etag_uq",
                "rg_api_connection_revisions_revision_state_uq", "revision_state",
                "display_name varchar(200) not null", "secret_slot varchar(32)",
                "rg_api_resource_revisions_connection_fk", "on delete restrict");
        assertThat(sql).doesNotContain("activated");
        assertThat(sql).doesNotContain("secret_value", "secret_plaintext", "secret_ciphertext",
                "secret_ref_json", "credential_json", "password_value", "token_value");
    }

    @Test
    void checkClauseSemanticVerifierAcceptsH2AndPostgreSqlRepresentations() {
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "(\"STATUS\" IN ('PENDING', 'ABORT_REQUIRED'))", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "((status)::text = ANY ((ARRAY['PENDING'::character varying, "
                        + "'ABORT_REQUIRED'::character varying])::text[]))", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "((status)::text = ANY ((ARRAY['PENDING'::character varying, "
                        + "'ABORT_REQUIRED'::character varying]::character varying[])::text[]))", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status::varchar IN ('PENDING'::varchar, 'ABORT_REQUIRED'::varchar)", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status::character varying IN ('PENDING'::character varying, "
                        + "'ABORT_REQUIRED'::character varying)", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "((revision_state)::text = 'COMMITTED'::text)", "revision_state",
                Set.of("COMMITTED"))).isTrue();
    }

    @Test
    void checkClauseSemanticVerifierRejectsTamperedRepresentations() {
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status IN ('PENDING', 'ABORT_REQUIRED', 'OTHER')", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "owner_status IN ('PENDING', 'ABORT_REQUIRED')", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status <> 'PENDING'", "status", Set.of("PENDING"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "revision_state = 'STAGED'", "revision_state", Set.of("COMMITTED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "revision_state = 'STAGED'", "revision_state", Set.of("STAGED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "revision_state = 'COMMITTED' OR revision_state = 'STAGED'", "revision_state",
                Set.of("COMMITTED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status::char IN ('PENDING', 'ABORT_REQUIRED')", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status IN ('PENDING'::char, 'ABORT_REQUIRED')", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status = ANY (ARRAY['PENDING', 'ABORT_REQUIRED']::custom[])", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentCheckClause(
                "status::numeric IN ('PENDING', 'ABORT_REQUIRED')", "status",
                Set.of("PENDING", "ABORT_REQUIRED"))).isFalse();
    }

    @Test
    void authClosureSemanticVerifierAcceptsH2AndPostgreSqlRepresentations() {
        assertThat(ApiConnectionSchemaReadiness.equivalentAuthClosureClause("""
                (auth_kind = 'NONE' AND basic_username IS NULL AND api_key_header IS NULL AND secret_slot IS NULL)
                OR (auth_kind = 'BEARER' AND basic_username IS NULL AND api_key_header IS NULL
                    AND secret_slot IS NOT NULL AND secret_slot = 'token')
                OR (auth_kind = 'BASIC' AND basic_username IS NOT NULL AND CHAR_LENGTH(TRIM(basic_username)) > 0
                    AND api_key_header IS NULL AND secret_slot IS NOT NULL AND secret_slot = 'password')
                OR (auth_kind = 'API_KEY' AND basic_username IS NULL AND api_key_header IS NOT NULL
                    AND CHAR_LENGTH(TRIM(api_key_header)) > 0 AND secret_slot IS NOT NULL AND secret_slot = 'value')
                """)).isTrue();
        assertThat(ApiConnectionSchemaReadiness.equivalentAuthClosureClause("""
                (((auth_kind)::text = 'NONE'::text) AND (basic_username IS NULL) AND (api_key_header IS NULL)
                    AND (secret_slot IS NULL))
                OR (((auth_kind)::text = 'BEARER'::text) AND (basic_username IS NULL)
                    AND (api_key_header IS NULL) AND (secret_slot IS NOT NULL)
                    AND ((secret_slot)::text = 'token'::text))
                OR (((auth_kind)::text = 'BASIC'::text) AND (basic_username IS NOT NULL)
                    AND (CHAR_LENGTH(TRIM(basic_username)) > 0) AND (api_key_header IS NULL)
                    AND (secret_slot IS NOT NULL)
                    AND ((secret_slot)::text = 'password'::text))
                OR (((auth_kind)::text = 'API_KEY'::text) AND (basic_username IS NULL)
                    AND (api_key_header IS NOT NULL) AND (CHAR_LENGTH(TRIM(api_key_header)) > 0)
                    AND (secret_slot IS NOT NULL)
                    AND ((secret_slot)::text = 'value'::text))
                """)).isTrue();
    }

    @Test
    void authClosureSemanticVerifierRejectsMissingOrExtraBranches() {
        assertThat(ApiConnectionSchemaReadiness.equivalentAuthClosureClause(
                "auth_kind = 'NONE' AND secret_slot IS NULL")).isFalse();
        assertThat(ApiConnectionSchemaReadiness.equivalentAuthClosureClause("""
                (auth_kind = 'NONE' AND basic_username IS NULL AND api_key_header IS NULL AND secret_slot IS NULL)
                OR (auth_kind = 'BEARER' AND basic_username IS NULL AND api_key_header IS NULL
                    AND secret_slot IS NOT NULL AND secret_slot = 'token')
                OR (auth_kind = 'BASIC' AND basic_username IS NOT NULL AND CHAR_LENGTH(TRIM(basic_username)) > 0
                    AND api_key_header IS NULL AND secret_slot IS NOT NULL AND secret_slot = 'password')
                OR (auth_kind = 'API_KEY' AND basic_username IS NULL AND api_key_header IS NOT NULL
                    AND CHAR_LENGTH(TRIM(api_key_header)) > 0 AND secret_slot IS NOT NULL
                    AND secret_slot IN ('value', 'token'))
                """)).isFalse();
    }

    @Test
    void authClosureRejectsNullAndWrongSecretSlotsAtDatabaseBoundary() {
        applyMigrations();
        insertJournalFor("auth-command", "auth-key", "auth-connection", 1, "auth-attempt");
        insertIdentity("auth-connection");
        insertRevision("auth-connection", "auth-command", 1, "auth-attempt", "STAGED",
                "\"auth-etag\"", "https://example.com/auth");

        assertThatThrownBy(() -> jdbc.update("UPDATE rg_api_connection_revisions SET auth_kind = 'BEARER' "
                + "WHERE command_id = 'auth-command'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE rg_api_connection_revisions SET auth_kind = 'BEARER', secret_slot = 'token' "
                + "WHERE command_id = 'auth-command'");
        assertThatThrownBy(() -> jdbc.update("UPDATE rg_api_connection_revisions SET secret_slot = 'password' "
                + "WHERE command_id = 'auth-command'"))
                .isInstanceOf(DataIntegrityViolationException.class);
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
        insertRevision("url-connection", "url-command", 1, "url-attempt", "STAGED", "\"url-etag\"",
                "https://example.com/users/@me");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions WHERE connection_id = 'url-connection'",
                Integer.class)).isEqualTo(1);
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
    void pendingLeaseRejectsMismatchedCommandWithMatchingAttemptAndToken() {
        applyMigrations();
        insertJournalFor("lease-command", "lease-key", "lease-connection", 1, "lease-attempt");
        insertJournalFor("revision-command", "revision-key", "lease-connection", 1, "lease-attempt");
        insertIdentity("lease-connection");
        insertRevision("lease-connection", "revision-command", 1, "lease-attempt", "STAGED", "\"lease-etag\"",
                "https://example.com/lease");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rg_api_connection_pending_secret_leases
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     attempt_no, attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle,
                     status, lease_until)
                VALUES ('t', 'p', 'e', 'lease-connection', 1, 'lease-command', 1, 'lease-attempt',
                        'token', 'VALUE', 'provider', 'lease', 'opaque', 'PENDING', CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secretBindingRequiresCommittedRevisionAtDatabaseBoundary() {
        applyMigrations();
        insertJournalFor("binding-command", "binding-key", "binding-connection", 1, "binding-attempt");
        insertIdentity("binding-connection");
        insertRevision("binding-connection", "binding-command", 1, "binding-attempt", "STAGED",
                "\"binding-etag\"", "https://example.com/binding");

        assertThatThrownBy(() -> insertBinding("binding-connection", "binding-command", "COMMITTED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE rg_api_connection_revisions SET state = 'COMMITTED' WHERE command_id = 'binding-command'");
        insertBinding("binding-connection", "binding-command", "COMMITTED");
        assertThat(jdbc.queryForObject("SELECT revision_state FROM rg_api_connection_secret_bindings "
                        + "WHERE connection_id = 'binding-connection'", String.class))
                .isEqualTo("COMMITTED");
    }

    @Test
    void pendingLeaseRejectsActivatedStatusAtDatabaseBoundary() {
        applyMigrations();
        insertJournalFor("activated-command", "activated-key", "activated-connection", 1, "activated-attempt");
        insertIdentity("activated-connection");
        insertRevision("activated-connection", "activated-command", 1, "activated-attempt", "STAGED",
                "\"activated-etag\"", "https://example.com/activated");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rg_api_connection_pending_secret_leases
                    (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                     attempt_no, attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle,
                     status, lease_until)
                VALUES ('t', 'p', 'e', 'activated-connection', 1, 'activated-command', 1, 'activated-attempt',
                        'token', 'VALUE', 'provider', 'lease', 'opaque', 'ACTIVATED', CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readinessFailsWhenConnectionRevisionStateUniqueIsTampered() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP CONSTRAINT "
                + "rg_api_connection_secret_bindings_revision_fk");
        jdbc.execute("ALTER TABLE rg_api_connection_revisions DROP CONSTRAINT rg_api_connection_revisions_revision_state_uq");
        jdbc.execute("ALTER TABLE rg_api_connection_revisions ADD CONSTRAINT rg_api_connection_revisions_revision_state_uq "
                + "UNIQUE (tenant_id, project_id, environment_id, connection_id, revision, state, command_id)");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void readinessFailsWhenBindingRevisionForeignKeyOmitsState() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP CONSTRAINT "
                + "rg_api_connection_secret_bindings_revision_fk");
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings ADD CONSTRAINT "
                + "rg_api_connection_secret_bindings_revision_fk FOREIGN KEY "
                + "(tenant_id, project_id, environment_id, connection_id, revision, command_id) "
                + "REFERENCES rg_api_connection_revisions "
                + "(tenant_id, project_id, environment_id, connection_id, revision, command_id)");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void readinessFailsWhenBindingStateCheckIsTampered() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings DROP CONSTRAINT "
                + "rg_api_connection_secret_bindings_state_ck");
        jdbc.execute("ALTER TABLE rg_api_connection_secret_bindings ADD CONSTRAINT "
                + "rg_api_connection_secret_bindings_state_ck CHECK (revision_state IN ('COMMITTED', 'STAGED'))");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void readinessFailsWhenPendingStatusCheckIsTampered() {
        applyMigrations();
        jdbc.execute("ALTER TABLE rg_api_connection_pending_secret_leases DROP CONSTRAINT "
                + "rg_api_connection_pending_secret_leases_status_ck");
        jdbc.execute("ALTER TABLE rg_api_connection_pending_secret_leases ADD CONSTRAINT "
                + "rg_api_connection_pending_secret_leases_status_ck CHECK "
                + "(status IN ('PENDING', 'ACTIVATED', 'ABORT_REQUIRED'))");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
    }

    @Test
    void readinessFailsWhenRecoveryIndexOrderIsTampered() {
        applyMigrations();
        jdbc.execute("DROP INDEX rg_api_connection_pending_secret_leases_recovery_idx");
        jdbc.execute("CREATE INDEX rg_api_connection_pending_secret_leases_recovery_idx ON "
                + "rg_api_connection_pending_secret_leases "
                + "(status, lease_until, updated_at, command_id, attempt_no, slot, attempt_token)");

        assertThatThrownBy(() -> new ApiConnectionSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_003");
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
                     defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header,
                     display_name, secret_slot, strong_etag)
                VALUES ('t', 'p', 'e', ?, 1, ?, ?, ?, ?, '{}', ?, ?, '{}', 30000,
                        'NONE', NULL, NULL, 'Example connection', NULL, ?)
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

    private void insertBinding(String connectionId, String commandId, String revisionState) {
        jdbc.update("""
                INSERT INTO rg_api_connection_secret_bindings
                    (tenant_id, project_id, environment_id, connection_id, revision, revision_state,
                     slot, provider_id, active_locator, command_id)
                VALUES ('t', 'p', 'e', ?, 1, ?, 'token', 'provider', 'active-locator', ?)
                """, connectionId, revisionState, commandId);
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
