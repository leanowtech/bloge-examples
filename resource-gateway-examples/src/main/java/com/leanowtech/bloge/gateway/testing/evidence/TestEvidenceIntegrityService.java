package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Objects;

/**
 * Creates and verifies detached signatures over complete sanitized test-run evidence.
 *
 * <p>The service reuses the Resource Gateway signing authority so local Ed25519 and managed
 * KMS/HSM custody share one key lifecycle. Every newly produced signature is verified before it may
 * cross the persistence boundary.</p>
 */
public final class TestEvidenceIntegrityService {

    /** Stable diagnostic emitted when no signing authority can establish integrity. */
    public static final String SIGNER_UNAVAILABLE = "TEST_EVIDENCE_SIGNER_UNAVAILABLE";
    /** Stable diagnostic emitted when a newly produced signature cannot be verified. */
    public static final String SIGNATURE_INVALID = "TEST_EVIDENCE_SIGNATURE_INVALID";
    /** Stable diagnostic emitted when current evidence carries inconsistent semantic identity. */
    public static final String SEMANTIC_FINGERPRINT_INVALID =
            "TEST_EVIDENCE_SEMANTIC_FINGERPRINT_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;

    /**
     * Creates the integrity boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence-signing authority
     */
    public TestEvidenceIntegrityService(ObjectMapper objectMapper, VisualEvidenceSigner signer) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
    }

    /**
     * Fingerprints, signs, and immediately verifies one complete sanitized evidence value.
     *
     * @param evidence complete sanitized evidence
     * @return verified integrity or a fail-closed bounded failure result
     */
    public SealResult seal(TestRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        String fingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        if (!TestSemanticResultFingerprint.matches(objectMapper, evidence)) {
            return SealResult.failed(TestEvidenceIntegrity.unavailable(fingerprint),
                    SEMANTIC_FINGERPRINT_INVALID);
        }
        if (!signer.available()) {
            return SealResult.failed(TestEvidenceIntegrity.unavailable(fingerprint), SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = Instant.now();
            String signatureMaterial = signatureMaterialFingerprint(fingerprint, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(signatureMaterial);
            TestEvidenceIntegrity integrity = new TestEvidenceIntegrity("", fingerprint,
                    TestEvidenceIntegrity.SignatureStatus.VERIFIED, seal.keyId(), seal.algorithm(),
                    signedAt, seal.signature(), TestEvidenceIntegrity.Projection.FULL,
                    fingerprint, true);
            if (verify(evidence, integrity) != Verification.VERIFIED) {
                return SealResult.failed(TestEvidenceIntegrity.unavailable(fingerprint), SIGNATURE_INVALID);
            }
            return SealResult.verified(integrity);
        } catch (RuntimeException failure) {
            return SealResult.failed(TestEvidenceIntegrity.unavailable(fingerprint), SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Creates a fingerprinted fail-closed manifest for evidence produced after signing failed.
     *
     * @param evidence complete sanitized evidence
     * @return unavailable integrity manifest bound to the supplied evidence
     */
    public TestEvidenceIntegrity unavailable(TestRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return TestEvidenceIntegrity.unavailable(ProtocolFingerprint.of(objectMapper, evidence));
    }

    /**
     * Projects a persisted full-evidence manifest onto the evidence returned to one caller.
     *
     * @param persisted full persisted integrity manifest
     * @param projectedEvidence evidence value carried by the response
     * @param projection selected response projection
     * @return integrity manifest with an exact response projection fingerprint
     */
    public TestEvidenceIntegrity project(TestEvidenceIntegrity persisted,
                                         TestRunEvidence projectedEvidence,
                                         TestEvidenceIntegrity.Projection projection) {
        Objects.requireNonNull(projectedEvidence, "projectedEvidence");
        TestEvidenceIntegrity safe = persisted == null ? TestEvidenceIntegrity.unsigned() : persisted;
        return safe.withProjection(projection,
                ProtocolFingerprint.of(objectMapper, projectedEvidence));
    }

    /**
     * Recomputes complete evidence material and verifies its detached signature.
     *
     * @param evidence complete evidence value
     * @param integrity persisted full-evidence integrity manifest
     * @return bounded trust result suitable for API failure mapping
     */
    public Verification verify(TestRunEvidence evidence, TestEvidenceIntegrity integrity) {
        if (evidence == null || integrity == null) {
            return Verification.UNSIGNED;
        }
        if (integrity.signatureStatus() == TestEvidenceIntegrity.SignatureStatus.UNSIGNED) {
            return Verification.UNSIGNED;
        }
        if (integrity.signatureStatus()
                == TestEvidenceIntegrity.SignatureStatus.VERIFICATION_UNAVAILABLE) {
            return Verification.UNAVAILABLE;
        }
        if (!TestEvidenceIntegrity.SCHEMA_VERSION.equals(integrity.schemaVersion())
                || integrity.projection() != TestEvidenceIntegrity.Projection.FULL
                || !integrity.independentlyVerifiable()
                || !TestSemanticResultFingerprint.matches(objectMapper, evidence)) {
            return Verification.INVALID;
        }
        String actualFingerprint;
        try {
            actualFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        } catch (RuntimeException failure) {
            return Verification.INVALID;
        }
        if (!actualFingerprint.equals(integrity.evidenceFingerprint())) {
            return Verification.INVALID;
        }
        if (!signer.available()) {
            return Verification.UNAVAILABLE;
        }
        try {
            String signatureMaterial = signatureMaterialFingerprint(
                    integrity.evidenceFingerprint(), integrity.signedAt());
            VisualEvidenceSigner.Verification verification = signer.verify(
                    new VisualRunEvidenceSeal("", signatureMaterial,
                            integrity.algorithm(), integrity.keyId(), integrity.signedAt(),
                            integrity.signature()), signatureMaterial);
            if (verification.valid()) {
                return Verification.VERIFIED;
            }
            return "KEY_UNAVAILABLE".equals(verification.status())
                    || "UNAVAILABLE".equals(verification.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException failure) {
            return Verification.UNAVAILABLE;
        }
    }

    private String signatureMaterialFingerprint(String evidenceFingerprint, Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestEvidenceIntegrity.SCHEMA_VERSION, evidenceFingerprint, signedAt));
    }

    private record SignatureMaterial(String schemaVersion, String evidenceFingerprint,
                                     Instant signedAt) {
    }

    /** Trust result with invalid material separated from temporary verifier unavailability. */
    public enum Verification {
        /** Complete evidence and detached signature are valid. */
        VERIFIED,
        /** Evidence material or signature was altered. */
        INVALID,
        /** Historical evidence has no signature. */
        UNSIGNED,
        /** The signing key or verifier is temporarily unavailable. */
        UNAVAILABLE
    }

    /**
     * Result of signing one complete evidence value.
     *
     * @param integrity verified or fail-closed integrity manifest
     * @param failureCode bounded stable diagnostic; blank on success
     */
    public record SealResult(TestEvidenceIntegrity integrity, String failureCode) {
        /** Normalizes result values. */
        public SealResult {
            integrity = Objects.requireNonNull(integrity, "integrity");
            failureCode = failureCode == null ? "" : failureCode.trim();
        }

        /**
         * Creates one successfully verified result.
         *
         * @param integrity verified signature manifest
         * @return successful result
         */
        public static SealResult verified(TestEvidenceIntegrity integrity) {
            return new SealResult(integrity, "");
        }

        /**
         * Creates one fail-closed signing result.
         *
         * @param integrity unavailable integrity manifest
         * @param failureCode stable bounded diagnostic
         * @return failed result
         */
        public static SealResult failed(TestEvidenceIntegrity integrity, String failureCode) {
            return new SealResult(integrity, failureCode);
        }

        /**
         * Indicates whether signing and immediate verification succeeded.
         *
         * @return true only for a verified manifest with no failure diagnostic
         */
        public boolean verified() {
            return failureCode.isBlank()
                    && integrity.signatureStatus() == TestEvidenceIntegrity.SignatureStatus.VERIFIED;
        }
    }
}
