package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CheckConstraintDefinition;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
 * Fail-closed, read-only startup probe for the API Connection staging schema
 * after V20260830_003 and the additive V20260830_004 authority migration.
 * The probe checks every table and column used by the persistence contract and
 * the keys and checks that keep staged revisions and opaque leases scope-bound.
 * Active secret bindings must point at a committed revision and expose only the
 * provider locator and command metadata needed for runtime hydration.
 */
public final class ApiConnectionSchemaReadiness {
    private static final String MIGRATION = "V20260830_004";
    private static final String[] REQUIRED_QUERIES = {
            "SELECT tenant_id, project_id, environment_id, connection_id, created_at, updated_at "
                    + "FROM rg_api_connection_identities WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, state, "
                    + "attempt_no, attempt_token, view_json, metadata_fingerprint, base_url, "
                    + "defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header, "
                    + "display_name, secret_slot, strong_etag, created_at, updated_at "
                    + "FROM rg_api_connection_revisions WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag, "
                    + "revision_state, updated_at FROM rg_api_connection_heads WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no, "
                    + "attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle, status, "
                    + "lease_until, created_at, updated_at FROM rg_api_connection_pending_secret_leases WHERE 1 = 0",
            "SELECT tenant_id, project_id, environment_id, connection_id, revision, slot, provider_id, "
                    + "revision_state, active_locator, command_id, created_at, updated_at "
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
        requireUnique(jdbc, "rg_api_connection_revisions", "rg_api_connection_revisions_command_uq",
                List.of("command_id"));
        requireUnique(jdbc, "rg_api_connection_revisions", "rg_api_connection_revisions_etag_uq",
                List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id", "strong_etag"));
        requireUnique(jdbc, "rg_api_connection_revisions", "rg_api_connection_revisions_state_etag_uq",
                List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id", "strong_etag", "state"));
        requireUnique(jdbc, "rg_api_connection_revisions", "rg_api_connection_revisions_revision_state_uq",
                List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id", "state"));
        requireUnique(jdbc, "rg_api_connection_revisions", "rg_api_connection_revisions_revision_attempt_uq",
                List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id", "attempt_no", "attempt_token"));
        requireCheck(jdbc, "rg_api_connection_pending_secret_leases",
                "rg_api_connection_pending_secret_leases_status_ck", "status",
                Set.of("PENDING", "ABORT_REQUIRED"));
        requireCheck(jdbc, "rg_api_connection_secret_bindings", "rg_api_connection_secret_bindings_state_ck",
                "revision_state", Set.of("COMMITTED"));
        requireAuthClosure(jdbc);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            requireColumn(metadata, "rg_api_connection_revisions", "display_name", 200, false);
            requireColumn(metadata, "rg_api_connection_revisions", "secret_slot", 32, true);
            requirePrimaryKey(metadata, "rg_api_connection_identities", "rg_api_connection_identities_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id"));
            requirePrimaryKey(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_pk",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "revision", "command_id"));
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
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id")),
                    (short) DatabaseMetaData.importedKeyRestrict);
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
                            pair("revision", "revision"), pair("command_id", "command_id"),
                            pair("attempt_no", "attempt_no"), pair("attempt_token", "attempt_token")));
            requireForeignKey(metadata, "rg_api_connection_secret_bindings",
                    "rg_api_connection_secret_bindings_command_fk", "rg_authoring_command_journal",
                    List.of(pair("command_id", "command_id")));
            requireForeignKey(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_command_fk", "rg_authoring_command_journal", List.of(
                            pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                            pair("attempt_token", "attempt_token")));
            requireForeignKey(metadata, "rg_api_connection_secret_bindings",
                    "rg_api_connection_secret_bindings_revision_fk", "rg_api_connection_revisions", List.of(
                            pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                            pair("environment_id", "environment_id"), pair("connection_id", "connection_id"),
                            pair("revision", "revision"), pair("command_id", "command_id"),
                            pair("revision_state", "state")));
            requireIndex(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_visibility_idx",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "state", "revision"));
            requireIndex(metadata, "rg_api_connection_pending_secret_leases",
                    "rg_api_connection_pending_secret_leases_recovery_idx",
                    List.of("status", "lease_until", "updated_at", "command_id", "attempt_no", "attempt_token", "slot"));
            requireIndex(metadata, "rg_api_connection_revisions", "rg_api_connection_revisions_staging_cleanup_idx",
                    List.of("state", "updated_at"));
            requireIndex(metadata, "rg_api_connection_secret_bindings",
                    "rg_api_connection_secret_bindings_locator_idx",
                    List.of("tenant_id", "project_id", "environment_id", "connection_id", "slot", "provider_id"));
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

    private static void requireUnique(JdbcTemplate jdbc, String table, String expected,
                                      List<String> columns) {
        List<String> actual = jdbc.query("""
                SELECT kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.table_name = tc.table_name
                 WHERE UPPER(tc.table_name) = UPPER(?)
                   AND UPPER(tc.constraint_name) = UPPER(?)
                   AND tc.constraint_type = 'UNIQUE'
                 ORDER BY kcu.ordinal_position
                """, (rs, row) -> normalize(rs.getString("column_name")), table, expected);
        if (!actual.equals(normalized(columns))) {
            throw new IllegalStateException("missing or misordered unique constraint " + expected);
        }
    }

    private static void requireCheck(JdbcTemplate jdbc, String table, String expected,
                                     String column, Set<String> allowedLiterals) {
        List<String> clauses = jdbc.query("""
                SELECT cc.check_clause
                  FROM information_schema.check_constraints cc
                  JOIN information_schema.table_constraints tc
                    ON tc.constraint_schema = cc.constraint_schema
                   AND tc.constraint_name = cc.constraint_name
                 WHERE UPPER(tc.table_name) = UPPER(?)
                   AND UPPER(tc.constraint_name) = UPPER(?)
                """, (rs, row) -> rs.getString("check_clause"), table, expected);
        if (clauses.size() != 1 || !equivalentCheckClause(clauses.getFirst(), column, allowedLiterals)) {
            throw new IllegalStateException("missing or altered check constraint " + expected);
        }
    }

    private static void requireAuthClosure(JdbcTemplate jdbc) {
        List<String> clauses = jdbc.query("""
                SELECT cc.check_clause
                  FROM information_schema.check_constraints cc
                  JOIN information_schema.table_constraints tc
                    ON tc.constraint_schema = cc.constraint_schema
                   AND tc.constraint_name = cc.constraint_name
                 WHERE UPPER(tc.table_name) = UPPER(?)
                   AND UPPER(tc.constraint_name) = UPPER(?)
                """, (rs, row) -> rs.getString("check_clause"),
                "rg_api_connection_revisions", "rg_api_connection_revisions_auth_ck");
        if (clauses.size() != 1 || !equivalentAuthClosureClause(clauses.getFirst())) {
            throw new IllegalStateException("missing or altered check constraint rg_api_connection_revisions_auth_ck");
        }
    }

    private static void requireColumn(DatabaseMetaData metadata, String table, String column,
                                      int expectedSize, boolean nullable) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            try (ResultSet rows = metadata.getColumns(null, null, candidate, null)) {
                while (rows.next()) {
                    if (!normalize(column).equals(normalize(rows.getString("COLUMN_NAME")))) continue;
                    String typeName = normalize(rows.getString("TYPE_NAME")).replace(" ", "");
                    boolean shapeMatches = rows.getInt("DATA_TYPE") == Types.VARCHAR
                            && (typeName.equals("varchar") || typeName.equals("charactervarying"))
                            && rows.getInt("COLUMN_SIZE") == expectedSize
                            && rows.getInt("NULLABLE") == (nullable
                            ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
                    if (shapeMatches) return;
                }
            }
        }
        throw new IllegalStateException("missing or mis-shaped column " + table + "." + column);
    }

    /**
     * Matches the database metadata representation contract for status checks.
     * H2 commonly reports {@code IN}, while PostgreSQL can report equivalent
     * varchar checks as {@code = ANY (ARRAY[...])} with casts. This parser is
     * deliberately bounded to those equivalent forms; it is not PostgreSQL
     * certification or a general SQL parser.
     */
    static boolean equivalentCheckClause(String checkClause, String targetColumn,
                                         Set<String> allowedLiterals) {
        if ("revision_state".equalsIgnoreCase(targetColumn)
                && !allowedLiterals.equals(Set.of("COMMITTED"))) return false;
        return CheckConstraintDefinition.exactLiteralSet(checkClause, targetColumn, allowedLiterals);
    }

    /**
     * Matches the exact auth-to-secret-slot closure, allowing only the H2 and
     * PostgreSQL metadata spelling differences for identifier/literal casts.
     */
    static boolean equivalentAuthClosureClause(String checkClause) {
        if (checkClause == null) return false;
        String clause = CheckConstraintDefinition.stripOuterParens(checkClause.replaceAll("\\s+", ""));
        List<String> branches = splitTopLevel(clause, "OR");
        if (branches.size() != 4) return false;

        Set<Set<String>> actual = new HashSet<>();
        for (String branch : branches) {
            List<String> atoms = splitTopLevel(CheckConstraintDefinition.stripOuterParens(branch), "AND");
            if (atoms.isEmpty()) return false;
            Set<String> canonical = new HashSet<>();
            for (String atom : atoms) canonical.add(canonicalAuthAtom(atom));
            if (canonical.size() != atoms.size()) return false;
            actual.add(canonical);
        }
        return actual.equals(Set.of(
                Set.of("auth_kind='none'", "basic_usernameisnull", "api_key_headerisnull", "secret_slotisnull"),
                Set.of("auth_kind='bearer'", "basic_usernameisnull", "api_key_headerisnull",
                        "secret_slotisnotnull", "secret_slot='token'"),
                Set.of("auth_kind='basic'", "basic_usernameisnotnull", "char_lengthtrimbasic_username>0",
                        "api_key_headerisnull", "secret_slotisnotnull", "secret_slot='password'"),
                Set.of("auth_kind='api_key'", "basic_usernameisnull", "api_key_headerisnotnull",
                        "char_lengthtrimapi_key_header>0", "secret_slotisnotnull", "secret_slot='value'")));
    }

    private static String canonicalAuthAtom(String atom) {
        String canonical = CheckConstraintDefinition.stripOuterParens(atom.replaceAll("\\s+", ""))
                .replace("\"", "")
                .replaceAll("(?i)::(?:text|varchar|charactervarying)(?:\\[\\])?", "")
                .replaceAll("(?i)bothfrom", "")
                .replace("(", "")
                .replace(")", "")
                .toLowerCase(Locale.ROOT);
        return canonical;
    }

    private static List<String> splitTopLevel(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (current == '\'' && (i + 1 >= expression.length() || expression.charAt(i + 1) != '\'')) {
                quoted = !quoted;
            } else if (current == '\'' && quoted) {
                i++;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                if (--depth < 0) return List.of();
            } else if (!quoted && depth == 0 && expression.regionMatches(true, i, operator, 0, operator.length())) {
                parts.add(expression.substring(start, i));
                i += operator.length() - 1;
                start = i + 1;
            }
        }
        if (quoted || depth != 0) return List.of();
        parts.add(expression.substring(start));
        return parts.stream().allMatch(part -> !part.isEmpty()) ? parts : List.of();
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String expected,
                                           String targetTable, List<ColumnPair> columns) throws SQLException {
        requireForeignKey(metadata, table, expected, targetTable, columns, null);
    }

    private static void requireForeignKey(DatabaseMetaData metadata, String table, String expected,
                                           String targetTable, List<ColumnPair> columns,
                                           Short expectedDeleteRule) throws SQLException {
        for (String candidate : tableCandidates(table)) {
            Map<Short, ColumnPair> actual = new HashMap<>();
            String keyName = null;
            String actualTarget = null;
            Short actualDeleteRule = null;
            try (ResultSet rows = metadata.getImportedKeys(null, null, candidate)) {
                while (rows.next()) {
                    if (!normalize(expected).equals(normalize(rows.getString("FK_NAME")))) continue;
                    keyName = rows.getString("FK_NAME");
                    actualTarget = rows.getString("PKTABLE_NAME");
                    actualDeleteRule = rows.getShort("DELETE_RULE");
                    actual.put(rows.getShort("KEY_SEQ"), pair(rows.getString("FKCOLUMN_NAME"),
                            rows.getString("PKCOLUMN_NAME")));
                }
            }
            if (normalize(expected).equals(normalize(keyName))
                    && normalize(targetTable).equals(normalize(actualTarget))
                    && (expectedDeleteRule == null || expectedDeleteRule.equals(actualDeleteRule))
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
