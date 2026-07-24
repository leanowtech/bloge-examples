package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independently verifiable payload-free evidence for one terminal Scenario rehearsal batch.
 *
 * @param schemaVersion exact portable bundle version
 * @param bundleFingerprint canonical fingerprint of attestation and index material
 * @param payloadPolicy fixed proof that business values are excluded
 * @param attestation detached batch signature
 * @param index complete content-addressed terminal index
 */
public record ScenarioRehearsalBatchEvidenceBundle(
        String schemaVersion,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        ScenarioRehearsalBatchEvidenceAttestation attestation,
        ScenarioRehearsalBatchEvidenceIndex index
) {
    /** Current portable Scenario batch evidence version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchEvidenceBundle.v1";
    /** Maximum canonical portable bundle admitted to signing and verification. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            18 * 1024 * 1024;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Portable payload policy. */
    public enum PayloadPolicy {
        HASH_ONLY
    }

    /** Validates the complete signed identity closure. */
    public ScenarioRehearsalBatchEvidenceBundle {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch evidence bundle version");
        }
        bundleFingerprint = requiredFingerprint(
                bundleFingerprint, "bundleFingerprint");
        if (payloadPolicy != PayloadPolicy.HASH_ONLY) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence must be HASH_ONLY");
        }
        attestation = Objects.requireNonNull(
                attestation, "attestation");
        index = Objects.requireNonNull(index, "index");
        if (attestation.signatureStatus()
                != ScenarioRehearsalBatchEvidenceAttestation
                .SignatureStatus.VERIFIED
                || !attestation.independentlyVerifiable()
                || !attestation.jobId().equals(index.job().jobId())
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
            throw new IllegalArgumentException(
                    "Scenario batch evidence identity closure is invalid");
        }
    }

    /** Keeps item details and signature material out of generic logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalBatchEvidenceBundle[jobId="
                + attestation.jobId()
                + ", status=" + index.job().status()
                + ", bundleFingerprint=" + bundleFingerprint + "]";
    }

    private static String requiredFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }
}
