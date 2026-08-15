package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;

/** Integrity-addressed persisted Coverage Inventory revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCoverageInventory(
        String schemaVersion,
        String inventoryFingerprint,
        CoverageInventory inventory
) {
    public static final String SCHEMA_VERSION = "bloge.storedCoverageInventory.v1";

    public StoredCoverageInventory {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Inventory schemaVersion");
        }
        if (inventoryFingerprint == null
                || !inventoryFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Inventory fingerprint is required");
        }
        if (inventory == null || inventory.revision() < 1) {
            throw new IllegalArgumentException("Persisted Inventory revision is required");
        }
    }

    public static StoredCoverageInventory verified(
            ObjectMapper mapper,
            CoverageInventory inventory
    ) {
        return new StoredCoverageInventory(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.fingerprint(mapper, inventory),
                inventory);
    }
}
