package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies retained-window trend evidence in a dedicated signature domain.
 */
public final class TestSuiteStabilityTrendAttestationService {
    /** Stable failure when no signing authority can establish trust. */
    public static final String SIGNER_UNAVAILABLE = "STABILITY_TREND_SIGNER_UNAVAILABLE";
    /** Stable failure when newly produced signature material fails immediate verification. */
    public static final String SIGNATURE_INVALID = "STABILITY_TREND_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a trend attestation boundary using UTC system time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence signer
     */
    public TestSuiteStabilityTrendAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityTrendAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fingerprints, signs, and immediately verifies one complete trend projection.
     *
     * @param evidence exact derived trend evidence
     * @return verified signature or bounded fail-closed material
     */
    public SealResult seal(TestSuiteStabilityTrendEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> sources = sources(evidence);
        if (!signer.available()) {
            return SealResult.failed(TestSuiteStabilityTrendAttestation.unavailable(
                    evidence, evidenceFingerprint, sources), SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(
                    evidence.trendAnalysisId(), evidence.requestFingerprint(),
                    evidenceFingerprint, sources, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityTrendAttestation attestation =
                    new TestSuiteStabilityTrendAttestation(
                            TestSuiteStabilityTrendAttestation.SCHEMA_VERSION,
                            TestSuiteStabilityTrendAttestation.SignatureStatus.VERIFIED,
                            evidence.trendAnalysisId(), evidence.requestFingerprint(),
                            evidenceFingerprint, sources, signedAt, seal.keyId(),
                            seal.algorithm(), seal.signature(), true);
            if (verify(evidence, attestation) != Verification.VERIFIED) {
                return SealResult.failed(TestSuiteStabilityTrendAttestation.unavailable(
                        evidence, evidenceFingerprint, sources), SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(TestSuiteStabilityTrendAttestation.unavailable(
                    evidence, evidenceFingerprint, sources), SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes evidence, source closure, key resolution, and detached signature material.
     *
     * @param evidence exact trend evidence
     * @param attestation detached signature
     * @return bounded verification result
     */
    public Verification verify(
            TestSuiteStabilityTrendEvidence evidence,
            TestSuiteStabilityTrendAttestation attestation) {
        if (evidence == null || attestation == null) {
            return Verification.INVALID;
        }
        if (attestation.signatureStatus()
                == TestSuiteStabilityTrendAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE) {
            return Verification.UNAVAILABLE;
        }
        String evidenceFingerprint;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> sources = sources(evidence);
        if (!attestation.independentlyVerifiable()
                || !evidence.trendAnalysisId().equals(attestation.trendAnalysisId())
                || !evidence.requestFingerprint().equals(attestation.requestFingerprint())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !sources.equals(attestation.sourceEvidenceRefs())) {
            return Verification.INVALID;
        }
        if (!signer.available()) {
            return Verification.UNAVAILABLE;
        }
        try {
            VisualEvidenceSigner.KeyResolution resolution = signer.resolveKey(attestation.keyId());
            if (resolution.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE) {
                return Verification.UNAVAILABLE;
            }
            if (resolution.key() == null
                    || !attestation.algorithm().equals(resolution.key().algorithm())
                    || !List.of("ACTIVE", "RETIRED").contains(resolution.key().state())) {
                return Verification.INVALID;
            }
            String materialFingerprint = materialFingerprint(
                    attestation.trendAnalysisId(), attestation.requestFingerprint(),
                    attestation.evidenceFingerprint(), attestation.sourceEvidenceRefs(),
                    attestation.signedAt());
            VisualEvidenceSigner.Verification verification = signer.verify(
                    new VisualRunEvidenceSeal("", materialFingerprint,
                            attestation.algorithm(), attestation.keyId(), attestation.signedAt(),
                            attestation.signature()), materialFingerprint);
            if (verification.valid()) {
                return Verification.VERIFIED;
            }
            return "KEY_UNAVAILABLE".equals(verification.status())
                    || "UNAVAILABLE".equals(verification.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException unavailable) {
            return Verification.UNAVAILABLE;
        }
    }

    private String materialFingerprint(
            String trendAnalysisId,
            String requestFingerprint,
            String evidenceFingerprint,
            List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> sources,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityTrendAttestation.SCHEMA_VERSION,
                trendAnalysisId, requestFingerprint, evidenceFingerprint,
                List.copyOf(sources), signedAt));
    }

    private static List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> sources(
            TestSuiteStabilityTrendEvidence evidence) {
        return evidence.sources().stream()
                .map(value -> new TestSuiteStabilityTrendAttestation.SourceEvidenceRef(
                        value.stabilityRunId(), value.evidenceFingerprint(),
                        value.attestationFingerprint()))
                .toList();
    }

    /** Closed verification result separating invalid material from provider outage. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    /**
     * Result of one immediate sign-and-verify operation.
     *
     * @param attestation verified or fail-closed signature manifest
     * @param failureCode stable bounded reason; blank on success
     */
    public record SealResult(
            TestSuiteStabilityTrendAttestation attestation,
            String failureCode
    ) {
        /** Validates complete result material. */
        public SealResult {
            attestation = Objects.requireNonNull(attestation, "attestation");
            failureCode = failureCode == null ? "" : failureCode.trim();
        }

        /** @return successful verified result */
        public static SealResult verified(TestSuiteStabilityTrendAttestation attestation) {
            return new SealResult(attestation, "");
        }

        /** @return fail-closed result */
        public static SealResult failed(
                TestSuiteStabilityTrendAttestation attestation,
                String failureCode) {
            return new SealResult(attestation, failureCode);
        }

        /** @return true only when the generated signature immediately verified */
        public boolean verified() {
            return failureCode.isBlank() && attestation.terminallyVerifiable();
        }
    }

    private record SignatureMaterial(
            String schemaVersion,
            String trendAnalysisId,
            String requestFingerprint,
            String evidenceFingerprint,
            List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> sourceEvidenceRefs,
            Instant signedAt) {
    }
}
