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
 * Short-lived, externally signed and payload-free certification of a regional data plane.
 *
 * <p>The certification reports observations rather than declaring its own readiness. The
 * independent verifier compares it with an exact deployment contract and rejects every missing,
 * stale, degraded, non-converged, or write-escaping condition.</p>
 */
public record RegionalDataPlaneCertification(
        String schemaVersion,
        String certificationFingerprint,
        String certificationId,
        long revision,
        MirrorArtifactRef contractRef,
        CapabilitySnapshot.Scope scope,
        String region,
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
        Instant observedAt,
        Instant validFrom,
        Instant expiresAt,
        List<ComponentObservation> componentObservations,
        List<RotationObservation> rotationObservations,
        long externalBusinessWriteAttemptCount,
        long writeEscapeCount,
        String issuer,
        List<MirrorArtifactRef> proofRefs,
        VisualRunEvidenceSeal certificationSeal
) {
    /** Current regional certification protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.regionalDataPlaneCertification.v1";
    /** Artifact kind used by isolation bundle v2. */
    public static final String ARTIFACT_KIND = "REGIONAL_DATA_PLANE_CERTIFICATION";
    /** Maximum lifetime of one observed certification. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);

    /** Validates bounded syntax while preserving negative observations for fail-closed review. */
    public RegionalDataPlaneCertification {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported regional certification version");
        }
        certificationFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                certificationFingerprint, "certificationFingerprint");
        certificationId = RegionalDataPlaneDeploymentContract.identifier(
                certificationId, "certificationId");
        if (revision < 1) {
            throw new IllegalArgumentException("certification revision must be positive");
        }
        contractRef = requireKind(contractRef,
                RegionalDataPlaneDeploymentContract.ARTIFACT_KIND, "contractRef");
        scope = Objects.requireNonNull(scope, "scope");
        region = RegionalDataPlaneDeploymentContract.identifier(region, "region");
        deployment = Objects.requireNonNull(deployment, "deployment");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (validFrom.isBefore(observedAt) || !expiresAt.isAfter(validFrom)
                || Duration.between(observedAt, expiresAt)
                .compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("regional certification window is invalid");
        }
        componentObservations = orderedComponents(componentObservations);
        rotationObservations = orderedRotations(rotationObservations);
        if (externalBusinessWriteAttemptCount < 0 || writeEscapeCount < 0) {
            throw new IllegalArgumentException("regional write counters cannot be negative");
        }
        issuer = RegionalDataPlaneDeploymentContract.identifier(issuer, "issuer");
        proofRefs = orderedProofs(proofRefs);
        certificationSeal = Objects.requireNonNull(certificationSeal, "certificationSeal");
    }

    /** @return exact immutable certification reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, certificationId, revision,
                certificationFingerprint);
    }

    /** Bounded runtime status observed by the external deployment authority. */
    public enum ComponentStatus {
        /** Component met its exact contract at observation time. */
        READY,
        /** Component remained reachable but violated at least one readiness property. */
        DEGRADED,
        /** Component could not serve governed traffic. */
        UNAVAILABLE,
        /** Component generation or authority was explicitly revoked. */
        REVOKED
    }

    /**
     * Exact observation for one contract component.
     *
     * @param kind component kind
     * @param authorityId observed authority identity
     * @param policyRef exact effective policy
     * @param generation observed monotonic serving generation
     * @param status bounded readiness status
     * @param observedAt component observation time
     * @param privateTransportEnforced whether transport is private and authenticated
     * @param failClosed whether authority or policy loss denies use
     * @param regionalResidencyEnforced whether data residency matches the contract region
     * @param externalBusinessWriteDenied whether writes to external business systems are denied
     * @param identityFingerprint effective service/workload identity fingerprint
     * @param proofRefs payload-free observation evidence
     */
    public record ComponentObservation(
            RegionalDataPlaneDeploymentContract.ComponentKind kind,
            String authorityId,
            MirrorArtifactRef policyRef,
            long generation,
            ComponentStatus status,
            Instant observedAt,
            boolean privateTransportEnforced,
            boolean failClosed,
            boolean regionalResidencyEnforced,
            boolean externalBusinessWriteDenied,
            String identityFingerprint,
            List<MirrorArtifactRef> proofRefs
    ) {
        /** Validates one bounded payload-free observation. */
        public ComponentObservation {
            kind = Objects.requireNonNull(kind, "kind");
            authorityId = RegionalDataPlaneDeploymentContract.identifier(
                    authorityId, "authorityId");
            policyRef = Objects.requireNonNull(policyRef, "policyRef");
            if (generation < 1) {
                throw new IllegalArgumentException("component generation must be positive");
            }
            status = Objects.requireNonNull(status, "status");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            identityFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    identityFingerprint, "identityFingerprint");
            proofRefs = orderedProofs(proofRefs);
        }
    }

    /** Key material classes whose rotation must be proven independently. */
    public enum RotationKind {
        /** KMS/HSM evidence-signing key rotation. */
        EVIDENCE_KMS_KEY,
        /** Private PKI mTLS CA rotation. */
        MUTUAL_TLS_CA
    }

    /**
     * Payload-free rotation drill result.
     *
     * @param kind key material class
     * @param previousGeneration prior generation
     * @param activeGeneration successor generation
     * @param activeGenerationActivatedAt activation time of the successor generation
     * @param overlapAchievedSeconds observed dual-trust overlap before revoking the predecessor
     * @param previousGenerationRevoked whether old material can no longer authenticate/sign
     * @param allReplicasConverged whether every serving replica acknowledged the successor
     * @param staleSessionsDrained whether old TLS/key sessions were evicted
     * @param restartFree whether convergence occurred without service restart
     * @param observedAt rotation drill observation time
     * @param proofRefs payload-free rotation evidence
     */
    public record RotationObservation(
            RotationKind kind,
            long previousGeneration,
            long activeGeneration,
            Instant activeGenerationActivatedAt,
            long overlapAchievedSeconds,
            boolean previousGenerationRevoked,
            boolean allReplicasConverged,
            boolean staleSessionsDrained,
            boolean restartFree,
            Instant observedAt,
            List<MirrorArtifactRef> proofRefs
    ) {
        /** Validates monotonic generation syntax while retaining failed drill states. */
        public RotationObservation {
            kind = Objects.requireNonNull(kind, "kind");
            if (previousGeneration < 1 || activeGeneration <= previousGeneration) {
                throw new IllegalArgumentException("rotation generations are invalid");
            }
            activeGenerationActivatedAt = Objects.requireNonNull(
                    activeGenerationActivatedAt, "activeGenerationActivatedAt");
            if (overlapAchievedSeconds < 0) {
                throw new IllegalArgumentException("rotation overlap cannot be negative");
            }
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            if (activeGenerationActivatedAt.isAfter(observedAt)) {
                throw new IllegalArgumentException(
                        "active generation cannot be newer than its observation");
            }
            proofRefs = orderedProofs(proofRefs);
        }
    }

    private static List<ComponentObservation> orderedComponents(
            List<ComponentObservation> values) {
        int expected = RegionalDataPlaneDeploymentContract.ComponentKind.values().length;
        if (values == null || values.size() != expected) {
            throw new IllegalArgumentException("regional component observations are incomplete");
        }
        List<ComponentObservation> exact = new ArrayList<>(values);
        if (exact.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("regional component observations are invalid");
        }
        exact.sort(Comparator.comparing(ComponentObservation::kind));
        EnumSet<RegionalDataPlaneDeploymentContract.ComponentKind> kinds =
                EnumSet.noneOf(RegionalDataPlaneDeploymentContract.ComponentKind.class);
        exact.forEach(value -> {
            if (value == null || !kinds.add(value.kind())) {
                throw new IllegalArgumentException("regional component observations are invalid");
            }
        });
        return List.copyOf(exact);
    }

    private static List<RotationObservation> orderedRotations(
            List<RotationObservation> values) {
        if (values == null || values.size() != RotationKind.values().length) {
            throw new IllegalArgumentException("both KMS and mTLS rotations must be observed");
        }
        List<RotationObservation> exact = new ArrayList<>(values);
        if (exact.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("regional rotation observations are invalid");
        }
        exact.sort(Comparator.comparing(RotationObservation::kind));
        EnumSet<RotationKind> kinds = EnumSet.noneOf(RotationKind.class);
        exact.forEach(value -> {
            if (value == null || !kinds.add(value.kind())) {
                throw new IllegalArgumentException("regional rotation observations are invalid");
            }
        });
        return List.copyOf(exact);
    }

    private static List<MirrorArtifactRef> orderedProofs(List<MirrorArtifactRef> values) {
        if (values == null || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("proofRefs must contain between 1 and 64 values");
        }
        List<MirrorArtifactRef> exact = new ArrayList<>(values);
        if (exact.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("proofRefs must be unique and non-null");
        }
        exact.sort(Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint));
        if (exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException("proofRefs must be unique and non-null");
        }
        return List.copyOf(exact);
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " has an invalid artifact kind");
        }
        return exact;
    }
}
