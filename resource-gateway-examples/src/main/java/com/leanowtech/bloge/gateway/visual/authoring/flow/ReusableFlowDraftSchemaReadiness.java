package com.leanowtech.bloge.gateway.visual.authoring.flow;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only fail-startup gate for the exact V014 reusable Flow draft authority. */
public final class ReusableFlowDraftSchemaReadiness {
    private static final String MIGRATION = "V20260901_014";

    /** Verifies every table and the keys/checks used by {@link JdbcReusableFlowDraftStore}. */
    public ReusableFlowDraftSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            jdbc.query("""
                    SELECT h.revision, r.draft_json, c.receipt_json
                      FROM rg_authoring_flow_heads h
                      JOIN rg_authoring_flow_revisions r
                        ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id
                       AND r.environment_id=h.environment_id AND r.flow_id=h.flow_id
                       AND r.revision=h.revision AND r.draft_id=h.draft_id
                       AND r.content_fingerprint=h.content_fingerprint
                       AND r.strong_etag=h.strong_etag
                      LEFT JOIN rg_authoring_flow_commands c
                        ON c.tenant_id=r.tenant_id AND c.project_id=r.project_id
                       AND c.environment_id=r.environment_id AND c.flow_id=r.flow_id
                       AND c.committed_revision=r.revision AND c.strong_etag=r.strong_etag
                     WHERE 1=0
                    """, ignored -> { });
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                DatabaseMetaData metadata = connection.getMetaData();
                String catalog = connection.getCatalog();
                String schema = connection.getSchema();
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_identities",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id"));
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_revisions",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id", "revision"));
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_heads",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id"));
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_commands", List.of(
                        "tenant_id", "project_id", "environment_id", "actor_id", "flow_id",
                        "idempotency_key"));
                requireImportedKey(metadata, catalog, schema, "rg_authoring_flow_heads",
                        "rg_authoring_flow_heads_revision_fk", "rg_authoring_flow_revisions",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id", "revision",
                                "draft_id", "content_fingerprint", "strong_etag"));
                return null;
            });
            List<String> checks = jdbc.query("""
                    SELECT cc.check_clause
                      FROM information_schema.check_constraints cc
                      JOIN information_schema.table_constraints tc
                        ON tc.constraint_catalog=cc.constraint_catalog
                       AND tc.constraint_schema=cc.constraint_schema
                       AND tc.constraint_name=cc.constraint_name
                     WHERE UPPER(tc.table_name)='RG_AUTHORING_FLOW_COMMANDS'
                       AND UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA)
                       AND UPPER(tc.constraint_name)='RG_AUTHORING_FLOW_COMMANDS_EXPECTED_CK'
                       AND tc.constraint_type='CHECK'
                    """, (rs, row) -> rs.getString(1));
            String expected = "expected_mode='create'andexpected_revisionisnullor"
                    + "expected_mode='match'andexpected_revisionisnotnullandexpected_revision>0";
            if (checks.size() != 1 || !canonical(checks.getFirst()).equals(expected)) throw notReady();
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, String catalog, String schema, String table,
                                          List<String> expected) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            Map<Short, String> columns = new HashMap<>();
            try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, candidate)) {
                while (rows.next()) columns.put(rows.getShort("KEY_SEQ"), normalize(rows.getString("COLUMN_NAME")));
            }
            if (ordered(columns).equals(expected)) return;
        }
        throw notReady();
    }

    private static void requireImportedKey(DatabaseMetaData metadata, String catalog, String schema,
                                           String table, String constraint, String targetTable,
                                           List<String> expectedColumns) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            Map<Short, String> sourceColumns = new HashMap<>();
            Map<Short, String> targetColumns = new HashMap<>();
            String actualTarget = null;
            try (ResultSet rows = metadata.getImportedKeys(catalog, schema, candidate)) {
                while (rows.next()) {
                    if (constraint.equalsIgnoreCase(rows.getString("FK_NAME"))) {
                        short sequence = rows.getShort("KEY_SEQ");
                        sourceColumns.put(sequence, normalize(rows.getString("FKCOLUMN_NAME")));
                        targetColumns.put(sequence, normalize(rows.getString("PKCOLUMN_NAME")));
                        actualTarget = normalize(rows.getString("PKTABLE_NAME"));
                    }
                }
            }
            if (normalize(targetTable).equals(actualTarget)
                    && ordered(sourceColumns).equals(expectedColumns)
                    && ordered(targetColumns).equals(expectedColumns)) return;
        }
        throw notReady();
    }

    private static List<String> ordered(Map<Short, String> columns) {
        List<Map.Entry<Short, String>> values = new ArrayList<>(columns.entrySet());
        values.sort(Comparator.comparingInt(Map.Entry::getKey));
        return values.stream().map(Map.Entry::getValue).toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String canonical(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", "").replace("\"", "")
                .replaceAll("(?i)::(?:text|varchar|charactervarying)", "")
                .replaceAll("(?i)cast\\(0asbigint\\)", "0")
                .replace("(", "").replace(")", "").toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notReady() {
        return new IllegalStateException("Reusable Flow draft schema is not ready; apply " + MIGRATION);
    }
}
