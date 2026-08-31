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

/** Read-only fail-startup probe for the exact V015 Flow publication authority. */
public final class ReusableFlowPublicationSchemaReadiness {
    private static final String MIGRATION = "V20260901_015";

    /** Verifies tables and exact keys used by {@link JdbcReusableFlowPublicationStore}. */
    public ReusableFlowPublicationSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            jdbc.query("""
                    SELECT v.version_json, v.receipt_json, c.receipt_json
                      FROM rg_authoring_flow_versions v
                      JOIN rg_authoring_flow_publication_identities i
                        ON i.tenant_id=v.tenant_id AND i.project_id=v.project_id
                       AND i.environment_id=v.environment_id AND i.flow_id=v.flow_id
                       AND i.publication_id=v.publication_id
                      LEFT JOIN rg_authoring_flow_publish_commands c
                        ON c.tenant_id=v.tenant_id AND c.project_id=v.project_id
                       AND c.environment_id=v.environment_id
                       AND c.publication_id=v.publication_id
                       AND c.committed_revision=v.revision
                     WHERE 1=0
                    """, ignored -> { });
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                DatabaseMetaData metadata = connection.getMetaData();
                String catalog = connection.getCatalog();
                String schema = connection.getSchema();
                requirePrimaryKey(metadata, catalog, schema,
                        "rg_authoring_flow_publication_identities",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id"));
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_versions",
                        List.of("tenant_id", "project_id", "environment_id", "publication_id", "revision"));
                requirePrimaryKey(metadata, catalog, schema, "rg_authoring_flow_publish_commands",
                        List.of("tenant_id", "project_id", "environment_id", "actor_id", "flow_id",
                                "idempotency_key"));
                requireImportedKey(metadata, catalog, schema, "rg_authoring_flow_versions",
                        "rg_authoring_flow_versions_identity_fk",
                        "rg_authoring_flow_publication_identities",
                        List.of("tenant_id", "project_id", "environment_id", "flow_id", "publication_id"),
                        List.of("tenant_id", "project_id", "environment_id", "flow_id", "publication_id"));
                requireImportedKey(metadata, catalog, schema, "rg_authoring_flow_publish_commands",
                        "rg_authoring_flow_publish_commands_version_fk", "rg_authoring_flow_versions",
                        List.of("tenant_id", "project_id", "environment_id", "publication_id",
                                "committed_revision"),
                        List.of("tenant_id", "project_id", "environment_id", "publication_id", "revision"));
                return null;
            });
            List<String> checks = jdbc.query("""
                    SELECT cc.check_clause
                      FROM information_schema.check_constraints cc
                      JOIN information_schema.table_constraints tc
                        ON tc.constraint_catalog=cc.constraint_catalog
                       AND tc.constraint_schema=cc.constraint_schema
                       AND tc.constraint_name=cc.constraint_name
                     WHERE UPPER(tc.table_name)='RG_AUTHORING_FLOW_VERSIONS'
                       AND UPPER(tc.table_schema)=UPPER(CURRENT_SCHEMA)
                       AND UPPER(tc.constraint_name)='RG_AUTHORING_FLOW_VERSIONS_STATUS_CK'
                       AND tc.constraint_type='CHECK'
                    """, (rs, row) -> rs.getString(1));
            if (checks.size() != 1 || !canonical(checks.getFirst()).equals("status='published'")) {
                throw notReady();
            }
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw notReady();
        }
    }

    private static void requirePrimaryKey(DatabaseMetaData metadata, String catalog, String schema,
                                          String table, List<String> expected) throws SQLException {
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
                                           String table, String constraint, String target,
                                           List<String> expectedSource,
                                           List<String> expectedTarget) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            Map<Short, String> source = new HashMap<>();
            Map<Short, String> destination = new HashMap<>();
            String targetTable = null;
            try (ResultSet rows = metadata.getImportedKeys(catalog, schema, candidate)) {
                while (rows.next()) {
                    if (constraint.equalsIgnoreCase(rows.getString("FK_NAME"))) {
                        short key = rows.getShort("KEY_SEQ");
                        source.put(key, normalize(rows.getString("FKCOLUMN_NAME")));
                        destination.put(key, normalize(rows.getString("PKCOLUMN_NAME")));
                        targetTable = normalize(rows.getString("PKTABLE_NAME"));
                    }
                }
            }
            if (normalize(target).equals(targetTable)
                    && ordered(source).equals(expectedSource)
                    && ordered(destination).equals(expectedTarget)) return;
        }
        throw notReady();
    }

    private static List<String> ordered(Map<Short, String> columns) {
        List<Map.Entry<Short, String>> entries = new ArrayList<>(columns.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));
        return entries.stream().map(Map.Entry::getValue).toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String canonical(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").replace("\"", "")
                .replaceAll("(?i)::(?:text|varchar|charactervarying)", "")
                .replace("(", "").replace(")", "").toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notReady() {
        return new IllegalStateException("Reusable Flow publication schema is not ready; apply " + MIGRATION);
    }
}
