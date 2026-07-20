package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Locale;
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
 * @param secretRefs opaque references resolved by the external test-secret authority
 */
public record FixtureExecutionServices(
        boolean configured,
        Map<String, Object> identityAttributes,
        Map<String, Boolean> featureFlags,
        Map<String, String> secretRefs
) {
    /** Reserved fixture metadata property. */
    public static final String METADATA_KEY = "executionServices";
    /** Version of the nested execution-service fixture contract. */
    public static final String SCHEMA_VERSION = "bloge.fixtureExecutionServices.v1";
    /** Version adding opaque external test-secret references without carrying secret values. */
    public static final String SCHEMA_VERSION_V2 = "bloge.fixtureExecutionServices.v2";
    /** Maximum entries per execution-service namespace. */
    public static final int MAX_ENTRIES = 100;
    /** Maximum UTF-8 bytes across the nested execution-service control material. */
    public static final int MAX_CONTROL_BYTES = 65_536;
    /** Maximum characters in one identity string value. */
    public static final int MAX_IDENTITY_STRING_CHARACTERS = 4_096;
    /** Maximum characters in one opaque test-secret reference. */
    public static final int MAX_SECRET_REF_CHARACTERS = 1_024;

    private static final int MAX_KEY_CHARACTERS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9._:/-]{0,127}");
    private static final Set<String> V1_PROPERTIES = Set.of(
            "schemaVersion", "identityAttributes", "featureFlags");
    private static final Set<String> V2_PROPERTIES = Set.of(
            "schemaVersion", "identityAttributes", "featureFlags", "secretRefs");
    private static final Set<String> FORBIDDEN_SECRET_REF_SCHEMES = Set.of(
            "data", "file", "http", "javascript");

    /** Creates validated, defensive, name-ordered maps for deterministic lookup and fingerprinting. */
    public FixtureExecutionServices {
        Map<String, Object> identities = identityAttributes(
                identityAttributes == null ? Map.of() : identityAttributes);
        Map<String, Boolean> flags = featureFlags(featureFlags == null ? Map.of() : featureFlags);
        Map<String, String> refs = secretRefs(secretRefs == null ? Map.of() : secretRefs);
        if (!configured && (!identities.isEmpty() || !flags.isEmpty() || !refs.isEmpty())) {
            throw invalid("cannot carry values when the reserved metadata object is absent");
        }
        if (encodedBytes(identities, flags, refs) > MAX_CONTROL_BYTES) {
            throw invalid("exceeds the 65536-byte control-material bound");
        }
        identityAttributes = immutableSorted(identities);
        featureFlags = immutableSorted(flags);
        secretRefs = immutableSorted(refs);
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
            return new FixtureExecutionServices(false, Map.of(), Map.of(), Map.of());
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid("must be an object");
        }
        Object schemaVersion = raw.get("schemaVersion");
        boolean v1 = SCHEMA_VERSION.equals(schemaVersion);
        boolean v2 = SCHEMA_VERSION_V2.equals(schemaVersion);
        if (!v1 && !v2) {
            throw invalid("has an unsupported schemaVersion");
        }
        Set<String> expected = v2 ? V2_PROPERTIES : V1_PROPERTIES;
        if (!raw.keySet().equals(expected)) {
            throw invalid(v2
                    ? "must contain exactly schemaVersion, identityAttributes, featureFlags, and secretRefs"
                    : "must contain exactly schemaVersion, identityAttributes, and featureFlags");
        }
        Map<String, Object> identities = identityAttributes(raw.get("identityAttributes"));
        Map<String, Boolean> flags = featureFlags(raw.get("featureFlags"));
        Map<String, String> refs = v2 ? secretRefs(raw.get("secretRefs")) : Map.of();
        if (v2 && refs.isEmpty()) {
            throw invalid("v2 secretRefs must contain at least one opaque reference");
        }
        if (encodedBytes(identities, flags, refs) > MAX_CONTROL_BYTES) {
            throw invalid("exceeds the 65536-byte control-material bound");
        }
        return new FixtureExecutionServices(true, identities, flags, refs);
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
            case SECRET -> !secretRefs.isEmpty();
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
            case SECRET -> secretRefs;
            default -> Map.of();
        };
    }

    private static Map<String, String> secretRefs(Object value) {
        Map<?, ?> raw = requiredMap(value, "secretRefs");
        boundedEntries(raw, "secretRefs");
        Map<String, String> result = new TreeMap<>();
        int index = 0;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = validKey(entry.getKey(), "secretRefs", index++);
            if (!(entry.getValue() instanceof String reference)) {
                throw invalid("secretRefs values must be absolute opaque URI references");
            }
            result.put(key, validSecretRef(reference));
        }
        return result;
    }

    private static String validSecretRef(String value) {
        String reference = value == null ? "" : value.trim();
        if (reference.isBlank() || reference.length() > MAX_SECRET_REF_CHARACTERS) {
            throw invalid("secretRefs values must be absolute opaque URI references of at most 1024 characters");
        }
        final URI uri;
        try {
            uri = new URI(reference);
        } catch (URISyntaxException malformed) {
            throw invalid("secretRefs values must be absolute opaque URI references");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.isOpaque() || scheme.isBlank()
                || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                || FORBIDDEN_SECRET_REF_SCHEMES.contains(scheme)) {
            throw invalid("secretRefs values must be absolute opaque URI references");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("secretRefs values must not contain user info");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("secretRefs values must not contain a query or fragment");
        }
        return reference;
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
                                    Map<String, Boolean> flags,
                                    Map<String, String> refs) {
        try {
            Map<String, Object> material = new TreeMap<>();
            material.put("schemaVersion", refs.isEmpty() ? SCHEMA_VERSION : SCHEMA_VERSION_V2);
            material.put("identityAttributes", identities);
            material.put("featureFlags", flags);
            if (!refs.isEmpty()) {
                material.put("secretRefs", refs);
            }
            return JSON.writeValueAsBytes(material).length;
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
