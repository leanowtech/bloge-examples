package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed V010 gate for the standalone Connection authoring runtime. */
public final class ApiConnectionAuthoringSchemaReadiness {
    private static final String MIGRATION = "V20260831_010";
    private static final List<String> REVISION_KEY = List.of(
            "tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id",
            "attempt_no", "attempt_token");

    /** Performs read-only final-authority checks; migrations remain external. */
    public ApiConnectionAuthoringSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            jdbc.query("""
                    SELECT command_id, attempt_no, attempt_token, tenant_id, project_id,
                           environment_id, actor_id, endpoint, target_id, idempotency_key,
                           request_fingerprint, status, lease_until, expected_mode, expected_revision
                      FROM rg_authoring_command_attempts
                     WHERE 1 = 0
                    """, ignored -> { });
            jdbc.query("""
                    SELECT tenant_id, project_id, environment_id, connection_id, revision,
                           command_id, attempt_no, attempt_token, state, strong_etag
                      FROM rg_api_connection_revisions
                     WHERE 1 = 0
                    """, ignored -> { });
            jdbc.query("""
                    SELECT tenant_id, project_id, environment_id, connection_id, revision,
                           command_id, attempt_no, attempt_token, strong_etag, revision_state
                      FROM rg_api_connection_heads
                     WHERE 1 = 0
                    """, ignored -> { });
            requireAttemptStatusClosure(jdbc);
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                verify(connection.getMetaData());
                return null;
            });
        } catch (DataAccessException | IllegalStateException failure) {
            throw notReady(failure);
        }
    }

    private static void requireAttemptStatusClosure(JdbcTemplate jdbc) {
        List<String> clauses = jdbc.query("""
                SELECT cc.check_clause
                  FROM information_schema.check_constraints cc
                  JOIN information_schema.table_constraints tc
                    ON tc.constraint_schema = cc.constraint_schema
                   AND tc.constraint_name = cc.constraint_name
                 WHERE UPPER(tc.table_name) = UPPER(?)
                   AND UPPER(tc.constraint_name) = UPPER(?)
                """, (rows, ignored) -> rows.getString("check_clause"),
                "rg_authoring_command_attempts", "rg_authoring_command_attempts_status_ck");
        if (clauses.size() != 1 || !ApiConnectionSchemaReadiness.equivalentCheckClause(
                clauses.getFirst(), "status", Set.of("PREPARING", "SUPERSEDED", "COMMITTED", "FAILED"))) {
            throw new IllegalStateException("missing or altered immutable attempt status closure");
        }
    }

    private static void verify(DatabaseMetaData metadata) throws SQLException {
        requirePrimaryKey(metadata, "rg_authoring_command_attempts",
                List.of("command_id", "attempt_no", "attempt_token"));
        requirePrimaryKey(metadata, "rg_api_connection_revisions", REVISION_KEY);
        requireForeignKey(metadata, "rg_authoring_command_attempts",
                "rg_authoring_command_attempts_journal_fk", "rg_authoring_command_journal",
                List.of(pair("command_id", "command_id")));
        requireForeignKey(metadata, "rg_api_connection_revisions",
                "rg_api_connection_revisions_command_fk", "rg_authoring_command_attempts",
                List.of(pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                        pair("attempt_token", "attempt_token")));
        requireForeignKey(metadata, "rg_api_connection_heads",
                "rg_api_connection_heads_revision_fk", "rg_api_connection_revisions",
                List.of(pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                        pair("environment_id", "environment_id"), pair("connection_id", "connection_id"),
                        pair("revision", "revision"), pair("command_id", "command_id"),
                        pair("attempt_no", "attempt_no"), pair("attempt_token", "attempt_token"),
                        pair("strong_etag", "strong_etag"), pair("revision_state", "state")));
        requireIndex(metadata, "rg_authoring_command_attempts", "rg_authoring_command_attempts_recovery_idx",
                List.of("status", "lease_until", "command_id", "attempt_no", "attempt_token"));
        requireIndex(metadata, "rg_api_connection_heads", "rg_api_connection_heads_attempt_idx",
                List.of("command_id", "attempt_no", "attempt_token"));
        requireNotNull(metadata, "rg_api_connection_heads", Set.of("attempt_no", "attempt_token"));
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, String table,
                                          List<String> expected) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, String> columns = new HashMap<>();
            try (ResultSet rows = metadata.getPrimaryKeys(null, null, candidate)) {
                while (rows.next()) {
                    columns.put(rows.getShort("KEY_SEQ"), normalize(rows.getString("COLUMN_NAME")));
                }
            }
            if (ordered(columns).equals(expected)) return;
        }
        throw new IllegalStateException("missing or misordered primary key for " + table);
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String name,
                                          String target, List<ColumnPair> expected) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, ColumnPair> columns = new HashMap<>();
            String actualTarget = null;
            try (ResultSet rows = metadata.getImportedKeys(null, null, candidate)) {
                while (rows.next()) {
                    if (!name.equalsIgnoreCase(rows.getString("FK_NAME"))) continue;
                    actualTarget = normalize(rows.getString("PKTABLE_NAME"));
                    columns.put(rows.getShort("KEY_SEQ"),
                            pair(rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME")));
                }
            }
            if (normalize(target).equals(actualTarget) && orderedPairs(columns).equals(expected)) return;
        }
        throw new IllegalStateException("missing or misordered foreign key " + name);
    }

    private static void requireIndex(DatabaseMetaData metadata, String table, String name,
                                     List<String> expected) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, String> columns = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (rows.next()) {
                    if (name.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                        columns.put(rows.getShort("ORDINAL_POSITION"),
                                normalize(rows.getString("COLUMN_NAME")));
                    }
                }
            }
            if (ordered(columns).equals(expected)) return;
        }
        throw new IllegalStateException("missing or misordered index " + name);
    }

    private static void requireNotNull(DatabaseMetaData metadata, String table,
                                       Set<String> expected) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Set<String> actual = new java.util.HashSet<>();
            try (ResultSet rows = metadata.getColumns(null, null, candidate, null)) {
                while (rows.next()) {
                    if (rows.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                        actual.add(normalize(rows.getString("COLUMN_NAME")));
                    }
                }
            }
            if (actual.containsAll(expected)) return;
        }
        throw new IllegalStateException("nullable immutable attempt columns in " + table);
    }

    private static List<String> ordered(Map<Short, String> columns) {
        return columns.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue).toList();
    }

    private static List<ColumnPair> orderedPairs(Map<Short, ColumnPair> columns) {
        return columns.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue).toList();
    }

    private static List<String> tableCandidates(String table) {
        return java.util.stream.Stream.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))
                .distinct().toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static ColumnPair pair(String foreign, String primary) {
        return new ColumnPair(normalize(foreign), normalize(primary));
    }

    private static IllegalStateException notReady(Throwable cause) {
        return new IllegalStateException("API Connection authoring schema is not ready; apply "
                + MIGRATION, cause);
    }

    private record ColumnPair(String foreign, String primary) { }
}
