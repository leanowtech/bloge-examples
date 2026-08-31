package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CheckConstraintDefinition;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only startup gate for the V012 private Fixture Set authority. */
public final class ApiFixtureSetSchemaReadiness {
    private static final String MIGRATION = "V20260831_012";

    /** Probes required tables and exact columns without creating schema. */
    public ApiFixtureSetSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        Namespace namespace = namespace(jdbc);
        requireColumns(jdbc, namespace, "RG_API_FIXTURE_SET_IDENTITIES",
                List.of("TENANT_ID", "PROJECT_ID", "ENVIRONMENT_ID", "FIXTURE_SET_ID"));
        requireColumns(jdbc, namespace, "RG_API_FIXTURE_SET_REVISIONS", List.of(
                "TENANT_ID", "PROJECT_ID", "ENVIRONMENT_ID", "FIXTURE_SET_ID", "REVISION", "STATE",
                "FINGERPRINT", "STATUS", "STATUS_REVISION", "SUBJECT_KIND", "SUBJECT_ID",
                "SUBJECT_REVISION", "SUBJECT_FINGERPRINT", "GENERATED_JSON", "COMMAND_ID",
                "ATTEMPT_NO", "ATTEMPT_TOKEN"));
        requireColumns(jdbc, namespace, "RG_API_FIXTURE_SET_HEADS", List.of(
                "TENANT_ID", "PROJECT_ID", "ENVIRONMENT_ID", "FIXTURE_SET_ID", "REVISION",
                "COMMAND_ID", "ATTEMPT_NO", "ATTEMPT_TOKEN", "REVISION_STATE"));
        verifyMetadata(jdbc, namespace);
        requireLiteralCheck(jdbc, namespace, "RG_API_FIXTURE_SET_REVISIONS",
                "RG_API_FIXTURE_SET_REVISIONS_STATE_CK", "state", Set.of("STAGED", "COMMITTED"));
        requireLiteralCheck(jdbc, namespace, "RG_API_FIXTURE_SET_REVISIONS",
                "RG_API_FIXTURE_SET_REVISIONS_STATUS_CK", "status", Set.of("PRIVATE_DRAFT"));
        requireConjunctionCheck(jdbc, namespace, "RG_API_FIXTURE_SET_REVISIONS",
                "RG_API_FIXTURE_SET_REVISIONS_REVISION_CK",
                Set.of("revision>0", "status_revision>0", "subject_revision>0"));
        requireConjunctionCheck(jdbc, namespace, "RG_API_FIXTURE_SET_REVISIONS",
                "RG_API_FIXTURE_SET_REVISIONS_FINGERPRINT_CK", Set.of(
                        "char_lengthfingerprint=71", "fingerprintlike'sha256:%'",
                        "char_lengthsubject_fingerprint=71", "subject_fingerprintlike'sha256:%'"));
        requireLiteralCheck(jdbc, namespace, "RG_API_FIXTURE_SET_HEADS",
                "RG_API_FIXTURE_SET_HEADS_STATE_CK", "revision_state", Set.of("COMMITTED"));
    }

    private static Namespace namespace(JdbcTemplate jdbc) {
        try {
            Namespace value = jdbc.execute((ConnectionCallback<Namespace>) connection ->
                    new Namespace(connection.getCatalog(), connection.getSchema()));
            if (value == null || value.schema() == null || value.schema().isBlank()) throw notReady();
            return value;
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static void requireColumns(JdbcTemplate jdbc, Namespace namespace,
                                       String table, List<String> required) {
        try {
            List<String> actual = jdbc.queryForList("""
                    SELECT UPPER(column_name)
                      FROM information_schema.columns
                     WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = ?
                    """, String.class, namespace.schema(), table);
            if (!actual.containsAll(required)) throw notReady();
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static void verifyMetadata(JdbcTemplate jdbc, Namespace namespace) {
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                DatabaseMetaData metadata = connection.getMetaData();
                requirePrimaryKey(metadata, namespace, "rg_api_fixture_set_identities",
                        List.of("tenant_id", "project_id", "environment_id", "fixture_set_id"));
                requirePrimaryKey(metadata, namespace, "rg_api_fixture_set_revisions", List.of(
                        "tenant_id", "project_id", "environment_id", "fixture_set_id", "revision",
                        "command_id", "attempt_no", "attempt_token"));
                requirePrimaryKey(metadata, namespace, "rg_api_fixture_set_heads",
                        List.of("tenant_id", "project_id", "environment_id", "fixture_set_id"));
                requireForeignKey(metadata, namespace, "rg_api_fixture_set_revisions",
                        "rg_authoring_command_attempts",
                        List.of(pair("command_id", "command_id"), pair("attempt_no", "attempt_no"),
                                pair("attempt_token", "attempt_token")));
                requireForeignKey(metadata, namespace, "rg_api_fixture_set_revisions",
                        "rg_api_fixture_set_identities", scopeFixturePairs());
                requireForeignKey(metadata, namespace, "rg_api_fixture_set_heads",
                        "rg_api_fixture_set_revisions",
                        List.of(pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                                pair("environment_id", "environment_id"), pair("fixture_set_id", "fixture_set_id"),
                                pair("revision", "revision"), pair("command_id", "command_id"),
                                pair("attempt_no", "attempt_no"), pair("attempt_token", "attempt_token"),
                                pair("revision_state", "state")));
                requireIndex(metadata, namespace, "rg_api_fixture_set_revisions", true, List.of(
                        "tenant_id", "project_id", "environment_id", "fixture_set_id", "revision",
                        "command_id", "attempt_no", "attempt_token", "state"));
                requireIndex(metadata, namespace, "rg_api_fixture_set_revisions", false, List.of(
                        "tenant_id", "project_id", "environment_id", "subject_kind", "subject_id",
                        "subject_revision", "subject_fingerprint", "state", "fixture_set_id"));
                requireIndex(metadata, namespace, "rg_api_fixture_set_revisions", false,
                        List.of("command_id", "attempt_no", "attempt_token", "state"));
                return null;
            });
        } catch (RuntimeException ex) {
            throw notReady();
        }
    }

    private static void requireLiteralCheck(JdbcTemplate jdbc, Namespace namespace, String table,
                                            String constraint, String column, Set<String> literals) {
        List<String> clauses = checkClauses(jdbc, namespace, table, constraint);
        if (clauses.size() != 1
                || !CheckConstraintDefinition.exactLiteralSet(clauses.getFirst(), column, literals)) {
            throw notReady();
        }
    }

    private static void requireConjunctionCheck(JdbcTemplate jdbc, Namespace namespace, String table,
                                                String constraint, Set<String> expected) {
        List<String> clauses = checkClauses(jdbc, namespace, table, constraint);
        if (clauses.size() != 1 || !canonicalConjunction(clauses.getFirst()).equals(expected)) {
            throw notReady();
        }
    }

    private static List<String> checkClauses(JdbcTemplate jdbc, Namespace namespace,
                                             String table, String constraint) {
        try {
            return jdbc.query("""
                    SELECT cc.check_clause
                      FROM information_schema.check_constraints cc
                      JOIN information_schema.table_constraints tc
                        ON tc.constraint_catalog=cc.constraint_catalog
                       AND tc.constraint_schema=cc.constraint_schema
                       AND tc.constraint_name=cc.constraint_name
                     WHERE UPPER(tc.table_schema)=UPPER(?) AND UPPER(tc.table_name)=UPPER(?)
                       AND UPPER(tc.constraint_name)=UPPER(?) AND tc.constraint_type='CHECK'
                    """, (rs, row) -> rs.getString(1), namespace.schema(), table, constraint);
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static Set<String> canonicalConjunction(String clause) {
        if (clause == null || clause.toLowerCase(Locale.ROOT).contains(" or ")) return Set.of();
        String canonical = clause.replaceAll("\\s+", "").replace("\"", "")
                .replaceAll("(?i)::(?:text|varchar|charactervarying)", "")
                .replaceAll("(?i)character_length", "char_length")
                .replaceAll("(?i)~~", "like")
                .replace("(", "").replace(")", "").toLowerCase(Locale.ROOT);
        List<String> atoms = List.of(canonical.split("(?i)and", -1));
        Set<String> result = new HashSet<>(atoms);
        return result.size() == atoms.size() && result.stream().noneMatch(String::isBlank) ? result : Set.of();
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, Namespace namespace,
                                          String table, List<String> expected) throws SQLException {
        for (String candidate : candidates(table)) {
            Map<Short, String> actual = new HashMap<>();
            try (ResultSet rows = metadata.getPrimaryKeys(namespace.catalog(), namespace.schema(), candidate)) {
                while (rows.next()) actual.put(rows.getShort("KEY_SEQ"), normalize(rows.getString("COLUMN_NAME")));
            }
            if (ordered(actual).equals(normalized(expected))) return;
        }
        throw notReady();
    }

    private static void requireForeignKey(DatabaseMetaData metadata, Namespace namespace,
                                          String table, String target, List<ColumnPair> expected)
            throws SQLException {
        for (String candidate : candidates(table)) {
            Map<String, Map<Short, ColumnPair>> keys = new HashMap<>();
            Map<String, ForeignTarget> targets = new HashMap<>();
            try (ResultSet rows = metadata.getImportedKeys(namespace.catalog(), namespace.schema(), candidate)) {
                while (rows.next()) {
                    String name = normalize(rows.getString("FK_NAME"));
                    targets.put(name, new ForeignTarget(normalize(rows.getString("PKTABLE_CAT")),
                            normalize(rows.getString("PKTABLE_SCHEM")),
                            normalize(rows.getString("PKTABLE_NAME"))));
                    keys.computeIfAbsent(name, ignored -> new HashMap<>()).put(rows.getShort("KEY_SEQ"),
                            pair(rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME")));
                }
            }
            for (Map.Entry<String, Map<Short, ColumnPair>> entry : keys.entrySet()) {
                ForeignTarget actualTarget = targets.get(entry.getKey());
                if (actualTarget != null && normalize(namespace.catalog()).equals(actualTarget.catalog())
                        && normalize(namespace.schema()).equals(actualTarget.schema())
                        && normalize(target).equals(actualTarget.table())
                        && orderedPairs(entry.getValue()).equals(normalizedPairs(expected))) return;
            }
        }
        throw notReady();
    }

    private static void requireIndex(DatabaseMetaData metadata, Namespace namespace,
                                     String table, boolean unique, List<String> expected) throws SQLException {
        for (String candidate : candidates(table)) {
            Map<String, Map<Short, String>> indexes = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(
                    namespace.catalog(), namespace.schema(), candidate, unique, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    String column = rows.getString("COLUMN_NAME");
                    if (name != null && column != null) {
                        indexes.computeIfAbsent(normalize(name), ignored -> new HashMap<>())
                                .put(rows.getShort("ORDINAL_POSITION"), normalize(column));
                    }
                }
            }
            if (indexes.values().stream().anyMatch(value -> ordered(value).equals(normalized(expected)))) return;
        }
        throw notReady();
    }

    private static Set<String> candidates(String table) {
        return new HashSet<>(List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)));
    }

    private static List<String> ordered(Map<Short, String> values) {
        return values.entrySet().stream().sorted(Comparator.comparingInt(entry -> entry.getKey()))
                .map(Map.Entry::getValue).toList();
    }

    private static List<ColumnPair> orderedPairs(Map<Short, ColumnPair> values) {
        return values.entrySet().stream().sorted(Comparator.comparingInt(entry -> entry.getKey()))
                .map(Map.Entry::getValue).toList();
    }

    private static List<String> normalized(List<String> values) {
        return values.stream().map(ApiFixtureSetSchemaReadiness::normalize).toList();
    }

    private static List<ColumnPair> normalizedPairs(List<ColumnPair> values) {
        return values.stream().map(value -> pair(value.foreign(), value.primary())).toList();
    }

    private static List<ColumnPair> scopeFixturePairs() {
        return List.of(pair("tenant_id", "tenant_id"), pair("project_id", "project_id"),
                pair("environment_id", "environment_id"), pair("fixture_set_id", "fixture_set_id"));
    }

    private static ColumnPair pair(String foreign, String primary) {
        return new ColumnPair(normalize(foreign), normalize(primary));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notReady() {
        return new IllegalStateException("API Fixture Set schema is not ready; apply " + MIGRATION);
    }

    private record ColumnPair(String foreign, String primary) { }

    private record ForeignTarget(String catalog, String schema, String table) { }

    private record Namespace(String catalog, String schema) { }
}
