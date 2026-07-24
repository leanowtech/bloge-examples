package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Seals and verifies terminal Scenario batch indexes under an independent signature domain.
 */
public final class ScenarioRehearsalBatchEvidenceIntegrityService {
    /** Stable failure when no signing authority can establish batch integrity. */
    public static final String SIGNER_UNAVAILABLE =
            "SCENARIO_REHEARSAL_BATCH_EVIDENCE_SIGNER_UNAVAILABLE";
    /** Stable failure when nested batch material or content addresses are invalid. */
    public static final String MATERIAL_INVALID =
            "SCENARIO_REHEARSAL_BATCH_EVIDENCE_MATERIAL_INVALID";
    /** Stable failure when a detached signature cannot be verified immediately. */
    public static final String SIGNATURE_INVALID =
            "SCENARIO_REHEARSAL_BATCH_EVIDENCE_SIGNATURE_INVALID";
    private static final int MAXIMUM_REQUEST_BYTES =
            2 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES =
            8 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_EVIDENCE_V1";

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates the batch evidence integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed Ed25519 signing authority and verification key ring
     * @param clock server signing clock
     */
    public ScenarioRehearsalBatchEvidenceIntegrityService(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null
                ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Canonically snapshots, signs, immediately verifies, and bundles one terminal batch index.
     *
     * @param request original strict payload-free request
     * @param manifest immutable exact-plan closure
     * @param job terminal integrity-sealed job
     * @param items complete ordered terminal item index
     * @return verified bundle or fail-closed bounded result
     */
    public SealResult seal(
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchJob job,
            List<ScenarioRehearsalBatchItemPage.Item> items) {
        ScenarioRehearsalBatchEvidenceIndex index;
        try {
            ScenarioRehearsalBatchEvidenceIndex material =
                    canonicalSnapshot(
                            new ScenarioRehearsalBatchEvidenceIndex(
                                    "",
                                    "",
                                    request,
                                    manifest,
                                    job,
                                    items));
            verifySourceMaterial(material);
            index = material.withFingerprint(
                    fingerprint(
                            material.withFingerprint(""),
                            ScenarioRehearsalBatchEvidenceIndex
                                    .MAXIMUM_CANONICAL_BYTES));
        } catch (RuntimeException invalid) {
            return SealResult.failed(
                    safeIndex(request, manifest, job, items),
                    null,
                    MATERIAL_INVALID);
        }
        ScenarioRehearsalBatchEvidenceAttestation unavailable =
                ScenarioRehearsalBatchEvidenceAttestation
                        .unavailable(index);
        if (!signer.available()) {
            return SealResult.failed(
                    index, unavailable, SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            if (Instant.EPOCH.equals(signedAt)
                    || signedAt.isBefore(job.completedAt())) {
                return SealResult.failed(
                        index, unavailable, MATERIAL_INVALID);
            }
            String materialFingerprint =
                    signatureMaterialFingerprint(index, signedAt);
            VisualRunEvidenceSeal seal =
                    signer.seal(materialFingerprint);
            ScenarioRehearsalBatchEvidenceAttestation attestation =
                    new ScenarioRehearsalBatchEvidenceAttestation(
                            ScenarioRehearsalBatchEvidenceAttestation
                                    .SCHEMA_VERSION,
                            ScenarioRehearsalBatchEvidenceAttestation
                                    .SignatureStatus.VERIFIED,
                            job.jobId(),
                            job.requestFingerprint(),
                            manifest.manifestFingerprint(),
                            job.recordFingerprint(),
                            index.indexFingerprint(),
                            signedAt,
                            seal.keyId(),
                            seal.algorithm(),
                            seal.signature(),
                            true);
            if (!verifyAttestation(attestation)) {
                return SealResult.failed(
                        index, unavailable, SIGNATURE_INVALID);
            }
            BundleMaterial bundleMaterial =
                    new BundleMaterial(
                            ScenarioRehearsalBatchEvidenceBundle
                                    .SCHEMA_VERSION,
                            ScenarioRehearsalBatchEvidenceBundle
                                    .PayloadPolicy.HASH_ONLY,
                            attestation,
                            index);
            ScenarioRehearsalBatchEvidenceBundle bundle =
                    new ScenarioRehearsalBatchEvidenceBundle(
                            ScenarioRehearsalBatchEvidenceBundle
                                    .SCHEMA_VERSION,
                            fingerprint(
                                    bundleMaterial,
                                    ScenarioRehearsalBatchEvidenceBundle
                                            .MAXIMUM_CANONICAL_BYTES),
                            ScenarioRehearsalBatchEvidenceBundle
                                    .PayloadPolicy.HASH_ONLY,
                            attestation,
                            index);
            if (verify(bundle) != Verification.VERIFIED) {
                return SealResult.failed(
                        index, unavailable, SIGNATURE_INVALID);
            }
            return SealResult.verified(bundle);
        } catch (RuntimeException failure) {
            return SealResult.failed(
                    index, unavailable, SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes nested content addresses, detached signature, and bundle identity.
     *
     * @param bundle caller or repository supplied portable evidence
     * @return independent verification outcome
     */
    public Verification verify(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        if (bundle == null
                || !ScenarioRehearsalBatchEvidenceBundle.SCHEMA_VERSION
                .equals(bundle.schemaVersion())) {
            return Verification.INVALID;
        }
        try {
            ScenarioRehearsalBatchEvidenceIndex index =
                    canonicalSnapshot(bundle.index());
            verifySourceMaterial(index);
            String expectedIndexFingerprint =
                    fingerprint(
                            index.withFingerprint(""),
                            ScenarioRehearsalBatchEvidenceIndex
                                    .MAXIMUM_CANONICAL_BYTES);
            ScenarioRehearsalBatchEvidenceAttestation attestation =
                    bundle.attestation();
            if (!expectedIndexFingerprint.equals(
                    index.indexFingerprint())
                    || !attestation.jobId().equals(
                    index.job().jobId())
                    || !attestation.requestFingerprint().equals(
                    index.job().requestFingerprint())
                    || !attestation.manifestFingerprint().equals(
                    index.manifest().manifestFingerprint())
                    || !attestation.terminalJobFingerprint().equals(
                    index.job().recordFingerprint())
                    || !attestation.indexFingerprint().equals(
                    index.indexFingerprint())
                    || attestation.signedAt().isBefore(
                    index.job().completedAt())) {
                return Verification.INVALID;
            }
            BundleMaterial material = new BundleMaterial(
                    bundle.schemaVersion(),
                    bundle.payloadPolicy(),
                    attestation,
                    index);
            if (!fingerprint(
                    material,
                    ScenarioRehearsalBatchEvidenceBundle
                            .MAXIMUM_CANONICAL_BYTES)
                    .equals(bundle.bundleFingerprint())) {
                return Verification.INVALID;
            }
            if (!signer.available()) {
                return Verification.UNAVAILABLE;
            }
            return verifyAttestation(attestation)
                    ? Verification.VERIFIED
                    : Verification.INVALID;
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
    }

    /**
     * Verifies and canonically detaches a bundle before trusted consumption.
     *
     * @param bundle caller or repository supplied portable evidence
     * @return capability token containing a verified detached bundle
     * @throws IllegalArgumentException when material or signature is invalid
     * @throws IllegalStateException when verification authority is unavailable
     */
    public VerifiedBundle requireVerified(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        Verification verification = verify(bundle);
        if (verification == Verification.UNAVAILABLE) {
            throw new IllegalStateException(
                    "Scenario batch evidence verification authority is unavailable");
        }
        if (verification != Verification.VERIFIED) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence bundle is not verified");
        }
        return new VerifiedBundle(
                new ScenarioRehearsalBatchEvidenceBundle(
                        bundle.schemaVersion(),
                        bundle.bundleFingerprint(),
                        bundle.payloadPolicy(),
                        bundle.attestation(),
                        canonicalSnapshot(bundle.index())));
    }

    private void verifySourceMaterial(
            ScenarioRehearsalBatchEvidenceIndex index) {
        ScenarioRehearsalBatchIntegrity.verify(
                mapper, index.job());
        ScenarioRehearsalBatchManifestIntegrity.verify(
                mapper, index.manifest());
        String requestFingerprint = ProtocolFingerprint.ofBounded(
                mapper,
                index.request(),
                MAXIMUM_REQUEST_BYTES);
        if (!requestFingerprint.equals(
                index.job().requestFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario batch request fingerprint differs from terminal job");
        }
    }

    private boolean verifyAttestation(
            ScenarioRehearsalBatchEvidenceAttestation attestation) {
        if (!attestation.independentlyVerifiable()
                || attestation.signatureStatus()
                != ScenarioRehearsalBatchEvidenceAttestation
                .SignatureStatus.VERIFIED) {
            return false;
        }
        String materialFingerprint =
                signatureMaterialFingerprint(
                        attestation.jobId(),
                        attestation.requestFingerprint(),
                        attestation.manifestFingerprint(),
                        attestation.terminalJobFingerprint(),
                        attestation.indexFingerprint(),
                        attestation.signedAt());
        VisualEvidenceSigner.Verification verification =
                signer.verify(
                        new VisualRunEvidenceSeal(
                                "",
                                materialFingerprint,
                                attestation.algorithm(),
                                attestation.keyId(),
                                attestation.signedAt(),
                                attestation.signature()),
                        materialFingerprint);
        return verification.valid();
    }

    private String signatureMaterialFingerprint(
            ScenarioRehearsalBatchEvidenceIndex index,
            Instant signedAt) {
        return signatureMaterialFingerprint(
                index.job().jobId(),
                index.job().requestFingerprint(),
                index.manifest().manifestFingerprint(),
                index.job().recordFingerprint(),
                index.indexFingerprint(),
                signedAt);
    }

    private String signatureMaterialFingerprint(
            String jobId,
            String requestFingerprint,
            String manifestFingerprint,
            String terminalJobFingerprint,
            String indexFingerprint,
            Instant signedAt) {
        return fingerprint(
                new SignatureMaterial(
                        SIGNATURE_DOMAIN,
                        ScenarioRehearsalBatchEvidenceAttestation
                                .SCHEMA_VERSION,
                        jobId,
                        requestFingerprint,
                        manifestFingerprint,
                        terminalJobFingerprint,
                        indexFingerprint,
                        signedAt),
                MAXIMUM_SIGNATURE_MATERIAL_BYTES);
    }

    private ScenarioRehearsalBatchEvidenceIndex canonicalSnapshot(
            ScenarioRehearsalBatchEvidenceIndex index) {
        Objects.requireNonNull(index, "index");
        try {
            return mapper.readValue(
                    mapper.writeValueAsBytes(index),
                    ScenarioRehearsalBatchEvidenceIndex.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence index cannot be canonically detached",
                    failure);
        }
    }

    private ScenarioRehearsalBatchEvidenceIndex safeIndex(
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchJob job,
            List<ScenarioRehearsalBatchItemPage.Item> items) {
        try {
            return new ScenarioRehearsalBatchEvidenceIndex(
                    "",
                    "sha256:" + "0".repeat(64),
                    request,
                    manifest,
                    job,
                    items);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence material is invalid",
                    invalid);
        }
    }

    private String fingerprint(Object value, int maximumBytes) {
        return ProtocolFingerprint.ofBounded(
                mapper, value, maximumBytes);
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            String jobId,
            String requestFingerprint,
            String manifestFingerprint,
            String terminalJobFingerprint,
            String indexFingerprint,
            Instant signedAt) {
    }

    private record BundleMaterial(
            String schemaVersion,
            ScenarioRehearsalBatchEvidenceBundle.PayloadPolicy
                    payloadPolicy,
            ScenarioRehearsalBatchEvidenceAttestation attestation,
            ScenarioRehearsalBatchEvidenceIndex index) {
    }

    /** Verification outcome with invalid material separated from authority outage. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    /** Capability token that only this integrity boundary can construct. */
    public static final class VerifiedBundle {
        private final ScenarioRehearsalBatchEvidenceBundle bundle;

        private VerifiedBundle(
                ScenarioRehearsalBatchEvidenceBundle bundle) {
            this.bundle = Objects.requireNonNull(
                    bundle, "bundle");
        }

        /** @return canonically detached independently verified batch evidence */
        public ScenarioRehearsalBatchEvidenceBundle bundle() {
            return bundle;
        }

        /** Keeps evidence internals out of generic logs. */
        @Override
        public String toString() {
            return "VerifiedScenarioRehearsalBatchEvidence[jobId="
                    + bundle.attestation().jobId()
                    + ", bundleFingerprint="
                    + bundle.bundleFingerprint() + "]";
        }
    }

    /**
     * Fail-closed result of one batch signing attempt.
     *
     * @param index canonical payload-free terminal index
     * @param bundle verified portable bundle, or {@code null}
     * @param attestation verified or unavailable integrity manifest
     * @param failureCode stable machine-readable failure code
     */
    public record SealResult(
            ScenarioRehearsalBatchEvidenceIndex index,
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchEvidenceAttestation attestation,
            String failureCode) {
        /** Enforces exactly one success or failure representation. */
        public SealResult {
            index = Objects.requireNonNull(index, "index");
            attestation = Objects.requireNonNull(
                    attestation, "attestation");
            failureCode = failureCode == null
                    ? "" : failureCode.trim();
            if (failureCode.isBlank() != (bundle != null)) {
                throw new IllegalArgumentException(
                        "successful Scenario batch evidence sealing requires one bundle");
            }
        }

        /** @return successful immediately verified result */
        public static SealResult verified(
                ScenarioRehearsalBatchEvidenceBundle bundle) {
            Objects.requireNonNull(bundle, "bundle");
            return new SealResult(
                    bundle.index(),
                    bundle,
                    bundle.attestation(),
                    "");
        }

        /** @return failed result carrying no portable bundle */
        public static SealResult failed(
                ScenarioRehearsalBatchEvidenceIndex index,
                ScenarioRehearsalBatchEvidenceAttestation attestation,
                String failureCode) {
            String normalized = failureCode == null
                    ? "" : failureCode.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(
                        "Scenario batch evidence failure code is required");
            }
            ScenarioRehearsalBatchEvidenceAttestation safe =
                    attestation == null
                            ? ScenarioRehearsalBatchEvidenceAttestation
                            .unavailable(index)
                            : attestation;
            return new SealResult(
                    index, null, safe, normalized);
        }

        /** @return true only for a verified portable evidence bundle */
        public boolean verified() {
            return bundle != null
                    && failureCode.isBlank()
                    && attestation.independentlyVerifiable();
        }
    }
}
