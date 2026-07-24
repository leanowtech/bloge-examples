package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independently verifiable payload-free evidence for one Scenario rehearsal aggregate.
 *
 * @param schemaVersion exact portable bundle version
 * @param bundleFingerprint canonical fingerprint of attestation and result material
 * @param payloadPolicy fixed proof that business values are excluded
 * @param attestation detached aggregate signature
 * @param result complete content-addressed Scenario result
 */
public record ScenarioRehearsalEvidenceBundle(
        String schemaVersion,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        ScenarioRehearsalEvidenceAttestation attestation,
        ScenarioRehearsalResult result
) {
    /** Current portable Scenario rehearsal evidence version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalEvidenceBundle.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Portable payload policy. */
    public enum PayloadPolicy {
        HASH_ONLY
    }

    /** Validates the complete signed identity closure. */
    public ScenarioRehearsalEvidenceBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal evidence bundle version");
        }
        bundleFingerprint = requiredFingerprint(
                bundleFingerprint, "bundleFingerprint");
        if (payloadPolicy != PayloadPolicy.HASH_ONLY) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal evidence must be HASH_ONLY");
        }
        attestation = Objects.requireNonNull(
                attestation, "attestation");
        result = Objects.requireNonNull(result, "result");
        if (!ScenarioRehearsalEvidenceAttestation.SCHEMA_VERSION.equals(
                attestation.schemaVersion())
                || attestation.signatureStatus()
                != ScenarioRehearsalEvidenceAttestation.SignatureStatus.VERIFIED
                || !attestation.independentlyVerifiable()
                || !attestation.requestId().equals(result.requestId())
                || !attestation.compiledPlanFingerprint().equals(
                result.compiledPlanRef().fingerprint())
                || !attestation.resultFingerprint().equals(
                result.resultFingerprint())
                || attestation.signedAt().isBefore(result.completedAt())) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal evidence identity closure is invalid");
        }
    }

    /** Keeps case and assertion detail out of generic logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalEvidenceBundle[runId="
                + attestation.runId()
                + ", requestId=" + result.requestId()
                + ", outcome=" + result.outcome()
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
