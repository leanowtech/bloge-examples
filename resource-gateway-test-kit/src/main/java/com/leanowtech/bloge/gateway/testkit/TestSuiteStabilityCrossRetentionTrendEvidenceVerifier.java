package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-light independent verifier for signed compact-observation range trends.
 *
 * <p>The verifier does not need retained full stability runs. It recomputes the exact request,
 * observation identities, observation signatures, entry/head/range fingerprints, deterministic
 * trend projection, outer closure, and outer signature. Producer scope and database ordering facts
 * remain signed producer-authoritative facts because tenant/environment values are deliberately not
 * exposed in the payload-free range.</p>
 */
public final class TestSuiteStabilityCrossRetentionTrendEvidenceVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;

    /** Creates a verifier using current UTC time for key-set freshness policy. */
    public TestSuiteStabilityCrossRetentionTrendEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteStabilityCrossRetentionTrendEvidenceVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Closed verification outcomes suitable for CI and governance consumers. */
    public enum Outcome {
        /** Every canonical layer, signature, closure, and derived label passed. */
        VERIFIED,
        /** Evidence, range, signature, closure, or derived semantics is invalid. */
        INVALID,
        /** A required public verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** An externally pinned key lifecycle policy rejects the evidence. */
        POLICY_REJECTED
    }

    /**
     * Bounded payload-free verification result.
     *
     * @param outcome closed trust outcome
     * @param reasonCode stable machine-readable reason
     * @param trendAnalysisId exact analyzed trend identity
     * @param keyId outer signature key id when available
     * @param verifiedObservations number of compact signatures verified before termination
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String trendAnalysisId,
            String keyId,
            int verifiedObservations
    ) {
        /** Normalizes bounded result fields. */
        public VerificationResult {
            outcome = outcome == null ? Outcome.INVALID : outcome;
            reasonCode = normalized(reasonCode);
            trendAnalysisId = normalized(trendAnalysisId);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")
                    || verifiedObservations < 0 || verifiedObservations > 100) {
                throw new IllegalArgumentException(
                        "Cross-retention verification result is invalid");
            }
        }

        /**
         * Indicates whether every required verification layer passed.
         *
         * @return whether every required verification layer passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one range against explicitly supplied public keys.
     *
     * <p>This overload is useful for local diagnosis. A release gate should use the pinned key-set
     * overload because a map alone does not prove lifecycle completeness or split-view resistance.</p>
     *
     * @param analysis strict schema-validated range projection
     * @param keys exact public keys by key id
     * @return bounded verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityCrossRetentionTrendAnalysis analysis,
            Map<String, EvidenceVerificationKey> keys) {
        if (analysis == null) {
            return result(Outcome.INVALID, "CROSS_RETENTION_EVIDENCE_MISSING", "", "", 0);
        }
        Map<String, EvidenceVerificationKey> exactKeys = keys == null
                ? Map.of() : Map.copyOf(keys);
        JsonNode response = analysis.rawResponse();
        JsonNode evidence = response.path("evidence");
        JsonNode range = evidence.path("range");
        ObjectNode identity = JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("requestFingerprint", analysis.request().requestFingerprint());
        identity.put("rangeFingerprint", analysis.range().rangeFingerprint());
        String expectedTrendId = "stability-cross-retention-trend-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
        if (!expectedTrendId.equals(analysis.trendAnalysisId())) {
            return result(Outcome.INVALID, "CROSS_RETENTION_TREND_IDENTITY_INVALID",
                    analysis, 0);
        }
        if (!analysis.request().requestFingerprint().equals(
                evidence.path("requestFingerprint").asText())
                || !analysis.evidenceFingerprint().equals(
                EvidenceVerificationSupport.sha256(evidence))) {
            return result(Outcome.INVALID, "CROSS_RETENTION_EVIDENCE_FINGERPRINT_INVALID",
                    analysis, 0);
        }
        VerificationResult structural = verifyStructuralFingerprints(analysis, range);
        if (structural != null) {
            return structural;
        }
        int verified = 0;
        List<JsonNode> rawEntries = new ArrayList<>();
        range.path("entries").forEach(rawEntries::add);
        for (int index = 0; index < analysis.range().entries().size(); index++) {
            TestSuiteStabilityCrossRetentionTrendAnalysis.LedgerEntry entry =
                    analysis.range().entries().get(index);
            VerificationResult observation = verifyObservation(
                    analysis, entry, rawEntries.get(index), exactKeys, verified);
            if (observation != null) {
                return observation;
            }
            verified++;
        }
        List<TestSuiteStabilityTrendAnalysis.SourceObservation> sources =
                analysis.range().entries().stream()
                        .map(value -> value.observation().source())
                        .sorted(Comparator.comparing(
                                TestSuiteStabilityTrendAnalysis.SourceObservation::createdAt)
                                .thenComparing(TestSuiteStabilityTrendAnalysis.SourceObservation
                                        ::stabilityRunId))
                        .toList();
        TestSuiteStabilityTrendProjection.Result projection =
                TestSuiteStabilityTrendProjection.project(
                        sources, analysis.request().minimumRuns(), true, List.of());
        if (analysis.status() != projection.status()
                || !analysis.caseTrends().equals(projection.caseTrends())
                || !analysis.correlationSignals().equals(projection.signals())
                || !analysis.diagnostics().equals(projection.diagnostics())) {
            return result(Outcome.INVALID, "CROSS_RETENTION_TREND_DERIVATION_INVALID",
                    analysis, verified);
        }
        EvidenceVerificationKey outerKey = exactKeys.get(analysis.attestation().keyId());
        if (outerKey == null) {
            return result(Outcome.KEY_UNAVAILABLE, "VERIFICATION_KEY_UNAVAILABLE",
                    analysis, verified);
        }
        String policy = directKeyPolicy(
                outerKey, analysis.attestation().keyId(), analysis.attestation().algorithm(),
                analysis.attestation().signedAt());
        if (!policy.isBlank()) {
            return result(Outcome.POLICY_REJECTED, policy, analysis, verified);
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256(
                    outerSignatureMaterial(analysis.attestation()));
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    analysis.attestation().signature(), outerKey.encodedPublicKey())) {
                return result(Outcome.INVALID,
                        "CROSS_RETENTION_ATTESTATION_SIGNATURE_INVALID", analysis, verified);
            }
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(Outcome.INVALID,
                    "CROSS_RETENTION_ATTESTATION_MATERIAL_INVALID", analysis, verified);
        }
        return result(Outcome.VERIFIED, "VERIFIED", analysis, verified);
    }

    /**
     * Performs release-grade verification against one externally pinned key lifecycle snapshot.
     *
     * @param analysis strict schema-validated range projection
     * @param keySet complete signed key lifecycle snapshot
     * @param trustedSnapshotFingerprint snapshot fingerprint pinned outside Gateway output
     * @return lifecycle-aware bounded verification result
     */
    public VerificationResult verify(
            TestSuiteStabilityCrossRetentionTrendAnalysis analysis,
            EvidenceVerificationKeySet keySet,
            String trustedSnapshotFingerprint) {
        if (analysis == null) {
            return result(Outcome.INVALID, "CROSS_RETENTION_EVIDENCE_MISSING", "", "", 0);
        }
        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                new TestSuiteEvidenceVerifier(clock).verifyKeySet(
                        keySet, trustedSnapshotFingerprint);
        if (!keySetResult.verified()) {
            return result(Outcome.valueOf(keySetResult.outcome().name()),
                    keySetResult.reasonCode(), analysis, 0);
        }
        List<SigningCoordinate> signatures = new ArrayList<>();
        analysis.range().entries().forEach(entry -> signatures.add(new SigningCoordinate(
                entry.observation().attestation().keyId(),
                entry.observation().attestation().signedAt())));
        signatures.add(new SigningCoordinate(
                analysis.attestation().keyId(), analysis.attestation().signedAt()));
        Map<String, EvidenceVerificationKey> keys = new LinkedHashMap<>();
        for (SigningCoordinate signature : signatures) {
            String reason = EvidenceVerificationSupport.signingTimePolicyReason(
                    keySet, signature.keyId(), signature.signedAt());
            if (!reason.isBlank()) {
                return result(Outcome.POLICY_REJECTED, reason, analysis, 0);
            }
            EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                    .filter(candidate -> candidate.keyId().equals(signature.keyId()))
                    .findFirst().orElse(null);
            if (policy == null) {
                return result(Outcome.KEY_UNAVAILABLE,
                        "EVIDENCE_KEY_NOT_IN_PINNED_SET", analysis, 0);
            }
            keys.putIfAbsent(policy.keyId(), new EvidenceVerificationKey(
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, policy.keyId(),
                    policy.algorithm(), policy.encodedPublicKey(), policy.notBefore(),
                    policy.state() == EvidenceVerificationKeySet.KeyState.ACTIVE
                            ? "ACTIVE" : "RETIRED", keySet.provider()));
        }
        return verify(analysis, keys);
    }

    private static VerificationResult verifyStructuralFingerprints(
            TestSuiteStabilityCrossRetentionTrendAnalysis analysis,
            JsonNode range) {
        try {
            for (JsonNode entry : range.path("entries")) {
                JsonNode observation = entry.path("observation");
                if (!observation.path("evidenceFingerprint").asText().equals(
                        EvidenceVerificationSupport.sha256(observation.path("evidence")))
                        || !observation.path("attestationFingerprint").asText().equals(
                        EvidenceVerificationSupport.sha256(observation.path("attestation")))
                        || !entry.path("entryFingerprint").asText().equals(
                        EvidenceVerificationSupport.sha256(without(entry, "entryFingerprint")))) {
                    return result(Outcome.INVALID,
                            "CROSS_RETENTION_OBSERVATION_FINGERPRINT_INVALID", analysis, 0);
                }
            }
            if (!range.path("head").path("headFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(
                            without(range.path("head"), "headFingerprint")))) {
                return result(Outcome.INVALID, "CROSS_RETENTION_HEAD_FINGERPRINT_INVALID",
                        analysis, 0);
            }
            if (!range.path("rangeFingerprint").asText().equals(
                    EvidenceVerificationSupport.sha256(without(range, "rangeFingerprint")))) {
                return result(Outcome.INVALID, "CROSS_RETENTION_RANGE_FINGERPRINT_INVALID",
                        analysis, 0);
            }
            return null;
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "CROSS_RETENTION_RANGE_MATERIAL_INVALID",
                    analysis, 0);
        }
    }

    private static VerificationResult verifyObservation(
            TestSuiteStabilityCrossRetentionTrendAnalysis analysis,
            TestSuiteStabilityCrossRetentionTrendAnalysis.LedgerEntry entry,
            JsonNode rawEntry,
            Map<String, EvidenceVerificationKey> keys,
            int verified) {
        TestSuiteStabilityCrossRetentionTrendAnalysis.Observation observation =
                entry.observation();
        JsonNode evidence = rawEntry.path("observation").path("evidence");
        ObjectNode identity = JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("scopeFingerprint", observation.scopeFingerprint());
        identity.set("suiteRef", evidence.path("suiteRef").deepCopy());
        identity.put("sourceRequestFingerprint", observation.sourceRequestFingerprint());
        identity.put("stabilityRunId", observation.source().stabilityRunId());
        identity.put("sourceEvidenceFingerprint", observation.source().evidenceFingerprint());
        identity.put("sourceAttestationFingerprint",
                observation.source().attestationFingerprint());
        String expectedId = "stability-observation-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
        if (!expectedId.equals(observation.observationId())) {
            return result(Outcome.INVALID, "OBSERVATION_IDENTITY_INVALID", analysis, verified);
        }
        EvidenceVerificationKey key = keys.get(observation.attestation().keyId());
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE,
                    "OBSERVATION_VERIFICATION_KEY_UNAVAILABLE", analysis, verified);
        }
        String policy = directKeyPolicy(key, observation.attestation().keyId(),
                observation.attestation().algorithm(), observation.attestation().signedAt());
        if (!policy.isBlank()) {
            return result(Outcome.POLICY_REJECTED, policy, analysis, verified);
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256(
                    observationSignatureMaterial(observation.attestation()));
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    observation.attestation().signature(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "OBSERVATION_SIGNATURE_INVALID",
                        analysis, verified);
            }
            return null;
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(Outcome.INVALID, "OBSERVATION_SIGNATURE_MATERIAL_INVALID",
                    analysis, verified);
        }
    }

    private static ObjectNode observationSignatureMaterial(
            TestSuiteStabilityCrossRetentionTrendAnalysis.ObservationAttestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", value.schemaVersion());
        material.put("observationId", value.observationId());
        material.put("observationFingerprint", value.observationFingerprint());
        material.put("sourceEvidenceFingerprint", value.sourceEvidenceFingerprint());
        material.put("sourceAttestationFingerprint", value.sourceAttestationFingerprint());
        material.put("signedAt", value.signedAt().toString());
        return material;
    }

    private static ObjectNode outerSignatureMaterial(
            TestSuiteStabilityCrossRetentionTrendAnalysis.Attestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", value.schemaVersion());
        material.put("trendAnalysisId", value.trendAnalysisId());
        material.put("requestFingerprint", value.requestFingerprint());
        material.put("evidenceFingerprint", value.evidenceFingerprint());
        material.put("rangeFingerprint", value.rangeFingerprint());
        ArrayNode observations = material.putArray("observationRefs");
        value.observationRefs().forEach(reference -> {
            ObjectNode item = observations.addObject();
            item.put("sequence", reference.sequence());
            item.put("observationId", reference.observationId());
            item.put("observationFingerprint", reference.observationFingerprint());
            item.put("observationAttestationFingerprint",
                    reference.observationAttestationFingerprint());
            item.put("entryFingerprint", reference.entryFingerprint());
        });
        material.put("signedAt", value.signedAt().toString());
        return material;
    }

    private static String directKeyPolicy(
            EvidenceVerificationKey key,
            String expectedKeyId,
            String algorithm,
            Instant signedAt) {
        if (!key.keyId().equals(expectedKeyId)) {
            return "VERIFICATION_KEY_ID_MISMATCH";
        }
        if (!"Ed25519".equals(algorithm) || !algorithm.equals(key.algorithm())) {
            return "SIGNATURE_ALGORITHM_REJECTED";
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return "VERIFICATION_KEY_POLICY_REJECTED";
        }
        return "";
    }

    private static JsonNode without(JsonNode value, String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            TestSuiteStabilityCrossRetentionTrendAnalysis analysis,
            int verified) {
        return result(outcome, reason, analysis.trendAnalysisId(),
                analysis.attestation().keyId(), verified);
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            String trendId,
            String keyId,
            int verified) {
        return new VerificationResult(outcome, reason, trendId, keyId, verified);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SigningCoordinate(String keyId, Instant signedAt) {
    }
}
