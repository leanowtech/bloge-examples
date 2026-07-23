package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Portable signed bundle for one terminal payload-free mirror run.
 *
 * <p>The bundle fingerprint covers the payload policy, attestation, and complete run evidence.
 * Consumers must still independently recompute fingerprints and verify the detached signature;
 * producer-side {@code independentlyVerifiable} is never sufficient by itself.</p>
 *
 * @param schemaVersion portable bundle protocol version
 * @param bundleFingerprint canonical fingerprint with this field omitted from material
 * @param payloadPolicy mandatory business-payload omission policy
 * @param attestation verified detached signature over the complete run evidence
 * @param evidence complete payload-free terminal mirror evidence
 */
public record MirrorEvidenceBundle(
        String schemaVersion,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        MirrorEvidenceAttestation attestation,
        MirrorRunEvidence evidence
) {
    /** Legacy portable mirror evidence bundle version. */
    public static final String SCHEMA_VERSION_V1 = "resourceGateway.mirrorEvidenceBundle.v1";
    /** Current portable mirror evidence bundle version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorEvidenceBundle.v2";
    /** Stateful portable mirror evidence bundle version. */
    public static final String STATEFUL_SCHEMA_VERSION =
            "resourceGateway.mirrorEvidenceBundle.v3";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Business payload handling for every supported bundle version. */
    public enum PayloadPolicy {
        HASH_ONLY
    }

    /** Validates exact cross-object identity and terminal verification state. */
    public MirrorEvidenceBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        bundleFingerprint = bundleFingerprint == null ? "" : bundleFingerprint.trim();
        payloadPolicy = payloadPolicy == null ? PayloadPolicy.HASH_ONLY : payloadPolicy;
        attestation = Objects.requireNonNull(attestation, "attestation");
        evidence = Objects.requireNonNull(evidence, "evidence");
        boolean v2 = SCHEMA_VERSION.equals(schemaVersion);
        boolean v1 = SCHEMA_VERSION_V1.equals(schemaVersion);
        boolean v3 = STATEFUL_SCHEMA_VERSION.equals(schemaVersion);
        if ((!v1 && !v2 && !v3)
                || !FINGERPRINT.matcher(bundleFingerprint).matches()
                || !attestation.independentlyVerifiable()
                || !evidence.runId().equals(attestation.runId())
                || !evidence.planFingerprint().equals(attestation.planFingerprint())
                || v2 && (!MirrorRunEvidence.SCHEMA_VERSION.equals(evidence.schemaVersion())
                || !MirrorEvidenceAttestation.SCHEMA_VERSION.equals(
                attestation.schemaVersion()))
                || v1 && (!MirrorRunEvidence.SCHEMA_VERSION_V1.equals(
                evidence.schemaVersion())
                || !MirrorEvidenceAttestation.SCHEMA_VERSION_V1.equals(
                attestation.schemaVersion()))
                || v3 && (!MirrorRunEvidence.STATEFUL_SCHEMA_VERSION.equals(
                evidence.schemaVersion())
                || !MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION.equals(
                attestation.schemaVersion()))) {
            throw new IllegalArgumentException("portable mirror evidence bundle is incomplete");
        }
    }

    /** Keeps evidence details and fingerprint closures out of generic logs. */
    @Override
    public String toString() {
        return "MirrorEvidenceBundle[runId=" + evidence.runId()
                + ", bundleFingerprint=" + bundleFingerprint
                + ", signatureStatus=" + attestation.signatureStatus() + "]";
    }
}
