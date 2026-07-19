package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies exact compact-observation floor retirements in an independent domain.
 *
 * <p>The service rechecks deterministic ids, every nested canonical fingerprint, the complete
 * archive chain, previous floor, and pinned head before signing. It immediately verifies the new
 * detached signature so persistence never receives unchecked retirement authority.</p>
 */
public final class TestSuiteStabilityObservationFloorRetirementAttestationService {
    /** Stable failure when candidate evidence or nested archive material is invalid. */
    public static final String EVIDENCE_INVALID =
            "STABILITY_OBSERVATION_FLOOR_RETIREMENT_EVIDENCE_INVALID";
    /** Stable failure when no signing authority is currently available. */
    public static final String SIGNER_UNAVAILABLE =
            "STABILITY_OBSERVATION_FLOOR_RETIREMENT_SIGNER_UNAVAILABLE";
    /** Stable failure when a newly produced detached signature cannot be verified. */
    public static final String SIGNATURE_INVALID =
            "STABILITY_OBSERVATION_FLOOR_RETIREMENT_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a floor-retirement signing boundary using current UTC time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed Ed25519 evidence signer
     */
    public TestSuiteStabilityObservationFloorRetirementAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityObservationFloorRetirementAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Canonically signs and immediately verifies one exact retirement intent.
     *
     * @param evidence complete database-planned retirement evidence
     * @return verified signature or bounded fail-closed reason
     */
    public SealResult seal(TestSuiteStabilityObservationFloorRetirementEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!structurallyValid(evidence)) {
            return SealResult.failed(EVIDENCE_INVALID);
        }
        if (!signer.available()) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
        try {
            String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(
                    evidence, evidenceFingerprint, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityObservationFloorRetirementAttestation attestation =
                    new TestSuiteStabilityObservationFloorRetirementAttestation(
                            TestSuiteStabilityObservationFloorRetirementAttestation.SCHEMA_VERSION,
                            TestSuiteStabilityObservationFloorRetirementAttestation.SignatureStatus
                                    .VERIFIED,
                            evidence.retirementId(), evidenceFingerprint,
                            evidence.archiveSegment().segmentFingerprint(),
                            evidence.previousFloor().floorFingerprint(),
                            evidence.pinnedHead().headFingerprint(), signedAt,
                            seal.keyId(), seal.algorithm(), seal.signature(), true);
            if (verify(evidence, attestation) != Verification.VERIFIED) {
                return SealResult.failed(SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes the complete evidence closure and detached signature material.
     *
     * @param evidence exact database-planned retirement evidence
     * @param attestation detached retirement signature
     * @return bounded trust result
     */
    public Verification verify(
            TestSuiteStabilityObservationFloorRetirementEvidence evidence,
            TestSuiteStabilityObservationFloorRetirementAttestation attestation) {
        if (evidence == null || attestation == null || !structurallyValid(evidence)) {
            return Verification.INVALID;
        }
        String evidenceFingerprint;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        if (!attestation.independentlyVerifiable()
                || !evidence.retirementId().equals(attestation.retirementId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !evidence.archiveSegment().segmentFingerprint().equals(
                attestation.archiveSegmentFingerprint())
                || !evidence.previousFloor().floorFingerprint().equals(
                attestation.previousFloorFingerprint())
                || !evidence.pinnedHead().headFingerprint().equals(
                attestation.pinnedHeadFingerprint())) {
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
                    evidence, evidenceFingerprint, attestation.signedAt());
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

    private boolean structurallyValid(
            TestSuiteStabilityObservationFloorRetirementEvidence evidence) {
        try {
            TestSuiteStabilityObservationArchiveSegment archive = evidence.archiveSegment();
            return evidence.retirementId().equals(
                    TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                            objectMapper, evidence))
                    && archive.segmentId().equals(
                    TestSuiteStabilityObservationFloorRetirementIntegrity.archiveId(
                            objectMapper, archive.scopeFingerprint(), archive.suiteRef(),
                            archive.retirementGeneration(), archive.previousObservationId(),
                            archive.previousEntryFingerprint(), archive.retiredEntries(),
                            archive.successorEntry(), archive.archivedAt()))
                    && archive.segmentFingerprint().equals(
                    TestSuiteStabilityObservationFloorRetirementIntegrity.archiveFingerprint(
                            objectMapper, archive))
                    && TestSuiteStabilityObservationLedgerFloorIntegrity.valid(
                    objectMapper, evidence.previousFloor())
                    && TestSuiteStabilityObservationLedgerHeadIntegrity.valid(
                    objectMapper, evidence.pinnedHead())
                    && archive.retiredEntries().stream().allMatch(entry ->
                    TestSuiteStabilityObservationLedgerEntryIntegrity.valid(objectMapper, entry))
                    && TestSuiteStabilityObservationLedgerEntryIntegrity.valid(
                    objectMapper, archive.successorEntry());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String materialFingerprint(
            TestSuiteStabilityObservationFloorRetirementEvidence evidence,
            String evidenceFingerprint,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityObservationFloorRetirementAttestation.SCHEMA_VERSION,
                evidence.retirementId(), evidenceFingerprint,
                evidence.archiveSegment().segmentFingerprint(),
                evidence.previousFloor().floorFingerprint(),
                evidence.pinnedHead().headFingerprint(), signedAt));
    }

    /** Closed verification state separating invalid material from authority outage. */
    public enum Verification {
        /** Complete evidence and detached signature verified. */
        VERIFIED,
        /** Material, key state, or signature is invalid. */
        INVALID,
        /** Current authority cannot resolve the required key. */
        UNAVAILABLE
    }

    /**
     * Result of one immediate sign-and-verify operation.
     *
     * @param attestation verified attestation; null on failure
     * @param failureCode stable bounded reason; blank on success
     */
    public record SealResult(
            TestSuiteStabilityObservationFloorRetirementAttestation attestation,
            String failureCode
    ) {
        /** Normalizes one exclusive success-or-failure result. */
        public SealResult {
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((attestation == null) == failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Floor retirement seal result must contain exactly one outcome");
            }
        }

        /**
         * Creates a successful verified result.
         *
         * @param attestation verified detached attestation
         * @return successful result
         */
        public static SealResult verified(
                TestSuiteStabilityObservationFloorRetirementAttestation attestation) {
            return new SealResult(Objects.requireNonNull(attestation, "attestation"), "");
        }

        /**
         * Creates a fail-closed result without partial signature material.
         *
         * @param failureCode stable bounded failure reason
         * @return failed result
         */
        public static SealResult failed(String failureCode) {
            return new SealResult(null, Objects.requireNonNull(failureCode, "failureCode"));
        }

        /** @return whether a detached signature was generated and immediately verified */
        public boolean verified() {
            return attestation != null && failureCode.isBlank();
        }
    }

    private record SignatureMaterial(
            String schemaVersion,
            String retirementId,
            String evidenceFingerprint,
            String archiveSegmentFingerprint,
            String previousFloorFingerprint,
            String pinnedHeadFingerprint,
            Instant signedAt) {
    }
}
