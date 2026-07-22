package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable local deployment coordinates used to verify an isolation attestation.
 *
 * @param deploymentScopeId operator-owned deployment scope
 * @param clusterId exact cluster or scheduler identity
 * @param namespace exact namespace or workload isolation domain
 * @param workloadName exact deployment/workload name
 * @param serviceAccount exact non-production workload identity
 * @param imageDigest immutable executable image digest
 */
public record MirrorDeploymentIdentity(
        String deploymentScopeId,
        String clusterId,
        String namespace,
        String workloadName,
        String serviceAccount,
        String imageDigest
) {
    /** Validates immutable deployment coordinates. */
    public MirrorDeploymentIdentity {
        deploymentScopeId = identifier(deploymentScopeId, "deploymentScopeId");
        clusterId = identifier(clusterId, "clusterId");
        namespace = identifier(namespace, "namespace");
        workloadName = identifier(workloadName, "workloadName");
        serviceAccount = identifier(serviceAccount, "serviceAccount");
        imageDigest = fingerprint(imageDigest, "imageDigest");
    }

    /**
     * Decodes the deployment object from a compatibility fixture.
     *
     * @param value strict deployment object
     * @return typed immutable deployment identity
     */
    public static MirrorDeploymentIdentity from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 6) {
            throw new IllegalArgumentException("mirror deployment identity is invalid");
        }
        return new MirrorDeploymentIdentity(value.path("deploymentScopeId").asText(),
                value.path("clusterId").asText(), value.path("namespace").asText(),
                value.path("workloadName").asText(), value.path("serviceAccount").asText(),
                value.path("imageDigest").asText());
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
