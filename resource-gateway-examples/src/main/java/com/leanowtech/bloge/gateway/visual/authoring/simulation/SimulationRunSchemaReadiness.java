package com.leanowtech.bloge.gateway.visual.authoring.simulation;

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

/** Read-only startup gate for the exact V013 simulation-run authority. */
public final class SimulationRunSchemaReadiness {
    private static final String TABLE = "rg_authoring_simulation_runs";
    private static final String MIGRATION = "V20260831_013";

    /**
     * Verifies the columns, keys, recovery index and state-machine checks used by
     * {@link JdbcSimulationRunStore}; this probe never creates or repairs schema.
     */
    public SimulationRunSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            Namespace namespace = namespace(jdbc);
            requireColumns(jdbc, namespace);
            requireMetadata(jdbc, namespace);
            requireLiteralCheck(jdbc, namespace, "rg_authoring_simulation_runs_status_ck",
                    "status", Set.of("RUNNING", "SUCCEEDED", "FAILED", "BLOCKED"));
            requireConjunctionCheck(jdbc, namespace, "rg_authoring_simulation_runs_fingerprint_ck",
                    Set.of("char_lengthrequest_fingerprint=71",
                            "request_fingerprintlike'sha256:%'"));
            requireCompletionCheck(jdbc, namespace);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static Namespace namespace(JdbcTemplate jdbc) {
        Namespace value = jdbc.execute((ConnectionCallback<Namespace>) connection ->
                new Namespace(connection.getCatalog(), connection.getSchema()));
        if (value == null || value.schema() == null || value.schema().isBlank()) throw notReady();
        return value;
    }

    private static void requireColumns(JdbcTemplate jdbc, Namespace namespace) {
        List<String> columns = jdbc.queryForList("""
                SELECT UPPER(column_name)
                  FROM information_schema.columns
                 WHERE UPPER(table_schema)=UPPER(?) AND UPPER(table_name)=UPPER(?)
                """, String.class, namespace.schema(), TABLE);
        if (!columns.containsAll(List.of("TENANT_ID", "PROJECT_ID", "ENVIRONMENT_ID", "RUN_ID",
                "IDEMPOTENCY_KEY", "REQUEST_FINGERPRINT", "STATUS", "RUN_JSON", "LEASE_UNTIL",
                "STARTED_AT", "ENDED_AT"))) {
            throw notReady();
        }
    }

    private static void requireMetadata(JdbcTemplate jdbc, Namespace namespace) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            requirePrimaryKey(metadata, namespace, List.of(
                    "tenant_id", "project_id", "environment_id", "run_id"));
            requireIndex(metadata, namespace, true, List.of(
                    "tenant_id", "project_id", "environment_id", "idempotency_key"));
            requireIndex(metadata, namespace, false, List.of(
                    "status", "lease_until", "tenant_id", "project_id", "environment_id", "run_id"));
            return null;
        });
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, Namespace namespace,
                                          List<String> expected) throws SQLException {
        for (String candidate : tableCandidates()) {
            Map<Short, String> columns = new HashMap<>();
            try (ResultSet rows = metadata.getPrimaryKeys(namespace.catalog(), namespace.schema(), candidate)) {
                while (rows.next()) {
                    columns.put(rows.getShort("KEY_SEQ"), normalize(rows.getString("COLUMN_NAME")));
                }
            }
            if (ordered(columns).equals(normalized(expected))) return;
        }
        throw notReady();
    }

    private static void requireIndex(DatabaseMetaData metadata, Namespace namespace,
                                     boolean unique, List<String> expected) throws SQLException {
        for (String candidate : tableCandidates()) {
            Map<String, Map<Short, String>> indexes = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(
                    namespace.catalog(), namespace.schema(), candidate, unique, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    String column = rows.getString("COLUMN_NAME");
                    if (name != null && column != null && (!unique || !rows.getBoolean("NON_UNIQUE"))) {
                        indexes.computeIfAbsent(normalize(name), ignored -> new HashMap<>())
                                .put(rows.getShort("ORDINAL_POSITION"), normalize(column));
                    }
                }
            }
            if (indexes.values().stream().anyMatch(value -> ordered(value).equals(normalized(expected)))) return;
        }
        throw notReady();
    }

    private static void requireLiteralCheck(JdbcTemplate jdbc, Namespace namespace,
                                            String constraint, String column, Set<String> literals) {
        List<String> clauses = checkClauses(jdbc, namespace, constraint);
        if (clauses.size() != 1
                || !CheckConstraintDefinition.exactLiteralSet(clauses.getFirst(), column, literals)) {
            throw notReady();
        }
    }

    private static void requireConjunctionCheck(JdbcTemplate jdbc, Namespace namespace,
                                                String constraint, Set<String> expected) {
        List<String> clauses = checkClauses(jdbc, namespace, constraint);
        if (clauses.size() != 1 || !conjunction(clauses.getFirst()).equals(expected)) throw notReady();
    }

    private static void requireCompletionCheck(JdbcTemplate jdbc, Namespace namespace) {
        List<String> clauses = checkClauses(jdbc, namespace, "rg_authoring_simulation_runs_completion_ck");
        String expected = "status='running'andrun_jsonisnullandended_atisnull"
                + "orstatus<>'running'andrun_jsonisnotnullandended_atisnotnull";
        if (clauses.size() != 1 || !canonical(clauses.getFirst()).equals(expected)) throw notReady();
    }

    private static List<String> checkClauses(JdbcTemplate jdbc, Namespace namespace, String constraint) {
        return jdbc.query("""
                SELECT cc.check_clause
                  FROM information_schema.check_constraints cc
                  JOIN information_schema.table_constraints tc
                    ON tc.constraint_catalog=cc.constraint_catalog
                   AND tc.constraint_schema=cc.constraint_schema
                   AND tc.constraint_name=cc.constraint_name
                 WHERE UPPER(tc.table_schema)=UPPER(?) AND UPPER(tc.table_name)=UPPER(?)
                   AND UPPER(tc.constraint_name)=UPPER(?) AND tc.constraint_type='CHECK'
                """, (rs, row) -> rs.getString(1), namespace.schema(), TABLE, constraint);
    }

    private static Set<String> conjunction(String clause) {
        String value = canonical(clause);
        if (value.contains("or")) return Set.of();
        List<String> atoms = List.of(value.split("and", -1));
        Set<String> result = new HashSet<>(atoms);
        return result.size() == atoms.size() && result.stream().noneMatch(String::isBlank)
                ? result : Set.of();
    }

    private static String canonical(String clause) {
        if (clause == null) return "";
        return clause.replaceAll("\\s+", "").replace("\"", "")
                .replaceAll("(?i)::(?:text|varchar|charactervarying)", "")
                .replaceAll("(?i)character_length", "char_length")
                .replaceAll("(?i)~~", "like")
                .replace("!=", "<>")
                .replace("(", "").replace(")", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> tableCandidates() {
        return Set.of(TABLE, TABLE.toUpperCase(Locale.ROOT));
    }

    private static List<String> ordered(Map<Short, String> columns) {
        return columns.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue).toList();
    }

    private static List<String> normalized(List<String> columns) {
        return columns.stream().map(SimulationRunSchemaReadiness::normalize).toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notReady() {
        return new IllegalStateException("API authoring simulation schema is not ready; apply " + MIGRATION);
    }

    private record Namespace(String catalog, String schema) { }
}
