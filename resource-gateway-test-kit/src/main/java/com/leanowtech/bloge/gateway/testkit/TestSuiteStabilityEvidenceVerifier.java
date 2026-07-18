package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Clock;

/**
 * Dependency-light offline verifier for signed suite-stability evidence.
 *
 * <p>The typed projection has already re-derived aggregate semantics and source closure. This
 * verifier independently recomputes canonical evidence and signature material, applies public-key
 * or externally pinned lifecycle policy, and verifies the Ed25519 signature. Producer trust labels
 * alone can never produce a verified result.</p>
 */
public final class TestSuiteStabilityEvidenceVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;

    /** Creates a verifier using current UTC time for key-set freshness decisions. */
    public TestSuiteStabilityEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteStabilityEvidenceVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Structure, semantics, fingerprints, key policy, and signature all passed. */
        VERIFIED,
        /** Evidence, closure, fingerprint, key material, or signature is invalid. */
        INVALID,
        /** The required verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Key lifecycle or algorithm policy rejects the material. */
        POLICY_REJECTED
    }

    /**
     * Payload-free verification result suitable for CI logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param stabilityRunId deterministic analysis id
     * @param keyId verification key id
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String stabilityRunId,
            String keyId
    ) {
        /** Normalizes log-safe result fields. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            stabilityRunId = normalized(stabilityRunId);
            keyId = normalized(keyId);
            if (outcome == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("Stability verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a verified result
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one stability result with its resolved public key.
     *
     * @param run schema-validated and semantically re-derived stability result
     * @param key public verification key resolved by attestation key id; may be {@code null}
     * @return payload-free verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityRun run,
            EvidenceVerificationKey key) {
        if (run == null) {
            return result(Outcome.INVALID, "STABILITY_EVIDENCE_MISSING", "", "");
        }
        TestSuiteStabilityAttestation attestation = run.attestation();
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, "VERIFICATION_KEY_UNAVAILABLE",
                    run.stabilityRunId(), attestation.keyId());
        }
        if (!attestation.terminallyVerifiable()) {
            return result(Outcome.INVALID, "TERMINAL_STABILITY_ATTESTATION_REQUIRED",
                    run.stabilityRunId(), attestation.keyId());
        }
        if (!key.keyId().equals(attestation.keyId())) {
            return result(Outcome.INVALID, "VERIFICATION_KEY_ID_MISMATCH",
                    run.stabilityRunId(), attestation.keyId());
        }
        if (!"Ed25519".equals(attestation.algorithm())
                || !attestation.algorithm().equals(key.algorithm())) {
            return result(Outcome.POLICY_REJECTED, "SIGNATURE_ALGORITHM_REJECTED",
                    run.stabilityRunId(), attestation.keyId());
        }
        if (!key.verificationAllowed() || attestation.signedAt().isBefore(
                key.createdAt().minus(EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "VERIFICATION_KEY_POLICY_REJECTED",
                    run.stabilityRunId(), attestation.keyId());
        }
        try {
            String actualEvidenceFingerprint = EvidenceVerificationSupport.sha256(
                    run.rawResponse().path("evidence"));
            if (!run.evidenceFingerprint().equals(actualEvidenceFingerprint)
                    || !attestation.evidenceFingerprint().equals(actualEvidenceFingerprint)) {
                return result(Outcome.INVALID, "STABILITY_EVIDENCE_FINGERPRINT_INVALID",
                        run.stabilityRunId(), attestation.keyId());
            }
            String materialFingerprint = EvidenceVerificationSupport.sha256(
                    signatureMaterial(attestation));
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    attestation.signature(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "STABILITY_ATTESTATION_SIGNATURE_INVALID",
                        run.stabilityRunId(), attestation.keyId());
            }
            return result(Outcome.VERIFIED, "VERIFIED",
                    run.stabilityRunId(), attestation.keyId());
        } catch (RuntimeException | GeneralSecurityException failure) {
            return result(Outcome.INVALID, "STABILITY_ATTESTATION_MATERIAL_INVALID",
                    run.stabilityRunId(), attestation.keyId());
        }
    }

    /**
     * Performs release-grade verification against an externally pinned key-set snapshot.
     *
     * @param run schema-validated and semantically re-derived stability result
     * @param keySet complete signed public-key lifecycle snapshot
     * @param trustedSnapshotFingerprint snapshot fingerprint obtained from an independent channel
     * @return payload-free verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityRun run,
            EvidenceVerificationKeySet keySet,
            String trustedSnapshotFingerprint) {
        if (run == null) {
            return result(Outcome.INVALID, "STABILITY_EVIDENCE_MISSING", "", "");
        }
        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                new TestSuiteEvidenceVerifier(clock).verifyKeySet(
                        keySet, trustedSnapshotFingerprint);
        if (!keySetResult.verified()) {
            return result(Outcome.valueOf(keySetResult.outcome().name()),
                    keySetResult.reasonCode(), run.stabilityRunId(), run.attestation().keyId());
        }
        String lifecycleReason = EvidenceVerificationSupport.signingTimePolicyReason(
                keySet, run.attestation().keyId(), run.attestation().signedAt());
        if (!lifecycleReason.isBlank()) {
            return result(Outcome.POLICY_REJECTED, lifecycleReason,
                    run.stabilityRunId(), run.attestation().keyId());
        }
        EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                .filter(candidate -> candidate.keyId().equals(run.attestation().keyId()))
                .findFirst().orElse(null);
        if (policy == null) {
            return result(Outcome.KEY_UNAVAILABLE, "EVIDENCE_KEY_NOT_IN_PINNED_SET",
                    run.stabilityRunId(), run.attestation().keyId());
        }
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, policy.keyId(), policy.algorithm(),
                policy.encodedPublicKey(), policy.notBefore(),
                policy.state() == EvidenceVerificationKeySet.KeyState.ACTIVE
                        ? "ACTIVE" : "RETIRED", keySet.provider());
        return verify(run, key);
    }

    private static ObjectNode signatureMaterial(TestSuiteStabilityAttestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", value.schemaVersion());
        material.put("stabilityRunId", value.stabilityRunId());
        ObjectNode suite = material.putObject("suiteRef");
        suite.put("suiteId", value.suiteRef().suiteId());
        suite.put("revision", value.suiteRef().revision());
        suite.put("fingerprint", value.suiteRef().fingerprint());
        material.put("requestFingerprint", value.requestFingerprint());
        material.put("evidenceFingerprint", value.evidenceFingerprint());
        ArrayNode sources = material.putArray("sourceSuiteEvidenceRefs");
        value.sourceSuiteEvidenceRefs().forEach(source -> {
            ObjectNode ref = sources.addObject();
            ref.put("attempt", source.attempt());
            ref.put("suiteRunId", source.suiteRunId());
            ref.put("aggregateEvidenceFingerprint", source.aggregateEvidenceFingerprint());
        });
        material.put("signedAt", value.signedAt().toString());
        return material;
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            String stabilityRunId,
            String keyId) {
        return new VerificationResult(outcome, reason, stabilityRunId, keyId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
