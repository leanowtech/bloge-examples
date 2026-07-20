package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Strict deterministic execution-service values carried by reserved fixture metadata.
 *
 * <p>The wire location is {@code fixtureBundle.metadata.executionServices}. Keeping this extension
 * below the existing metadata property preserves the v1 fixture shape while this parser turns the
 * reserved value into a bounded, immutable control contract. Raw values remain fixture payload;
 * plans and evidence must project only service-specific fingerprints.</p>
 *
 * @param configured whether the reserved metadata object was present
 * @param identityAttributes exact identity attributes available to the test run
 * @param featureFlags exact feature-flag decisions available to the test run
 */
public record FixtureExecutionServices(
        boolean configured,
        Map<String, Object> identityAttributes,
        Map<String, Boolean> featureFlags
) {
    /** Reserved fixture metadata property. */
    public static final String METADATA_KEY = "executionServices";
    /** Version of the nested execution-service fixture contract. */
    public static final String SCHEMA_VERSION = "bloge.fixtureExecutionServices.v1";
    /** Maximum entries per execution-service namespace. */
    public static final int MAX_ENTRIES = 100;
    /** Maximum UTF-8 bytes across the nested execution-service control material. */
    public static final int MAX_CONTROL_BYTES = 65_536;
    /** Maximum characters in one identity string value. */
    public static final int MAX_IDENTITY_STRING_CHARACTERS = 4_096;

    private static final int MAX_KEY_CHARACTERS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9._:/-]{0,127}");
    private static final Set<String> PROPERTIES = Set.of(
            "schemaVersion", "identityAttributes", "featureFlags");

    /** Creates validated, defensive, name-ordered maps for deterministic lookup and fingerprinting. */
    public FixtureExecutionServices {
        Map<String, Object> identities = identityAttributes(
                identityAttributes == null ? Map.of() : identityAttributes);
        Map<String, Boolean> flags = featureFlags(featureFlags == null ? Map.of() : featureFlags);
        if (!configured && (!identities.isEmpty() || !flags.isEmpty())) {
            throw invalid("cannot carry values when the reserved metadata object is absent");
        }
        if (encodedBytes(identities, flags) > MAX_CONTROL_BYTES) {
            throw invalid("exceeds the 65536-byte control-material bound");
        }
        identityAttributes = immutableSorted(identities);
        featureFlags = immutableSorted(flags);
    }

    /**
     * Parses and validates the reserved execution-service metadata from one fixture bundle.
     *
     * @param bundle fixture whose metadata is being frozen
     * @return absent or fully validated immutable execution-service controls
     * @throws IllegalArgumentException when reserved metadata is malformed or exceeds a bound
     */
    public static FixtureExecutionServices from(FixtureBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("Fixture bundle is required.");
        }
        Object value = bundle.metadata().get(METADATA_KEY);
        if (value == null) {
            return new FixtureExecutionServices(false, Map.of(), Map.of());
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid("must be an object");
        }
        if (!raw.keySet().equals(PROPERTIES)) {
            throw invalid("must contain exactly schemaVersion, identityAttributes, and featureFlags");
        }
        if (!SCHEMA_VERSION.equals(raw.get("schemaVersion"))) {
            throw invalid("has an unsupported schemaVersion");
        }
        Map<String, Object> identities = identityAttributes(raw.get("identityAttributes"));
        Map<String, Boolean> flags = featureFlags(raw.get("featureFlags"));
        if (encodedBytes(identities, flags) > MAX_CONTROL_BYTES) {
            throw invalid("exceeds the 65536-byte control-material bound");
        }
        return new FixtureExecutionServices(true, identities, flags);
    }

    /**
     * Returns whether one service has a deterministic fixture authority.
     *
     * @param kind execution service kind
     * @return true when the requested namespace contains at least one exact fixture binding
     */
    public boolean configures(ExecutionServiceKind kind) {
        if (!configured) {
            return false;
        }
        return switch (kind) {
            case IDENTITY -> !identityAttributes.isEmpty();
            case FEATURE_FLAG -> !featureFlags.isEmpty();
            default -> false;
        };
    }

    /**
     * Returns payload-bearing configuration for service-specific fingerprinting only.
     *
     * @param kind execution service kind
     * @return exact immutable service map, or an empty map for unsupported kinds
     */
    public Map<String, ?> configuration(ExecutionServiceKind kind) {
        return switch (kind) {
            case IDENTITY -> identityAttributes;
            case FEATURE_FLAG -> featureFlags;
            default -> Map.of();
        };
    }

    private static Map<String, Object> identityAttributes(Object value) {
        Map<?, ?> raw = requiredMap(value, "identityAttributes");
        boundedEntries(raw, "identityAttributes");
        Map<String, Object> result = new TreeMap<>();
        int index = 0;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = validKey(entry.getKey(), "identityAttributes", index++);
            Object scalar = entry.getValue();
            if (scalar instanceof String text) {
                if (text.length() > MAX_IDENTITY_STRING_CHARACTERS) {
                    throw invalid("identityAttributes contains a string longer than 4096 characters");
                }
            } else if (!(scalar instanceof Boolean) && !isIntegral(scalar)) {
                throw invalid("identityAttributes values must be non-null JSON strings, booleans, or integers");
            }
            result.put(key, scalar);
        }
        return result;
    }

    private static Map<String, Boolean> featureFlags(Object value) {
        Map<?, ?> raw = requiredMap(value, "featureFlags");
        boundedEntries(raw, "featureFlags");
        Map<String, Boolean> result = new TreeMap<>();
        int index = 0;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = validKey(entry.getKey(), "featureFlags", index++);
            if (!(entry.getValue() instanceof Boolean enabled)) {
                throw invalid("featureFlags values must be booleans");
            }
            result.put(key, enabled);
        }
        return result;
    }

    private static Map<?, ?> requiredMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(field + " must be an object");
        }
        return map;
    }

    private static void boundedEntries(Map<?, ?> values, String field) {
        if (values.size() > MAX_ENTRIES) {
            throw invalid(field + " may contain at most 100 entries");
        }
    }

    private static String validKey(Object value, String field, int index) {
        if (!(value instanceof String key) || key.length() > MAX_KEY_CHARACTERS
                || !KEY.matcher(key).matches()) {
            throw invalid(field + " key at index " + index
                    + " must match [A-Za-z_][A-Za-z0-9._:/-]{0,127}");
        }
        return key;
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger;
    }

    private static int encodedBytes(Map<String, Object> identities,
                                    Map<String, Boolean> flags) {
        try {
            return JSON.writeValueAsBytes(Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "identityAttributes", identities,
                    "featureFlags", flags)).length;
        } catch (JsonProcessingException impossible) {
            throw invalid("cannot be encoded as canonical JSON");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("fixtureBundle.metadata.executionServices " + reason + ".");
    }

    private static <T> Map<String, T> immutableSorted(Map<String, T> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values == null ? Map.of() : values));
    }
}
