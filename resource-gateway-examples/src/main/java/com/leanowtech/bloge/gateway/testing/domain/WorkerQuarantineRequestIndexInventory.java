package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Database-clock inventory of every live durable worker-quarantine request tombstone generation.
 *
 * <p>The projection deliberately contains no request identifier, request fingerprint, tenant, or
 * scope. It is sufficient for a deployment gate to prove whether a request-index format cutover is
 * safe without turning the rollout endpoint into a retained business-identity export.</p>
 *
 * @param observedAt database time used as the live-row boundary
 * @param liveLegacyRows number of unexpired v1 SHA-256 tombstones
 * @param liveKeyedRows number of unexpired v2 HMAC tombstones
 * @param latestLegacyExpiry latest expiry among live v1 rows, or epoch when none exist
 * @param latestKeyedExpiry latest expiry among live v2 rows, or epoch when none exist
 * @param keyedGenerations bounded key-generation counts ordered by key id
 */
public record WorkerQuarantineRequestIndexInventory(
        Instant observedAt,
        long liveLegacyRows,
        long liveKeyedRows,
        Instant latestLegacyExpiry,
        Instant latestKeyedExpiry,
        List<KeyGeneration> keyedGenerations) {

    /** Validates the projection as an internally consistent, payload-free authority snapshot. */
    public WorkerQuarantineRequestIndexInventory {
        if (observedAt == null) {
            throw new IllegalArgumentException("Request-index inventory observation time is required");
        }
        if (liveLegacyRows < 0 || liveKeyedRows < 0) {
            throw new IllegalArgumentException("Request-index inventory counts cannot be negative");
        }
        latestLegacyExpiry = latestLegacyExpiry == null ? Instant.EPOCH : latestLegacyExpiry;
        latestKeyedExpiry = latestKeyedExpiry == null ? Instant.EPOCH : latestKeyedExpiry;
        keyedGenerations = keyedGenerations == null ? List.of() : List.copyOf(keyedGenerations);
        requireExpiry(liveLegacyRows, latestLegacyExpiry, observedAt, "legacy");
        requireExpiry(liveKeyedRows, latestKeyedExpiry, observedAt, "keyed");
        if (keyedGenerations.size() > 16) {
            throw new IllegalArgumentException(
                    "Request-index key generation inventory exceeds the protocol bound");
        }
        long generationTotal = 0;
        Instant generationLatestExpiry = Instant.EPOCH;
        String previousKeyId = "";
        Set<String> keyIds = new HashSet<>();
        for (KeyGeneration generation : keyedGenerations) {
            if (generation == null
                    || !keyIds.add(generation.keyId())
                    || (!previousKeyId.isEmpty()
                    && previousKeyId.compareTo(generation.keyId()) >= 0)) {
                throw new IllegalArgumentException(
                        "Request-index key generations must be unique and ordered");
            }
            if (!generation.latestExpiry().isAfter(observedAt)) {
                throw new IllegalArgumentException(
                        "Request-index key generation expiry must be live at observation time");
            }
            generationTotal = Math.addExact(generationTotal, generation.liveRows());
            if (generation.latestExpiry().isAfter(generationLatestExpiry)) {
                generationLatestExpiry = generation.latestExpiry();
            }
            previousKeyId = generation.keyId();
        }
        if (generationTotal != liveKeyedRows
                || (liveKeyedRows == 0) != keyedGenerations.isEmpty()
                || !generationLatestExpiry.equals(latestKeyedExpiry)) {
            throw new IllegalArgumentException(
                    "Request-index keyed generation counts are inconsistent");
        }
    }

    /** @return total number of live request tombstones across both supported formats */
    public long totalLiveRows() {
        return Math.addExact(liveLegacyRows, liveKeyedRows);
    }

    private static void requireExpiry(
            long rows, Instant expiry, Instant observedAt, String generation) {
        boolean valid = rows == 0 ? Instant.EPOCH.equals(expiry) : expiry.isAfter(observedAt);
        if (!valid) {
            throw new IllegalArgumentException(
                    "Request-index " + generation + " expiry is inconsistent with its count");
        }
    }

    /**
     * Payload-free live-row aggregate for one configured HMAC key generation.
     *
     * @param keyId non-secret request-index key identifier
     * @param liveRows positive number of live rows using the generation
     * @param latestExpiry latest live-row expiry for this generation
     */
    public record KeyGeneration(String keyId, long liveRows, Instant latestExpiry) {

        /** Rejects malformed or empty key-generation aggregates. */
        public KeyGeneration {
            keyId = keyId == null ? "" : keyId.trim();
            if (!keyId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}")) {
                throw new IllegalArgumentException("Request-index key generation id is invalid");
            }
            if (liveRows < 1 || latestExpiry == null) {
                throw new IllegalArgumentException(
                        "Request-index key generation must contain live rows and an expiry");
            }
        }
    }
}
