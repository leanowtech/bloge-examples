package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-light offline verifier for portable suite evidence and Ed25519 attestations.
 *
 * <p>The verifier recomputes bundle, aggregate, and signature-material fingerprints. For bounded
 * property evidence it also re-derives the typed trial, minimum-counterexample, and coverage
 * closure. For mutation evidence it re-derives baseline state, mutant classification, score, and
 * the prefixed baseline/mutant child closure. It does not trust the producer's
 * {@code signatureStatus} claim and never requires child payload values.</p>
 */
public final class TestSuiteEvidenceVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;

    /** Creates a dependency-light verifier with the protocol's fixed Ed25519 policy. */
    public TestSuiteEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteEvidenceVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
     * Result of validating a pinned key-set snapshot before any embedded key is trusted.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine reason
     * @param snapshotFingerprint observed snapshot fingerprint
     * @param attestationKeyId key that signed the snapshot material
     */
    public record KeySetVerificationResult(
            Outcome outcome,
            String reasonCode,
            String snapshotFingerprint,
            String attestationKeyId
    ) {
        /** Normalizes payload-free result fields. */
        public KeySetVerificationResult {
            if (outcome == null) {
                throw new IllegalArgumentException("Key-set verification outcome is required");
            }
            reasonCode = normalized(reasonCode);
            snapshotFingerprint = normalized(snapshotFingerprint);
            attestationKeyId = normalized(attestationKeyId);
        }

        /**
         * Indicates whether the complete key-set trust decision passed.
         *
         * @return true only when pin, material, policy freshness, and signature all passed
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
        String evidenceVersion = bundle.evidence().path("schemaVersion").asText();
        String expectedAttestationVersion;
        String expectedBundleVersion;
        if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V5.equals(evidenceVersion)) {
            expectedAttestationVersion = TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V5;
            expectedBundleVersion = TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V5;
        } else if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V4.equals(evidenceVersion)) {
            expectedAttestationVersion = TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V4;
            expectedBundleVersion = TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V4;
        } else if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V3.equals(evidenceVersion)) {
            expectedAttestationVersion = TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3;
            expectedBundleVersion = TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3;
        } else if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2.equals(evidenceVersion)) {
            expectedAttestationVersion = TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V2;
            expectedBundleVersion = TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V2;
        } else {
            expectedAttestationVersion = TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1;
            expectedBundleVersion = TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1;
        }
        if (!expectedAttestationVersion.equals(attestation.schemaVersion())
                || !expectedBundleVersion.equals(
                bundle.rawResponse().path("schemaVersion").asText())) {
            return result(Outcome.INVALID, "EVIDENCE_GENERATION_MISMATCH",
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
                || attestation.signedAt().isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "VERIFICATION_KEY_POLICY_REJECTED",
                    bundle.suiteRunId(), attestation.keyId());
        }
        try {
            if (!EvidenceVerificationSupport.sha256(bundle.evidence())
                    .equals(attestation.aggregateEvidenceFingerprint())) {
                return result(Outcome.INVALID, "AGGREGATE_FINGERPRINT_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            if (!closureMatches(bundle.evidence(), attestation.childEvidenceRefs())) {
                return result(Outcome.INVALID, "CHILD_EVIDENCE_CLOSURE_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V4.equals(evidenceVersion)
                    && !propertySemanticsMatch(bundle)) {
                return result(Outcome.INVALID, "PROPERTY_EVIDENCE_SEMANTICS_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            if (TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V5.equals(evidenceVersion)
                    && !mutationSemanticsMatch(bundle)) {
                return result(Outcome.INVALID, "MUTATION_EVIDENCE_SEMANTICS_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            ObjectNode bundleMaterial = JSON.createObjectNode();
            bundleMaterial.put("payloadPolicy", bundle.payloadPolicy().name());
            bundleMaterial.set("attestation", bundle.rawResponse().path("attestation").deepCopy());
            bundleMaterial.set("evidence", bundle.evidence());
            if (!EvidenceVerificationSupport.sha256(bundleMaterial)
                    .equals(bundle.bundleFingerprint())) {
                return result(Outcome.INVALID, "BUNDLE_FINGERPRINT_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            String materialFingerprint = EvidenceVerificationSupport.sha256(
                    signatureMaterial(attestation));
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint, attestation.signature(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "ATTESTATION_SIGNATURE_INVALID",
                        bundle.suiteRunId(), attestation.keyId());
            }
            return result(Outcome.VERIFIED, "VERIFIED", bundle.suiteRunId(), attestation.keyId());
        } catch (RuntimeException | GeneralSecurityException failure) {
            return result(Outcome.INVALID, "ATTESTATION_MATERIAL_INVALID",
                    bundle.suiteRunId(), attestation.keyId());
        }
    }

    private static boolean propertySemanticsMatch(TestSuiteEvidenceBundle bundle) {
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V5);
        response.put("suiteRunId", bundle.suiteRunId());
        response.put("evidenceFingerprint",
                bundle.attestation().aggregateEvidenceFingerprint());
        response.set("evidence", bundle.evidence().deepCopy());
        response.set("attestation", bundle.rawResponse().path("attestation").deepCopy());
        try {
            TestSuiteRun.from(response);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean mutationSemanticsMatch(TestSuiteEvidenceBundle bundle) {
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V6);
        response.put("suiteRunId", bundle.suiteRunId());
        response.put("evidenceFingerprint",
                bundle.attestation().aggregateEvidenceFingerprint());
        response.set("evidence", bundle.evidence().deepCopy());
        response.set("attestation", bundle.rawResponse().path("attestation").deepCopy());
        try {
            TestSuiteRun.from(response);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /**
     * Verifies a suite bundle against a complete, externally pinned key lifecycle snapshot.
     *
     * @param bundle terminal suite evidence
     * @param keySet signed multi-key policy snapshot
     * @param trustedSnapshotFingerprint fingerprint obtained from an independent trust channel
     * @return release-grade verification result
     */
    public VerificationResult verify(TestSuiteEvidenceBundle bundle,
                                     EvidenceVerificationKeySet keySet,
                                     String trustedSnapshotFingerprint) {
        if (bundle == null) {
            return result(Outcome.INVALID, "BUNDLE_MISSING", "", "");
        }
        KeySetVerificationResult keySetResult = verifyKeySet(keySet, trustedSnapshotFingerprint);
        if (!keySetResult.verified()) {
            return result(keySetResult.outcome(), keySetResult.reasonCode(), bundle.suiteRunId(),
                    bundle.attestation().keyId());
        }
        EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                .filter(key -> key.keyId().equals(bundle.attestation().keyId()))
                .findFirst().orElse(null);
        if (policy == null) {
            return result(Outcome.KEY_UNAVAILABLE, "EVIDENCE_KEY_NOT_IN_PINNED_SET",
                    bundle.suiteRunId(), bundle.attestation().keyId());
        }
        VerificationResult policyResult = applyLifecyclePolicy(bundle, keySet, policy);
        if (policyResult != null) {
            return policyResult;
        }
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, policy.keyId(), policy.algorithm(),
                policy.encodedPublicKey(), policy.notBefore(),
                policy.state() == EvidenceVerificationKeySet.KeyState.ACTIVE ? "ACTIVE" : "RETIRED",
                keySet.provider());
        return verify(bundle, key);
    }

    /**
     * Verifies that a key-set snapshot matches an external pin and a valid active-key signature.
     *
     * @param keySet candidate snapshot
     * @param trustedSnapshotFingerprint independently trusted material fingerprint
     * @return bounded trust result without key or evidence payloads
     */
    public KeySetVerificationResult verifyKeySet(EvidenceVerificationKeySet keySet,
                                                 String trustedSnapshotFingerprint) {
        String pin = normalized(trustedSnapshotFingerprint);
        if (keySet == null) {
            return keySetResult(Outcome.KEY_UNAVAILABLE, "KEY_SET_UNAVAILABLE", "", "");
        }
        if (!pin.matches("sha256:[0-9a-f]{64}") || !pin.equals(keySet.snapshotFingerprint())) {
            return keySetResult(Outcome.POLICY_REJECTED, "KEY_SET_PIN_MISMATCH",
                    keySet.snapshotFingerprint(), keySet.attestation().keyId());
        }
        if (keySet.policyCompleteness() != EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE) {
            return keySetResult(Outcome.POLICY_REJECTED, "KEY_LIFECYCLE_POLICY_INCOMPLETE",
                    keySet.snapshotFingerprint(), keySet.attestation().keyId());
        }
        Instant now = clock.instant();
        if (!keySet.expiresAt().isAfter(now)) {
            return keySetResult(Outcome.POLICY_REJECTED, "KEY_SET_STALE",
                    keySet.snapshotFingerprint(), keySet.attestation().keyId());
        }
        if (keySet.generatedAt().isAfter(now.plus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return keySetResult(Outcome.POLICY_REJECTED, "KEY_SET_NOT_YET_VALID",
                    keySet.snapshotFingerprint(), keySet.attestation().keyId());
        }
        try {
            ObjectNode material = JSON.createObjectNode();
            material.put("schemaVersion", keySet.schemaVersion());
            material.put("provider", keySet.provider());
            material.put("generatedAt", keySet.generatedAt().toString());
            material.put("expiresAt", keySet.expiresAt().toString());
            material.put("activeKeyId", keySet.activeKeyId());
            material.put("policyCompleteness", keySet.policyCompleteness().name());
            material.set("keys", keySet.rawSnapshot().path("keys"));
            material.set("events", keySet.rawSnapshot().path("events"));
            if (!EvidenceVerificationSupport.sha256(material).equals(keySet.snapshotFingerprint())
                    || !keySet.snapshotFingerprint().equals(
                    keySet.attestation().materialFingerprint())) {
                return keySetResult(Outcome.INVALID, "KEY_SET_MATERIAL_INVALID",
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            EvidenceVerificationKeySet.KeyPolicy attestationKey = keySet.keys().stream()
                    .filter(key -> key.keyId().equals(keySet.attestation().keyId()))
                    .findFirst().orElse(null);
            if (attestationKey == null || !keySet.activeKeyId().equals(attestationKey.keyId())
                    || attestationKey.state() != EvidenceVerificationKeySet.KeyState.ACTIVE) {
                return keySetResult(Outcome.INVALID, "KEY_SET_ATTESTATION_KEY_INVALID",
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            if (!"Ed25519".equals(keySet.attestation().algorithm())
                    || !keySet.attestation().algorithm().equals(attestationKey.algorithm())) {
                return keySetResult(Outcome.POLICY_REJECTED, "KEY_SET_ALGORITHM_REJECTED",
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            Instant signedAt = keySet.attestation().signedAt();
            if (signedAt.isBefore(keySet.generatedAt().minus(
                    EvidenceVerificationSupport.KEY_CREATION_SKEW))
                    || !signedAt.isBefore(keySet.expiresAt())
                    || signedAt.isAfter(now.plus(
                    EvidenceVerificationSupport.KEY_CREATION_SKEW))
                    || signedAt.isBefore(attestationKey.notBefore().minus(
                    EvidenceVerificationSupport.KEY_CREATION_SKEW))
                    || (attestationKey.notAfter() != null
                    && !signedAt.isBefore(attestationKey.notAfter()))) {
                return keySetResult(Outcome.POLICY_REJECTED, "KEY_SET_SIGNING_TIME_REJECTED",
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    keySet.snapshotFingerprint(), keySet.attestation().signature(),
                    attestationKey.encodedPublicKey())) {
                return keySetResult(Outcome.INVALID, "KEY_SET_SIGNATURE_INVALID",
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            String policyReason = lifecyclePolicyReason(keySet);
            if (!policyReason.isBlank()) {
                return keySetResult(Outcome.POLICY_REJECTED, policyReason,
                        keySet.snapshotFingerprint(), keySet.attestation().keyId());
            }
            return keySetResult(Outcome.VERIFIED, "VERIFIED", keySet.snapshotFingerprint(),
                    keySet.attestation().keyId());
        } catch (RuntimeException | GeneralSecurityException failure) {
            return keySetResult(Outcome.INVALID, "KEY_SET_MATERIAL_INVALID",
                    keySet.snapshotFingerprint(), keySet.attestation().keyId());
        }
    }

    private VerificationResult applyLifecyclePolicy(
            TestSuiteEvidenceBundle bundle, EvidenceVerificationKeySet keySet,
            EvidenceVerificationKeySet.KeyPolicy key) {
        String reason = EvidenceVerificationSupport.signingTimePolicyReason(
                keySet, key.keyId(), bundle.attestation().signedAt());
        return reason.isBlank() ? null : result(Outcome.POLICY_REJECTED, reason,
                bundle.suiteRunId(), key.keyId());
    }

    private static String lifecyclePolicyReason(EvidenceVerificationKeySet keySet) {
        Map<String, EvidenceVerificationKeySet.KeyPolicy> keysById = new HashMap<>();
        int activeCount = 0;
        for (EvidenceVerificationKeySet.KeyPolicy key : keySet.keys()) {
            if (keysById.putIfAbsent(key.keyId(), key) != null
                    || !"Ed25519".equals(key.algorithm())
                    || !validEd25519PublicKey(key.encodedPublicKey())
                    || key.createdAt().isAfter(keySet.generatedAt())) {
                return "KEY_SET_POLICY_INVALID";
            }
            if (key.state() == EvidenceVerificationKeySet.KeyState.ACTIVE) {
                activeCount++;
            }
        }
        EvidenceVerificationKeySet.KeyPolicy active = keysById.get(keySet.activeKeyId());
        if (activeCount != 1 || active == null
                || active.state() != EvidenceVerificationKeySet.KeyState.ACTIVE) {
            return "KEY_SET_POLICY_INVALID";
        }
        long previousSequence = 0;
        Set<String> eventIds = new HashSet<>();
        Set<String> created = new HashSet<>();
        Set<String> activated = new HashSet<>();
        Map<String, EvidenceVerificationKeySet.EventType> latestState = new HashMap<>();
        for (EvidenceVerificationKeySet.LifecycleEvent event : keySet.events()) {
            EvidenceVerificationKeySet.KeyPolicy key = keysById.get(event.keyId());
            boolean revocation = event.type() == EvidenceVerificationKeySet.EventType.REVOKED
                    || event.type() == EvidenceVerificationKeySet.EventType.COMPROMISE_DECLARED;
            if (key == null || event.sequence() <= previousSequence
                    || !eventIds.add(event.eventId())
                    || event.occurredAt().isAfter(keySet.generatedAt())
                    || event.effectiveAt().isAfter(event.occurredAt())
                    || event.effectiveAt().isBefore(key.createdAt())
                    || (event.invalidFrom() != null && event.invalidFrom().isBefore(key.createdAt()))
                    || (revocation && event.revocationMode() == null)
                    || (!revocation && (event.revocationMode() != null || event.invalidFrom() != null))
                    || (event.revocationMode() == EvidenceVerificationKeySet.RevocationMode.RETROACTIVE
                    && event.invalidFrom() == null)
                    || (event.invalidFrom() != null
                    && event.invalidFrom().isAfter(event.effectiveAt()))) {
                return "KEY_SET_POLICY_INVALID";
            }
            previousSequence = event.sequence();
            if (event.type() == EvidenceVerificationKeySet.EventType.CREATED) {
                if (!created.add(event.keyId()) || !event.effectiveAt().equals(key.createdAt())) {
                    return "KEY_SET_POLICY_INVALID";
                }
                continue;
            }
            if (!created.contains(event.keyId())) {
                return "KEY_LIFECYCLE_POLICY_INCOMPLETE";
            }
            if (event.type() == EvidenceVerificationKeySet.EventType.ACTIVATED) {
                activated.add(event.keyId());
            }
            latestState.put(event.keyId(), event.type());
        }
        for (EvidenceVerificationKeySet.KeyPolicy key : keySet.keys()) {
            EvidenceVerificationKeySet.EventType latest = latestState.get(key.keyId());
            boolean stateMatches = switch (key.state()) {
                case ACTIVE -> latest == EvidenceVerificationKeySet.EventType.ACTIVATED;
                case VERIFY_ONLY -> latest == EvidenceVerificationKeySet.EventType.RETIRED;
                case DISABLED -> latest == EvidenceVerificationKeySet.EventType.DISABLED;
                case REVOKED -> latest == EvidenceVerificationKeySet.EventType.REVOKED
                        || latest == EvidenceVerificationKeySet.EventType.COMPROMISE_DECLARED;
            };
            if (!created.contains(key.keyId()) || !stateMatches
                    || ((key.state() == EvidenceVerificationKeySet.KeyState.ACTIVE
                    || key.state() == EvidenceVerificationKeySet.KeyState.VERIFY_ONLY)
                    && !activated.contains(key.keyId()))) {
                return "KEY_LIFECYCLE_POLICY_INCOMPLETE";
            }
        }
        return "";
    }

    private static boolean validEd25519PublicKey(String encodedPublicKey) {
        try {
            byte[] publicKey = Base64.getDecoder().decode(encodedPublicKey);
            KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKey));
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean closureMatches(
            JsonNode evidence, List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        List<String> expected = new ArrayList<>();
        boolean mutation = TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V5.equals(
                evidence.path("schemaVersion").asText());
        evidence.path("caseResults").forEach(result -> {
            if (!result.path("runId").asText().isBlank()) {
                String caseId = mutation ? "baseline/" + result.path("caseId").asText()
                        : result.path("caseId").asText();
                expected.add(caseId + "\u0000" + result.path("runId").asText());
            }
        });
        if (mutation) {
            evidence.path("mutantResults").forEach(mutant -> {
                String mutantId = mutant.path("mutant").path("mutantId").asText();
                mutant.path("caseResults").forEach(result -> {
                    if (!result.path("runId").asText().isBlank()) {
                        expected.add(mutantId + "/" + result.path("caseId").asText()
                                + "\u0000" + result.path("runId").asText());
                    }
                });
            });
        }
        List<String> actual = children.stream()
                .map(child -> child.caseId() + "\u0000" + child.runId()).toList();
        if (!expected.equals(actual)) {
            return false;
        }
        if (mutation) {
            Map<String, TestSuiteRunAttestation.ChildEvidenceRef> childrenByCoordinate =
                    new HashMap<>();
            children.forEach(child -> childrenByCoordinate.put(
                    child.caseId() + "\u0000" + child.runId(), child));
            for (JsonNode mutant : evidence.path("mutantResults")) {
                String mutantId = mutant.path("mutant").path("mutantId").asText();
                for (JsonNode result : mutant.path("caseResults")) {
                    if (!result.path("runId").asText().isBlank()) {
                        TestSuiteRunAttestation.ChildEvidenceRef child = childrenByCoordinate.get(
                                mutantId + "/" + result.path("caseId").asText()
                                        + "\u0000" + result.path("runId").asText());
                        if (child == null || !child.evidenceFingerprint().equals(
                                result.path("evidenceFingerprint").asText())) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static ObjectNode signatureMaterial(TestSuiteRunAttestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", value.schemaVersion());
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

    private static VerificationResult result(Outcome outcome, String reason,
                                             String suiteRunId, String keyId) {
        return new VerificationResult(outcome, reason, suiteRunId, keyId);
    }

    private static KeySetVerificationResult keySetResult(Outcome outcome, String reason,
                                                         String snapshotFingerprint, String keyId) {
        return new KeySetVerificationResult(outcome, reason, snapshotFingerprint, keyId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
