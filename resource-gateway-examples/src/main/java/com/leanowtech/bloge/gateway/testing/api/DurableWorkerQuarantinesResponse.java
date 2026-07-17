package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.List;

/**
 * Bounded payload-free exact-checkpoint quarantine page.
 *
 * @param schemaVersion response protocol version
 * @param actionableOnly whether live maintenance claims were excluded
 * @param quarantines ordered quarantine summaries without claim tokens or payloads
 */
public record DurableWorkerQuarantinesResponse(
        String schemaVersion,
        boolean actionableOnly,
        List<Quarantine> quarantines) {
    /** Current quarantine page response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableWorkerQuarantinesResponse.v1";

    /** Copies the externally visible page. */
    public DurableWorkerQuarantinesResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        quarantines = quarantines == null ? List.of() : List.copyOf(quarantines);
    }

    /** Creates a public payload-free page from verified control-plane records. */
    public static DurableWorkerQuarantinesResponse from(
            boolean actionableOnly,
            List<DatabaseDurableWorkerQuarantineControlPlane.QuarantineRecord> records) {
        return new DurableWorkerQuarantinesResponse("", actionableOnly,
                records == null ? List.of() : records.stream().map(Quarantine::from).toList());
    }

    /**
     * One payload-free quarantine projection.
     *
     * @param key exact run and checkpoint identity
     * @param reason closed deterministic failure reason
     * @param consecutiveFailures threshold-crossing count
     * @param quarantineThreshold applied policy threshold
     * @param firstObservedAt first same-reason observation
     * @param quarantinedAt isolation time
     * @param state maintenance ownership state
     * @param claimOwner live verified owner or blank
     * @param claimUntil live database-clock deadline or epoch
     * @param version maintenance fence generation
     */
    public record Quarantine(
            DurableWorkerQuarantineKey key,
            String reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String state,
            String claimOwner,
            Instant claimUntil,
            long version) {
        private static Quarantine from(
                DatabaseDurableWorkerQuarantineControlPlane.QuarantineRecord record) {
            return new Quarantine(new DurableWorkerQuarantineKey(
                    record.runId(), record.checkpointFingerprint()), record.reason().name(),
                    record.consecutiveFailures(), record.quarantineThreshold(),
                    record.firstObservedAt(), record.quarantinedAt(), record.state().name(),
                    record.claimOwner(), record.claimUntil(), record.version());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
