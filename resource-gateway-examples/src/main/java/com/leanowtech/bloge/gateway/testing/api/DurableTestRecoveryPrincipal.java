package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;

/** Canonical identity binding shared by durable owner claim and recovery control commands. */
public final class DurableTestRecoveryPrincipal {

    private DurableTestRecoveryPrincipal() {
    }

    /**
     * Fingerprints every stable authorization fact while excluding retry correlation identity.
     *
     * <p>Region, delegation, clearance, and the sorted group set are intentionally covered. A
     * recovery heartbeat may continue only under the exact authority that obtained its dispatch;
     * changing any of those facts requires a new owner claim. Correlation ID is transport telemetry
     * and is excluded so a lost-response retry remains reproducible.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param identity complete authenticated workload authority
     * @return canonical SHA-256 principal fingerprint
     */
    public static String fingerprint(
            ObjectMapper objectMapper, IntegrationRequestContext identity) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        IntegrationRequestContext requiredIdentity = Objects.requireNonNull(
                identity, "identity");
        requiredIdentity.requireComplete();
        return ProtocolFingerprint.of(mapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableRecoveryPrincipal.v1"),
                Map.entry("tenantId", requiredIdentity.tenantId()),
                Map.entry("organizationId", requiredIdentity.organizationId()),
                Map.entry("projectId", requiredIdentity.projectId()),
                Map.entry("environmentId", requiredIdentity.environmentId()),
                Map.entry("region", requiredIdentity.region()),
                Map.entry("actorType", requiredIdentity.actorType()),
                Map.entry("actorId", requiredIdentity.actorId()),
                Map.entry("delegatedBy", requiredIdentity.delegatedBy()),
                Map.entry("delegationGrantId", requiredIdentity.delegationGrantId()),
                Map.entry("purpose", requiredIdentity.purpose()),
                Map.entry("clearance", requiredIdentity.clearance()),
                Map.entry("groups", requiredIdentity.groups().stream().sorted().toList())));
    }
}
