package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed requirements for one regional Mirror data plane.
 *
 * <p>The contract contains policy coordinates only. It never carries endpoints, credentials,
 * payloads, or provider-specific configuration. A deployment authority must separately certify
 * every required component against this exact revision.</p>
 */
public record RegionalDataPlaneDeploymentContract(
        String schemaVersion,
        String contractFingerprint,
        String contractId,
        long revision,
        CapabilitySnapshot.Scope scope,
        String region,
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
        List<ComponentRequirement> requiredComponents,
        RotationPolicy rotationPolicy,
        Instant validFrom,
        Instant expiresAt,
        String owner
) {
    /** Current regional data-plane deployment-contract protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.regionalDataPlaneDeploymentContract.v1";
    /** Artifact kind used by exact contract references. */
    public static final String ARTIFACT_KIND = "REGIONAL_DATA_PLANE_DEPLOYMENT_CONTRACT";
    /** Maximum lifetime of one contract revision. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofDays(366);

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");

    /** Validates deterministic component coverage and bounded policy coordinates. */
    public RegionalDataPlaneDeploymentContract {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported regional data-plane contract version");
        }
        contractFingerprint = fingerprint(contractFingerprint, "contractFingerprint");
        contractId = identifier(contractId, "contractId");
        if (revision < 1) {
            throw new IllegalArgumentException("contract revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        region = identifier(region, "region");
        deployment = Objects.requireNonNull(deployment, "deployment");
        requiredComponents = orderedRequirements(requiredComponents);
        rotationPolicy = Objects.requireNonNull(rotationPolicy, "rotationPolicy");
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        owner = identifier(owner, "owner");
        if (!expiresAt.isAfter(validFrom)
                || Duration.between(validFrom, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("contract validity window is invalid");
        }
    }

    /** @return exact immutable contract reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, contractId, revision,
                contractFingerprint);
    }

    /** Regional controls that must be independently observed exactly once. */
    public enum ComponentKind {
        /** Evidence signing key held by KMS or HSM. */
        EVIDENCE_KMS,
        /** Payload reference store with purpose, region, and deletion enforcement. */
        PAYLOAD_VAULT,
        /** Fixture and runtime secret reference authority. */
        SECRET_AUTHORITY,
        /** Transactional, isolated Mirror session-state store. */
        SESSION_STATE_STORE,
        /** Bounded fixture, replay, and governed generative resolver chain. */
        FIXTURE_RESOLVER,
        /** Private mutual-TLS workload and authority identity. */
        MUTUAL_TLS,
        /** Out-of-process default-deny business egress enforcement. */
        EGRESS_ISOLATION
    }

    /**
     * Exact requirement for one independently governed component.
     *
     * @param kind closed component kind
     * @param authorityId externally owned authority identity
     * @param policyRef exact approved policy generation
     * @param minimumGeneration lowest non-rollback runtime generation
     * @param maximumObservationAgeSeconds maximum freshness at run admission
     * @param privateTransportRequired whether public transport is forbidden
     * @param failClosedRequired whether control loss must deny use
     * @param regionalResidencyRequired whether data must remain in the named region
     */
    public record ComponentRequirement(
            ComponentKind kind,
            String authorityId,
            MirrorArtifactRef policyRef,
            long minimumGeneration,
            long maximumObservationAgeSeconds,
            boolean privateTransportRequired,
            boolean failClosedRequired,
            boolean regionalResidencyRequired
    ) {
        /** Validates a bounded, production-strength component requirement. */
        public ComponentRequirement {
            kind = Objects.requireNonNull(kind, "kind");
            authorityId = identifier(authorityId, "authorityId");
            policyRef = Objects.requireNonNull(policyRef, "policyRef");
            if (minimumGeneration < 1 || maximumObservationAgeSeconds < 1
                    || maximumObservationAgeSeconds > Duration.ofHours(24).toSeconds()
                    || !privateTransportRequired || !failClosedRequired
                    || !regionalResidencyRequired) {
                throw new IllegalArgumentException(
                        "regional component requirements must be bounded and fail closed");
            }
        }
    }

    /**
     * Rotation expectations shared by KMS/HSM and private PKI.
     *
     * @param maximumKmsKeyAgeSeconds maximum active evidence-key age
     * @param maximumCaAgeSeconds maximum active mTLS CA age
     * @param minimumOverlapSeconds minimum dual-trust overlap during rotation
     * @param restartFreeRequired whether rotation must not require service restart
     * @param staleSessionDrainRequired whether old TLS sessions must be drained
     */
    public record RotationPolicy(
            long maximumKmsKeyAgeSeconds,
            long maximumCaAgeSeconds,
            long minimumOverlapSeconds,
            boolean restartFreeRequired,
            boolean staleSessionDrainRequired
    ) {
        /** Validates bounded rotation ages and mandatory convergence controls. */
        public RotationPolicy {
            long maximumAge = Duration.ofDays(366).toSeconds();
            if (maximumKmsKeyAgeSeconds < 60 || maximumKmsKeyAgeSeconds > maximumAge
                    || maximumCaAgeSeconds < 60 || maximumCaAgeSeconds > maximumAge
                    || minimumOverlapSeconds < 1
                    || minimumOverlapSeconds >= Math.min(
                    maximumKmsKeyAgeSeconds, maximumCaAgeSeconds)
                    || !restartFreeRequired || !staleSessionDrainRequired) {
                throw new IllegalArgumentException("regional rotation policy is invalid");
            }
        }
    }

    private static List<ComponentRequirement> orderedRequirements(
            List<ComponentRequirement> values) {
        if (values == null || values.size() != ComponentKind.values().length) {
            throw new IllegalArgumentException(
                    "every regional data-plane component must be required exactly once");
        }
        List<ComponentRequirement> exact = new ArrayList<>(values);
        if (exact.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("regional component requirements are invalid");
        }
        exact.sort(Comparator.comparing(ComponentRequirement::kind));
        EnumSet<ComponentKind> kinds = EnumSet.noneOf(ComponentKind.class);
        exact.forEach(value -> {
            if (value == null || !kinds.add(value.kind())) {
                throw new IllegalArgumentException("regional component requirements are invalid");
            }
        });
        if (kinds.size() != ComponentKind.values().length) {
            throw new IllegalArgumentException("regional component coverage is incomplete");
        }
        return List.copyOf(exact);
    }

    static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
