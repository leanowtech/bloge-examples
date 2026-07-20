package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Payload-free authorization context sent to an external test-secret authority.
 *
 * <p>The context binds one resolution to the authenticated enterprise scope, authorization
 * purpose, immutable execution and fixture targets, and the exact opaque reference closure. It
 * intentionally excludes correlation tokens, bearer credentials, graph inputs, and secret
 * values. Authorities must authorize the whole closure or deny it; partial resolution is never
 * accepted.</p>
 */
public record TestSecretResolutionContext(
        String schemaVersion,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorType,
        String actorId,
        String delegatedBy,
        String purpose,
        Set<String> groups,
        String clearance,
        String delegationGrantId,
        String authorizedPurpose,
        String executionTargetFingerprint,
        String fixtureTargetFingerprint,
        String fixtureBundleId,
        long fixtureRevision,
        String fixtureFingerprint,
        Map<String, String> secretRefs
) {
    /** Current external test-secret resolution context wire version. */
    public static final String SCHEMA_VERSION = "bloge.testSecretResolutionContext.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9._:/-]{0,127}");

    /** Normalizes and defensively freezes all authority-bound request material. */
    public TestSecretResolutionContext {
        schemaVersion = normalize(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("schemaVersion is unsupported");
        }
        tenantId = required(tenantId, "tenantId", 255);
        organizationId = bounded(organizationId, "organizationId", 255);
        projectId = bounded(projectId, "projectId", 255);
        environmentId = required(environmentId, "environmentId", 255);
        region = bounded(region, "region", 128);
        actorType = required(actorType, "actorType", 64).toUpperCase(Locale.ROOT);
        actorId = required(actorId, "actorId", 255);
        delegatedBy = bounded(delegatedBy, "delegatedBy", 255);
        purpose = required(purpose, "purpose", 128).toUpperCase(Locale.ROOT);
        TreeSet<String> frozenGroups = new TreeSet<>();
        if (groups != null) {
            for (String group : groups) {
                frozenGroups.add(required(group, "groups", 255));
            }
        }
        if (frozenGroups.size() > 100) {
            throw invalid("groups may contain at most 100 entries");
        }
        groups = Collections.unmodifiableSet(frozenGroups);
        clearance = required(clearance, "clearance", 64).toUpperCase(Locale.ROOT);
        delegationGrantId = bounded(delegationGrantId, "delegationGrantId", 255);
        authorizedPurpose = required(authorizedPurpose, "authorizedPurpose", 128)
                .toUpperCase(Locale.ROOT);
        executionTargetFingerprint = fingerprint(executionTargetFingerprint,
                "executionTargetFingerprint");
        fixtureTargetFingerprint = fingerprint(fixtureTargetFingerprint,
                "fixtureTargetFingerprint");
        fixtureBundleId = required(fixtureBundleId, "fixtureBundleId", 255);
        if (fixtureRevision < 1) {
            throw invalid("fixtureRevision must be positive");
        }
        fixtureFingerprint = fingerprint(fixtureFingerprint, "fixtureFingerprint");
        TreeMap<String, String> refs = new TreeMap<>();
        if (secretRefs != null) {
            secretRefs.forEach((alias, reference) -> refs.put(
                    secretAlias(alias), secretReference(reference)));
        }
        if (refs.isEmpty() || refs.size() > 100) {
            throw invalid("secretRefs must contain 1 to 100 entries");
        }
        secretRefs = Collections.unmodifiableMap(refs);
    }

    /**
     * Returns the canonical identity of this exact scope, target, purpose, and reference closure.
     *
     * @param objectMapper canonical protocol mapper
     * @return prefixed SHA-256 fingerprint safe for plans, checkpoints, and diagnostics
     */
    public String fingerprint(ObjectMapper objectMapper) {
        return ProtocolFingerprint.ofBounded(objectMapper, this, 131_072);
    }

    private static String fingerprint(String value, String field) {
        String normalized = normalize(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw invalid(field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String secretAlias(String value) {
        String alias = normalize(value);
        if (!KEY.matcher(alias).matches()) {
            throw invalid("secretRefs contains an invalid alias");
        }
        return alias;
    }

    private static String secretReference(String value) {
        String reference = normalize(value);
        if (reference.isBlank() || reference.length() > 1_024) {
            throw invalid("secretRefs contains an invalid opaque reference");
        }
        try {
            URI uri = new URI(reference);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.isOpaque() || scheme.isBlank()
                    || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    || Set.of("data", "file", "http", "javascript").contains(scheme)
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw invalid("secretRefs contains an invalid opaque reference");
            }
        } catch (URISyntaxException malformed) {
            throw invalid("secretRefs contains an invalid opaque reference");
        }
        return reference;
    }

    private static String required(String value, String field, int maximum) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw invalid(field + " must contain 1 to " + maximum + " characters");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = normalize(value);
        if (normalized.length() > maximum) {
            throw invalid(field + " may contain at most " + maximum + " characters");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Test-secret resolution context " + reason + ".");
    }
}
