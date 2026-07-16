package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes the stable authorization-policy identity used by durable test recovery.
 *
 * <p>The projection deliberately excludes health counters, refresh timestamps, key counts, and
 * other operational observations. Those values change during healthy operation and would strand
 * every checkpoint. It includes only properties that alter claim interpretation or fail-closed
 * authorization policy; the caller credential is freshly verified for every owner claim.</p>
 */
public final class DurableTestRecoveryAuthority {

    private static final Set<String> POLICY_PROPERTIES = Set.of(
            "acceptedAlgorithms",
            "keyRevocationSupported",
            "tokenRevocationSupported",
            "organizationGroupClaimsSupported",
            "clearanceClaimsSupported",
            "issuerAttestedDelegationGrantSupported",
            "maximumTokenLifetimeSeconds",
            "clockSkewSeconds",
            "trustSourceType",
            "dynamicRefreshSupported",
            "refreshIntervalSeconds",
            "revocationPropagationSloSeconds",
            "outageFailClosed",
            "staleSnapshotAccepted",
            "issuerFingerprint",
            "audienceFingerprint");

    private final IntegrationRequestAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    /**
     * Creates an authority snapshotter over the same verifier used at the HTTP boundary.
     *
     * @param authenticator integration credential authority
     * @param objectMapper canonical protocol mapper
     */
    public DurableTestRecoveryAuthority(
            IntegrationRequestAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Captures the current fail-closed authorization policy identity.
     *
     * @return payload-free policy snapshot suitable for durable checkpoint binding
     * @throws IllegalStateException when the authority is unavailable or permits stale trust state
     */
    public DurableTestExecutionCheckpoint.AuthoritySnapshot currentSnapshot() {
        IntegrationIdentityResolver.Descriptor descriptor = authenticator.descriptor();
        if (descriptor == null || !descriptor.available()) {
            throw new IllegalStateException("Integration identity authority is unavailable");
        }
        Map<String, Object> properties = new TreeMap<>();
        descriptor.properties().forEach((key, value) -> {
            if (POLICY_PROPERTIES.contains(key)) {
                properties.put(key, value);
            }
        });
        if (Boolean.FALSE.equals(properties.get("outageFailClosed"))
                || Boolean.TRUE.equals(properties.get("staleSnapshotAccepted"))) {
            throw new IllegalStateException(
                    "Durable recovery requires a fail-closed, non-stale identity authority");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.durableRecoveryAuthorityPolicy.v1");
        material.put("providerType", descriptor.providerType());
        material.put("claimsSource", descriptor.claimsSource());
        material.put("demoMode", descriptor.demoMode());
        material.put("delegatedIdentitySupported", descriptor.delegatedIdentitySupported());
        material.put("policyProperties", properties);
        return new DurableTestExecutionCheckpoint.AuthoritySnapshot(
                "FAIL_CLOSED", ProtocolFingerprint.of(objectMapper, material));
    }
}
