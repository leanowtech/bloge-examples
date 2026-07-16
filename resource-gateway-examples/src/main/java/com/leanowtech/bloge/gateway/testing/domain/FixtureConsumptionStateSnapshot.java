package com.leanowtech.bloge.gateway.testing.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Payload-free checkpoint of fixture consumption and dynamic occurrence cursors.
 *
 * <p>Rule ids remain visible because they are governed fixture identities. Runtime site and graph
 * keys must already be canonical SHA-256 digests so correlation values cannot leak into the durable
 * control store. Zero-use rules may be retained; occurrence cursors are positive and represent the
 * greatest allocation already observed.</p>
 *
 * @param schemaVersion fixture-consumption state protocol version
 * @param ruleUses cumulative use count by immutable fixture rule id
 * @param siteOccurrenceCursors cumulative occurrence by hashed invocation-site/correlation scope
 * @param graphOccurrenceCursors cumulative occurrence by hashed graph-path/correlation scope
 * @param stateFingerprint canonical fingerprint of all preceding fields
 */
public record FixtureConsumptionStateSnapshot(
        String schemaVersion,
        Map<String, Long> ruleUses,
        Map<String, Long> siteOccurrenceCursors,
        Map<String, Long> graphOccurrenceCursors,
        String stateFingerprint
) {
    /** Current fixture-consumption checkpoint protocol. */
    public static final String SCHEMA_VERSION = "bloge.fixtureConsumptionStateSnapshot.v1";
    /** Maximum canonical snapshot size accepted at capture and trusted restore boundaries. */
    public static final int MAX_CANONICAL_BYTES = 2 * 1024 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_CURSOR = 1_000_000_000L;

    /** Canonicalizes maps and rejects payload-bearing or unbounded cursor material. */
    public FixtureConsumptionStateSnapshot {
        schemaVersion = normalized(schemaVersion);
        stateFingerprint = normalized(stateFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported fixture-consumption state version");
        }
        ruleUses = immutableCounters(ruleUses, false, false, "ruleUses");
        siteOccurrenceCursors = immutableCounters(
                siteOccurrenceCursors, true, true, "siteOccurrenceCursors");
        graphOccurrenceCursors = immutableCounters(
                graphOccurrenceCursors, true, true, "graphOccurrenceCursors");
        if (!stateFingerprint.isEmpty() && !fingerprint(stateFingerprint)) {
            throw new IllegalArgumentException(
                    "stateFingerprint must be empty or a canonical SHA-256 fingerprint");
        }
    }

    /** @return canonical material covered by {@link #stateFingerprint()} */
    public Map<String, Object> fingerprintMaterial() {
        return Map.of(
                "schemaVersion", schemaVersion,
                "ruleUses", ruleUses,
                "siteOccurrenceCursors", siteOccurrenceCursors,
                "graphOccurrenceCursors", graphOccurrenceCursors);
    }

    /** @return an immutable copy carrying the supplied integrity fingerprint */
    public FixtureConsumptionStateSnapshot withStateFingerprint(String fingerprint) {
        return new FixtureConsumptionStateSnapshot(schemaVersion, ruleUses,
                siteOccurrenceCursors, graphOccurrenceCursors, fingerprint);
    }

    private static Map<String, Long> immutableCounters(Map<String, Long> source,
                                                        boolean hashedKeys,
                                                        boolean positive,
                                                        String field) {
        Map<String, Long> values = source == null ? Map.of() : source;
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_ENTRIES + " entries");
        }
        TreeMap<String, Long> sorted = new TreeMap<>();
        values.forEach((rawKey, counter) -> {
            String key = normalized(rawKey);
            boolean validKey = hashedKeys ? fingerprint(key) : RULE_ID.matcher(key).matches();
            if (!validKey) {
                throw new IllegalArgumentException(field + (hashedKeys
                        ? " keys must be canonical SHA-256 fingerprints"
                        : " keys must be bounded fixture rule ids"));
            }
            long minimum = positive ? 1 : 0;
            if (counter == null || counter < minimum || counter > MAX_CURSOR) {
                throw new IllegalArgumentException(field + " counters are outside the supported range");
            }
            sorted.put(key, counter);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
