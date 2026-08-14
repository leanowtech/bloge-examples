package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressed, payload-free replay package for one runtime certification.
 *
 * <p>The bundle carries the complete BM-012 regional trust closure together with the BM-013
 * manifest, single-use authorization, and signed report. It is an export envelope, not a new
 * authority: independent consumers still verify every constituent content address and seal.</p>
 */
public record RuntimeCertificationReplayBundle(
        String schemaVersion,
        String bundleFingerprint,
        String bundleId,
        long revision,
        RuntimeCertificationManifest manifest,
        RuntimeCertificationExecutionAuthorization authorization,
        RuntimeCertificationReport report,
        RegionalDataPlaneDeploymentContract regionalContract,
        RegionalDataPlaneCertification regionalCertification,
        MirrorDeploymentIsolationAttestationBundle isolationDecision,
        Instant exportedAt,
        String exporter
) {
    /** Current replay-bundle protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.runtimeCertificationReplayBundle.v1";
    /** Artifact kind used by release evidence indexes. */
    public static final String ARTIFACT_KIND = "RUNTIME_CERTIFICATION_REPLAY_BUNDLE";

    /** Validates exact constituent identity closure without trusting their signatures. */
    public RuntimeCertificationReplayBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported runtime certification replay bundle version");
        }
        bundleFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                bundleFingerprint, "bundleFingerprint");
        bundleId = RegionalDataPlaneDeploymentContract.identifier(bundleId, "bundleId");
        if (revision < 1) {
            throw new IllegalArgumentException("replay bundle revision must be positive");
        }
        manifest = Objects.requireNonNull(manifest, "manifest");
        authorization = Objects.requireNonNull(authorization, "authorization");
        report = Objects.requireNonNull(report, "report");
        regionalContract = Objects.requireNonNull(regionalContract, "regionalContract");
        regionalCertification = Objects.requireNonNull(
                regionalCertification, "regionalCertification");
        isolationDecision = Objects.requireNonNull(isolationDecision, "isolationDecision");
        exportedAt = Objects.requireNonNull(exportedAt, "exportedAt");
        exporter = RegionalDataPlaneDeploymentContract.identifier(exporter, "exporter");
        if (!manifest.artifactRef().equals(authorization.manifestRef())
                || !manifest.artifactRef().equals(report.manifestRef())
                || !authorization.artifactRef().equals(report.authorizationRef())
                || !regionalContract.artifactRef().equals(
                regionalCertification.contractRef())
                || !regionalCertification.artifactRef().equals(
                report.regionalDataPlaneCertificationRef())
                || !regionalCertification.artifactRef().equals(
                isolationDecision.regionalDataPlaneCertificationRef())
                || !isolationDecision.artifactRef().equals(report.isolationDecisionRef())
                || !isolationDecision.attestation().artifactRef().equals(
                report.isolationAttestationRef())
                || !manifest.scope().equals(regionalContract.scope())
                || !manifest.scope().equals(regionalCertification.scope())
                || !manifest.scope().equals(isolationDecision.scope())
                || !manifest.deployment().equals(regionalContract.deployment())
                || !manifest.deployment().equals(regionalCertification.deployment())
                || !manifest.deployment().equals(
                isolationDecision.attestation().material().deployment())) {
            throw new IllegalArgumentException(
                    "runtime certification replay bundle closure is invalid");
        }
    }

    /** @return exact immutable replay-bundle reference */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, bundleId, revision, bundleFingerprint);
    }
}
