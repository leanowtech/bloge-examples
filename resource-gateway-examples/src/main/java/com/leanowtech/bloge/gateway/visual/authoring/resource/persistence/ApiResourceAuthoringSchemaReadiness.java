package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed startup gate for the API Resource authoring persistence schema.
 * Construction performs read-only checks and never applies DDL. A later
 * production runtime configuration is responsible for instantiating this gate;
 * this class is not itself wired into the application context.
 */
public final class ApiResourceAuthoringSchemaReadiness {
    private static final String MIGRATION = "V20260830_001";
    private static final String[] REQUIRED_QUERIES = {
            "SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id, "
                    + "idempotency_key, command_id, request_fingerprint, status, attempt_no, "
                    + "attempt_token, lease_until, expected_mode, expected_revision, receipt_schema, "
                    + "receipt_json, receipt_fingerprint, receipt_etag, failure_code, created_at, updated_at "
                    + "FROM rg_authoring_command_journal WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, resource_id, revision, state, spec_json, "
                    + "spec_fingerprint, connection_id, strong_etag, command_id, attempt_no, attempt_token, "
                    + "created_at, updated_at "
                    + "FROM rg_api_resource_revisions WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, resource_id, revision, descriptor_json, "
                    + "descriptor_fingerprint, descriptor_state, design_contract_json, "
                    + "design_contract_fingerprint, design_contract_state, operator_json, "
                    + "operator_fingerprint, operator_state, set_fingerprint "
                    + "FROM rg_api_resource_projection_revisions WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, resource_id, revision, strong_etag, revision_state, updated_at "
                    + "FROM rg_api_resource_heads WHERE 1 = 0"
    };
    private static final String COMMITTED_PROJECTION_JOIN = """
            SELECT h.tenant_id, h.project_id, h.environment_id, h.resource_id, h.revision,
                   h.strong_etag, h.revision_state, r.state, r.spec_json, r.spec_fingerprint, r.connection_id,
                   p.descriptor_json, p.descriptor_fingerprint, p.descriptor_state,
                   p.design_contract_json, p.design_contract_fingerprint, p.design_contract_state,
                   p.operator_json, p.operator_fingerprint, p.operator_state, p.set_fingerprint
              FROM rg_api_resource_heads h
              JOIN rg_api_resource_revisions r
                ON r.tenant_id = h.tenant_id
               AND r.project_id = h.project_id
               AND r.environment_id = h.environment_id
               AND r.resource_id = h.resource_id
               AND r.revision = h.revision
               AND r.strong_etag = h.strong_etag
               AND r.state = h.revision_state
               AND r.state = 'COMMITTED'
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id
               AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id
               AND p.resource_id = r.resource_id
               AND p.revision = r.revision
               AND p.descriptor_state = 'READY'
               AND p.design_contract_state = 'READY'
               AND p.operator_state = 'READY'
             WHERE 1 = 0
            """;

    /** Verifies the supported database and the complete schema immediately. */
    public ApiResourceAuthoringSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            verifyDatabaseProduct(jdbc);
            for (String query : REQUIRED_QUERIES) {
                jdbc.query(query, ignored -> { });
            }
            jdbc.query(COMMITTED_PROJECTION_JOIN, ignored -> { });
            verifyMetadata(jdbc);
        } catch (DataAccessException | IllegalStateException ex) {
            if (ex instanceof IllegalStateException stateException
                    && stateException.getMessage() != null
                    && stateException.getMessage().contains("only H2 or PostgreSQL")) {
                throw stateException;
            }
            throw notReady(ex);
        } catch (RuntimeException ex) {
            throw notReady(ex);
        }
    }

    private static void verifyDatabaseProduct(JdbcTemplate jdbc) {
        String product = jdbc.execute((ConnectionCallback<String>) connection -> {
            try {
                return connection.getMetaData().getDatabaseProductName();
            } catch (SQLException ex) {
                throw new IllegalStateException("cannot determine database product", ex);
            }
        });
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (!normalized.contains("h2") && !normalized.contains("postgresql")) {
            throw new IllegalStateException("API Resource authoring schema supports only H2 or PostgreSQL (found "
                    + product + ")");
        }
    }

    private static void verifyMetadata(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            requirePrimaryKey(metadata, "rg_authoring_command_journal", "rg_authoring_command_journal_pk");
            requirePrimaryKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_pk");
            requirePrimaryKey(metadata, "rg_api_resource_projection_revisions", "rg_api_resource_projection_revisions_pk");
            requirePrimaryKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_pk");
            requireUniqueColumns(metadata, "rg_authoring_command_journal",
                    Set.of("tenant_id", "project_id", "environment_id", "actor_id", "endpoint",
                            "target_id", "idempotency_key"));
            requireForeignKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_command_fk",
                    "rg_authoring_command_journal");
            requireForeignKey(metadata, "rg_api_resource_projection_revisions",
                    "rg_api_resource_projection_revisions_revision_fk", "rg_api_resource_revisions");
            requireForeignKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_revision_fk",
                    "rg_api_resource_revisions");
            requireForeignKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_projection_fk",
                    "rg_api_resource_projection_revisions");
            requireIndex(metadata, "rg_authoring_command_journal", "rg_authoring_command_journal_lease_recovery_idx");
            requireIndex(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_connection_visibility_idx");
            requireIndex(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_staging_cleanup_idx");
            return null;
        });
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, String table, String expected)
            throws SQLException {
        if (!metadataNames(metadata, table, true, false).contains(normalize(expected))) {
            throw new IllegalStateException("missing primary key " + expected);
        }
    }

    private static void requireIndex(DatabaseMetaData metadata, String table, String expected) throws SQLException {
        if (!metadataNames(metadata, table, false, false).contains(normalize(expected))) {
            throw new IllegalStateException("missing index " + expected);
        }
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String expected,
                                           String targetTable) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            try (ResultSet rows = metadata.getImportedKeys(null, null, candidate)) {
                while (rows.next()) {
                    if (normalize(expected).equals(normalize(rows.getString("FK_NAME")))
                            && normalize(targetTable).equals(normalize(rows.getString("PKTABLE_NAME")))) {
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("missing foreign key " + expected);
    }

    private static void requireUniqueColumns(DatabaseMetaData metadata, String table,
                                              Set<String> expectedColumns) throws SQLException {
        Map<String, Set<String>> uniqueIndexes = new HashMap<>();
        for (String candidate : tableCandidates(table)) {
            try (ResultSet rows = metadata.getIndexInfo(null, null, candidate, true, false)) {
                while (rows.next()) {
                    String index = rows.getString("INDEX_NAME");
                    String column = rows.getString("COLUMN_NAME");
                    if (index != null && column != null) {
                        uniqueIndexes.computeIfAbsent(normalize(index), ignored -> new HashSet<>())
                                .add(normalize(column));
                    }
                }
            }
        }
        Set<String> normalizedExpected = expectedColumns.stream().map(ApiResourceAuthoringSchemaReadiness::normalize).collect(java.util.stream.Collectors.toSet());
        if (!uniqueIndexes.values().stream().anyMatch(normalizedExpected::equals)) {
            throw new IllegalStateException("missing unique authoring command coordinate");
        }
    }

    private static Set<String> metadataNames(DatabaseMetaData metadata, String table, boolean primaryKey,
                                               boolean uniqueOnly) throws SQLException {
        Set<String> names = new HashSet<>();
        for (String candidate : tableCandidates(table)) {
            if (primaryKey) {
                try (ResultSet rows = metadata.getPrimaryKeys(null, null, candidate)) {
                    while (rows.next()) {
                        if (rows.getString("PK_NAME") != null) names.add(normalize(rows.getString("PK_NAME")));
                    }
                }
            } else {
                try (ResultSet rows = metadata.getIndexInfo(null, null, candidate, uniqueOnly, false)) {
                    while (rows.next()) {
                        if (rows.getString("INDEX_NAME") != null) names.add(normalize(rows.getString("INDEX_NAME")));
                    }
                }
            }
        }
        return names;
    }

    private static Set<String> tableCandidates(String table) {
        Set<String> candidates = new HashSet<>();
        candidates.add(table);
        candidates.add(table.toUpperCase(Locale.ROOT));
        candidates.add(table.toLowerCase(Locale.ROOT));
        return candidates;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notReady(Throwable cause) {
        return new IllegalStateException("API Resource authoring schema is not ready; apply "
                + MIGRATION, cause);
    }
}
