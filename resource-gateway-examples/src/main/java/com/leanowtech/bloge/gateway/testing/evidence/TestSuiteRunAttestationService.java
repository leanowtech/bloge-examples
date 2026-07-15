package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies suite-run checkpoints and terminal aggregate evidence.
 *
 * <p>The service deliberately uses a protocol domain distinct from child-run integrity. Every
 * signature is verified immediately before it can cross a persistence boundary.</p>
 */
public final class TestSuiteRunAttestationService {

    /** Stable diagnostic emitted when the signing authority is unavailable. */
    public static final String SIGNER_UNAVAILABLE = "TEST_SUITE_ATTESTATION_SIGNER_UNAVAILABLE";
    /** Stable diagnostic emitted when newly produced signature material is invalid. */
    public static final String SIGNATURE_INVALID = "TEST_SUITE_ATTESTATION_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;
    private final TestSuiteRunEvidenceProtocolCodec evidenceCodec;

    /**
     * Creates an attestation boundary using UTC system time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed evidence signer
     */
    public TestSuiteRunAttestationService(ObjectMapper objectMapper,
                                          VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteRunAttestationService(ObjectMapper objectMapper,
                                   VisualEvidenceSigner signer, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceCodec = new TestSuiteRunEvidenceProtocolCodec(objectMapper);
    }

    /**
     * Fingerprints, signs, and immediately verifies one aggregate and child closure.
     *
     * @param evidence checkpoint or terminal aggregate evidence
     * @param requestFingerprint canonical suite-execution request fingerprint
     * @param children ordered verified child evidence closure
     * @param scope checkpoint or terminal signature scope
     * @return verified attestation or a bounded fail-closed result
     */
    public SealResult seal(TestSuiteRunEvidenceProtocol evidence, String requestFingerprint,
                           List<TestSuiteRunAttestation.ChildEvidenceRef> children,
                           TestSuiteRunAttestation.Scope scope) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(scope, "scope");
        List<TestSuiteRunAttestation.ChildEvidenceRef> safeChildren = immutable(children);
        String aggregateFingerprint = evidenceCodec.fingerprint(evidence);
        if (!signer.available()) {
            return SealResult.failed(TestSuiteRunAttestation.unavailable(scope, evidence,
                    requestFingerprint, aggregateFingerprint, safeChildren), SIGNER_UNAVAILABLE);
        }
        try {
            Instant signedAt = clock.instant();
            String schemaVersion = attestationVersion(evidence);
            String materialFingerprint = materialFingerprint(schemaVersion, scope, evidence.suiteRunId(),
                    evidence.suiteRef(), requestFingerprint, aggregateFingerprint, safeChildren, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteRunAttestation attestation = new TestSuiteRunAttestation(schemaVersion,
                    TestSuiteRunAttestation.SignatureStatus.VERIFIED, scope, evidence.suiteRunId(),
                    evidence.suiteRef(), requestFingerprint, aggregateFingerprint, safeChildren,
                    signedAt, seal.keyId(), seal.algorithm(), seal.signature(), true);
            if (verify(evidence, attestation) != Verification.VERIFIED) {
                return SealResult.failed(TestSuiteRunAttestation.unavailable(scope, evidence,
                        requestFingerprint, aggregateFingerprint, safeChildren), SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException failure) {
            return SealResult.failed(TestSuiteRunAttestation.unavailable(scope, evidence,
                    requestFingerprint, aggregateFingerprint, safeChildren), SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes aggregate and signature material before trusting an attestation.
     *
     * @param evidence exact checkpoint or terminal aggregate
     * @param attestation persisted signature manifest
     * @return bounded trust result
     */
    public Verification verify(TestSuiteRunEvidenceProtocol evidence,
                               TestSuiteRunAttestation attestation) {
        if (evidence == null || attestation == null
                || attestation.signatureStatus() == TestSuiteRunAttestation.SignatureStatus.UNSIGNED) {
            return Verification.UNSIGNED;
        }
        if (attestation.signatureStatus()
                == TestSuiteRunAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE) {
            return Verification.UNAVAILABLE;
        }
        if (!attestationVersion(evidence).equals(attestation.schemaVersion())
                || !attestation.independentlyVerifiable()
                || !evidence.suiteRunId().equals(attestation.suiteRunId())
                || !Objects.equals(evidence.suiteRef(), attestation.suiteRef())) {
            return Verification.INVALID;
        }
        String aggregateFingerprint;
        try {
            aggregateFingerprint = evidenceCodec.fingerprint(evidence);
        } catch (RuntimeException failure) {
            return Verification.INVALID;
        }
        if (!aggregateFingerprint.equals(attestation.aggregateEvidenceFingerprint())) {
            return Verification.INVALID;
        }
        if (!signer.available()) {
            return Verification.UNAVAILABLE;
        }
        try {
            VisualEvidenceSigner.KeyResolution keyResolution = signer.resolveKey(attestation.keyId());
            if (keyResolution.status()
                    != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE
                    || keyResolution.key() == null) {
                return Verification.UNAVAILABLE;
            }
            VisualEvidenceSigner.VerificationKey key = keyResolution.key();
            if (!attestation.algorithm().equals(key.algorithm())
                    || !List.of("ACTIVE", "RETIRED").contains(key.state())) {
                return Verification.INVALID;
            }
            String materialFingerprint = materialFingerprint(attestation.schemaVersion(),
                    attestation.scope(),
                    attestation.suiteRunId(), attestation.suiteRef(),
                    attestation.requestFingerprint(), attestation.aggregateEvidenceFingerprint(),
                    attestation.childEvidenceRefs(), attestation.signedAt());
            VisualEvidenceSigner.Verification result = signer.verify(new VisualRunEvidenceSeal("",
                    materialFingerprint, attestation.algorithm(), attestation.keyId(),
                    attestation.signedAt(), attestation.signature()), materialFingerprint);
            if (result.valid()) {
                return Verification.VERIFIED;
            }
            return "KEY_UNAVAILABLE".equals(result.status()) || "UNAVAILABLE".equals(result.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException failure) {
            return Verification.UNAVAILABLE;
        }
    }

    private String materialFingerprint(
            String schemaVersion, TestSuiteRunAttestation.Scope scope, String suiteRunId,
            TestSuiteExecutionRequest.SuiteRef suiteRef, String requestFingerprint,
            String aggregateFingerprint,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children, Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                schemaVersion, scope, suiteRunId, suiteRef,
                requestFingerprint, aggregateFingerprint, immutable(children), signedAt));
    }

    private static String attestationVersion(TestSuiteRunEvidenceProtocol evidence) {
        return evidence instanceof TestSuiteRunEvidenceV2
                ? TestSuiteRunAttestation.SCHEMA_VERSION_V2
                : TestSuiteRunAttestation.SCHEMA_VERSION;
    }

    private static List<TestSuiteRunAttestation.ChildEvidenceRef> immutable(
            List<TestSuiteRunAttestation.ChildEvidenceRef> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record SignatureMaterial(
            String schemaVersion,
            TestSuiteRunAttestation.Scope scope,
            String suiteRunId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String requestFingerprint,
            String aggregateEvidenceFingerprint,
            List<TestSuiteRunAttestation.ChildEvidenceRef> childEvidenceRefs,
            Instant signedAt
    ) {
    }

    /** Signature trust result with invalid material separated from provider unavailability. */
    public enum Verification {
        /** Aggregate and detached signature are valid. */
        VERIFIED,
        /** Aggregate, closure, or signature was altered. */
        INVALID,
        /** Historical material has no attestation. */
        UNSIGNED,
        /** Verification key or provider is temporarily unavailable. */
        UNAVAILABLE
    }

    /**
     * Result of signing one aggregate closure.
     *
     * @param attestation verified or fail-closed attestation
     * @param failureCode bounded stable diagnostic; blank on success
     */
    public record SealResult(TestSuiteRunAttestation attestation, String failureCode) {
        /** Normalizes result values. */
        public SealResult {
            attestation = Objects.requireNonNull(attestation, "attestation");
            failureCode = failureCode == null ? "" : failureCode.trim();
        }

        /** @return successfully verified result */
        public static SealResult verified(TestSuiteRunAttestation attestation) {
            return new SealResult(attestation, "");
        }

        /** @return fail-closed signing result */
        public static SealResult failed(TestSuiteRunAttestation attestation, String failureCode) {
            return new SealResult(attestation, failureCode);
        }

        /** @return true only for a verified signature */
        public boolean verified() {
            return failureCode.isBlank() && attestation.independentlyVerifiable();
        }
    }
}
