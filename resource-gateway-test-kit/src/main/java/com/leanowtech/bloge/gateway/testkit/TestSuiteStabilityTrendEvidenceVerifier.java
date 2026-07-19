package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-light offline verifier for signed retained-window suite-stability trends.
 *
 * <p>The verifier establishes three distinct trust layers. It independently verifies every source
 * stability signature, reconstructs every payload-free source summary and derived trend label, and
 * finally verifies the trend signature over the producer-authoritative persistence facts. Database
 * retention and truncation facts are signed and checked for internal consistency; an offline
 * consumer cannot independently re-query the producer database.</p>
 */
public final class TestSuiteStabilityTrendEvidenceVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;

    /** Creates a verifier using current UTC time for key-set freshness policy. */
    public TestSuiteStabilityTrendEvidenceVerifier() {
        this(Clock.systemUTC());
    }

    TestSuiteStabilityTrendEvidenceVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Closed verification outcomes suitable for CI and governance gates. */
    public enum Outcome {
        /** Source evidence, derived semantics, closure, key policy, and signature all passed. */
        VERIFIED,
        /** Evidence, source closure, derived semantics, fingerprint, or signature is invalid. */
        INVALID,
        /** At least one required source or trend verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** A key algorithm, lifecycle, freshness, or signing-time policy rejected the evidence. */
        POLICY_REJECTED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param trendAnalysisId deterministic analysis id when available
     * @param keyId trend key id when available
     * @param verifiedSources number of source signatures verified before termination
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String trendAnalysisId,
            String keyId,
            int verifiedSources
    ) {
        /** Normalizes log-safe fields and rejects impossible counters. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            trendAnalysisId = normalized(trendAnalysisId);
            keyId = normalized(keyId);
            if (outcome == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || verifiedSources < 0 || verifiedSources > 100) {
                throw new IllegalArgumentException("Trend verification result is invalid");
            }
        }

        /**
         * Reports whether every verification layer passed.
         *
         * @return true only for a fully verified result
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one trend using explicitly resolved source and trend public keys.
     *
     * <p>The source list must follow the exact attested closure order. Keys are indexed by
     * {@link EvidenceVerificationKey#keyId()} and may contain one shared key or rotated keys.</p>
     *
     * @param analysis strict schema-validated trend projection
     * @param sourceRuns every referenced source stability response in closure order
     * @param keys verification keys indexed by key id
     * @return bounded payload-free result
     */
    public VerificationResult verify(
            TestSuiteStabilityTrendAnalysis analysis,
            List<TestSuiteStabilityRun> sourceRuns,
            Map<String, EvidenceVerificationKey> keys) {
        if (analysis == null) {
            return result(Outcome.INVALID, "TREND_EVIDENCE_MISSING", "", "", 0);
        }
        List<TestSuiteStabilityRun> sources = sourceRuns == null
                ? List.of() : List.copyOf(sourceRuns);
        Map<String, EvidenceVerificationKey> exactKeys = keys == null ? Map.of() : Map.copyOf(keys);
        if (sources.size() != analysis.sources().size()) {
            return result(Outcome.INVALID, "TREND_SOURCE_CLOSURE_INCOMPLETE", analysis, 0);
        }
        int verified = 0;
        TestSuiteStabilityEvidenceVerifier sourceVerifier =
                new TestSuiteStabilityEvidenceVerifier(clock);
        List<TestSuiteStabilityTrendAnalysis.SourceObservation> reconstructed = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            TestSuiteStabilityRun source = sources.get(index);
            TestSuiteStabilityTrendAnalysis.SourceObservation claimed =
                    analysis.sources().get(index);
            if (source == null || !claimed.stabilityRunId().equals(source.stabilityRunId())) {
                return result(Outcome.INVALID, "TREND_SOURCE_ORDER_INVALID", analysis, verified);
            }
            EvidenceVerificationKey sourceKey = exactKeys.get(source.attestation().keyId());
            TestSuiteStabilityEvidenceVerifier.VerificationResult sourceResult =
                    sourceVerifier.verify(source, sourceKey);
            if (!sourceResult.verified()) {
                return result(map(sourceResult.outcome()),
                        "SOURCE_" + sourceResult.reasonCode(), analysis, verified);
            }
            verified++;
            try {
                reconstructed.add(reconstructSource(source, claimed.createdAt(), analysis.request()));
            } catch (RuntimeException invalid) {
                return result(Outcome.INVALID, "TREND_SOURCE_DERIVATION_INVALID",
                        analysis, verified);
            }
        }
        if (!reconstructed.equals(analysis.sources())) {
            return result(Outcome.INVALID, "TREND_SOURCE_SUMMARY_INVALID", analysis, verified);
        }
        List<String> persistenceDiagnostics = new ArrayList<>();
        if (analysis.expiredMatchingRuns() > 0) {
            persistenceDiagnostics.add("SOURCE_RETENTION_GAP");
        }
        if (analysis.diagnostics().contains("SOURCE_WINDOW_TRUNCATED")) {
            persistenceDiagnostics.add("SOURCE_WINDOW_TRUNCATED");
        }
        TestSuiteStabilityTrendProjection.Result derived =
                TestSuiteStabilityTrendProjection.project(
                        reconstructed, analysis.request().minimumRuns(),
                        analysis.completeWindow(), persistenceDiagnostics);
        if (analysis.status() != derived.status()
                || !analysis.caseTrends().equals(derived.caseTrends())
                || !analysis.correlationSignals().equals(derived.signals())
                || !analysis.diagnostics().equals(derived.diagnostics())
                || analysis.evaluatedAt().isBefore(analysis.request().toExclusive())) {
            return result(Outcome.INVALID, "TREND_DERIVATION_INVALID", analysis, verified);
        }
        EvidenceVerificationKey trendKey = exactKeys.get(analysis.attestation().keyId());
        if (trendKey == null) {
            return result(Outcome.KEY_UNAVAILABLE, "VERIFICATION_KEY_UNAVAILABLE",
                    analysis, verified);
        }
        VerificationResult keyPolicy = directKeyPolicy(analysis, trendKey, verified);
        if (keyPolicy != null) {
            return keyPolicy;
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256(
                    signatureMaterial(analysis.attestation()));
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    analysis.attestation().signature(), trendKey.encodedPublicKey())) {
                return result(Outcome.INVALID, "TREND_ATTESTATION_SIGNATURE_INVALID",
                        analysis, verified);
            }
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(Outcome.INVALID, "TREND_ATTESTATION_MATERIAL_INVALID",
                    analysis, verified);
        }
        return result(Outcome.VERIFIED, "VERIFIED", analysis, verified);
    }

    /**
     * Performs release-grade verification against one externally pinned lifecycle snapshot.
     *
     * @param analysis strict schema-validated trend projection
     * @param sourceRuns every referenced source stability response in closure order
     * @param keySet complete signed key lifecycle snapshot
     * @param trustedSnapshotFingerprint snapshot fingerprint pinned outside Gateway output
     * @return bounded payload-free result
     */
    public VerificationResult verify(
            TestSuiteStabilityTrendAnalysis analysis,
            List<TestSuiteStabilityRun> sourceRuns,
            EvidenceVerificationKeySet keySet,
            String trustedSnapshotFingerprint) {
        if (analysis == null) {
            return result(Outcome.INVALID, "TREND_EVIDENCE_MISSING", "", "", 0);
        }
        TestSuiteEvidenceVerifier.KeySetVerificationResult keySetResult =
                new TestSuiteEvidenceVerifier(clock).verifyKeySet(
                        keySet, trustedSnapshotFingerprint);
        if (!keySetResult.verified()) {
            return result(Outcome.valueOf(keySetResult.outcome().name()),
                    keySetResult.reasonCode(), analysis, 0);
        }
        List<TestSuiteStabilityRun> sources = sourceRuns == null
                ? List.of() : List.copyOf(sourceRuns);
        List<SigningCoordinate> signatures = new ArrayList<>();
        sources.forEach(source -> {
            if (source != null) {
                signatures.add(new SigningCoordinate(
                        source.attestation().keyId(), source.attestation().signedAt()));
            }
        });
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
                return result(Outcome.KEY_UNAVAILABLE, "EVIDENCE_KEY_NOT_IN_PINNED_SET",
                        analysis, 0);
            }
            keys.putIfAbsent(policy.keyId(), new EvidenceVerificationKey(
                    TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, policy.keyId(),
                    policy.algorithm(), policy.encodedPublicKey(), policy.notBefore(),
                    policy.state() == EvidenceVerificationKeySet.KeyState.ACTIVE
                            ? "ACTIVE" : "RETIRED", keySet.provider()));
        }
        return verify(analysis, sources, keys);
    }

    private static TestSuiteStabilityTrendAnalysis.SourceObservation reconstructSource(
            TestSuiteStabilityRun source,
            java.time.Instant createdAt,
            TestSuiteStabilityTrendRequest request) {
        if (!request.suiteId().equals(source.suiteRef().suiteId())
                || request.revision() != source.suiteRef().revision()
                || !request.fingerprint().equals(source.suiteRef().fingerprint())) {
            throw new IllegalArgumentException("Trend source suite identity is inconsistent");
        }
        JsonNode response = source.rawResponse();
        JsonNode evidence = response.path("evidence");
        List<TestSuiteStabilityTrendAnalysis.CaseSnapshot> cases = new ArrayList<>();
        evidence.path("caseResults").forEach(value -> cases.add(caseSnapshot(value)));
        cases.sort(Comparator.comparing(TestSuiteStabilityTrendAnalysis.CaseSnapshot::caseId));
        ObjectNode regime = JSON.createObjectNode();
        regime.put("suiteFingerprint", request.fingerprint());
        regime.put("targetFingerprint", source.target().fingerprint());
        ArrayNode regimes = regime.putArray("cases");
        cases.forEach(value -> {
            ObjectNode item = regimes.addObject();
            item.put("caseId", value.caseId());
            item.put("fixtureSetFingerprint", value.fixtureSetFingerprint());
            item.put("planSetFingerprint", value.planSetFingerprint());
        });
        TestSuiteStabilityRun.StatisticalStatus statisticalStatus =
                evidence.has("statisticalAssessment")
                        ? TestSuiteStabilityRun.StatisticalStatus.valueOf(
                        evidence.path("statisticalAssessment").path("status").asText()) : null;
        return new TestSuiteStabilityTrendAnalysis.SourceObservation(
                source.stabilityRunId(), source.evidenceFingerprint(),
                EvidenceVerificationSupport.sha256(response.path("attestation")),
                evidence.path("schemaVersion").asText(), source.target().fingerprint(),
                source.status(), source.promotion().status(), source.quarantine().status(),
                statisticalStatus, EvidenceVerificationSupport.sha256(regime), cases,
                source.startedAt(), source.completedAt(), createdAt);
    }

    private static TestSuiteStabilityTrendAnalysis.CaseSnapshot caseSnapshot(JsonNode value) {
        List<String> outcomes = new ArrayList<>();
        List<String> fixtures = new ArrayList<>();
        List<String> plans = new ArrayList<>();
        value.path("observations").forEach(observation -> {
            if ("VERIFIED".equals(observation.path("status").asText())) {
                outcomes.add(observation.path("evidenceStatus").asText() + ':'
                        + observation.path("semanticResultFingerprint").asText());
                fixtures.add(observation.path("fixtureBundleFingerprint").asText());
                plans.add(observation.path("planFingerprint").asText());
            }
        });
        return new TestSuiteStabilityTrendAnalysis.CaseSnapshot(
                value.path("caseId").asText(), TestSuiteStabilityRun.CaseStatus.valueOf(
                value.path("status").asText()), fingerprintSet(outcomes),
                fingerprintSet(fixtures), fingerprintSet(plans));
    }

    private static String fingerprintSet(List<String> values) {
        ArrayNode material = JSON.createArrayNode();
        values.stream().distinct().sorted().forEach(material::add);
        return EvidenceVerificationSupport.sha256(material);
    }

    private static ObjectNode signatureMaterial(
            TestSuiteStabilityTrendAnalysis.Attestation value) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", value.schemaVersion());
        material.put("trendAnalysisId", value.trendAnalysisId());
        material.put("requestFingerprint", value.requestFingerprint());
        material.put("evidenceFingerprint", value.evidenceFingerprint());
        ArrayNode sources = material.putArray("sourceEvidenceRefs");
        value.sourceEvidenceRefs().forEach(source -> {
            ObjectNode item = sources.addObject();
            item.put("stabilityRunId", source.stabilityRunId());
            item.put("evidenceFingerprint", source.evidenceFingerprint());
            item.put("attestationFingerprint", source.attestationFingerprint());
        });
        material.put("signedAt", value.signedAt().toString());
        return material;
    }

    private static VerificationResult directKeyPolicy(
            TestSuiteStabilityTrendAnalysis analysis,
            EvidenceVerificationKey key,
            int verifiedSources) {
        if (!key.keyId().equals(analysis.attestation().keyId())) {
            return result(Outcome.INVALID, "VERIFICATION_KEY_ID_MISMATCH",
                    analysis, verifiedSources);
        }
        if (!"Ed25519".equals(key.algorithm())) {
            return result(Outcome.POLICY_REJECTED, "SIGNATURE_ALGORITHM_REJECTED",
                    analysis, verifiedSources);
        }
        if (!key.verificationAllowed() || analysis.attestation().signedAt().isBefore(
                key.createdAt().minus(EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "VERIFICATION_KEY_POLICY_REJECTED",
                    analysis, verifiedSources);
        }
        return null;
    }

    private static Outcome map(TestSuiteStabilityEvidenceVerifier.Outcome outcome) {
        return Outcome.valueOf(outcome.name());
    }

    private static VerificationResult result(
            Outcome outcome, String reason, TestSuiteStabilityTrendAnalysis analysis,
            int verifiedSources) {
        return result(outcome, reason, analysis.trendAnalysisId(),
                analysis.attestation().keyId(), verifiedSources);
    }

    private static VerificationResult result(
            Outcome outcome, String reason, String trendId, String keyId, int verifiedSources) {
        return new VerificationResult(outcome, reason, trendId, keyId, verifiedSources);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SigningCoordinate(String keyId, java.time.Instant signedAt) {
    }
}
