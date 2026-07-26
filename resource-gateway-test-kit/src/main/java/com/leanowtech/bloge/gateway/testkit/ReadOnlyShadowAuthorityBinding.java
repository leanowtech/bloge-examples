package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable local current-head binding for one read-only Shadow authority publication.
 *
 * <p>The binding must come from a trusted control-plane observation. Requiring the exact complete
 * publication fingerprint prevents an otherwise valid stale predecessor from being accepted
 * after an inactive grant, disabled kill switch, or replacement guard policy is published.</p>
 *
 * @param type expected authority protocol
 * @param streamId stable authority stream identity
 * @param revision exact current-head revision
 * @param publicationFingerprint exact current-head publication fingerprint
 * @param scope complete expected business or shared guard scope
 * @param issuer exact expected authority identity
 */
public record ReadOnlyShadowAuthorityBinding(
        Type type,
        String streamId,
        long revision,
        String publicationFingerprint,
        Scope scope,
        String issuer
) {
    /** Validates one exact local current-head policy binding. */
    public ReadOnlyShadowAuthorityBinding {
        if (type == null || scope == null) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority binding identity is required");
        }
        streamId = identifier(streamId, "streamId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        publicationFingerprint = fingerprint(
                publicationFingerprint, "publicationFingerprint");
        issuer = identifier(issuer, "issuer");
    }

    /** Supported online authority publication protocols. */
    public enum Type {
        /** Shared execution pressure and circuit policy. */
        GUARD_POLICY(
                "resourceGateway.readOnlyShadowGuardPolicyPublication.v1",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_GUARD_POLICY_V1",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_GUARD_POLICY_PUBLICATION_SCHEMA_RESOURCE,
                "policyId",
                "guardScope"),
        /** Business-scope logical sampling authorization. */
        SAMPLING_GRANT(
                "resourceGateway.readOnlyShadowSamplingGrantPublication.v1",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SAMPLING_GRANT_V1",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_SAMPLING_GRANT_PUBLICATION_SCHEMA_RESOURCE,
                "grantId",
                "scope"),
        /** Business-scope operational emergency switch. */
        KILL_SWITCH(
                "resourceGateway.readOnlyShadowKillSwitchPublication.v1",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_KILL_SWITCH_V1",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_KILL_SWITCH_PUBLICATION_SCHEMA_RESOURCE,
                "switchId",
                "scope");

        private final String schemaVersion;
        private final String signatureDomain;
        private final String schemaResource;
        private final String streamIdField;
        private final String scopeField;

        Type(
                String schemaVersion,
                String signatureDomain,
                String schemaResource,
                String streamIdField,
                String scopeField) {
            this.schemaVersion = schemaVersion;
            this.signatureDomain = signatureDomain;
            this.schemaResource = schemaResource;
            this.streamIdField = streamIdField;
            this.scopeField = scopeField;
        }

        String schemaVersion() {
            return schemaVersion;
        }

        String signatureDomain() {
            return signatureDomain;
        }

        String schemaResource() {
            return schemaResource;
        }

        String streamIdField() {
            return streamIdField;
        }

        String scopeField() {
            return scopeField;
        }
    }

    /**
     * Complete enterprise namespace expected by the offline consumer.
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
            projectId = optionalIdentifier(projectId, "scope.projectId");
            environmentId = identifier(environmentId, "scope.environmentId");
            region = optionalIdentifier(region, "scope.region");
        }

        boolean matches(JsonNode value) {
            return value != null
                    && value.isObject()
                    && value.size() == 5
                    && tenantId.equals(value.path("tenantId").asText())
                    && organizationId.equals(value.path("organizationId").asText())
                    && projectId.equals(value.path("projectId").asText())
                    && environmentId.equals(value.path("environmentId").asText())
                    && region.equals(value.path("region").asText());
        }
    }

    boolean matches(JsonNode publication) {
        JsonNode material = publication.path("material");
        return type.schemaVersion().equals(publication.path("schemaVersion").asText())
                && streamId.equals(material.path(type.streamIdField()).asText())
                && revision == material.path("revision").asLong()
                && publicationFingerprint.equals(
                        publication.path("publicationFingerprint").asText())
                && scope.matches(material.path(type.scopeField()))
                && issuer.equals(material.path("issuer").asText());
    }

    private static String fingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalIdentifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.isBlank()
                && !exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
