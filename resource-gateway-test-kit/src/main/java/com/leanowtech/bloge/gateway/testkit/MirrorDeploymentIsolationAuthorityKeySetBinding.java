package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable local identity and policy binding for one isolation-authority key-set stream.
 *
 * <p>This value is supplied by trusted local configuration. It is intentionally not inferred from
 * the untrusted publication being verified.</p>
 *
 * @param scope complete expected enterprise scope
 * @param deployment exact expected mirror deployment generation
 * @param attestationIssuer expected external isolation-attestation issuer
 * @param keySetId expected stable key-set stream identity
 * @param rootTrustDomain expected bootstrap-root trust domain
 * @param rootThreshold exact locally required M-of-N threshold
 * @param acceptedPolicyFingerprints canonical allowlist of publication policy generations
 */
public record MirrorDeploymentIsolationAuthorityKeySetBinding(
        Scope scope,
        MirrorDeploymentIdentity deployment,
        String attestationIssuer,
        String keySetId,
        String rootTrustDomain,
        int rootThreshold,
        List<String> acceptedPolicyFingerprints
) {
    /** Validates complete local binding policy. */
    public MirrorDeploymentIsolationAuthorityKeySetBinding {
        if (scope == null || deployment == null) {
            throw new IllegalArgumentException("isolation authority binding identity is required");
        }
        attestationIssuer = identifier(attestationIssuer, "attestationIssuer");
        keySetId = identifier(keySetId, "keySetId");
        rootTrustDomain = identifier(rootTrustDomain, "rootTrustDomain");
        if (rootThreshold < 1 || rootThreshold > 16) {
            throw new IllegalArgumentException("rootThreshold is outside protocol bounds");
        }
        if (acceptedPolicyFingerprints == null || acceptedPolicyFingerprints.isEmpty()
                || acceptedPolicyFingerprints.size() > 16) {
            throw new IllegalArgumentException(
                    "acceptedPolicyFingerprints are outside protocol bounds");
        }
        List<String> copy = acceptedPolicyFingerprints.stream()
                .map(value -> fingerprint(value, "acceptedPolicyFingerprint")).toList();
        List<String> sorted = copy.stream().sorted().toList();
        if (!copy.equals(sorted) || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(
                    "acceptedPolicyFingerprints must be canonical and unique");
        }
        acceptedPolicyFingerprints = List.copyOf(copy);
    }

    /**
     * Complete enterprise namespace for one authority publication.
     *
     * @param tenantId owning tenant
     * @param organizationId owning enterprise organization or business unit
     * @param projectId optional project namespace
     * @param environmentId exact environment namespace
     * @param region optional residency or execution region
     */
    public record Scope(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region
    ) {
        /** Validates stable mandatory coordinates and bounded optional coordinates. */
        public Scope {
            tenantId = identifier(tenantId, "scope.tenantId");
            organizationId = identifier(organizationId, "scope.organizationId");
            projectId = optional(projectId, "scope.projectId");
            environmentId = identifier(environmentId, "scope.environmentId");
            region = optional(region, "scope.region");
        }

        private boolean matches(JsonNode value) {
            return tenantId.equals(value.path("tenantId").asText())
                    && organizationId.equals(value.path("organizationId").asText())
                    && projectId.equals(value.path("projectId").asText())
                    && environmentId.equals(value.path("environmentId").asText())
                    && region.equals(value.path("region").asText());
        }
    }

    /**
     * Decodes one strict compatibility-fixture binding.
     *
     * @param value expected-binding JSON value
     * @return typed immutable binding
     */
    public static MirrorDeploymentIsolationAuthorityKeySetBinding from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 7
                || !Set.of("scope", "deployment", "attestationIssuer", "keySetId",
                "rootTrustDomain", "rootThreshold", "acceptedPolicyFingerprints")
                .equals(fieldNames(value))) {
            throw new IllegalArgumentException("isolation authority binding is malformed");
        }
        JsonNode scope = value.path("scope");
        if (!scope.isObject() || scope.size() != 5
                || !Set.of("tenantId", "organizationId", "projectId", "environmentId",
                "region").equals(fieldNames(scope))) {
            throw new IllegalArgumentException("isolation authority scope is malformed");
        }
        List<String> policies = new ArrayList<>();
        value.path("acceptedPolicyFingerprints").forEach(
                item -> policies.add(item.asText()));
        try {
            return new MirrorDeploymentIsolationAuthorityKeySetBinding(
                    new Scope(scope.path("tenantId").asText(),
                            scope.path("organizationId").asText(),
                            scope.path("projectId").asText(),
                            scope.path("environmentId").asText(),
                            scope.path("region").asText()),
                    MirrorDeploymentIdentity.from(value.path("deployment")),
                    value.path("attestationIssuer").asText(),
                    value.path("keySetId").asText(),
                    value.path("rootTrustDomain").asText(),
                    value.path("rootThreshold").asInt(), policies);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "isolation authority binding is malformed", invalid);
        }
    }

    boolean matches(JsonNode material) {
        return scope.matches(material.path("scope"))
                && deploymentMatches(material.path("deployment"))
                && attestationIssuer.equals(material.path("attestationIssuer").asText())
                && keySetId.equals(material.path("keySetId").asText())
                && rootTrustDomain.equals(material.path("rootTrustDomain").asText())
                && rootThreshold == material.path("rootThreshold").asInt()
                && acceptedPolicyFingerprints.contains(
                        material.path("policyFingerprint").asText());
    }

    private boolean deploymentMatches(JsonNode actual) {
        return deployment.deploymentScopeId().equals(actual.path("deploymentScopeId").asText())
                && deployment.clusterId().equals(actual.path("clusterId").asText())
                && deployment.namespace().equals(actual.path("namespace").asText())
                && deployment.workloadName().equals(actual.path("workloadName").asText())
                && deployment.serviceAccount().equals(actual.path("serviceAccount").asText())
                && deployment.imageDigest().equals(actual.path("imageDigest").asText());
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String optional(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isEmpty()
                && !exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = optional(value, field);
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return exact;
    }

    private static Set<String> fieldNames(JsonNode value) {
        HashSet<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
