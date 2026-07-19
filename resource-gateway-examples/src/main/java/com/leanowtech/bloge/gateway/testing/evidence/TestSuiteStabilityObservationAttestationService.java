package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.RunObservation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Builds and signs compact stability observations in a domain separate from source and trend data.
 *
 * <p>The service first verifies the original terminal stability signature, then projects only the
 * payload-free longitudinal material, signs that projection, and immediately verifies the new
 * signature. A failed projection or signer can therefore never enter the durable ledger.</p>
 */
public final class TestSuiteStabilityObservationAttestationService {
    /** Stable failure when original stability evidence is invalid. */
    public static final String SOURCE_INVALID = "STABILITY_OBSERVATION_SOURCE_INVALID";
    /** Stable failure when no signing authority can establish observation trust. */
    public static final String SIGNER_UNAVAILABLE =
            "STABILITY_OBSERVATION_SIGNER_UNAVAILABLE";
    /** Stable failure when a newly produced observation signature does not verify. */
    public static final String SIGNATURE_INVALID =
            "STABILITY_OBSERVATION_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;
    private final TestSuiteStabilityAttestationService sourceAttestations;
    private final TestSuiteStabilityObservationProjector projector;

    /**
     * Creates an observation boundary using UTC system time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence signer
     * @param sourceAttestations original stability signature verifier
     */
    public TestSuiteStabilityObservationAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            TestSuiteStabilityAttestationService sourceAttestations) {
        this(objectMapper, signer, sourceAttestations, Clock.systemUTC());
    }

    TestSuiteStabilityObservationAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            TestSuiteStabilityAttestationService sourceAttestations,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.sourceAttestations = Objects.requireNonNull(
                sourceAttestations, "sourceAttestations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projector = new TestSuiteStabilityObservationProjector(objectMapper);
    }

    /**
     * Verifies, projects, signs, and immediately verifies one compact observation.
     *
     * @param record complete signed terminal stability source
     * @return verified observation or bounded fail-closed diagnostic
     */
    public SealResult seal(TestSuiteStabilityRunRecord record) {
        Objects.requireNonNull(record, "record");
        RunObservation source;
        String sourceAttestationFingerprint;
        try {
            if (sourceAttestations.verify(record.evidence(), record.attestation())
                    != TestSuiteStabilityAttestationService.Verification.VERIFIED
                    || !record.evidenceFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, record.evidence()))) {
                return SealResult.failed(SOURCE_INVALID);
            }
            source = projector.project(record);
            sourceAttestationFingerprint = ProtocolFingerprint.of(
                    objectMapper, record.attestation());
        } catch (RuntimeException invalid) {
            return SealResult.failed(SOURCE_INVALID);
        }
        String scopeFingerprint = ProtocolFingerprint.of(objectMapper,
                new ScopeIdentity(record.tenantId(), record.environmentId(),
                        record.evidence().suiteRef()));
        String observationId = observationId(scopeFingerprint, record, sourceAttestationFingerprint);
        TestSuiteStabilityObservationEvidence evidence =
                new TestSuiteStabilityObservationEvidence(
                        TestSuiteStabilityObservationEvidence.SCHEMA_VERSION,
                        observationId, scopeFingerprint, record.evidence().suiteRef(),
                        record.requestFingerprint(), source);
        String observationFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        if (!signer.available()) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(
                    evidence, observationFingerprint, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityObservationAttestation attestation =
                    new TestSuiteStabilityObservationAttestation(
                            TestSuiteStabilityObservationAttestation.SCHEMA_VERSION,
                            TestSuiteStabilityObservationAttestation.SignatureStatus.VERIFIED,
                            observationId, observationFingerprint,
                            source.evidenceFingerprint(), source.attestationFingerprint(),
                            signedAt, seal.keyId(), seal.algorithm(), seal.signature(), true);
            TestSuiteStabilityObservation observation = new TestSuiteStabilityObservation(
                    observationFingerprint, evidence,
                    ProtocolFingerprint.of(objectMapper, attestation), attestation);
            if (verify(observation) != Verification.VERIFIED) {
                return SealResult.failed(SIGNATURE_INVALID);
            }
            return SealResult.verified(observation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes the complete observation and detached signature material.
     *
     * @param observation persisted compact observation
     * @return bounded trust result
     */
    public Verification verify(TestSuiteStabilityObservation observation) {
        if (observation == null || observation.evidence() == null
                || observation.attestation() == null
                || !observation.attestation().terminallyVerifiable()) {
            return Verification.INVALID;
        }
        try {
            TestSuiteStabilityObservationEvidence evidence = observation.evidence();
            TestSuiteStabilityObservationAttestation attestation = observation.attestation();
            String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
            String attestationFingerprint = ProtocolFingerprint.of(objectMapper, attestation);
            String expectedId = observationId(evidence.scopeFingerprint(), evidence.suiteRef(),
                    evidence.sourceRequestFingerprint(), evidence.source().stabilityRunId(),
                    evidence.source().evidenceFingerprint(),
                    evidence.source().attestationFingerprint());
            if (!expectedId.equals(evidence.observationId())
                    || !evidenceFingerprint.equals(observation.evidenceFingerprint())
                    || !attestationFingerprint.equals(observation.attestationFingerprint())
                    || !evidence.observationId().equals(attestation.observationId())
                    || !evidenceFingerprint.equals(attestation.observationFingerprint())
                    || !evidence.source().evidenceFingerprint().equals(
                    attestation.sourceEvidenceFingerprint())
                    || !evidence.source().attestationFingerprint().equals(
                    attestation.sourceAttestationFingerprint())) {
                return Verification.INVALID;
            }
            if (!signer.available()) {
                return Verification.UNAVAILABLE;
            }
            VisualEvidenceSigner.KeyResolution key = signer.resolveKey(attestation.keyId());
            if (key.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE) {
                return Verification.UNAVAILABLE;
            }
            if (key.key() == null || !attestation.algorithm().equals(key.key().algorithm())
                    || !java.util.List.of("ACTIVE", "RETIRED").contains(key.key().state())) {
                return Verification.INVALID;
            }
            String materialFingerprint = materialFingerprint(
                    evidence, evidenceFingerprint, attestation.signedAt());
            VisualEvidenceSigner.Verification result = signer.verify(
                    new VisualRunEvidenceSeal("", materialFingerprint,
                            attestation.algorithm(), attestation.keyId(), attestation.signedAt(),
                            attestation.signature()), materialFingerprint);
            return result.valid() ? Verification.VERIFIED
                    : "KEY_UNAVAILABLE".equals(result.status())
                    || "UNAVAILABLE".equals(result.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
    }

    private String observationId(
            String scopeFingerprint,
            TestSuiteStabilityRunRecord record,
            String sourceAttestationFingerprint) {
        return observationId(scopeFingerprint, record.evidence().suiteRef(),
                record.requestFingerprint(), record.stabilityRunId(),
                record.evidenceFingerprint(), sourceAttestationFingerprint);
    }

    private String observationId(
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String sourceRequestFingerprint,
            String stabilityRunId,
            String sourceEvidenceFingerprint,
            String sourceAttestationFingerprint) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, new ObservationIdentity(
                TestSuiteStabilityObservationEvidence.SCHEMA_VERSION,
                scopeFingerprint, suiteRef, sourceRequestFingerprint, stabilityRunId,
                sourceEvidenceFingerprint, sourceAttestationFingerprint));
        return "stability-observation-" + fingerprint.substring("sha256:".length());
    }

    private String materialFingerprint(
            TestSuiteStabilityObservationEvidence evidence,
            String evidenceFingerprint,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityObservationAttestation.SCHEMA_VERSION,
                evidence.observationId(), evidenceFingerprint,
                evidence.source().evidenceFingerprint(),
                evidence.source().attestationFingerprint(), signedAt));
    }

    private record ScopeIdentity(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
    }

    private record ObservationIdentity(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String sourceRequestFingerprint,
            String stabilityRunId,
            String sourceEvidenceFingerprint,
            String sourceAttestationFingerprint) {
    }

    private record SignatureMaterial(
            String schemaVersion,
            String observationId,
            String observationFingerprint,
            String sourceEvidenceFingerprint,
            String sourceAttestationFingerprint,
            Instant signedAt) {
    }

    /** Observation trust result with invalid material separated from authority unavailability. */
    public enum Verification {
        /** Complete observation and detached signature verified. */
        VERIFIED,
        /** Material or signature is invalid. */
        INVALID,
        /** Current authority cannot resolve the required key. */
        UNAVAILABLE
    }

    /**
     * Result of preparing one compact observation.
     *
     * @param observation complete verified observation; null on failure
     * @param failureCode bounded stable diagnostic; blank on success
     */
    public record SealResult(
            TestSuiteStabilityObservation observation,
            String failureCode
    ) {
        /** Normalizes one preparation result. */
        public SealResult {
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((observation == null) == failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Observation seal result must contain exactly one outcome");
            }
        }

        /** @return successful verified observation */
        public static SealResult verified(TestSuiteStabilityObservation observation) {
            return new SealResult(Objects.requireNonNull(observation, "observation"), "");
        }

        /** @return fail-closed result without partial signature material */
        public static SealResult failed(String failureCode) {
            return new SealResult(null, Objects.requireNonNull(failureCode, "failureCode"));
        }

        /** @return whether a verified observation is available */
        public boolean verified() {
            return observation != null && failureCode.isBlank();
        }
    }
}
