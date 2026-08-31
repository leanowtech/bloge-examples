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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fail-closed V011 gate for persisted Resource-to-Connection snapshots. */
public final class ApiResourceConnectionSnapshotSchemaReadiness {
    private static final String MIGRATION = "V20260831_011";
    private static final List<String> INDEX_COLUMNS = List.of(
            "tenant_id", "project_id", "environment_id", "connection_id",
            "connection_revision", "connection_metadata_fingerprint", "state");

    /** Performs read-only column and index checks; migrations remain external. */
    public ApiResourceConnectionSnapshotSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        try {
            jdbc.query("""
                    SELECT connection_id, connection_revision, connection_metadata_fingerprint
                      FROM rg_api_resource_revisions
                     WHERE 1 = 0
                    """, ignored -> { });
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                requireNotNullColumns(connection.getMetaData());
                requireIndex(connection.getMetaData());
                return null;
            });
        } catch (DataAccessException | IllegalStateException ex) {
            throw new IllegalStateException("API Resource Connection snapshot schema is not ready; apply "
                    + MIGRATION, ex);
        }
    }

    private static void requireNotNullColumns(DatabaseMetaData metadata) throws SQLException {
        for (String table : List.of("rg_api_resource_revisions", "RG_API_RESOURCE_REVISIONS")) {
            Map<String, Integer> nullability = new HashMap<>();
            try (ResultSet rows = metadata.getColumns(null, null, table, null)) {
                while (rows.next()) {
                    nullability.put(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                            rows.getInt("NULLABLE"));
                }
            }
            if (nullability.get("connection_revision") != null
                    && nullability.get("connection_revision") == DatabaseMetaData.columnNoNulls
                    && nullability.get("connection_metadata_fingerprint") != null
                    && nullability.get("connection_metadata_fingerprint") == DatabaseMetaData.columnNoNulls) {
                return;
            }
        }
        throw new IllegalStateException("nullable Connection snapshot columns");
    }

    private static void requireIndex(DatabaseMetaData metadata) throws SQLException {
        for (String table : List.of("rg_api_resource_revisions", "RG_API_RESOURCE_REVISIONS")) {
            Map<Short, String> columns = new HashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(null, null, table, false, false)) {
                while (rows.next()) {
                    if ("rg_api_resource_revisions_connection_snapshot_idx".equalsIgnoreCase(
                            rows.getString("INDEX_NAME"))) {
                        columns.put(rows.getShort("ORDINAL_POSITION"),
                                rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            List<Map.Entry<Short, String>> ordered = new ArrayList<>(columns.entrySet());
            ordered.sort(Comparator.comparingInt(Map.Entry::getKey));
            if (ordered.stream().map(Map.Entry::getValue).toList().equals(INDEX_COLUMNS)) return;
        }
        throw new IllegalStateException("missing or misordered Connection snapshot index");
    }
}
