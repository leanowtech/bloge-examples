package com.leanowtech.bloge.gateway.testing.domain;

import java.util.Locale;

/**
 * Closed operating modes for the durable worker-quarantine request-index upgrade.
 *
 * <p>The modes form a one-way rollout protocol. A fleet first writes the legacy SHA-256 index
 * while old binaries may still be present, then enables keyed writes after every old binary has
 * been removed, and finally rejects all live legacy rows after migration or expiry. The deployment
 * controller, rather than an individual process, owns the fleet-wide transition.</p>
 */
public enum WorkerQuarantineRequestIndexMode {

    /** Writes v1 SHA-256 indexes that the previous Resource Gateway binary can read. */
    LEGACY_READ_WRITE,

    /** Reads v1 and v2 indexes, writes v2 keyed HMAC indexes, and lazily upgrades v1 rows. */
    DUAL_READ_KEYED_WRITE,

    /** Reads and writes only v2 keyed HMAC indexes and fails closed on any live v1 row. */
    KEYED_ONLY;

    /**
     * Parses the deployment value without accepting aliases or unknown future modes.
     *
     * @param value configured mode name
     * @return exact closed mode
     * @throws IllegalArgumentException when the value is blank or outside the closed vocabulary
     */
    public static WorkerQuarantineRequestIndexMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker quarantine request-index mode is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Worker quarantine request-index mode must be LEGACY_READ_WRITE, "
                            + "DUAL_READ_KEYED_WRITE, or KEYED_ONLY", invalid);
        }
    }

    /** @return whether new tombstones use the previous release's v1 SHA-256 index */
    public boolean writesLegacy() {
        return this == LEGACY_READ_WRITE;
    }

    /** @return whether exact v1 access is allowed to migrate the row to the active keyed index */
    public boolean migratesLegacyOnAccess() {
        return this == DUAL_READ_KEYED_WRITE;
    }

    /** @return whether live v1 tombstones are valid when this process becomes ready */
    public boolean permitsLiveLegacyRows() {
        return this != KEYED_ONLY;
    }

    /** @return whether live v2 tombstones are valid when this process becomes ready */
    public boolean permitsLiveKeyedRows() {
        return this != LEGACY_READ_WRITE;
    }
}
