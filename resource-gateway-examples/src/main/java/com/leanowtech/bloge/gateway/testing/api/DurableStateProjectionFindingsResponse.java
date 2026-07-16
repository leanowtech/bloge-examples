package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;

import java.time.Instant;
import java.util.List;

/**
 * Bounded payload-free projection finding page.
 *
 * @param schemaVersion response protocol version
 * @param actionableOnly whether the query excluded live claims and resolved findings
 * @param findings ordered finding summaries without claim tokens or authority payloads
 */
public record DurableStateProjectionFindingsResponse(
        String schemaVersion,
        boolean actionableOnly,
        List<Finding> findings) {
    /** Current finding page response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableStateProjectionFindingsResponse.v1";

    /** Copies the externally visible page. */
    public DurableStateProjectionFindingsResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * Creates the public payload-free page from internal finding records.
     *
     * @param actionableOnly applied queue filter
     * @param records internal records with tokens already excluded from their public projection
     * @return immutable response page
     */
    public static DurableStateProjectionFindingsResponse from(
            boolean actionableOnly,
            List<DatabaseDurableStateProjectionControlPlane.FindingRecord> records) {
        List<Finding> values = records == null ? List.of()
                : records.stream().map(Finding::from).toList();
        return new DurableStateProjectionFindingsResponse("", actionableOnly, values);
    }

    /**
     * One payload-free queue projection.
     *
     * @param key authority row identity
     * @param kind stable discrepancy kind
     * @param columns mismatched column names, never values
     * @param repairable whether safe automatic repair is structurally possible
     * @param outcome latest scanner outcome
     * @param status owner-queue status
     * @param occurrences discrepant scan count
     * @param firstSeenAt first database observation
     * @param lastSeenAt latest discrepant observation
     * @param resolution current resolution classification
     * @param resolvedAt resolution time or {@code null}
     * @param claimOwner current verified owner or blank
     * @param claimUntil database-clock claim deadline
     * @param version current finding revision
     */
    public record Finding(
            DurableStateProjectionFindingKey key,
            String kind,
            List<String> columns,
            boolean repairable,
            String outcome,
            String status,
            long occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            String resolution,
            Instant resolvedAt,
            String claimOwner,
            Instant claimUntil,
            long version) {
        /** Copies collection values in the public queue row. */
        public Finding {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        private static Finding from(
                DatabaseDurableStateProjectionControlPlane.FindingRecord record) {
            return new Finding(new DurableStateProjectionFindingKey(
                    record.key().entityType().name(), record.key().rowId()), record.kind().name(),
                    record.columns(), record.repairable(), record.outcome().name(),
                    record.status().name(), record.occurrences(), record.firstSeenAt(),
                    record.lastSeenAt(), record.resolution().name(), record.resolvedAt(),
                    record.claimOwner(), record.claimUntil(), record.version());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
