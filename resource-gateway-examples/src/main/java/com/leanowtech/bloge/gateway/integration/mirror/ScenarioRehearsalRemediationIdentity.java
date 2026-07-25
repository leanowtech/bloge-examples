package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Derives the stable namespace reserved for reviewed Scenario remediation successors.
 */
public final class ScenarioRehearsalRemediationIdentity {
    /** Prefix excluded from ordinary caller-authored batch request identities. */
    public static final String RESERVED_PREFIX =
            "scenario-remediation-";

    private ScenarioRehearsalRemediationIdentity() {
    }

    /**
     * Derives one idempotent remediation identity from scope, predecessor, and preview request.
     *
     * <p>Proposal content is intentionally not part of the identity: reusing a preview request id
     * for changed content is a conflict instead of creating a second governance lineage.</p>
     */
    public static String derive(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String predecessorJobId,
            String previewRequestId) {
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("domain",
                "resource-gateway-scenario-remediation-v1");
        material.put("scope",
                Objects.requireNonNull(scope, "scope"));
        material.put("predecessorJobId",
                MirrorStateProtocolSupport.required(
                        predecessorJobId, "predecessorJobId"));
        material.put("previewRequestId",
                MirrorStateProtocolSupport.required(
                        previewRequestId, "previewRequestId"));
        return RESERVED_PREFIX
                + ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                material,
                16 * 1024).substring("sha256:".length());
    }
}
