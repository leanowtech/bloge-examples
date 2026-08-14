package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Canonical producer and independent fail-closed verifier for regional data-plane certification.
 */
public final class RegionalDataPlaneCertificationIntegrity {
    /** Maximum canonical deployment contract size. */
    public static final int MAXIMUM_CONTRACT_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical certification material size. */
    public static final int MAXIMUM_CERTIFICATION_BYTES = 4 * 1024 * 1024;
    /** Signature domain separating regional certification from every other evidence protocol. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_REGIONAL_DATA_PLANE_CERTIFICATION_V1";

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public RegionalDataPlaneCertificationIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Content-addresses one externally approved deployment contract.
     *
     * @param material complete contract material without an asserted fingerprint
     * @return canonical immutable contract
     */
    public RegionalDataPlaneDeploymentContract address(ContractMaterial material) {
        Objects.requireNonNull(material, "material");
        String fingerprint = contractFingerprint(material);
        return new RegionalDataPlaneDeploymentContract("", fingerprint,
                material.contractId(), material.revision(), material.scope(), material.region(),
                material.deployment(), material.requiredComponents(), material.rotationPolicy(),
                material.validFrom(), material.expiresAt(), material.owner());
    }

    /**
     * Signs one observed certification with an externally owned deployment authority.
     *
     * @param material complete payload-free observation material
     * @param signer external Ed25519 deployment-certification signer
     * @return canonical signed certification
     */
    public RegionalDataPlaneCertification seal(
            CertificationMaterial material, VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner authority = Objects.requireNonNull(signer, "signer");
        if (!authority.available()) {
            throw new IllegalArgumentException("regional certification signer is unavailable");
        }
        String materialFingerprint = certificationMaterialFingerprint(material);
        VisualRunEvidenceSeal seal = authority.seal(materialFingerprint);
        RegionalDataPlaneCertification unsigned = certification(
                material, zeroFingerprint(), seal);
        String certificationFingerprint = certificationFingerprint(unsigned);
        return certification(material, certificationFingerprint, seal);
    }

    /** @return whether an untrusted contract has its exact canonical content address */
    public boolean canonicalContractVerified(RegionalDataPlaneDeploymentContract contract) {
        if (contract == null) {
            return false;
        }
        try {
            return contract.contractFingerprint().equals(contractFingerprint(
                    ContractMaterial.from(contract)));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** @return whether both certification material and complete artifact addresses are exact */
    public boolean canonicalCertificationVerified(RegionalDataPlaneCertification certification) {
        if (certification == null) {
            return false;
        }
        try {
            CertificationMaterial material = CertificationMaterial.from(certification);
            return certification.certificationSeal().materialFingerprint().equals(
                    certificationMaterialFingerprint(material))
                    && certification.certificationFingerprint().equals(
                    certificationFingerprint(certification));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Verifies the complete deployment contract, external signature, isolation binding, component
     * observations, rotations, freshness, and zero-write invariant for one execution window.
     *
     * @param contract exact approved deployment contract
     * @param certification externally signed certification
     * @param authorityKey externally pinned certification key
     * @param isolationDecision current v2 isolation decision containing the certification ref
     * @param expectedScope authenticated execution scope
     * @param expectedDeployment immutable local deployment identity
     * @param executionStartedAt complete run-window start
     * @param executionCompletedAt complete run-window end
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            RegionalDataPlaneDeploymentContract contract,
            RegionalDataPlaneCertification certification,
            AuthorityKey authorityKey,
            MirrorDeploymentIsolationAttestationBundle isolationDecision,
            CapabilitySnapshot.Scope expectedScope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity expectedDeployment,
            Instant executionStartedAt,
            Instant executionCompletedAt) {
        Coordinates coordinates = Coordinates.from(certification);
        if (!canonicalContractVerified(contract)) {
            return result(Outcome.INVALID, "CONTRACT_FINGERPRINT_INVALID", coordinates);
        }
        if (!canonicalCertificationVerified(certification)) {
            return result(Outcome.INVALID, "CERTIFICATION_FINGERPRINT_INVALID", coordinates);
        }
        if (authorityKey == null) {
            return result(Outcome.KEY_UNAVAILABLE, "AUTHORITY_KEY_UNAVAILABLE", coordinates);
        }
        if (!authorityKey.keyId().equals(certification.certificationSeal().keyId())
                || !authorityKey.issuer().equals(certification.issuer())
                || !authorityKey.verificationAllowed()
                || !"Ed25519".equals(certification.certificationSeal().algorithm())) {
            return result(Outcome.POLICY_REJECTED, "AUTHORITY_POLICY_REJECTED", coordinates);
        }
        Instant signedAt = certification.certificationSeal().signedAt();
        if (signedAt.isBefore(authorityKey.notBefore())
                || !signedAt.isBefore(authorityKey.notAfter())
                || signedAt.isBefore(certification.observedAt())
                || !signedAt.isBefore(certification.expiresAt())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_SIGNING_WINDOW_REJECTED", coordinates);
        }
        if (!signatureVerified(certification.certificationSeal(), authorityKey)) {
            return result(Outcome.INVALID, "CERTIFICATION_SIGNATURE_INVALID", coordinates);
        }
        if (expectedScope == null || expectedDeployment == null
                || !expectedScope.equals(contract.scope())
                || !expectedScope.equals(certification.scope())
                || !expectedDeployment.equals(contract.deployment())
                || !expectedDeployment.equals(certification.deployment())
                || !contract.region().equals(certification.region())
                || !contract.artifactRef().equals(certification.contractRef())) {
            return result(Outcome.IDENTITY_MISMATCH,
                    "DEPLOYMENT_CONTRACT_IDENTITY_MISMATCH", coordinates);
        }
        if (isolationDecision == null
                || !MirrorDeploymentIsolationAttestationBundle
                .REGIONAL_DATA_PLANE_SCHEMA_VERSION.equals(isolationDecision.schemaVersion())
                || !isolationDecision.scope().equals(expectedScope)
                || !isolationDecision.attestation().material().deployment()
                .equals(expectedDeployment)
                || !certification.artifactRef().equals(
                isolationDecision.regionalDataPlaneCertificationRef())
                || !isolationDecision.active()) {
            return result(Outcome.ISOLATION_MISMATCH,
                    "ISOLATION_DECISION_CERTIFICATION_MISMATCH", coordinates);
        }
        if (!covers(contract.validFrom(), contract.expiresAt(),
                executionStartedAt, executionCompletedAt)
                || !covers(certification.validFrom(), certification.expiresAt(),
                executionStartedAt, executionCompletedAt)) {
            return result(Outcome.WINDOW_REJECTED,
                    "EXECUTION_OUTSIDE_CERTIFIED_WINDOW", coordinates);
        }
        VerificationResult components = verifyComponents(
                contract, certification, executionStartedAt, coordinates);
        if (!components.verified()) {
            return components;
        }
        if (certification.externalBusinessWriteAttemptCount() != 0
                || certification.writeEscapeCount() != 0) {
            return result(Outcome.WRITE_ESCAPE,
                    "EXTERNAL_BUSINESS_WRITE_OBSERVED", coordinates);
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private VerificationResult verifyComponents(
            RegionalDataPlaneDeploymentContract contract,
            RegionalDataPlaneCertification certification,
            Instant executionStartedAt,
            Coordinates coordinates) {
        for (int index = 0; index < contract.requiredComponents().size(); index++) {
            var required = contract.requiredComponents().get(index);
            var observed = certification.componentObservations().get(index);
            if (required.kind() != observed.kind()
                    || !required.authorityId().equals(observed.authorityId())
                    || !required.policyRef().equals(observed.policyRef())
                    || observed.generation() < required.minimumGeneration()) {
                return result(Outcome.COMPONENT_REJECTED,
                        "COMPONENT_COORDINATES_REJECTED", coordinates);
            }
            Duration age = Duration.between(observed.observedAt(), executionStartedAt);
            if (age.isNegative()
                    || observed.observedAt().isAfter(certification.observedAt())
                    || age.compareTo(Duration.ofSeconds(
                    required.maximumObservationAgeSeconds())) > 0) {
                return result(Outcome.COMPONENT_REJECTED,
                        "COMPONENT_OBSERVATION_STALE", coordinates);
            }
            if (observed.status()
                    != RegionalDataPlaneCertification.ComponentStatus.READY
                    || required.privateTransportRequired()
                    && !observed.privateTransportEnforced()
                    || required.failClosedRequired() && !observed.failClosed()
                    || required.regionalResidencyRequired()
                    && !observed.regionalResidencyEnforced()
                    || !observed.externalBusinessWriteDenied()) {
                return result(Outcome.COMPONENT_REJECTED,
                        "COMPONENT_CONTROL_NOT_READY", coordinates);
            }
        }
        for (RegionalDataPlaneCertification.RotationObservation rotation
                : certification.rotationObservations()) {
            if (!rotation.previousGenerationRevoked()
                    || !rotation.allReplicasConverged()
                    || !rotation.staleSessionsDrained()
                    || contract.rotationPolicy().restartFreeRequired()
                    && !rotation.restartFree()
                    || rotation.overlapAchievedSeconds()
                    < contract.rotationPolicy().minimumOverlapSeconds()
                    || rotation.observedAt().isAfter(certification.observedAt())) {
                return result(Outcome.ROTATION_REJECTED,
                        "KEY_OR_CA_ROTATION_NOT_CONVERGED", coordinates);
            }
            long maximumAgeSeconds = rotation.kind()
                    == RegionalDataPlaneCertification.RotationKind.EVIDENCE_KMS_KEY
                    ? contract.rotationPolicy().maximumKmsKeyAgeSeconds()
                    : contract.rotationPolicy().maximumCaAgeSeconds();
            Duration activeAge = Duration.between(
                    rotation.activeGenerationActivatedAt(), executionStartedAt);
            if (activeAge.isNegative()
                    || activeAge.compareTo(Duration.ofSeconds(maximumAgeSeconds)) > 0) {
                return result(Outcome.ROTATION_REJECTED,
                        "ACTIVE_KEY_OR_CA_AGE_REJECTED", coordinates);
            }
            RegionalDataPlaneDeploymentContract.ComponentKind componentKind =
                    rotation.kind() == RegionalDataPlaneCertification.RotationKind
                    .EVIDENCE_KMS_KEY
                            ? RegionalDataPlaneDeploymentContract.ComponentKind.EVIDENCE_KMS
                            : RegionalDataPlaneDeploymentContract.ComponentKind.MUTUAL_TLS;
            long servingGeneration = certification.componentObservations().stream()
                    .filter(value -> value.kind() == componentKind)
                    .findFirst().orElseThrow().generation();
            if (servingGeneration != rotation.activeGeneration()) {
                return result(Outcome.ROTATION_REJECTED,
                        "ROTATION_SERVING_GENERATION_MISMATCH", coordinates);
            }
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private boolean signatureVerified(
            VisualRunEvidenceSeal seal, AuthorityKey authorityKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(
                            authorityKey.encodedPublicKey()))));
            verifier.update(seal.materialFingerprint().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(seal.signature()));
        } catch (Exception invalid) {
            return false;
        }
    }

    private RegionalDataPlaneCertification certification(
            CertificationMaterial material,
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new RegionalDataPlaneCertification("", fingerprint,
                material.certificationId(), material.revision(), material.contractRef(),
                material.scope(), material.region(), material.deployment(), material.observedAt(),
                material.validFrom(), material.expiresAt(), material.componentObservations(),
                material.rotationObservations(), material.externalBusinessWriteAttemptCount(),
                material.writeEscapeCount(), material.issuer(), material.proofRefs(), seal);
    }

    private String contractFingerprint(ContractMaterial material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new ContractFingerprintMaterial(
                        RegionalDataPlaneDeploymentContract.SCHEMA_VERSION, "", material),
                MAXIMUM_CONTRACT_BYTES);
    }

    private String certificationMaterialFingerprint(CertificationMaterial material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new CertificationSignatureMaterial(SIGNATURE_DOMAIN,
                        RegionalDataPlaneCertification.SCHEMA_VERSION, material),
                MAXIMUM_CERTIFICATION_BYTES);
    }

    private String certificationFingerprint(RegionalDataPlaneCertification certification) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new CertificationFingerprintMaterial(certification.schemaVersion(), "",
                        CertificationMaterial.from(certification),
                        certification.certificationSeal()), MAXIMUM_CERTIFICATION_BYTES);
    }

    private static boolean covers(
            Instant validFrom, Instant expiresAt, Instant start, Instant end) {
        return start != null && end != null && !end.isBefore(start)
                && !start.isBefore(validFrom) && end.isBefore(expiresAt);
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    /** Bounded verification outcomes suitable for admission and health reporting. */
    public enum Outcome {
        /** Every independent verification check passed. */
        VERIFIED,
        /** Canonical material or signature was invalid. */
        INVALID,
        /** Exact external authority key was unavailable. */
        KEY_UNAVAILABLE,
        /** Authority lifecycle or signing policy rejected the artifact. */
        POLICY_REJECTED,
        /** Scope, region, deployment, or contract identity disagreed. */
        IDENTITY_MISMATCH,
        /** Isolation decision did not bind this certification. */
        ISOLATION_MISMATCH,
        /** Execution was outside the contract or certification window. */
        WINDOW_REJECTED,
        /** A required component was stale, degraded, or drifted. */
        COMPONENT_REJECTED,
        /** KMS or CA rotation did not converge safely. */
        ROTATION_REJECTED,
        /** An external business write attempt or escape was observed. */
        WRITE_ESCAPE
    }

    /** Externally pinned certification-authority key. */
    public record AuthorityKey(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            String issuer,
            Instant notBefore,
            Instant notAfter,
            KeyState state
    ) {
        /** Validates independent Ed25519 trust material. */
        public AuthorityKey {
            keyId = RegionalDataPlaneDeploymentContract.identifier(keyId, "keyId");
            algorithm = algorithm == null ? "" : algorithm.trim();
            encodedPublicKey = encodedPublicKey == null ? "" : encodedPublicKey.trim();
            issuer = RegionalDataPlaneDeploymentContract.identifier(issuer, "issuer");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            state = Objects.requireNonNull(state, "state");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("regional certification authority key is invalid");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(encodedPublicKey);
                if (decoded.length == 0 || !encodedPublicKey.equals(
                        Base64.getEncoder().encodeToString(decoded))) {
                    throw new IllegalArgumentException("public key is not canonical");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "regional authority public key must be canonical base64", invalid);
            }
        }

        /** @return whether current lifecycle permits verification */
        public boolean verificationAllowed() {
            return state == KeyState.ACTIVE || state == KeyState.RETIRED;
        }
    }

    /** Certification-authority key lifecycle. */
    public enum KeyState {
        /** Key may issue and verify certifications. */
        ACTIVE,
        /** Key may only verify historical certifications. */
        RETIRED,
        /** Key must not be trusted. */
        REVOKED
    }

    /** Payload-free result returned by the verifier. */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String certificationId,
            String certificationFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            certificationId = certificationId == null ? "" : certificationId.trim();
            certificationFingerprint = certificationFingerprint == null
                    ? "" : certificationFingerprint.trim();
            keyId = keyId == null ? "" : keyId.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("regional verification reason is invalid");
            }
        }

        /** @return true only when every independent check passed */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Contract material before content addressing.
     *
     * @param contractId stable contract id
     * @param revision positive contract revision
     * @param scope complete enterprise scope
     * @param region exact regional residency id
     * @param deployment immutable workload coordinates
     * @param requiredComponents all seven exact component requirements
     * @param rotationPolicy KMS and PKI rotation policy
     * @param validFrom inclusive contract validity
     * @param expiresAt exclusive contract validity
     * @param owner external security/SRE owner
     */
    public record ContractMaterial(
            String contractId,
            long revision,
            CapabilitySnapshot.Scope scope,
            String region,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            List<RegionalDataPlaneDeploymentContract.ComponentRequirement> requiredComponents,
            RegionalDataPlaneDeploymentContract.RotationPolicy rotationPolicy,
            Instant validFrom,
            Instant expiresAt,
            String owner
    ) {
        static ContractMaterial from(RegionalDataPlaneDeploymentContract value) {
            return new ContractMaterial(value.contractId(), value.revision(), value.scope(),
                    value.region(), value.deployment(), value.requiredComponents(),
                    value.rotationPolicy(), value.validFrom(), value.expiresAt(), value.owner());
        }
    }

    /**
     * Certification observation material before signing and content addressing.
     *
     * @param certificationId stable certification stream id
     * @param revision positive certification revision
     * @param contractRef exact approved contract
     * @param scope complete enterprise scope
     * @param region exact residency region
     * @param deployment immutable workload coordinates
     * @param observedAt aggregate observation time
     * @param validFrom inclusive certified window
     * @param expiresAt exclusive certified window
     * @param componentObservations exact observations for all required components
     * @param rotationObservations KMS and mTLS rotation drill observations
     * @param externalBusinessWriteAttemptCount observed external business write attempts
     * @param writeEscapeCount writes that escaped physical policy
     * @param issuer external certification authority id
     * @param proofRefs payload-free aggregate evidence references
     */
    public record CertificationMaterial(
            String certificationId,
            long revision,
            MirrorArtifactRef contractRef,
            CapabilitySnapshot.Scope scope,
            String region,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            Instant observedAt,
            Instant validFrom,
            Instant expiresAt,
            List<RegionalDataPlaneCertification.ComponentObservation> componentObservations,
            List<RegionalDataPlaneCertification.RotationObservation> rotationObservations,
            long externalBusinessWriteAttemptCount,
            long writeEscapeCount,
            String issuer,
            List<MirrorArtifactRef> proofRefs
    ) {
        static CertificationMaterial from(RegionalDataPlaneCertification value) {
            return new CertificationMaterial(value.certificationId(), value.revision(),
                    value.contractRef(), value.scope(), value.region(), value.deployment(),
                    value.observedAt(), value.validFrom(), value.expiresAt(),
                    value.componentObservations(), value.rotationObservations(),
                    value.externalBusinessWriteAttemptCount(), value.writeEscapeCount(),
                    value.issuer(), value.proofRefs());
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reasonCode, Coordinates coordinates) {
        return new VerificationResult(outcome, reasonCode, coordinates.certificationId(),
                coordinates.certificationFingerprint(), coordinates.keyId());
    }

    private record Coordinates(
            String certificationId,
            String certificationFingerprint,
            String keyId) {
        static Coordinates from(RegionalDataPlaneCertification value) {
            return value == null ? new Coordinates("", "", "")
                    : new Coordinates(value.certificationId(), value.certificationFingerprint(),
                    value.certificationSeal().keyId());
        }
    }

    private record ContractFingerprintMaterial(
            String schemaVersion,
            String contractFingerprint,
            ContractMaterial material) {
    }

    private record CertificationSignatureMaterial(
            String domain,
            String schemaVersion,
            CertificationMaterial material) {
    }

    private record CertificationFingerprintMaterial(
            String schemaVersion,
            String certificationFingerprint,
            CertificationMaterial material,
            VisualRunEvidenceSeal certificationSeal) {
    }
}
