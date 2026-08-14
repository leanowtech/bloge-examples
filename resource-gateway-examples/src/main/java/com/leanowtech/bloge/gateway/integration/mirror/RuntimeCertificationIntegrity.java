package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical producer and fail-closed verifier for runtime certification artifacts. */
public final class RuntimeCertificationIntegrity {
    /** Maximum canonical manifest size. */
    public static final int MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical authorization size. */
    public static final int MAXIMUM_AUTHORIZATION_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical report size. */
    public static final int MAXIMUM_REPORT_BYTES = 8 * 1024 * 1024;
    /** Signature domain for destructive execution authorization. */
    public static final String AUTHORIZATION_SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_RUNTIME_CERTIFICATION_EXECUTION_AUTHORIZATION_V1";
    /** Signature domain for the resulting certification report. */
    public static final String REPORT_SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_RUNTIME_CERTIFICATION_REPORT_V1";

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public RuntimeCertificationIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** @return content-addressed immutable manifest */
    public RuntimeCertificationManifest addressManifest(ManifestMaterial material) {
        Objects.requireNonNull(material, "material");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper,
                new ManifestFingerprintMaterial(RuntimeCertificationManifest.SCHEMA_VERSION,
                        "", material), MAXIMUM_MANIFEST_BYTES);
        return manifest(material, fingerprint);
    }

    /** @return externally signed and content-addressed single-use authorization */
    public RuntimeCertificationExecutionAuthorization sealAuthorization(
            AuthorizationMaterial material, VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner authority = requireSigner(signer, "authorization");
        String materialFingerprint = authorizationMaterialFingerprint(material);
        VisualRunEvidenceSeal seal = authority.seal(materialFingerprint,
                "runtime-certification-authorization:" + material.authorizationId()
                        + ":" + material.revision());
        RuntimeCertificationExecutionAuthorization unsigned = authorization(
                material, zeroFingerprint(), seal);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper,
                new AuthorizationFingerprintMaterial(unsigned.schemaVersion(), "",
                        AuthorizationMaterial.from(unsigned), unsigned.authorizationSeal()),
                MAXIMUM_AUTHORIZATION_BYTES);
        return authorization(material, fingerprint, seal);
    }

    /** @return externally signed and content-addressed complete certification report */
    public RuntimeCertificationReport sealReport(
            ReportMaterial material, VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner authority = requireSigner(signer, "report");
        String materialFingerprint = reportMaterialFingerprint(material);
        VisualRunEvidenceSeal seal = authority.seal(materialFingerprint,
                "runtime-certification-report:" + material.reportId()
                        + ":" + material.revision());
        RuntimeCertificationReport unsigned = report(material, zeroFingerprint(), seal);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper,
                new ReportFingerprintMaterial(unsigned.schemaVersion(), "",
                        ReportMaterial.from(unsigned), unsigned.reportSeal()),
                MAXIMUM_REPORT_BYTES);
        return report(material, fingerprint, seal);
    }

    /** @return whether the manifest content address is canonical */
    public boolean canonicalManifestVerified(RuntimeCertificationManifest manifest) {
        if (manifest == null) {
            return false;
        }
        try {
            return manifest.manifestFingerprint().equals(
                    addressManifest(ManifestMaterial.from(manifest)).manifestFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** @return whether authorization content, signature material, and address are canonical */
    public boolean canonicalAuthorizationVerified(
            RuntimeCertificationExecutionAuthorization authorization) {
        if (authorization == null) {
            return false;
        }
        try {
            AuthorizationMaterial material = AuthorizationMaterial.from(authorization);
            String actualMaterial = authorizationMaterialFingerprint(material);
            String actualArtifact = VisualBundleFingerprint.fromCanonicalValue(mapper,
                    new AuthorizationFingerprintMaterial(authorization.schemaVersion(), "",
                            material, authorization.authorizationSeal()),
                    MAXIMUM_AUTHORIZATION_BYTES);
            return actualMaterial.equals(
                    authorization.authorizationSeal().materialFingerprint())
                    && actualArtifact.equals(authorization.authorizationFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** @return whether report content, signature material, and address are canonical */
    public boolean canonicalReportVerified(RuntimeCertificationReport report) {
        if (report == null) {
            return false;
        }
        try {
            ReportMaterial material = ReportMaterial.from(report);
            String actualMaterial = reportMaterialFingerprint(material);
            String actualArtifact = VisualBundleFingerprint.fromCanonicalValue(mapper,
                    new ReportFingerprintMaterial(report.schemaVersion(), "", material,
                            report.reportSeal()), MAXIMUM_REPORT_BYTES);
            return actualMaterial.equals(report.reportSeal().materialFingerprint())
                    && actualArtifact.equals(report.reportFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Verifies one authorization before a customer Adapter may observe or inject any failure.
     */
    public VerificationResult verifyAuthorization(
            RuntimeCertificationManifest manifest,
            RuntimeCertificationExecutionAuthorization authorization,
            VisualEvidenceSigner authorizationAuthority,
            Instant executionStartedAt) {
        if (!canonicalManifestVerified(manifest)) {
            return result(Outcome.INVALID, "MANIFEST_FINGERPRINT_INVALID");
        }
        if (!canonicalAuthorizationVerified(authorization)) {
            return result(Outcome.INVALID, "AUTHORIZATION_FINGERPRINT_INVALID");
        }
        if (!signatureVerified(authorizationAuthority, authorization.authorizationSeal())) {
            return result(Outcome.AUTHORITY_REJECTED, "AUTHORIZATION_AUTHORITY_REJECTED");
        }
        if (!manifest.artifactRef().equals(authorization.manifestRef())
                || !manifest.scope().equals(authorization.scope())
                || manifest.environmentClass() != authorization.environmentClass()
                || !manifest.environmentFingerprint().equals(
                authorization.environmentFingerprint())
                || !manifest.deployment().equals(authorization.deployment())
                || !manifest.scenarios().stream().map(
                RuntimeCertificationManifest.ScenarioRequirement::scenario).toList()
                .equals(authorization.allowedScenarios())) {
            return result(Outcome.IDENTITY_MISMATCH, "AUTHORIZATION_CLOSURE_INVALID");
        }
        if (manifest.environmentClass()
                == RuntimeCertificationManifest.EnvironmentClass.PRODUCTION) {
            return result(Outcome.PRODUCTION_FORBIDDEN, "PRODUCTION_EXECUTION_FORBIDDEN");
        }
        if (!covered(manifest.validFrom(), manifest.expiresAt(), executionStartedAt)
                || !covered(authorization.validFrom(), authorization.expiresAt(),
                executionStartedAt)) {
            return result(Outcome.WINDOW_REJECTED, "AUTHORIZATION_WINDOW_REJECTED");
        }
        return result(Outcome.VERIFIED, "VERIFIED");
    }

    /**
     * Verifies complete authorization, report, component, scenario, and regional-certification
     * closure for an offline release consumer.
     */
    public VerificationResult verifyReport(
            RuntimeCertificationManifest manifest,
            RuntimeCertificationExecutionAuthorization authorization,
            RuntimeCertificationReport report,
            MirrorArtifactRef expectedRegionalCertificationRef,
            MirrorArtifactRef expectedIsolationDecisionRef,
            MirrorArtifactRef expectedIsolationAttestationRef,
            VisualEvidenceSigner authorizationAuthority,
            VisualEvidenceSigner reportAuthority) {
        VerificationResult authorizationResult = verifyAuthorization(manifest, authorization,
                authorizationAuthority, report == null ? null : report.startedAt());
        if (!authorizationResult.verified()) {
            return authorizationResult;
        }
        if (!canonicalReportVerified(report)) {
            return result(Outcome.INVALID, "REPORT_FINGERPRINT_INVALID");
        }
        if (!signatureVerified(reportAuthority, report.reportSeal())) {
            return result(Outcome.AUTHORITY_REJECTED, "REPORT_AUTHORITY_REJECTED");
        }
        if (!manifest.artifactRef().equals(report.manifestRef())
                || !authorization.artifactRef().equals(report.authorizationRef())
                || expectedRegionalCertificationRef == null
                || !expectedRegionalCertificationRef.equals(
                report.regionalDataPlaneCertificationRef())
                || expectedIsolationDecisionRef == null
                || !expectedIsolationDecisionRef.equals(report.isolationDecisionRef())
                || expectedIsolationAttestationRef == null
                || !expectedIsolationAttestationRef.equals(report.isolationAttestationRef())
                || !manifest.scope().equals(report.scope())
                || !manifest.region().equals(report.region())
                || !manifest.deployment().equals(report.deployment())
                || manifest.environmentClass() != report.environmentClass()
                || !manifest.environmentFingerprint().equals(report.environmentFingerprint())
                || report.adapter().environmentClass() != manifest.environmentClass()
                || !report.adapter().environmentFingerprint().equals(
                manifest.environmentFingerprint())) {
            return result(Outcome.IDENTITY_MISMATCH, "REPORT_CLOSURE_INVALID");
        }
        if (!report.adapter().available()
                || !report.adapter().isolatedControlPlane()
                || !report.adapter().productionExecutionDenied()
                || !report.adapter().externalAuthorizationRequired()
                || !report.adapter().durableReplayProtection()) {
            return result(Outcome.ADAPTER_REJECTED, "ADAPTER_SAFETY_CONTROLS_REJECTED");
        }
        if (!manifest.components().equals(report.observedComponents())) {
            return result(Outcome.COMPONENT_REJECTED, "COMPONENT_COORDINATES_DRIFTED");
        }
        if (report.completedAt().isAfter(authorization.expiresAt())
                || report.completedAt().isAfter(manifest.expiresAt())) {
            return result(Outcome.WINDOW_REJECTED, "REPORT_WINDOW_REJECTED");
        }
        long writeAttempts = 0;
        long writeEscapes = 0;
        for (int index = 0; index < manifest.scenarios().size(); index++) {
            RuntimeCertificationManifest.ScenarioRequirement requirement =
                    manifest.scenarios().get(index);
            RuntimeCertificationReport.ScenarioResult observed =
                    report.scenarioResults().get(index);
            if (requirement.scenario() != observed.scenario()) {
                return result(Outcome.SCENARIO_REJECTED,
                        "SCENARIO_DENOMINATOR_MISMATCH");
            }
            List<String> observedCodes = observed.invariantObservations().stream()
                    .map(RuntimeCertificationReport.InvariantObservation::code).toList();
            if (!requirement.requiredInvariantCodes().equals(observedCodes)) {
                return result(Outcome.SCENARIO_REJECTED,
                        "SCENARIO_INVARIANT_DENOMINATOR_MISMATCH");
            }
            Duration elapsed = Duration.between(observed.startedAt(), observed.completedAt());
            Duration recovery = observed.faultRemovedAt() == null
                    || observed.recoveryObservedAt() == null ? Duration.ZERO
                    : Duration.between(observed.faultRemovedAt(),
                    observed.recoveryObservedAt());
            if (observed.startedAt().isBefore(report.startedAt())
                    || observed.completedAt().isAfter(report.completedAt())
                    || elapsed.compareTo(Duration.ofSeconds(
                    requirement.maximumExecutionSeconds())) > 0
                    || recovery.compareTo(Duration.ofSeconds(
                    requirement.maximumRecoverySeconds())) > 0) {
                return result(Outcome.SCENARIO_REJECTED,
                        "SCENARIO_EXECUTION_WINDOW_REJECTED");
            }
            writeAttempts += observed.externalBusinessWriteAttemptCount();
            writeEscapes += observed.writeEscapeCount();
        }
        if (writeAttempts != report.externalBusinessWriteAttemptCount()
                || writeEscapes != report.writeEscapeCount()) {
            return result(Outcome.SCENARIO_REJECTED,
                    "SCENARIO_WRITE_COUNTER_MISMATCH");
        }
        if (report.verdict() != RuntimeCertificationReport.Verdict.CERTIFIED) {
            return result(Outcome.NOT_CERTIFIED, "REQUIRED_SCENARIO_NOT_PASSED");
        }
        return result(Outcome.VERIFIED, "VERIFIED");
    }

    private RuntimeCertificationManifest manifest(
            ManifestMaterial material, String fingerprint) {
        return new RuntimeCertificationManifest("", fingerprint, material.manifestId(),
                material.revision(), material.scope(), material.region(), material.deployment(),
                material.environmentClass(), material.environmentFingerprint(),
                material.components(), material.scenarios(), material.validFrom(),
                material.expiresAt(), material.owner());
    }

    private RuntimeCertificationExecutionAuthorization authorization(
            AuthorizationMaterial material, String fingerprint, VisualRunEvidenceSeal seal) {
        return new RuntimeCertificationExecutionAuthorization("", fingerprint,
                material.authorizationId(), material.revision(), material.manifestRef(),
                material.scope(), material.environmentClass(), material.environmentFingerprint(),
                material.deployment(), material.allowedScenarios(),
                material.destructiveActionsAllowed(), material.productionExecutionDenied(),
                material.singleUse(), material.nonceFingerprint(), material.issuedAt(),
                material.validFrom(), material.expiresAt(), material.issuer(),
                material.approvalRefs(), seal);
    }

    private RuntimeCertificationReport report(
            ReportMaterial material, String fingerprint, VisualRunEvidenceSeal seal) {
        return new RuntimeCertificationReport("", fingerprint, material.reportId(),
                material.revision(), material.manifestRef(), material.authorizationRef(),
                material.authorizationConsumptionRef(),
                material.regionalDataPlaneCertificationRef(), material.isolationDecisionRef(),
                material.isolationAttestationRef(), material.scope(),
                material.region(), material.deployment(), material.environmentClass(),
                material.environmentFingerprint(), material.adapter(),
                material.observedComponents(), material.startedAt(), material.completedAt(),
                material.scenarioResults(), material.verdict(),
                material.externalBusinessWriteAttemptCount(), material.writeEscapeCount(),
                material.issuer(), material.proofRefs(), seal);
    }

    private String authorizationMaterialFingerprint(AuthorizationMaterial material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new SignatureMaterial(AUTHORIZATION_SIGNATURE_DOMAIN,
                        RuntimeCertificationExecutionAuthorization.SCHEMA_VERSION, material),
                MAXIMUM_AUTHORIZATION_BYTES);
    }

    private String reportMaterialFingerprint(ReportMaterial material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new SignatureMaterial(REPORT_SIGNATURE_DOMAIN,
                        RuntimeCertificationReport.SCHEMA_VERSION, material),
                MAXIMUM_REPORT_BYTES);
    }

    private static VisualEvidenceSigner requireSigner(
            VisualEvidenceSigner signer, String purpose) {
        VisualEvidenceSigner exact = Objects.requireNonNull(signer, "signer");
        if (!exact.available()) {
            throw new IllegalArgumentException(
                    "runtime certification " + purpose + " signer is unavailable");
        }
        return exact;
    }

    private static boolean signatureVerified(
            VisualEvidenceSigner authority, VisualRunEvidenceSeal seal) {
        if (authority == null || !authority.available() || seal == null || !seal.signed()) {
            return false;
        }
        try {
            return authority.verify(seal, seal.materialFingerprint()).valid();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean covered(Instant validFrom, Instant expiresAt, Instant at) {
        return at != null && !at.isBefore(validFrom) && at.isBefore(expiresAt);
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private static VerificationResult result(Outcome outcome, String reasonCode) {
        return new VerificationResult(outcome, reasonCode);
    }

    /** Bounded fail-closed verification outcomes. */
    public enum Outcome {
        VERIFIED,
        INVALID,
        AUTHORITY_REJECTED,
        IDENTITY_MISMATCH,
        PRODUCTION_FORBIDDEN,
        WINDOW_REJECTED,
        ADAPTER_REJECTED,
        COMPONENT_REJECTED,
        SCENARIO_REJECTED,
        NOT_CERTIFIED
    }

    /** Payload-free verification result. */
    public record VerificationResult(Outcome outcome, String reasonCode) {
        /** Validates a bounded diagnostic suitable for capability and release gates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("runtime certification reason is invalid");
            }
        }

        /** @return true only when every independent verification check passed */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /** Manifest fields before content addressing. */
    public record ManifestMaterial(
            String manifestId,
            long revision,
            CapabilitySnapshot.Scope scope,
            String region,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            RuntimeCertificationManifest.EnvironmentClass environmentClass,
            String environmentFingerprint,
            List<RuntimeCertificationManifest.ComponentCoordinate> components,
            List<RuntimeCertificationManifest.ScenarioRequirement> scenarios,
            Instant validFrom,
            Instant expiresAt,
            String owner
    ) {
        static ManifestMaterial from(RuntimeCertificationManifest value) {
            return new ManifestMaterial(value.manifestId(), value.revision(), value.scope(),
                    value.region(), value.deployment(), value.environmentClass(),
                    value.environmentFingerprint(), value.components(), value.scenarios(),
                    value.validFrom(), value.expiresAt(), value.owner());
        }
    }

    /** Authorization fields before signing and content addressing. */
    public record AuthorizationMaterial(
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
            List<MirrorArtifactRef> approvalRefs
    ) {
        static AuthorizationMaterial from(
                RuntimeCertificationExecutionAuthorization value) {
            return new AuthorizationMaterial(value.authorizationId(), value.revision(),
                    value.manifestRef(), value.scope(), value.environmentClass(),
                    value.environmentFingerprint(), value.deployment(),
                    value.allowedScenarios(), value.destructiveActionsAllowed(),
                    value.productionExecutionDenied(), value.singleUse(),
                    value.nonceFingerprint(), value.issuedAt(), value.validFrom(),
                    value.expiresAt(), value.issuer(), value.approvalRefs());
        }
    }

    /** Report fields before signing and content addressing. */
    public record ReportMaterial(
            String reportId,
            long revision,
            MirrorArtifactRef manifestRef,
            MirrorArtifactRef authorizationRef,
            MirrorArtifactRef authorizationConsumptionRef,
            MirrorArtifactRef regionalDataPlaneCertificationRef,
            MirrorArtifactRef isolationDecisionRef,
            MirrorArtifactRef isolationAttestationRef,
            CapabilitySnapshot.Scope scope,
            String region,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            RuntimeCertificationManifest.EnvironmentClass environmentClass,
            String environmentFingerprint,
            RuntimeCertificationReport.AdapterDescriptor adapter,
            List<RuntimeCertificationManifest.ComponentCoordinate> observedComponents,
            Instant startedAt,
            Instant completedAt,
            List<RuntimeCertificationReport.ScenarioResult> scenarioResults,
            RuntimeCertificationReport.Verdict verdict,
            long externalBusinessWriteAttemptCount,
            long writeEscapeCount,
            String issuer,
            List<MirrorArtifactRef> proofRefs
    ) {
        static ReportMaterial from(RuntimeCertificationReport value) {
            return new ReportMaterial(value.reportId(), value.revision(), value.manifestRef(),
                    value.authorizationRef(), value.authorizationConsumptionRef(),
                    value.regionalDataPlaneCertificationRef(), value.isolationDecisionRef(),
                    value.isolationAttestationRef(), value.scope(), value.region(),
                    value.deployment(), value.environmentClass(), value.environmentFingerprint(),
                    value.adapter(), value.observedComponents(), value.startedAt(),
                    value.completedAt(), value.scenarioResults(), value.verdict(),
                    value.externalBusinessWriteAttemptCount(), value.writeEscapeCount(),
                    value.issuer(), value.proofRefs());
        }
    }

    private record ManifestFingerprintMaterial(
            String schemaVersion,
            String manifestFingerprint,
            ManifestMaterial material) {
    }

    private record AuthorizationFingerprintMaterial(
            String schemaVersion,
            String authorizationFingerprint,
            AuthorizationMaterial material,
            VisualRunEvidenceSeal authorizationSeal) {
    }

    private record ReportFingerprintMaterial(
            String schemaVersion,
            String reportFingerprint,
            ReportMaterial material,
            VisualRunEvidenceSeal reportSeal) {
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            Object material) {
    }
}
