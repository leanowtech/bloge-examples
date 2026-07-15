package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Dependency-light offline verifier for portable suite evidence and Ed25519 attestations.
 *
 * <p>The verifier recomputes bundle, aggregate, and signature-material fingerprints. It does not
 * trust the producer's {@code signatureStatus} claim and never requires child payload values.</p>
 */
public final class TestSuiteEvidenceVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration KEY_CREATION_SKEW = Duration.ofMinutes(5);

    /** Creates a dependency-light verifier with the protocol's fixed Ed25519 policy. */
    public TestSuiteEvidenceVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Every structural, fingerprint, key-policy, and signature check passed. */
        VERIFIED,
        /** Evidence, closure, fingerprint, key material, or signature is invalid. */
        INVALID,
        /** The required verification key is not available to the caller. */
        KEY_UNAVAILABLE,
        /** Key lifecycle or algorithm policy rejects otherwise parseable material. */
        POLICY_REJECTED
    }

    /**
     * Payload-free result suitable for CI logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param suiteRunId aggregate run id
     * @param keyId verification key id
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String suiteRunId,
            String keyId
    ) {
        /** Normalizes log-safe verification result fields. */
        public VerificationResult {
            if (outcome == null) {
                throw new IllegalArgumentException("Verification outcome is required");
            }
            reasonCode = normalized(reasonCode);
            suiteRunId = normalized(suiteRunId);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("Verification reason must be a machine code");
            }
        }

        /**
         * Indicates whether every independent verification step passed.
         *
         * @return true only when all independent checks passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one portable bundle with its resolved public key.
     *
     * @param bundle schema-validated portable terminal bundle
     * @param key verification key resolved by attestation key id; may be {@code null}
     * @return payload-free verification result
     */
    public VerificationResult verify(TestSuiteEvidenceBundle bundle,
                                     EvidenceVerificationKey key) {
        if (bundle == null) {
            return result(Outcome.INVALID, "BUNDLE_MISSING", "", "");
        }
        TestSuiteRunAttestation attestation = bundle.attestation();
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, "VERIFICATION_KEY_UNAVAILABLE",
                    bundle.suiteRunId(), attestation.keyId());
        }
        if (!attestation.terminallyVerifiable()
                || bundle.payloadPolicy() != TestSuiteEvidenceBundle.PayloadPolicy.OMITTED) {
            return result(Outcome.INVALID, "TERMINAL_ATTESTATION_REQUIRED",
                    bundle.suiteRunId(), attestation.keyId());
        }
        if (!key.keyId().equals(attestation.keyId())) {
            return result(Outcome.INVALID, "VERIFICATION_KEY_ID_MISMATCH",
                    bundle.suiteRunId(), attestation.keyId());
        }
        if (!"Ed25519".equals(attestation.algorithm())
                || !attestation.algorithm().equals(key.algorithm())) {
            return result(Outcome.POLICY_REJECTED, "SIGNATURE_ALGORITHM_REJECTED",
                    bundle.suiteRunId(), attestation.keyId());
        }
        if (!key.verificationAllowed()
                || attestation.signedAt().isBefore(key.createdAt().minus(KEY_CREATION_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "VERIFICATION_KEY_POLICY_REJECTED",
                    bundle.suiteRunId(), attestation.keyId());
        }
        try {
            if (!sha256(bundle.evidence()).equals(attestation.aggregateEvidenceFingerprint())) {
                return result(Outcome.INVALID, "AGGREGATE_FINGERPRINT_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            if (!closureMatches(bundle.evidence(), attestation.childEvidenceRefs())) {
                return result(Outcome.INVALID, "CHILD_EVIDENCE_CLOSURE_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            ObjectNode bundleMaterial = JSON.createObjectNode();
            bundleMaterial.put("payloadPolicy", bundle.payloadPolicy().name());
            bundleMaterial.set("attestation", bundle.rawResponse().path("attestation").deepCopy());
            bundleMaterial.set("evidence", bundle.evidence());
            if (!sha256(bundleMaterial).equals(bundle.bundleFingerprint())) {
                return result(Outcome.INVALID, "BUNDLE_FINGERPRINT_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            String materialFingerprint = sha256(signatureMaterial(attestation));
            if (!verifyEd25519(materialFingerprint, attestation.signature(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "ATTESTATION_SIGNATURE_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            return result(Outcome.VERIFIED, "VERIFIED", bundle.suiteRunId(), attestation.keyId());
        } catch (RuntimeException | GeneralSecurityException failure) {
            return result(Outcome.INVALID, "ATTESTATION_MATERIAL_INVALID",
                    bundle.suiteRunId(), attestation.keyId());
        }
    }

    private static boolean closureMatches(
            JsonNode evidence, List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        List<String> expected = new ArrayList<>();
        evidence.path("caseResults").forEach(result -> {
            if (!result.path("runId").asText().isBlank()) {
                expected.add(result.path("caseId").asText() + "\u0000" + result.path("runId").asText());
            }
        });
        List<String> actual = children.stream()
                .map(child -> child.caseId() + "\u0000" + child.runId()).toList();
        return expected.equals(actual);
    }

    private static ObjectNode signatureMaterial(TestSuiteRunAttestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1);
        material.put("scope", value.scope().name());
        material.put("suiteRunId", value.suiteRunId());
        ObjectNode suiteRef = material.putObject("suiteRef");
        suiteRef.put("suiteId", value.suiteRef().suiteId());
        suiteRef.put("revision", value.suiteRef().revision());
        suiteRef.put("fingerprint", value.suiteRef().fingerprint());
        material.put("requestFingerprint", value.requestFingerprint());
        material.put("aggregateEvidenceFingerprint", value.aggregateEvidenceFingerprint());
        ArrayNode children = material.putArray("childEvidenceRefs");
        value.childEvidenceRefs().forEach(child -> {
            ObjectNode ref = children.addObject();
            ref.put("caseId", child.caseId());
            ref.put("runId", child.runId());
            ref.put("evidenceFingerprint", child.evidenceFingerprint());
        });
        material.put("signedAt", value.signedAt().toString());
        return material;
    }

    private static boolean verifyEd25519(String materialFingerprint, String encodedSignature,
                                         String encodedPublicKey) throws GeneralSecurityException {
        byte[] publicKey = Base64.getDecoder().decode(encodedPublicKey);
        byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signatureBytes);
    }

    private static String sha256(JsonNode value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("Canonical evidence cannot be fingerprinted", failure);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static VerificationResult result(Outcome outcome, String reason,
                                             String suiteRunId, String keyId) {
        return new VerificationResult(outcome, reason, suiteRunId, keyId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
