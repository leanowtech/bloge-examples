package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Seals and verifies complete Scenario aggregates under an independent signature domain.
 */
public final class ScenarioRehearsalEvidenceIntegrityService {
    /** Stable failure when no signing authority can establish aggregate integrity. */
    public static final String SIGNER_UNAVAILABLE =
            "SCENARIO_REHEARSAL_EVIDENCE_SIGNER_UNAVAILABLE";
    /** Stable failure when result material or nested content addresses are invalid. */
    public static final String MATERIAL_INVALID =
            "SCENARIO_REHEARSAL_EVIDENCE_MATERIAL_INVALID";
    /** Stable failure when a detached signature cannot be verified immediately. */
    public static final String SIGNATURE_INVALID =
            "SCENARIO_REHEARSAL_EVIDENCE_SIGNATURE_INVALID";
    /** Maximum canonical aggregate admitted to signing and verification. */
    public static final int MAXIMUM_RESULT_BYTES = 160 * 1024 * 1024;
    /** Maximum canonical portable bundle admitted to signing and verification. */
    public static final int MAXIMUM_BUNDLE_BYTES = 168 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES =
            8 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_EVIDENCE_V1";

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates the aggregate evidence integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed Ed25519 signing authority and verification key ring
     * @param clock server signing clock
     */
    public ScenarioRehearsalEvidenceIntegrityService(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null
                ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Canonically snapshots, signs, immediately verifies, and bundles one result.
     *
     * @param runId stable aggregate run identity
     * @param result complete payload-free result
     * @return verified bundle or fail-closed bounded result
     */
    public SealResult seal(
            String runId, ScenarioRehearsalResult result) {
        ScenarioRehearsalResult snapshot;
        try {
            snapshot = canonicalSnapshot(result);
            ScenarioRehearsalResultIntegrity.verify(mapper, snapshot);
            fingerprint(snapshot, MAXIMUM_RESULT_BYTES);
            if (!ScenarioRehearsalRunIdentity.derive(
                    mapper,
                    snapshot.scope(),
                    snapshot.requestId()).equals(runId)) {
                return SealResult.failed(
                        snapshot, null, MATERIAL_INVALID);
            }
        } catch (RuntimeException invalid) {
            return SealResult.failed(
                    Objects.requireNonNull(result, "result"),
                    null,
                    MATERIAL_INVALID);
        }
        ScenarioRehearsalEvidenceAttestation unavailable =
                ScenarioRehearsalEvidenceAttestation.unavailable(
                        runId, snapshot);
        if (!signer.available()) {
            return SealResult.failed(
                    snapshot, unavailable, SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            if (Instant.EPOCH.equals(signedAt)
                    || signedAt.isBefore(snapshot.completedAt())) {
                return SealResult.failed(
                        snapshot, unavailable, MATERIAL_INVALID);
            }
            String materialFingerprint =
                    signatureMaterialFingerprint(
                            runId, snapshot, signedAt);
            VisualRunEvidenceSeal seal =
                    signer.seal(materialFingerprint);
            ScenarioRehearsalEvidenceAttestation attestation =
                    new ScenarioRehearsalEvidenceAttestation(
                            ScenarioRehearsalEvidenceAttestation.SCHEMA_VERSION,
                            ScenarioRehearsalEvidenceAttestation
                                    .SignatureStatus.VERIFIED,
                            runId,
                            snapshot.requestId(),
                            snapshot.compiledPlanRef().fingerprint(),
                            snapshot.resultFingerprint(),
                            signedAt,
                            seal.keyId(),
                            seal.algorithm(),
                            seal.signature(),
                            true);
            if (!verifyAttestation(attestation)) {
                return SealResult.failed(
                        snapshot, unavailable, SIGNATURE_INVALID);
            }
            BundleMaterial material = new BundleMaterial(
                    ScenarioRehearsalEvidenceBundle.SCHEMA_VERSION,
                    ScenarioRehearsalEvidenceBundle
                            .PayloadPolicy.HASH_ONLY,
                    attestation,
                    snapshot);
            String bundleFingerprint =
                    fingerprint(material, MAXIMUM_BUNDLE_BYTES);
            ScenarioRehearsalEvidenceBundle bundle =
                    new ScenarioRehearsalEvidenceBundle(
                            ScenarioRehearsalEvidenceBundle.SCHEMA_VERSION,
                            bundleFingerprint,
                            ScenarioRehearsalEvidenceBundle
                                    .PayloadPolicy.HASH_ONLY,
                            attestation,
                            snapshot);
            if (verify(bundle) != Verification.VERIFIED) {
                return SealResult.failed(
                        snapshot, unavailable, SIGNATURE_INVALID);
            }
            return SealResult.verified(bundle);
        } catch (RuntimeException failure) {
            return SealResult.failed(
                    snapshot, unavailable, SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes nested content addresses, detached signature, and bundle identity.
     *
     * @param bundle caller or repository supplied portable evidence
     * @return independent verification outcome
     */
    public Verification verify(
            ScenarioRehearsalEvidenceBundle bundle) {
        if (bundle == null
                || !ScenarioRehearsalEvidenceBundle.SCHEMA_VERSION
                .equals(bundle.schemaVersion())) {
            return Verification.INVALID;
        }
        try {
            ScenarioRehearsalResult result =
                    canonicalSnapshot(bundle.result());
            ScenarioRehearsalResultIntegrity.verify(mapper, result);
            fingerprint(result, MAXIMUM_RESULT_BYTES);
            ScenarioRehearsalEvidenceAttestation attestation =
                    bundle.attestation();
            if (!attestation.runId().equals(
                    ScenarioRehearsalRunIdentity.derive(
                            mapper,
                            result.scope(),
                            result.requestId()))
                    || !attestation.requestId().equals(result.requestId())
                    || !attestation.compiledPlanFingerprint().equals(
                    result.compiledPlanRef().fingerprint())
                    || !attestation.resultFingerprint().equals(
                    result.resultFingerprint())
                    || attestation.signedAt().isBefore(
                    result.completedAt())) {
                return Verification.INVALID;
            }
            BundleMaterial material = new BundleMaterial(
                    bundle.schemaVersion(),
                    bundle.payloadPolicy(),
                    attestation,
                    result);
            if (!fingerprint(material, MAXIMUM_BUNDLE_BYTES)
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
     * Verifies and canonically detaches a bundle before trusted consumption.
     *
     * @throws IllegalArgumentException when material or signature is invalid
     * @throws IllegalStateException when verification authority is unavailable
     */
    public VerifiedBundle requireVerified(
            ScenarioRehearsalEvidenceBundle bundle) {
        Verification verification = verify(bundle);
        if (verification == Verification.UNAVAILABLE) {
            throw new IllegalStateException(
                    "Scenario rehearsal evidence verification authority is unavailable");
        }
        if (verification != Verification.VERIFIED) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal evidence bundle is not verified");
        }
        return new VerifiedBundle(
                new ScenarioRehearsalEvidenceBundle(
                        bundle.schemaVersion(),
                        bundle.bundleFingerprint(),
                        bundle.payloadPolicy(),
                        bundle.attestation(),
                        canonicalSnapshot(bundle.result())));
    }

    /** Returns a canonical independently owned result snapshot. */
    public ScenarioRehearsalResult canonicalSnapshot(
            ScenarioRehearsalResult result) {
        Objects.requireNonNull(result, "result");
        try {
            return mapper.readValue(
                    mapper.writeValueAsBytes(result),
                    ScenarioRehearsalResult.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal result cannot be canonically detached",
                    failure);
        }
    }

    private boolean verifyAttestation(
            ScenarioRehearsalEvidenceAttestation attestation) {
        if (!attestation.independentlyVerifiable()
                || attestation.signatureStatus()
                != ScenarioRehearsalEvidenceAttestation
                .SignatureStatus.VERIFIED) {
            return false;
        }
        String materialFingerprint =
                signatureMaterialFingerprint(
                        attestation.runId(),
                        attestation.requestId(),
                        attestation.compiledPlanFingerprint(),
                        attestation.resultFingerprint(),
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
            String runId,
            ScenarioRehearsalResult result,
            Instant signedAt) {
        return signatureMaterialFingerprint(
                runId,
                result.requestId(),
                result.compiledPlanRef().fingerprint(),
                result.resultFingerprint(),
                signedAt);
    }

    private String signatureMaterialFingerprint(
            String runId,
            String requestId,
            String compiledPlanFingerprint,
            String resultFingerprint,
            Instant signedAt) {
        return fingerprint(
                new SignatureMaterial(
                        SIGNATURE_DOMAIN,
                        ScenarioRehearsalEvidenceAttestation
                                .SCHEMA_VERSION,
                        runId,
                        requestId,
                        compiledPlanFingerprint,
                        resultFingerprint,
                        signedAt),
                MAXIMUM_SIGNATURE_MATERIAL_BYTES);
    }

    private String fingerprint(Object value, int maximumBytes) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper, value, maximumBytes);
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            String runId,
            String requestId,
            String compiledPlanFingerprint,
            String resultFingerprint,
            Instant signedAt) {
    }

    private record BundleMaterial(
            String schemaVersion,
            ScenarioRehearsalEvidenceBundle.PayloadPolicy payloadPolicy,
            ScenarioRehearsalEvidenceAttestation attestation,
            ScenarioRehearsalResult result) {
    }

    /** Verification outcome with invalid material separated from authority outage. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    /** Capability token that only this integrity boundary can construct. */
    public static final class VerifiedBundle {
        private final ScenarioRehearsalEvidenceBundle bundle;

        private VerifiedBundle(
                ScenarioRehearsalEvidenceBundle bundle) {
            this.bundle = Objects.requireNonNull(
                    bundle, "bundle");
        }

        /** @return canonically detached independently verified evidence */
        public ScenarioRehearsalEvidenceBundle bundle() {
            return bundle;
        }

        /** Keeps evidence internals out of generic logs. */
        @Override
        public String toString() {
            return "VerifiedScenarioRehearsalEvidence[runId="
                    + bundle.attestation().runId()
                    + ", bundleFingerprint="
                    + bundle.bundleFingerprint() + "]";
        }
    }

    /**
     * Fail-closed result of one aggregate signing attempt.
     *
     * @param result canonical payload-free aggregate
     * @param bundle verified portable bundle, or {@code null}
     * @param attestation verified or unavailable integrity manifest
     * @param failureCode stable machine-readable failure code
     */
    public record SealResult(
            ScenarioRehearsalResult result,
            ScenarioRehearsalEvidenceBundle bundle,
            ScenarioRehearsalEvidenceAttestation attestation,
            String failureCode) {
        /** Enforces exactly one success or failure representation. */
        public SealResult {
            result = Objects.requireNonNull(result, "result");
            attestation = Objects.requireNonNull(
                    attestation, "attestation");
            failureCode = failureCode == null
                    ? "" : failureCode.trim();
            if (failureCode.isBlank() != (bundle != null)) {
                throw new IllegalArgumentException(
                        "successful Scenario evidence sealing requires one bundle");
            }
        }

        /** @return successful immediately verified result */
        public static SealResult verified(
                ScenarioRehearsalEvidenceBundle bundle) {
            Objects.requireNonNull(bundle, "bundle");
            return new SealResult(
                    bundle.result(),
                    bundle,
                    bundle.attestation(),
                    "");
        }

        /** @return failed result carrying no portable bundle */
        public static SealResult failed(
                ScenarioRehearsalResult result,
                ScenarioRehearsalEvidenceAttestation attestation,
                String failureCode) {
            String normalized = failureCode == null
                    ? "" : failureCode.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(
                        "Scenario evidence failure code is required");
            }
            ScenarioRehearsalEvidenceAttestation safe =
                    attestation == null
                            ? ScenarioRehearsalEvidenceAttestation
                            .unavailable(
                                    "scenario-" + "0".repeat(64),
                                    result)
                            : attestation;
            return new SealResult(
                    result, null, safe, normalized);
        }

        /** @return true only for a verified portable evidence bundle */
        public boolean verified() {
            return bundle != null
                    && failureCode.isBlank()
                    && attestation.independentlyVerifiable();
        }
    }
}
