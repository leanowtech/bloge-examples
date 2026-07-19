package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Signed source-closed fixtures for independent retained-window trend verification tests. */
final class TestSuiteStabilityTrendTestFixtures {
    private static final Instant NOW = TestSuiteStabilityTestFixtures.SIGNED_AT;

    private TestSuiteStabilityTrendTestFixtures() {
    }

    static Fixture stableFixture() {
        return fixture('e', 'e', 'd', 'd');
    }

    static Fixture outcomeShiftFixture() {
        return fixture('e', '9', 'd', 'd');
    }

    static Fixture regimeDriftFixture() {
        return fixture('e', 'e', 'd', '8');
    }

    private static Fixture fixture(
            char firstOutcome, char secondOutcome, char firstPlan, char secondPlan) {
        TestSuiteStabilityTestFixtures.Fixture trust =
                TestSuiteStabilityTestFixtures.fixture();
        TestSuiteStabilityRun first = source('2', firstOutcome, firstPlan, trust.keyPair());
        TestSuiteStabilityRun second = source('3', secondOutcome, secondPlan, trust.keyPair());
        List<Source> sources = List.of(
                new Source(first, NOW.minusSeconds(30)),
                new Source(second, NOW.minusSeconds(20)));
        ObjectNode response = response(sources, trust.keyPair());
        return new Fixture(response, sources.stream().map(Source::run).toList(),
                trust.key(), trust.keySet(), trust.keyPair());
    }

    private static TestSuiteStabilityRun source(
            char identity, char outcome, char plan, KeyPair keyPair) {
        ObjectNode response = TestSuiteStabilityTestFixtures.response(
                fingerprint('1'), keyPair);
        String runId = "stability-" + String.valueOf(identity).repeat(64);
        response.put("stabilityRunId", runId);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("stabilityRunId", runId);
        evidence.put("clientRequestId", "trend-source-" + identity);
        evidence.path("caseResults").forEach(caseResult ->
                caseResult.path("observations").forEach(observation -> {
                    ((ObjectNode) observation).put("semanticResultFingerprint",
                            fingerprint(outcome));
                    ((ObjectNode) observation).put("planFingerprint",
                            fingerprint(plan));
                }));
        ((ObjectNode) response.path("attestation")).put("stabilityRunId", runId);
        TestSuiteStabilityTestFixtures.seal(response, keyPair, true);
        return TestSuiteStabilityRun.from(response);
    }

    private static ObjectNode response(List<Source> sources, KeyPair keyPair) {
        TestSuiteStabilityTrendRequest request = new TestSuiteStabilityTrendRequest(
                TestSuiteStabilityTestFixtures.SUITE_ID,
                TestSuiteStabilityTestFixtures.SUITE_REVISION,
                TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                NOW.minusSeconds(3_600), NOW, 2, 10);
        List<ObjectNode> summaries = sources.stream().map(
                TestSuiteStabilityTrendTestFixtures::summary).toList();
        List<CaseTrend> trends = trends(summaries);
        String status = aggregateStatus(summaries, trends);

        ObjectNode response = EvidenceTrustTestFixtures.JSON.createObjectNode();
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_TREND_ANALYSIS_RESPONSE_V1);
        String trendId = "stability-trend-" + "9".repeat(64);
        response.put("trendAnalysisId", trendId);
        ObjectNode evidence = response.putObject("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_TREND_EVIDENCE_V1);
        evidence.put("trendAnalysisId", trendId);
        evidence.put("requestFingerprint", request.requestFingerprint());
        evidence.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
        evidence.put("fromInclusive", request.fromInclusive().toString());
        evidence.put("toExclusive", request.toExclusive().toString());
        evidence.put("minimumRuns", request.minimumRuns());
        evidence.put("maximumRuns", request.maximumRuns());
        evidence.put("observedRuns", summaries.size());
        evidence.put("expiredMatchingRuns", 0);
        evidence.put("completeWindow", true);
        evidence.put("status", status);
        ArrayNode sourceArray = evidence.putArray("sources");
        summaries.forEach(sourceArray::add);
        ArrayNode trendArray = evidence.putArray("caseTrends");
        trends.forEach(value -> trendArray.add(value.json()));
        evidence.putArray("correlationSignals");
        evidence.put("causalityStatus", "NOT_PROVEN");
        evidence.putArray("diagnostics");
        evidence.put("evaluatedAt", request.toExclusive().toString());

        ObjectNode attestation = response.putObject("attestation");
        attestation.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_TREND_ATTESTATION_V1);
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("trendAnalysisId", trendId);
        attestation.put("requestFingerprint", request.requestFingerprint());
        ArrayNode closure = attestation.putArray("sourceEvidenceRefs");
        summaries.forEach(source -> {
            ObjectNode ref = closure.addObject();
            ref.put("stabilityRunId", source.path("stabilityRunId").asText());
            ref.put("evidenceFingerprint", source.path("evidenceFingerprint").asText());
            ref.put("attestationFingerprint", source.path("attestationFingerprint").asText());
        });
        attestation.put("signedAt", NOW.plusSeconds(2).toString());
        attestation.put("keyId", "evidence-key-a");
        attestation.put("algorithm", "Ed25519");
        attestation.put("independentlyVerifiable", true);
        sealTrend(response, keyPair);
        return response;
    }

    private static ObjectNode summary(Source source) {
        TestSuiteStabilityRun run = source.run();
        JsonNode response = run.rawResponse();
        JsonNode evidence = response.path("evidence");
        ObjectNode summary = EvidenceTrustTestFixtures.JSON.createObjectNode();
        summary.put("stabilityRunId", run.stabilityRunId());
        summary.put("evidenceFingerprint", run.evidenceFingerprint());
        summary.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(response.path("attestation")));
        summary.put("evidenceSchemaVersion", evidence.path("schemaVersion").asText());
        summary.put("targetFingerprint", run.target().fingerprint());
        summary.put("status", run.status().name());
        summary.put("promotionStatus", run.promotion().status().name());
        summary.put("quarantineStatus", run.quarantine().status().name());
        ArrayNode cases = summary.putArray("cases");
        List<ObjectNode> caseSummaries = new ArrayList<>();
        evidence.path("caseResults").forEach(value -> caseSummaries.add(caseSummary(value)));
        caseSummaries.sort(Comparator.comparing(value -> value.path("caseId").asText()));
        caseSummaries.forEach(cases::add);
        ObjectNode regime = EvidenceTrustTestFixtures.JSON.createObjectNode();
        regime.put("suiteFingerprint", run.suiteRef().fingerprint());
        regime.put("targetFingerprint", run.target().fingerprint());
        ArrayNode regimeCases = regime.putArray("cases");
        caseSummaries.forEach(value -> {
            ObjectNode item = regimeCases.addObject();
            item.put("caseId", value.path("caseId").asText());
            item.put("fixtureSetFingerprint", value.path("fixtureSetFingerprint").asText());
            item.put("planSetFingerprint", value.path("planSetFingerprint").asText());
        });
        summary.put("regimeFingerprint", EvidenceVerificationSupport.sha256(regime));
        summary.put("startedAt", run.startedAt().toString());
        summary.put("completedAt", run.completedAt().toString());
        summary.put("createdAt", source.createdAt().toString());
        return summary;
    }

    private static ObjectNode caseSummary(JsonNode value) {
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
        ObjectNode result = EvidenceTrustTestFixtures.JSON.createObjectNode();
        result.put("caseId", value.path("caseId").asText());
        result.put("status", value.path("status").asText());
        result.put("outcomeSetFingerprint", setFingerprint(outcomes));
        result.put("fixtureSetFingerprint", setFingerprint(fixtures));
        result.put("planSetFingerprint", setFingerprint(plans));
        return result;
    }

    private static List<CaseTrend> trends(List<ObjectNode> sources) {
        Map<String, List<CasePoint>> byCase = new LinkedHashMap<>();
        for (ObjectNode source : sources) {
            source.path("cases").forEach(value -> byCase
                    .computeIfAbsent(value.path("caseId").asText(), ignored -> new ArrayList<>())
                    .add(new CasePoint(source.path("stabilityRunId").asText(),
                            source.path("regimeFingerprint").asText(), value)));
        }
        return byCase.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> trend(entry.getKey(), entry.getValue(), sources.size())).toList();
    }

    private static CaseTrend trend(String caseId, List<CasePoint> points, int sourceCount) {
        Set<String> regimes = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        CasePoint previous = null;
        for (CasePoint point : points) {
            regimes.add(point.snapshot().path("fixtureSetFingerprint").asText() + ':'
                    + point.snapshot().path("planSetFingerprint").asText());
            if (previous != null && previous.runRegime().equals(point.runRegime())
                    && !previous.snapshot().path("outcomeSetFingerprint").asText().equals(
                    point.snapshot().path("outcomeSetFingerprint").asText())) {
                changed.add(point.runId());
            }
            previous = point;
        }
        String status = points.size() != sourceCount ? "INCONCLUSIVE"
                : !changed.isEmpty() ? "INSTABILITY_OBSERVED"
                : regimes.size() > 1 ? "REGIME_DRIFT_OBSERVED" : "STABLE_PASS";
        ObjectNode json = EvidenceTrustTestFixtures.JSON.createObjectNode();
        json.put("caseId", caseId);
        json.put("status", status);
        ArrayNode sourceIds = json.putArray("sourceRunIds");
        points.forEach(value -> sourceIds.add(value.runId()));
        ArrayNode changedIds = json.putArray("changedAtRunIds");
        changed.forEach(changedIds::add);
        json.put("regimeCount", regimes.size());
        return new CaseTrend(status, json);
    }

    private static String aggregateStatus(List<ObjectNode> sources, List<CaseTrend> trends) {
        if (sources.stream().anyMatch(value -> "FLAKY".equals(value.path("status").asText()))
                || trends.stream().anyMatch(value ->
                "INSTABILITY_OBSERVED".equals(value.status()))) {
            return "INSTABILITY_OBSERVED";
        }
        if (sources.stream().map(value -> value.path("regimeFingerprint").asText())
                .distinct().count() > 1
                || trends.stream().anyMatch(value ->
                "REGIME_DRIFT_OBSERVED".equals(value.status()))) {
            return "REGIME_DRIFT_OBSERVED";
        }
        return "STABLE_PASS";
    }

    private static String setFingerprint(List<String> values) {
        ArrayNode material = EvidenceTrustTestFixtures.JSON.createArrayNode();
        values.stream().distinct().sorted().forEach(material::add);
        return EvidenceVerificationSupport.sha256(material);
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    static void sealTrend(ObjectNode response, KeyPair keyPair) {
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(response.path("evidence"));
        response.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode seal = (ObjectNode) response.path("attestation");
        seal.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        material.put("schemaVersion", seal.path("schemaVersion").asText());
        material.put("trendAnalysisId", seal.path("trendAnalysisId").asText());
        material.put("requestFingerprint", seal.path("requestFingerprint").asText());
        material.put("evidenceFingerprint", evidenceFingerprint);
        material.set("sourceEvidenceRefs", seal.path("sourceEvidenceRefs").deepCopy());
        material.put("signedAt", seal.path("signedAt").asText());
        seal.put("signature", sign(
                EvidenceVerificationSupport.sha256(material), keyPair));
    }

    private static String sign(String materialFingerprint, KeyPair keyPair) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    record Fixture(ObjectNode response, List<TestSuiteStabilityRun> sources,
                   EvidenceVerificationKey key, EvidenceVerificationKeySet keySet,
                   KeyPair keyPair) {
        TestSuiteStabilityTrendAnalysis analysis() {
            return TestSuiteStabilityTrendAnalysis.from(response);
        }

        ObjectNode copyResponse() {
            return response.deepCopy();
        }
    }

    private record Source(TestSuiteStabilityRun run, Instant createdAt) {
    }

    private record CasePoint(String runId, String runRegime, JsonNode snapshot) {
    }

    private record CaseTrend(String status, ObjectNode json) {
    }
}
