package com.leanowtech.bloge.gateway.visual.authoring.migration;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic, payload-free evidence that one legacy inventory snapshot was completely classified.
 *
 * <p>This receipt proves assessment coverage and replay identity only. It does not claim that any legacy
 * object was migrated or mutated. {@code failures} contains only the public repair/legacy coordinates
 * already present in the inventory.</p>
 */
public record LegacyMigrationAssessment(
        String schemaVersion,
        String inventoryFingerprint,
        Coverage coverage,
        List<LegacyAssetMigrationInventory.Item> failures
) {
    public static final String SCHEMA_VERSION = "bloge.legacyMigrationAssessment.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public LegacyMigrationAssessment {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || inventoryFingerprint == null
                || !FINGERPRINT.matcher(inventoryFingerprint).matches() || coverage == null) {
            throw new IllegalArgumentException("A migration assessment requires exact versioned coverage");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
        if (failures.size() != coverage.needsRepair() + coverage.legacyOnly()
                || failures.stream().anyMatch(item ->
                item.status() == LegacyAssetMigrationInventory.Status.READY_TO_REAUTHOR)) {
            throw new IllegalArgumentException("Migration failures must match the non-ready coverage counts");
        }
    }

    /** Counts classified source coordinates; {@code unclassified=0} is required for complete coverage. */
    public record Coverage(
            int total,
            int classified,
            int unclassified,
            int readyToReauthor,
            int needsRepair,
            int legacyOnly,
            int fixtureReferences
    ) {
        public Coverage {
            if (total < 0 || classified < 0 || unclassified < 0 || readyToReauthor < 0
                    || needsRepair < 0 || legacyOnly < 0 || fixtureReferences < 0
                    || total != classified + unclassified
                    || classified != readyToReauthor + needsRepair + legacyOnly) {
                throw new IllegalArgumentException("Migration coverage counts must close exactly");
            }
        }
    }
}
