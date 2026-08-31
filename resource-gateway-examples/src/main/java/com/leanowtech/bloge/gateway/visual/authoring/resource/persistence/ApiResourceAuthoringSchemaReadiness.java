package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;

/**
 * Fail-closed startup gate for the API Resource authoring persistence schema.
 * Construction performs read-only checks and never applies DDL. The opt-in
 * production runtime configuration directly wires this gate when authoring is
 * enabled; the disabled feature leaves it absent from the application context.
 */
public final class ApiResourceAuthoringSchemaReadiness {
    private static final String MIGRATION = "V20260830_002";
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
            "SELECT tenant_id, project_id, environment_id, resource_id, revision, command_id, descriptor_json, "
                    + "descriptor_fingerprint, descriptor_state, design_contract_json, "
                    + "design_contract_fingerprint, design_contract_state, operator_json, "
                    + "operator_fingerprint, operator_state, set_fingerprint "
                    + "FROM rg_api_resource_projection_revisions WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, resource_id, revision, command_id, strong_etag, revision_state, updated_at "
                    + "FROM rg_api_resource_heads WHERE 1 = 0"
    };
    private static final String COMMITTED_PROJECTION_JOIN = """
            SELECT h.tenant_id, h.project_id, h.environment_id, h.resource_id, h.revision, h.command_id,
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
               AND r.command_id = h.command_id
               AND r.strong_etag = h.strong_etag
               AND r.state = h.revision_state
               AND r.state = 'COMMITTED'
              JOIN rg_api_resource_projection_revisions p
                ON p.tenant_id = r.tenant_id
               AND p.project_id = r.project_id
               AND p.environment_id = r.environment_id
               AND p.resource_id = r.resource_id
               AND p.revision = r.revision
               AND p.command_id = r.command_id
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
            requirePrimaryKey(metadata, "rg_authoring_command_journal", "rg_authoring_command_journal_pk",
                    List.of("command_id"));
            requirePrimaryKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_pk",
                    scopeResourceColumns("revision", "command_id"));
            requirePrimaryKey(metadata, "rg_api_resource_projection_revisions",
                    "rg_api_resource_projection_revisions_pk", scopeResourceColumns("revision", "command_id"));
            requirePrimaryKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_pk",
                    scopeResourceColumns());
            requireUniqueColumns(metadata, "rg_authoring_command_journal", "coordinate unique",
                    List.of("tenant_id", "project_id", "environment_id", "actor_id", "endpoint",
                            "target_id", "idempotency_key"));
            requireUniqueColumns(metadata, "rg_authoring_command_journal", "attempt unique",
                    List.of("command_id", "attempt_no", "attempt_token"));
            requireResourceCommandForeignKey(metadata, List.of(
                            pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                            pair("attempt_token", "attempt_token")));
            requireForeignKey(metadata, "rg_api_resource_projection_revisions",
                    "rg_api_resource_projection_revisions_revision_fk", "rg_api_resource_revisions",
                    scopeResourcePairs("revision", "command_id"));
            requireForeignKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_revision_fk",
                    "rg_api_resource_revisions", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("resource_id", "resource_id"),
                            pair("revision", "revision"), pair("command_id", "command_id"),
                            pair("strong_etag", "strong_etag"),
                            pair("revision_state", "state")));
            requireForeignKey(metadata, "rg_api_resource_heads", "rg_api_resource_heads_projection_fk",
                    "rg_api_resource_projection_revisions", scopeResourcePairs("revision", "command_id"));
            requireIndexColumns(metadata, "rg_authoring_command_journal",
                    "rg_authoring_command_journal_lease_recovery_idx",
                    List.of("status", "lease_until", "updated_at"));
            requireIndexColumns(metadata, "rg_api_resource_revisions",
                    "rg_api_resource_revisions_connection_visibility_idx",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "state", "resource_id"));
            requireIndexColumns(metadata, "rg_api_resource_revisions",
                    "rg_api_resource_revisions_staging_cleanup_idx", List.of("state", "updated_at"));
            return null;
        });
    }

    private static void requireResourceCommandForeignKey(DatabaseMetaData metadata,
                                                         List<ColumnPair> columns) throws SQLException {
        try {
            requireForeignKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_command_fk",
                    "rg_authoring_command_journal", columns);
        } catch (IllegalStateException legacyMiss) {
            requireForeignKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_command_fk",
                    "rg_authoring_command_attempts", columns);
        }
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, String table, String expected,
                                          List<String> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, String> actual = new HashMap<>();
            String primaryKeyName = null;
            try (ResultSet rows = metadata.getPrimaryKeys(null, null, candidate)) {
                while (rows.next()) {
                    primaryKeyName = rows.getString("PK_NAME");
                    actual.put(rows.getShort("KEY_SEQ"), normalize(rows.getString("COLUMN_NAME")));
                }
            }
            if (normalize(expected).equals(normalize(primaryKeyName))
                    && ordered(actual).equals(normalized(columns))) {
                return;
            }
        }
        throw new IllegalStateException("missing or misordered primary key " + expected);
    }

    private static void requireIndexColumns(DatabaseMetaData metadata, String table, String expected,
                                            List<String> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<String, Map<Short, String>> indexes = indexColumns(metadata, candidate, false);
            Map<Short, String> actual = indexes.get(normalize(expected));
            if (actual != null && ordered(actual).equals(normalized(columns))) {
                return;
            }
        }
        throw new IllegalStateException("missing or misordered index " + expected);
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String expected,
                                           String targetTable, List<ColumnPair> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<String, Map<Short, ColumnPair>> actualByName = new HashMap<>();
            Map<String, String> targetByName = new HashMap<>();
            try (ResultSet rows = metadata.getImportedKeys(null, null, candidate)) {
                while (rows.next()) {
                    String foreignKeyName = normalize(rows.getString("FK_NAME"));
                    targetByName.put(foreignKeyName, normalize(rows.getString("PKTABLE_NAME")));
                    actualByName.computeIfAbsent(foreignKeyName, ignored -> new HashMap<>())
                            .put(rows.getShort("KEY_SEQ"), pair(rows.getString("FKCOLUMN_NAME"),
                                    rows.getString("PKCOLUMN_NAME")));
                }
            }
            String expectedName = normalize(expected);
            Map<Short, ColumnPair> actual = actualByName.get(expectedName);
            if (normalize(targetTable).equals(targetByName.get(expectedName))
                    && actual != null && orderedPairs(actual).equals(normalizedPairs(columns))) {
                return;
            }
        }
        throw new IllegalStateException("missing or misordered foreign key " + expected);
    }

    private static void requireUniqueColumns(DatabaseMetaData metadata, String table,
                                              String description, List<String> expectedColumns)
            throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<String, Map<Short, String>> indexes = indexColumns(metadata, candidate, true);
            for (Map<Short, String> actual : indexes.values()) {
                if (ordered(actual).equals(normalized(expectedColumns))) {
                    return;
                }
            }
        }
        throw new IllegalStateException("missing or misordered " + description);
    }

    private static Map<String, Map<Short, String>> indexColumns(DatabaseMetaData metadata, String table,
                                                                 boolean uniqueOnly) throws SQLException {
        Map<String, Map<Short, String>> indexes = new HashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(null, null, table, uniqueOnly, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (index != null && column != null) {
                    indexes.computeIfAbsent(normalize(index), ignored -> new HashMap<>())
                            .put(rows.getShort("ORDINAL_POSITION"), normalize(column));
                }
            }
        }
        return indexes;
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

    private static List<String> ordered(Map<Short, String> columns) {
        List<Map.Entry<Short, String>> entries = new ArrayList<>(columns.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getKey()));
        return entries.stream().map(Map.Entry::getValue).toList();
    }

    private static List<ColumnPair> orderedPairs(Map<Short, ColumnPair> columns) {
        List<Map.Entry<Short, ColumnPair>> entries = new ArrayList<>(columns.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getKey()));
        return entries.stream().map(Map.Entry::getValue).toList();
    }

    private static List<String> normalized(List<String> values) {
        return values.stream().map(ApiResourceAuthoringSchemaReadiness::normalize).toList();
    }

    private static List<ColumnPair> normalizedPairs(List<ColumnPair> values) {
        return values.stream().map(pair -> pair(pair.foreign(), pair.primary())).toList();
    }

    private static List<String> scopeResourceColumns(String... tail) {
        List<String> columns = new ArrayList<>(List.of("tenant_id", "project_id", "environment_id", "resource_id"));
        columns.addAll(List.of(tail));
        return columns;
    }

    private static List<ColumnPair> scopeResourcePairs(String... tail) {
        List<ColumnPair> pairs = new ArrayList<>(List.of(pair("tenant_id", "tenant_id"),
                pair("project_id", "project_id"), pair("environment_id", "environment_id"),
                pair("resource_id", "resource_id")));
        for (String column : tail) {
            pairs.add(pair(column, column));
        }
        return pairs;
    }

    private static ColumnPair pair(String foreign, String primary) {
        return new ColumnPair(normalize(foreign), normalize(primary));
    }

    private record ColumnPair(String foreign, String primary) { }

    private static IllegalStateException notReady(Throwable cause) {
        return new IllegalStateException("API Resource authoring schema is not ready; apply "
                + MIGRATION, cause);
    }
}
