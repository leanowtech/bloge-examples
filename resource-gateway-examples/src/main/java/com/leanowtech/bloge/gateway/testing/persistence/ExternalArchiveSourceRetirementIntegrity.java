package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Objects;

/** Shared schema and canonical fingerprint for permanent source-retirement markers. */
final class ExternalArchiveSourceRetirementIntegrity {
    private static final String MARKER_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveSourceRetirementMarker.v1";

    private ExternalArchiveSourceRetirementIntegrity() {
    }

    /** Creates the shared marker table before either source retention or evidence export runs. */
    static void initializeMarkerTable(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc").execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_source_retirements (
                    cycle_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    retirement_mode VARCHAR(32) NOT NULL,
                    retirement_status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_source_retirement_comparison
                ON rg_test_suite_stability_observation_external_source_retirements (
                    comparison_id, retirement_status
                )
                """);
    }

    /** Returns the canonical fingerprint over every marker column except itself. */
    static String markerFingerprint(
            ObjectMapper objectMapper,
            String cycleId,
            String comparisonId,
            String authorityId,
            String mode,
            String status,
            Instant startedAt,
            Instant completedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new MarkerMaterial(MARKER_SCHEMA, cycleId, comparisonId, authorityId, mode,
                        status, startedAt, completedAt));
    }

    private record MarkerMaterial(
            String schemaVersion,
            String cycleId,
            String comparisonId,
            String authorityId,
            String mode,
            String status,
            Instant startedAt,
            Instant completedAt) {
    }
}
