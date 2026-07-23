package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Seals and verifies portable mirror evidence without retaining business payload values.
 *
 * <p>The signature covers a domain-separated canonical material value containing the run, plan,
 * complete evidence fingerprint, and signing time. The service immediately verifies every newly
 * produced signature and separately fingerprints the complete portable bundle.</p>
 */
public final class MirrorEvidenceIntegrityService {
    /** Stable failure when no signing authority can establish evidence integrity. */
    public static final String SIGNER_UNAVAILABLE = "MIRROR_EVIDENCE_SIGNER_UNAVAILABLE";
    /** Stable failure when canonical evidence or nested resolution integrity is invalid. */
    public static final String MATERIAL_INVALID = "MIRROR_EVIDENCE_MATERIAL_INVALID";
    /** Stable failure when a newly produced detached signature cannot be verified. */
    public static final String SIGNATURE_INVALID = "MIRROR_EVIDENCE_SIGNATURE_INVALID";
    /** Maximum canonical run evidence admitted to signing and verification. */
    public static final int MAXIMUM_EVIDENCE_BYTES = 64 * 1024 * 1024;
    /** Maximum canonical portable bundle admitted to signing and verification. */
    public static final int MAXIMUM_BUNDLE_BYTES = 72 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final String SIGNATURE_DOMAIN_V1 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V1";
    private static final String SIGNATURE_DOMAIN_V2 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V2";
    private static final String SIGNATURE_DOMAIN_V3 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V3";

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a mirror evidence integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer local or managed Ed25519 signing authority
     * @param clock server signing clock
     */
    public MirrorEvidenceIntegrityService(
            ObjectMapper mapper, VisualEvidenceSigner signer, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Canonically snapshots, signs, immediately verifies, and bundles one terminal evidence value.
     *
     * @param evidence complete payload-free mirror evidence
     * @return verified bundle or a fail-closed bounded result
     */
    public SealResult seal(MirrorRunEvidence evidence) {
        MirrorRunEvidence snapshot;
        String evidenceFingerprint;
        try {
            snapshot = canonicalSnapshot(evidence);
            verifyNestedResolutions(snapshot);
            evidenceFingerprint = fingerprint(snapshot, MAXIMUM_EVIDENCE_BYTES);
        } catch (RuntimeException invalid) {
            return SealResult.failed(Objects.requireNonNull(evidence, "evidence"), null,
                    MATERIAL_INVALID);
        }
        MirrorEvidenceAttestation unavailable = MirrorEvidenceAttestation.unavailable(
                snapshot, evidenceFingerprint);
        if (!signer.available()) {
            return SealResult.failed(snapshot, unavailable, SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            if (Instant.EPOCH.equals(signedAt) || signedAt.isBefore(snapshot.completedAt())) {
                return SealResult.failed(snapshot, unavailable, MATERIAL_INVALID);
            }
            String attestationVersion = attestationVersion(
                    snapshot.schemaVersion());
            String materialFingerprint = signatureMaterialFingerprint(attestationVersion,
                    snapshot.runId(), snapshot.planFingerprint(), evidenceFingerprint, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            String bundleVersion = bundleVersion(
                    snapshot.schemaVersion());
            MirrorEvidenceAttestation attestation = new MirrorEvidenceAttestation(
                    attestationVersion,
                    MirrorEvidenceAttestation.SignatureStatus.VERIFIED,
                    snapshot.runId(), snapshot.planFingerprint(), evidenceFingerprint, signedAt,
                    seal.keyId(), seal.algorithm(), seal.signature(), true);
            if (!verifyAttestation(attestation)) {
                return SealResult.failed(snapshot, unavailable, SIGNATURE_INVALID);
            }
            BundleMaterial bundleMaterial = new BundleMaterial(
                    bundleVersion,
                    MirrorEvidenceBundle.PayloadPolicy.HASH_ONLY, attestation, snapshot);
            String bundleFingerprint = fingerprint(bundleMaterial, MAXIMUM_BUNDLE_BYTES);
            MirrorEvidenceBundle bundle = new MirrorEvidenceBundle(bundleVersion, bundleFingerprint,
                    MirrorEvidenceBundle.PayloadPolicy.HASH_ONLY, attestation, snapshot);
            if (verify(bundle) != Verification.VERIFIED) {
                return SealResult.failed(snapshot, unavailable, SIGNATURE_INVALID);
            }
            return SealResult.verified(bundle);
        } catch (RuntimeException failure) {
            return SealResult.failed(snapshot, unavailable, SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Independently recomputes nested, evidence, signature-material, and bundle integrity.
     *
     * @param bundle portable mirror evidence bundle
     * @return bounded verification outcome
     */
    public Verification verify(MirrorEvidenceBundle bundle) {
        if (bundle == null || !MirrorEvidenceBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())
                && !MirrorEvidenceBundle.SCHEMA_VERSION_V1.equals(bundle.schemaVersion())
                && !MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION.equals(
                bundle.schemaVersion())) {
            return Verification.INVALID;
        }
        try {
            MirrorRunEvidence evidence = canonicalSnapshot(bundle.evidence());
            verifyNestedResolutions(evidence);
            String evidenceFingerprint = fingerprint(evidence, MAXIMUM_EVIDENCE_BYTES);
            MirrorEvidenceAttestation attestation = bundle.attestation();
            if (!evidenceFingerprint.equals(attestation.evidenceFingerprint())
                    || !evidence.runId().equals(attestation.runId())
                    || !evidence.planFingerprint().equals(attestation.planFingerprint())
                    || attestation.signedAt().isBefore(evidence.completedAt())) {
                return Verification.INVALID;
            }
            BundleMaterial bundleMaterial = new BundleMaterial(bundle.schemaVersion(),
                    bundle.payloadPolicy(), attestation, evidence);
            if (!fingerprint(bundleMaterial, MAXIMUM_BUNDLE_BYTES)
                    .equals(bundle.bundleFingerprint())) {
                return Verification.INVALID;
            }
            if (!signer.available()) {
                return Verification.UNAVAILABLE;
            }
            return verifyAttestation(attestation)
                    ? Verification.VERIFIED : Verification.INVALID;
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
    }

    /**
     * Returns a canonical independently owned snapshot before any identity is trusted.
     *
     * @param evidence caller-owned evidence
     * @return detached protocol value
     */
    public MirrorRunEvidence canonicalSnapshot(MirrorRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try {
            return mapper.readValue(mapper.writeValueAsBytes(evidence), MirrorRunEvidence.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("mirror evidence cannot be canonically detached", failure);
        }
    }

    private void verifyNestedResolutions(MirrorRunEvidence evidence) {
        for (MirrorResolution resolution : evidence.resolutions()) {
            MirrorResolutionIntegrity.verify(mapper, resolution);
        }
        if (evidence.stateEvidence() != null) {
            MirrorStateRunEvidenceIntegrity.verify(
                    mapper, evidence.stateEvidence());
        }
    }

    private boolean verifyAttestation(MirrorEvidenceAttestation attestation) {
        if (!attestation.independentlyVerifiable()
                || attestation.signatureStatus()
                != MirrorEvidenceAttestation.SignatureStatus.VERIFIED) {
            return false;
        }
        String materialFingerprint = signatureMaterialFingerprint(attestation.schemaVersion(),
                attestation.runId(),
                attestation.planFingerprint(), attestation.evidenceFingerprint(),
                attestation.signedAt());
        VisualEvidenceSigner.Verification verification = signer.verify(
                new VisualRunEvidenceSeal("", materialFingerprint, attestation.algorithm(),
                        attestation.keyId(), attestation.signedAt(), attestation.signature()),
                materialFingerprint);
        return verification.valid();
    }

    private String signatureMaterialFingerprint(
            String schemaVersion,
            String runId,
            String planFingerprint,
            String evidenceFingerprint,
            Instant signedAt) {
        String domain = switch (schemaVersion) {
            case MirrorEvidenceAttestation.SCHEMA_VERSION_V1 -> SIGNATURE_DOMAIN_V1;
            case MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION -> SIGNATURE_DOMAIN_V3;
            case MirrorEvidenceAttestation.SCHEMA_VERSION -> SIGNATURE_DOMAIN_V2;
            default -> throw new IllegalArgumentException(
                    "unsupported mirror evidence attestation version");
        };
        return fingerprint(new SignatureMaterial(domain,
                schemaVersion, runId, planFingerprint,
                evidenceFingerprint, signedAt), MAXIMUM_SIGNATURE_MATERIAL_BYTES);
    }

    private static String attestationVersion(
            String evidenceVersion) {
        return switch (evidenceVersion) {
            case MirrorRunEvidence.SCHEMA_VERSION_V1 ->
                    MirrorEvidenceAttestation.SCHEMA_VERSION_V1;
            case MirrorRunEvidence.STATEFUL_SCHEMA_VERSION ->
                    MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION;
            case MirrorRunEvidence.SCHEMA_VERSION ->
                    MirrorEvidenceAttestation.SCHEMA_VERSION;
            default -> throw new IllegalArgumentException(
                    "unsupported mirror run evidence version");
        };
    }

    private static String bundleVersion(String evidenceVersion) {
        return switch (evidenceVersion) {
            case MirrorRunEvidence.SCHEMA_VERSION_V1 ->
                    MirrorEvidenceBundle.SCHEMA_VERSION_V1;
            case MirrorRunEvidence.STATEFUL_SCHEMA_VERSION ->
                    MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION;
            case MirrorRunEvidence.SCHEMA_VERSION ->
                    MirrorEvidenceBundle.SCHEMA_VERSION;
            default -> throw new IllegalArgumentException(
                    "unsupported mirror run evidence version");
        };
    }

    private String fingerprint(Object value, int maximumBytes) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, maximumBytes);
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            String runId,
            String planFingerprint,
            String evidenceFingerprint,
            Instant signedAt
    ) {
    }

    private record BundleMaterial(
            String schemaVersion,
            MirrorEvidenceBundle.PayloadPolicy payloadPolicy,
            MirrorEvidenceAttestation attestation,
            MirrorRunEvidence evidence
    ) {
    }

    /** Independent trust outcome with invalid material separated from verifier availability. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    /**
     * Fail-closed result of one signing attempt.
     *
     * @param evidence canonical payload-free evidence associated with the attempt
     * @param bundle verified portable bundle, or {@code null} on failure
     * @param attestation verified or unavailable integrity manifest
     * @param failureCode stable machine-readable failure code, blank on success
     */
    public record SealResult(
            MirrorRunEvidence evidence,
            MirrorEvidenceBundle bundle,
            MirrorEvidenceAttestation attestation,
            String failureCode
    ) {
        /** Normalizes one bounded signing result. */
        public SealResult {
            evidence = Objects.requireNonNull(evidence, "evidence");
            attestation = Objects.requireNonNull(attestation, "attestation");
            failureCode = failureCode == null ? "" : failureCode.trim();
            if (failureCode.isBlank() != (bundle != null)) {
                throw new IllegalArgumentException(
                        "successful mirror evidence sealing requires exactly one bundle");
            }
        }

        /** @return successful result for one immediately verified portable bundle */
        public static SealResult verified(MirrorEvidenceBundle bundle) {
            Objects.requireNonNull(bundle, "bundle");
            return new SealResult(bundle.evidence(), bundle, bundle.attestation(), "");
        }

        /** @return failed result carrying no portable bundle */
        public static SealResult failed(
                MirrorRunEvidence evidence,
                MirrorEvidenceAttestation attestation,
                String failureCode) {
            String normalized = failureCode == null ? "" : failureCode.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("mirror evidence failure code is required");
            }
            MirrorEvidenceAttestation safe = attestation;
            if (safe == null) {
                String fallbackFingerprint = "sha256:" + "0".repeat(64);
                safe = MirrorEvidenceAttestation.unavailable(evidence, fallbackFingerprint);
            }
            return new SealResult(evidence, null, safe, normalized);
        }

        /** @return true only when a verified portable bundle was produced */
        public boolean verified() {
            return bundle != null && failureCode.isBlank()
                    && attestation.independentlyVerifiable();
        }
    }
}
