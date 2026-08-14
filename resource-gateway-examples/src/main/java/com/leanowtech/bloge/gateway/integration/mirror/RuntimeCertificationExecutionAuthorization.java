package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Short-lived, single-use external approval for destructive runtime certification.
 *
 * <p>The authorization is not a generic change ticket. It is bound to one manifest, deployment,
 * environment fingerprint, and exact scenario inventory. Production cannot be represented as an
 * executable authorization.</p>
 */
public record RuntimeCertificationExecutionAuthorization(
        String schemaVersion,
        String authorizationFingerprint,
        String authorizationId,
        long revision,
        MirrorArtifactRef manifestRef,
        CapabilitySnapshot.Scope scope,
        RuntimeCertificationManifest.EnvironmentClass environmentClass,
        String environmentFingerprint,
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
        List<RuntimeCertificationManifest.Scenario> allowedScenarios,
        boolean destructiveActionsAllowed,
        boolean productionExecutionDenied,
        boolean singleUse,
        String nonceFingerprint,
        Instant issuedAt,
        Instant validFrom,
        Instant expiresAt,
        String issuer,
        List<MirrorArtifactRef> approvalRefs,
        VisualRunEvidenceSeal authorizationSeal
) {
    /** Current execution authorization protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.runtimeCertificationExecutionAuthorization.v1";
    /** Artifact kind used by reports and durable consumption journals. */
    public static final String ARTIFACT_KIND = "RUNTIME_CERTIFICATION_EXECUTION_AUTHORIZATION";
    /** Maximum lifetime of a destructive execution approval. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(30);

    /** Validates a narrow, fail-closed execution grant. */
    public RuntimeCertificationExecutionAuthorization {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported runtime certification authorization version");
        }
        authorizationFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                authorizationFingerprint, "authorizationFingerprint");
        authorizationId = RegionalDataPlaneDeploymentContract.identifier(
                authorizationId, "authorizationId");
        if (revision < 1) {
            throw new IllegalArgumentException("authorization revision must be positive");
        }
        manifestRef = requireKind(manifestRef,
                RuntimeCertificationManifest.ARTIFACT_KIND, "manifestRef");
        scope = Objects.requireNonNull(scope, "scope");
        environmentClass = Objects.requireNonNull(environmentClass, "environmentClass");
        if (environmentClass == RuntimeCertificationManifest.EnvironmentClass.PRODUCTION) {
            throw new IllegalArgumentException(
                    "production runtime certification execution is forbidden");
        }
        environmentFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                environmentFingerprint, "environmentFingerprint");
        deployment = Objects.requireNonNull(deployment, "deployment");
        allowedScenarios = exactScenarios(allowedScenarios);
        if (!destructiveActionsAllowed || !productionExecutionDenied || !singleUse) {
            throw new IllegalArgumentException(
                    "runtime certification authorization must be destructive, non-production, and single-use");
        }
        nonceFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                nonceFingerprint, "nonceFingerprint");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (validFrom.isBefore(issuedAt) || !expiresAt.isAfter(validFrom)
                || Duration.between(issuedAt, expiresAt)
                .compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "runtime certification authorization window is invalid");
        }
        issuer = RegionalDataPlaneDeploymentContract.identifier(issuer, "issuer");
        approvalRefs = orderedRefs(approvalRefs, "approvalRefs");
        authorizationSeal = Objects.requireNonNull(authorizationSeal, "authorizationSeal");
        if (!authorizationSeal.signed()) {
            throw new IllegalArgumentException("runtime certification authorization must be signed");
        }
    }

    /** @return exact immutable authorization reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, authorizationId, revision,
                authorizationFingerprint);
    }

    private static List<RuntimeCertificationManifest.Scenario> exactScenarios(
            List<RuntimeCertificationManifest.Scenario> values) {
        if (values == null || values.size()
                != RuntimeCertificationManifest.Scenario.values().length) {
            throw new IllegalArgumentException(
                    "authorization must allow the complete certification scenario inventory");
        }
        List<RuntimeCertificationManifest.Scenario> exact = new ArrayList<>(values);
        exact.sort(Comparator.naturalOrder());
        EnumSet<RuntimeCertificationManifest.Scenario> unique =
                EnumSet.noneOf(RuntimeCertificationManifest.Scenario.class);
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().anyMatch(value -> !unique.add(value))) {
            throw new IllegalArgumentException("authorization scenarios are invalid");
        }
        return List.copyOf(exact);
    }

    static List<MirrorArtifactRef> orderedRefs(List<MirrorArtifactRef> values, String field) {
        if (values == null || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException(field + " must contain between 1 and 64 values");
        }
        List<MirrorArtifactRef> exact = new ArrayList<>(values);
        exact.sort(Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint));
        if (exact.stream().anyMatch(Objects::isNull)
                || exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException(field + " must be unique and non-null");
        }
        return List.copyOf(exact);
    }

    static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " has an invalid kind");
        }
        return value;
    }
}
