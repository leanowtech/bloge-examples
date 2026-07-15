package com.leanowtech.bloge.gateway.testing.domain;

import java.util.regex.Pattern;

/**
 * Portable, payload-free evidence bundle for one terminal immutable suite run.
 *
 * <p>The aggregate contains no child input/output payloads. Its terminal attestation binds the
 * aggregate fingerprint and ordered child evidence fingerprints; authorized payload retrieval
 * remains a separate governed API.</p>
 *
 * @param schemaVersion portable bundle protocol version
 * @param suiteRunId durable aggregate run id
 * @param bundleFingerprint canonical bundle-content fingerprint
 * @param payloadPolicy explicit payload omission policy
 * @param attestation verified terminal aggregate attestation
 * @param evidence signed aggregate evidence
 */
public record TestSuiteEvidenceBundle(
        String schemaVersion,
        String suiteRunId,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        TestSuiteRunAttestation attestation,
        TestSuiteRunEvidenceProtocol evidence
) {
    /** Current portable suite evidence bundle version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteEvidenceBundle.v1";
    /** Portable bundle version carrying semantic aggregate evidence and attestation. */
    public static final String SCHEMA_VERSION_V2 = "bloge.testSuiteEvidenceBundle.v2";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Payload handling for this first portable bundle version. */
    public enum PayloadPolicy {
        /** Child inputs and outputs are omitted and remain in governed storage. */
        OMITTED
    }

    /** Normalizes and validates portable bundle identity. */
    public TestSuiteEvidenceBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? evidence instanceof TestSuiteRunEvidenceV2 ? SCHEMA_VERSION_V2 : SCHEMA_VERSION
                : schemaVersion.trim();
        suiteRunId = suiteRunId == null ? "" : suiteRunId.trim();
        bundleFingerprint = bundleFingerprint == null ? "" : bundleFingerprint.trim();
        payloadPolicy = payloadPolicy == null ? PayloadPolicy.OMITTED : payloadPolicy;
        boolean v1 = SCHEMA_VERSION.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidence structural
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(structural.schemaVersion())
                && attestation != null
                && TestSuiteRunAttestation.SCHEMA_VERSION.equals(attestation.schemaVersion());
        boolean v2 = SCHEMA_VERSION_V2.equals(schemaVersion)
                && evidence instanceof TestSuiteRunEvidenceV2 semantic
                && TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(semantic.schemaVersion())
                && attestation != null
                && TestSuiteRunAttestation.SCHEMA_VERSION_V2.equals(attestation.schemaVersion());
        if (suiteRunId.isBlank() || !FINGERPRINT.matcher(bundleFingerprint).matches()
                || attestation == null || !attestation.terminallyVerifiable()
                || evidence == null || !suiteRunId.equals(evidence.suiteRunId())
                || !suiteRunId.equals(attestation.suiteRunId()) || (!v1 && !v2)) {
            throw new IllegalArgumentException("Portable suite evidence bundle is incomplete");
        }
    }
}
