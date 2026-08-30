package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed, read-only startup probe for the API Connection staging schema.
 * The probe checks every table and column used by the persistence contract and
 * the keys that keep staged revisions and opaque leases scope-bound.
 */
public final class ApiConnectionSchemaReadiness {
    private static final String MIGRATION = "V20260830_003";
    private static final String[] REQUIRED_QUERIES = {
            "SELECT tenant_id, project_id, environment_id, connection_id, created_at, updated_at "
                    + "FROM rg_api_connection_identities WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, state, "
                    + "attempt_no, attempt_token, view_json, metadata_fingerprint, base_url, "
                    + "defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header, "
                    + "strong_etag, created_at, updated_at FROM rg_api_connection_revisions WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag, "
                    + "revision_state, updated_at FROM rg_api_connection_heads WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no, "
                    + "attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle, status, "
                    + "lease_until, created_at, updated_at FROM rg_api_connection_pending_secret_leases WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, slot, provider_id, "
                    + "active_locator, command_id, created_at, updated_at "
                    + "FROM rg_api_connection_secret_bindings WHERE 1 = 0"
    };

    /** Verifies the supported database and complete Connection schema immediately. */
    public ApiConnectionSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            verifyDatabaseProduct(jdbc);
            for (String query : REQUIRED_QUERIES) {
                jdbc.query(query, ignored -> { });
            }
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
            throw new IllegalStateException("API Connection schema supports only H2 or PostgreSQL (found "
                    + product + ")");
        }
    }

    private static void verifyMetadata(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            requirePrimaryKey(metadata, "rg_api_connection_identities", "rg_api_connection_identities_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id"));
            requirePrimaryKey(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id"));
            requireUnique(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_command_uq",
                    List.of("command_id"));
            requirePrimaryKey(metadata, "rg_api_connection_heads", "rg_api_connection_heads_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id"));
            requirePrimaryKey(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_pk",
                    List.of("command_id", "attempt_no", "attempt_token", "slot"));
            requirePrimaryKey(metadata, "rg_api_connection_secret_bindings", "rg_api_connection_secret_bindings_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "slot"));

            requireForeignKey(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_identity_fk",
                    "rg_api_connection_identities", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id")));
            requireForeignKey(metadata, "rg_api_resource_revisions", "rg_api_resource_revisions_connection_fk",
                    "rg_api_connection_identities", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id")));
            requireForeignKey(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_command_fk",
                    "rg_authoring_command_journal", List.of(
                            pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                            pair("attempt_token", "attempt_token")));
            requireForeignKey(metadata, "rg_api_connection_heads", "rg_api_connection_heads_revision_fk",
                    "rg_api_connection_revisions", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id"),
                            pair("revision", "revision"), pair("command_id", "command_id"),
                            pair("strong_etag", "strong_etag"), pair("revision_state", "state")));
            requireForeignKey(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_revision_fk", "rg_api_connection_revisions", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id"),
                            pair("revision", "revision"), pair("command_id", "command_id")));
            requireForeignKey(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_command_fk", "rg_authoring_command_journal", List.of(
                            pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                            pair("attempt_token", "attempt_token")));
            requireForeignKey(metadata, "rg_api_connection_secret_bindings",
                    "rg_api_connection_secret_bindings_revision_fk", "rg_api_connection_revisions", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id"),
                            pair("revision", "revision"), pair("command_id", "command_id")));
            requireIndex(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_visibility_idx",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "state", "revision"));
            requireIndex(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_recovery_idx",
                    List.of("status", "lease_until", "updated_at"));
            return null;
        });
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
                    && ordered(actual).equals(normalized(columns))) return;
        }
        throw new IllegalStateException("missing or misordered primary key " + expected);
    }

    private static void requireIndex(DatabaseMetaData metadata, String table, String expected,
                                     List<String> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, String> actual = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (rows.next()) {
                    if (normalize(expected).equals(normalize(rows.getString("INDEX_NAME")))) {
                        actual.put(rows.getShort("ORDINAL_POSITION"), normalize(rows.getString("COLUMN_NAME")));
                    }
                }
            }
            if (ordered(actual).equals(normalized(columns))) return;
        }
        throw new IllegalStateException("missing or misordered index " + expected);
    }

    private static void requireUnique(DatabaseMetaData metadata, String table, String expected,
                                      List<String> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<String, Map<Short, String>> indexes = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(null, null, candidate, true, false)) {
                while (rows.next()) {
                    String index = rows.getString("INDEX_NAME");
                    String column = rows.getString("COLUMN_NAME");
                    if (index != null && column != null) {
                        indexes.computeIfAbsent(normalize(index), ignored -> new HashMap<>())
                                .put(rows.getShort("ORDINAL_POSITION"), normalize(column));
                    }
                }
            }
            for (Map.Entry<String, Map<Short, String>> index : indexes.entrySet()) {
                if (ordered(index.getValue()).equals(normalized(columns))) return;
            }
        }
        throw new IllegalStateException("missing or misordered unique constraint " + expected);
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String expected,
                                           String targetTable, List<ColumnPair> columns) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, ColumnPair> actual = new HashMap<>();
            String keyName = null;
            String actualTarget = null;
            try (ResultSet rows = metadata.getImportedKeys(null, null, candidate)) {
                while (rows.next()) {
                    if (keyName == null || normalize(expected).equals(normalize(rows.getString("FK_NAME")))) {
                        keyName = rows.getString("FK_NAME");
                        actualTarget = rows.getString("PKTABLE_NAME");
                        actual.put(rows.getShort("KEY_SEQ"), pair(rows.getString("FKCOLUMN_NAME"),
                                rows.getString("PKCOLUMN_NAME")));
                    }
                }
            }
            if (normalize(expected).equals(normalize(keyName))
                    && normalize(targetTable).equals(normalize(actualTarget))
                    && orderedPairs(actual).equals(normalizedPairs(columns))) return;
        }
        throw new IllegalStateException("missing or misordered foreign key " + expected);
    }

    private static Set<String> tableCandidates(String table) {
        Set<String> candidates = new HashSet<>();
        candidates.add(table);
        candidates.add(table.toUpperCase(Locale.ROOT));
        candidates.add(table.toLowerCase(Locale.ROOT));
        return candidates;
    }

    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

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
        return values.stream().map(ApiConnectionSchemaReadiness::normalize).toList();
    }

    private static List<ColumnPair> normalizedPairs(List<ColumnPair> values) {
        return values.stream().map(pair -> pair(pair.foreign(), pair.primary())).toList();
    }

    private static ColumnPair pair(String foreign, String primary) {
        return new ColumnPair(normalize(foreign), normalize(primary));
    }

    private record ColumnPair(String foreign, String primary) { }

    private static IllegalStateException notReady(Throwable cause) {
        return new IllegalStateException("API Connection schema is not ready; apply " + MIGRATION, cause);
    }
}
