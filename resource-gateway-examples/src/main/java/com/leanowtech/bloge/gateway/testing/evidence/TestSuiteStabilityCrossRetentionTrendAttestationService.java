package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendAttestation.ObservationRef;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies cross-retention trends in a range-closing signature domain.
 *
 * <p>The signature material binds the complete evidence fingerprint, producer range fingerprint,
 * and ordered observation/entry identities. This prevents an otherwise valid observation from
 * being inserted, removed, reordered, or rebound to a different floor/head snapshot.</p>
 */
public final class TestSuiteStabilityCrossRetentionTrendAttestationService {
    /** Stable failure when no signing authority can establish trust. */
    public static final String SIGNER_UNAVAILABLE =
            "STABILITY_CROSS_RETENTION_TREND_SIGNER_UNAVAILABLE";
    /** Stable failure when canonical range material is internally inconsistent. */
    public static final String EVIDENCE_INVALID =
            "STABILITY_CROSS_RETENTION_TREND_EVIDENCE_INVALID";
    /** Stable failure when newly produced signature material does not verify. */
    public static final String SIGNATURE_INVALID =
            "STABILITY_CROSS_RETENTION_TREND_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a range attestation boundary using UTC system time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence signer
     */
    public TestSuiteStabilityCrossRetentionTrendAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityCrossRetentionTrendAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fingerprints, signs, and immediately verifies one complete range trend.
     *
     * @param evidence exact cross-retention trend evidence
     * @return verified signature or bounded fail-closed material
     */
    public SealResult seal(TestSuiteStabilityCrossRetentionTrendEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        List<ObservationRef> observations = observationRefs(evidence);
        if (!structurallyValid(evidence)) {
            return SealResult.failed(
                    TestSuiteStabilityCrossRetentionTrendAttestation.unavailable(
                            evidence, evidenceFingerprint, observations),
                    EVIDENCE_INVALID);
        }
        if (!signer.available()) {
            return SealResult.failed(
                    TestSuiteStabilityCrossRetentionTrendAttestation.unavailable(
                            evidence, evidenceFingerprint, observations),
                    SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(
                    evidence.trendAnalysisId(), evidence.requestFingerprint(),
                    evidenceFingerprint, evidence.range().rangeFingerprint(),
                    observations, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityCrossRetentionTrendAttestation attestation =
                    new TestSuiteStabilityCrossRetentionTrendAttestation(
                            TestSuiteStabilityCrossRetentionTrendAttestation.SCHEMA_VERSION,
                            TestSuiteStabilityCrossRetentionTrendAttestation.SignatureStatus
                                    .VERIFIED,
                            evidence.trendAnalysisId(), evidence.requestFingerprint(),
                            evidenceFingerprint, evidence.range().rangeFingerprint(),
                            observations, signedAt, seal.keyId(), seal.algorithm(),
                            seal.signature(), true);
            if (verify(evidence, attestation) != Verification.VERIFIED) {
                return SealResult.failed(
                        TestSuiteStabilityCrossRetentionTrendAttestation.unavailable(
                                evidence, evidenceFingerprint, observations),
                        SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(
                    TestSuiteStabilityCrossRetentionTrendAttestation.unavailable(
                            evidence, evidenceFingerprint, observations),
                    SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes evidence, range, source closure, key resolution, and signature material.
     *
     * @param evidence exact range evidence
     * @param attestation detached range signature
     * @return bounded verification result
     */
    public Verification verify(
            TestSuiteStabilityCrossRetentionTrendEvidence evidence,
            TestSuiteStabilityCrossRetentionTrendAttestation attestation) {
        if (evidence == null || attestation == null) {
            return Verification.INVALID;
        }
        try {
            if (!structurallyValid(evidence)) {
                return Verification.INVALID;
            }
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        if (attestation.signatureStatus()
                == TestSuiteStabilityCrossRetentionTrendAttestation.SignatureStatus
                .VERIFICATION_UNAVAILABLE) {
            return Verification.UNAVAILABLE;
        }
        String evidenceFingerprint;
        List<ObservationRef> observations;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
            observations = observationRefs(evidence);
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        if (!attestation.independentlyVerifiable()
                || !evidence.trendAnalysisId().equals(attestation.trendAnalysisId())
                || !evidence.requestFingerprint().equals(attestation.requestFingerprint())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !evidence.range().rangeFingerprint().equals(attestation.rangeFingerprint())
                || !observations.equals(attestation.observationRefs())) {
            return Verification.INVALID;
        }
        if (!signer.available()) {
            return Verification.UNAVAILABLE;
        }
        try {
            VisualEvidenceSigner.KeyResolution resolution = signer.resolveKey(
                    attestation.keyId());
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
                    attestation.evidenceFingerprint(), attestation.rangeFingerprint(),
                    attestation.observationRefs(), attestation.signedAt());
            VisualEvidenceSigner.Verification verification = signer.verify(
                    new VisualRunEvidenceSeal("", materialFingerprint,
                            attestation.algorithm(), attestation.keyId(),
                            attestation.signedAt(), attestation.signature()),
                    materialFingerprint);
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
            String rangeFingerprint,
            List<ObservationRef> observations,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityCrossRetentionTrendAttestation.SCHEMA_VERSION,
                trendAnalysisId, requestFingerprint, evidenceFingerprint,
                rangeFingerprint, List.copyOf(observations), signedAt));
    }

    private static List<ObservationRef> observationRefs(
            TestSuiteStabilityCrossRetentionTrendEvidence evidence) {
        return evidence.range().entries().stream()
                .map(value -> new ObservationRef(
                        value.sequence(), value.observation().evidence().observationId(),
                        value.observation().evidenceFingerprint(),
                        value.observation().attestationFingerprint(),
                        value.entryFingerprint()))
                .toList();
    }

    private boolean structurallyValid(
            TestSuiteStabilityCrossRetentionTrendEvidence evidence) {
        return TestSuiteStabilityObservationLedgerRangeIntegrity.valid(
                objectMapper, evidence.range())
                && TestSuiteStabilityObservationLedgerHeadIntegrity.valid(
                objectMapper, evidence.range().head())
                && evidence.range().entries().stream().allMatch(entry ->
                TestSuiteStabilityObservationLedgerEntryIntegrity.valid(
                        objectMapper, entry));
    }

    /** Closed verification state separating invalid material from authority outage. */
    public enum Verification {
        /** Complete range and signature verified. */
        VERIFIED,
        /** Evidence, range, key state, or signature is invalid. */
        INVALID,
        /** Current authority cannot resolve the required verification key. */
        UNAVAILABLE
    }

    /**
     * Result of one immediate sign-and-verify operation.
     *
     * @param attestation verified or fail-closed signature manifest
     * @param failureCode stable bounded reason; blank on success
     */
    public record SealResult(
            TestSuiteStabilityCrossRetentionTrendAttestation attestation,
            String failureCode
    ) {
        /** Validates complete result material. */
        public SealResult {
            attestation = Objects.requireNonNull(attestation, "attestation");
            failureCode = failureCode == null ? "" : failureCode.trim();
        }

        /** @return successful verified result */
        public static SealResult verified(
                TestSuiteStabilityCrossRetentionTrendAttestation attestation) {
            return new SealResult(attestation, "");
        }

        /** @return fail-closed result */
        public static SealResult failed(
                TestSuiteStabilityCrossRetentionTrendAttestation attestation,
                String failureCode) {
            return new SealResult(attestation, Objects.requireNonNull(
                    failureCode, "failureCode"));
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
            String rangeFingerprint,
            List<ObservationRef> observationRefs,
            Instant signedAt) {
    }
}
