package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies domain-separated terminal stability evidence.
 *
 * <p>The service recomputes both the evidence fingerprint and ordered source closure before every
 * trust decision. Newly produced signatures are immediately verified before they may cross the
 * persistence boundary.</p>
 */
public final class TestSuiteStabilityAttestationService {
    /** Stable failure when no signing authority can establish trust. */
    public static final String SIGNER_UNAVAILABLE = "STABILITY_ATTESTATION_SIGNER_UNAVAILABLE";
    /** Stable failure when newly produced signature material does not verify. */
    public static final String SIGNATURE_INVALID = "STABILITY_ATTESTATION_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates an attestation boundary using UTC system time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence signer
     */
    public TestSuiteStabilityAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fingerprints, signs, and immediately verifies one terminal analysis.
     *
     * @param evidence exact stability evidence
     * @param requestFingerprint canonical parent request fingerprint
     * @return verified signature or a bounded fail-closed result
     */
    public SealResult seal(
            TestSuiteStabilityEvidence evidence,
            String requestFingerprint) {
        Objects.requireNonNull(evidence, "evidence");
        String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sources = sources(evidence);
        if (!signer.available()) {
            return SealResult.failed(TestSuiteStabilityAttestation.unavailable(
                    evidence, requestFingerprint, evidenceFingerprint, sources),
                    SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            String attestationVersion = TestSuiteStabilityEvidence.SCHEMA_VERSION_V1.equals(
                    evidence.schemaVersion())
                    ? TestSuiteStabilityAttestation.SCHEMA_VERSION_V1
                    : TestSuiteStabilityAttestation.SCHEMA_VERSION;
            String materialFingerprint = materialFingerprint(evidence.stabilityRunId(),
                    evidence.suiteRef(), requestFingerprint, evidenceFingerprint,
                    sources, signedAt, attestationVersion);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityAttestation attestation = new TestSuiteStabilityAttestation(
                    attestationVersion,
                    TestSuiteStabilityAttestation.SignatureStatus.VERIFIED,
                    evidence.stabilityRunId(), evidence.suiteRef(), requestFingerprint,
                    evidenceFingerprint, sources, signedAt, seal.keyId(), seal.algorithm(),
                    seal.signature(), true);
            if (verify(evidence, attestation) != Verification.VERIFIED) {
                return SealResult.failed(TestSuiteStabilityAttestation.unavailable(
                        evidence, requestFingerprint, evidenceFingerprint, sources),
                        SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(TestSuiteStabilityAttestation.unavailable(
                    evidence, requestFingerprint, evidenceFingerprint, sources),
                    SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes complete analysis and signature material.
     *
     * @param evidence exact stability evidence
     * @param attestation persisted detached signature
     * @return bounded trust result
     */
    public Verification verify(
            TestSuiteStabilityEvidence evidence,
            TestSuiteStabilityAttestation attestation) {
        if (evidence == null || attestation == null
                || attestation.signatureStatus()
                == TestSuiteStabilityAttestation.SignatureStatus.UNSIGNED) {
            return Verification.UNSIGNED;
        }
        if (attestation.signatureStatus()
                == TestSuiteStabilityAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE) {
            return Verification.UNAVAILABLE;
        }
        String evidenceFingerprint;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sources = sources(evidence);
        if (!attestation.independentlyVerifiable()
                || !evidence.stabilityRunId().equals(attestation.stabilityRunId())
                || !Objects.equals(evidence.suiteRef(), attestation.suiteRef())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !sources.equals(attestation.sourceSuiteEvidenceRefs())) {
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
            String materialFingerprint = materialFingerprint(attestation.stabilityRunId(),
                    attestation.suiteRef(), attestation.requestFingerprint(),
                    attestation.evidenceFingerprint(), attestation.sourceSuiteEvidenceRefs(),
                    attestation.signedAt(), attestation.schemaVersion());
            VisualEvidenceSigner.Verification result = signer.verify(
                    new VisualRunEvidenceSeal("", materialFingerprint,
                            attestation.algorithm(), attestation.keyId(), attestation.signedAt(),
                            attestation.signature()), materialFingerprint);
            if (result.valid()) {
                return Verification.VERIFIED;
            }
            return "KEY_UNAVAILABLE".equals(result.status())
                    || "UNAVAILABLE".equals(result.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException unavailable) {
            return Verification.UNAVAILABLE;
        }
    }

    private String materialFingerprint(
            String stabilityRunId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String requestFingerprint,
            String evidenceFingerprint,
            List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sources,
            Instant signedAt,
            String schemaVersion) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                schemaVersion, stabilityRunId, suiteRef,
                requestFingerprint, evidenceFingerprint, List.copyOf(sources), signedAt));
    }

    private static List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sources(
            TestSuiteStabilityEvidence evidence) {
        return evidence.attempts().stream()
                .filter(value -> !value.suiteRunId().isBlank()
                        && !value.aggregateEvidenceFingerprint().isBlank())
                .map(value -> new TestSuiteStabilityAttestation.SourceSuiteEvidenceRef(
                        value.attempt(), value.suiteRunId(),
                        value.aggregateEvidenceFingerprint(), value.sourcePromotionStatus(),
                        value.sourcePromotionReasons()))
                .toList();
    }

    private record SignatureMaterial(
            String schemaVersion,
            String stabilityRunId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String requestFingerprint,
            String evidenceFingerprint,
            List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sourceSuiteEvidenceRefs,
            Instant signedAt
    ) {
    }

    /** Signature trust result with invalid material separated from provider unavailability. */
    public enum Verification {
        VERIFIED,
        INVALID,
        UNSIGNED,
        UNAVAILABLE
    }

    /**
     * Result of signing one stability analysis.
     *
     * @param attestation verified or fail-closed signature manifest
     * @param failureCode bounded stable diagnostic; blank on success
     */
    public record SealResult(
            TestSuiteStabilityAttestation attestation,
            String failureCode
    ) {
        /** Normalizes one sealing result. */
        public SealResult {
            attestation = Objects.requireNonNull(attestation, "attestation");
            failureCode = failureCode == null ? "" : failureCode.trim();
        }

        /** @return successful verified result */
        public static SealResult verified(TestSuiteStabilityAttestation attestation) {
            return new SealResult(attestation, "");
        }

        /** @return fail-closed result with a stable reason */
        public static SealResult failed(
                TestSuiteStabilityAttestation attestation,
                String failureCode) {
            return new SealResult(attestation, failureCode);
        }

        /** @return true only when signing and immediate verification succeeded */
        public boolean verified() {
            return failureCode.isBlank() && attestation.terminallyVerifiable();
        }
    }
}
