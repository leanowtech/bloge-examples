package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free proof that an exact durable recovery closure passed current authorization.
 *
 * <p>The receipt is an auditable decision fact, not a bearer token. A recovery worker must still
 * hold the matching owner fence and reconstruct the exact target, fixture, replay, provider, and
 * authority closure. The receipt binds that reconstruction to the authenticated principal used by
 * the owner-claim service without persisting credentials, groups, fixture values, or replay
 * payloads.</p>
 *
 * @param schemaVersion authorization receipt protocol version
 * @param sourceCheckpointFingerprint exact pre-claim checkpoint that was authorized
 * @param principalFingerprint canonical fingerprint of authenticated authority facts
 * @param targetFingerprint exact graph or operator content identity
 * @param planFingerprint exact effective execution-plan identity
 * @param fixtureFingerprint exact immutable fixture revision content identity
 * @param replayClosureFingerprint canonical replay dependency identity
 * @param providerStateFingerprint exact deterministic provider-state identity
 * @param authorityFingerprint exact fail-closed identity-policy snapshot
 * @param authorizedPurpose governed graph or operator test purpose
 * @param sideEffectPolicy fail-closed real-side-effect policy
 * @param authorizationFingerprint canonical fingerprint of every preceding field
 */
public record DurableTestRecoveryAuthorization(
        String schemaVersion,
        String sourceCheckpointFingerprint,
        String principalFingerprint,
        String targetFingerprint,
        String planFingerprint,
        String fixtureFingerprint,
        String replayClosureFingerprint,
        String providerStateFingerprint,
        String authorityFingerprint,
        String authorizedPurpose,
        String sideEffectPolicy,
        String authorizationFingerprint
) {
    /** Current payload-free authorization receipt protocol. */
    public static final String SCHEMA_VERSION = "bloge.durableTestRecoveryAuthorization.v1";
    private static final int MAX_CANONICAL_BYTES = 64 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects incomplete, unsupported, or non-content-addressed decision material. */
    public DurableTestRecoveryAuthorization {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported durable recovery authorization version");
        }
        sourceCheckpointFingerprint = fingerprint(
                sourceCheckpointFingerprint, "sourceCheckpointFingerprint");
        principalFingerprint = fingerprint(principalFingerprint, "principalFingerprint");
        targetFingerprint = fingerprint(targetFingerprint, "targetFingerprint");
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        fixtureFingerprint = fingerprint(fixtureFingerprint, "fixtureFingerprint");
        replayClosureFingerprint = fingerprint(
                replayClosureFingerprint, "replayClosureFingerprint");
        providerStateFingerprint = fingerprint(
                providerStateFingerprint, "providerStateFingerprint");
        authorityFingerprint = fingerprint(authorityFingerprint, "authorityFingerprint");
        authorizedPurpose = required(authorizedPurpose, "authorizedPurpose")
                .toUpperCase(Locale.ROOT);
        sideEffectPolicy = required(sideEffectPolicy, "sideEffectPolicy")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("GRAPH_CONTRACT_TEST", "OPERATOR_UNIT_TEST").contains(authorizedPurpose)) {
            throw new IllegalArgumentException("Unsupported durable recovery purpose");
        }
        if (!Set.of("DENY_REAL", "REPLAY_ONLY").contains(sideEffectPolicy)) {
            throw new IllegalArgumentException("Unsupported durable recovery side-effect policy");
        }
        authorizationFingerprint = authorizationFingerprint == null
                ? "" : authorizationFingerprint.trim();
        if (!authorizationFingerprint.isEmpty()
                && !FINGERPRINT.matcher(authorizationFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "authorizationFingerprint must be empty or a canonical SHA-256 fingerprint");
        }
    }

    /**
     * Issues a content-addressed receipt from already verified authorization facts.
     *
     * @param objectMapper canonical protocol mapper
     * @param sourceCheckpointFingerprint exact pre-claim checkpoint identity
     * @param principalFingerprint authenticated principal authority identity
     * @param targetFingerprint exact target content identity
     * @param planFingerprint exact effective-plan identity
     * @param fixtureFingerprint exact fixture content identity
     * @param replayClosureFingerprint exact replay dependency identity
     * @param providerStateFingerprint exact execution-service state identity
     * @param authorityFingerprint exact current identity-policy identity
     * @param authorizedPurpose governed test purpose
     * @param sideEffectPolicy fail-closed side-effect policy
     * @return sealed payload-free authorization receipt
     */
    public static DurableTestRecoveryAuthorization issue(
            ObjectMapper objectMapper,
            String sourceCheckpointFingerprint,
            String principalFingerprint,
            String targetFingerprint,
            String planFingerprint,
            String fixtureFingerprint,
            String replayClosureFingerprint,
            String providerStateFingerprint,
            String authorityFingerprint,
            String authorizedPurpose,
            String sideEffectPolicy) {
        DurableTestRecoveryAuthorization material = new DurableTestRecoveryAuthorization(
                SCHEMA_VERSION, sourceCheckpointFingerprint, principalFingerprint,
                targetFingerprint, planFingerprint, fixtureFingerprint,
                replayClosureFingerprint, providerStateFingerprint, authorityFingerprint,
                authorizedPurpose, sideEffectPolicy, "");
        String sealed = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"),
                material.fingerprintMaterial(), MAX_CANONICAL_BYTES);
        return material.withAuthorizationFingerprint(sealed);
    }

    /**
     * Verifies the receipt after a persistence or process boundary.
     *
     * @param objectMapper canonical protocol mapper
     */
    public void requireValid(ObjectMapper objectMapper) {
        String actual = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"),
                fingerprintMaterial(), MAX_CANONICAL_BYTES);
        if (!actual.equals(authorizationFingerprint)) {
            throw new IllegalArgumentException("Invalid durable recovery authorization fingerprint");
        }
    }

    /**
     * Projects the canonical material covered by {@link #authorizationFingerprint()}.
     *
     * @return payload-free authorization decision material
     */
    public Map<String, Object> fingerprintMaterial() {
        return Map.ofEntries(
                Map.entry("schemaVersion", schemaVersion),
                Map.entry("sourceCheckpointFingerprint", sourceCheckpointFingerprint),
                Map.entry("principalFingerprint", principalFingerprint),
                Map.entry("targetFingerprint", targetFingerprint),
                Map.entry("planFingerprint", planFingerprint),
                Map.entry("fixtureFingerprint", fixtureFingerprint),
                Map.entry("replayClosureFingerprint", replayClosureFingerprint),
                Map.entry("providerStateFingerprint", providerStateFingerprint),
                Map.entry("authorityFingerprint", authorityFingerprint),
                Map.entry("authorizedPurpose", authorizedPurpose),
                Map.entry("sideEffectPolicy", sideEffectPolicy));
    }

    private DurableTestRecoveryAuthorization withAuthorizationFingerprint(String value) {
        return new DurableTestRecoveryAuthorization(schemaVersion, sourceCheckpointFingerprint,
                principalFingerprint, targetFingerprint, planFingerprint, fixtureFingerprint,
                replayClosureFingerprint, providerStateFingerprint, authorityFingerprint,
                authorizedPurpose, sideEffectPolicy, value);
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
