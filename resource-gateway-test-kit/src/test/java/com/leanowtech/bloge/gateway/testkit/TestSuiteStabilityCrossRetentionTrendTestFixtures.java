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
import java.util.Collections;
import java.util.List;

/** Re-sealable signed fixtures for strict compact-observation range verification. */
final class TestSuiteStabilityCrossRetentionTrendTestFixtures {
    private static final String KEY_ID = "evidence-key-a";
    private static final String SCOPE_FINGERPRINT = fingerprint('c');
    private static final String SOURCE_REQUEST_FINGERPRINT = fingerprint('d');
    private static final Instant OBSERVED_AT = EvidenceTrustTestFixtures.NOW.plusSeconds(120);

    private TestSuiteStabilityCrossRetentionTrendTestFixtures() {
    }

    static Fixture stableFixture() {
        return fixture(TestSuiteStabilityTrendTestFixtures.stableFixture(), false);
    }

    static Fixture stableFixture(TestSuiteStabilityTrendTestFixtures.Fixture sourceFixture) {
        return fixture(sourceFixture, false);
    }

    static Fixture ledgerOrderDiffersFromSourceOrderFixture() {
        return fixture(TestSuiteStabilityTrendTestFixtures.stableFixture(), true);
    }

    private static Fixture fixture(
            TestSuiteStabilityTrendTestFixtures.Fixture sourceFixture,
            boolean reverseLedgerOrder) {
        ObjectNode retainedEvidence = (ObjectNode) sourceFixture.response().path("evidence");
        List<JsonNode> sources = new ArrayList<>();
        retainedEvidence.path("sources").forEach(value -> sources.add(value.deepCopy()));
        if (reverseLedgerOrder) {
            Collections.reverse(sources);
        }

        TestSuiteStabilityCrossRetentionTrendRequest request =
                TestSuiteStabilityCrossRetentionTrendRequest.firstPage(
                        TestSuiteStabilityTestFixtures.SUITE_ID,
                        TestSuiteStabilityTestFixtures.SUITE_REVISION,
                        TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 2, 10);
        ObjectNode response = EvidenceTrustTestFixtures.JSON.createObjectNode();
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_RESPONSE_V1);
        ObjectNode evidence = response.putObject("evidence");
        evidence.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_EVIDENCE_V1);
        evidence.put("requestFingerprint", request.requestFingerprint());
        evidence.set("request", request.toJson().deepCopy());
        evidence.put("observedRuns", sources.size());
        evidence.put("sourceOrder", "SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID");

        ObjectNode range = evidence.putObject("range");
        range.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_RANGE_V1);
        range.put("scopeFingerprint", SCOPE_FINGERPRINT);
        range.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
        range.put("floorSequence", 1);
        range.put("floorPreviousObservationId", "");
        range.put("floorPreviousEntryFingerprint", "");
        ObjectNode head = range.putObject("head");
        head.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_HEAD_V1);
        head.put("scopeFingerprint", SCOPE_FINGERPRINT);
        head.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
        head.put("coverageFrom", EvidenceTrustTestFixtures.NOW.plusSeconds(30).toString());
        range.put("afterSequence", 0);
        range.put("previousObservationId", "");
        range.put("previousEntryFingerprint", "");
        ArrayNode entries = range.putArray("entries");
        String predecessor = "";
        for (int index = 0; index < sources.size(); index++) {
            ObjectNode entry = entries.addObject();
            entry.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ENTRY_V1);
            entry.put("scopeFingerprint", SCOPE_FINGERPRINT);
            entry.put("sequence", index + 1L);
            entry.put("previousObservationId", predecessor);
            ObjectNode observation = entry.putObject("observation");
            ObjectNode observationEvidence = observation.putObject("evidence");
            observationEvidence.put("schemaVersion",
                    TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EVIDENCE_V1);
            observationEvidence.put("scopeFingerprint", SCOPE_FINGERPRINT);
            observationEvidence.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
            observationEvidence.put("sourceRequestFingerprint", SOURCE_REQUEST_FINGERPRINT);
            observationEvidence.set("source", sources.get(index));
            observationEvidence.put("observationId", observationId(observationEvidence));
            ObjectNode attestation = observation.putObject("attestation");
            attestation.put("schemaVersion",
                    TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ATTESTATION_V1);
            attestation.put("signatureStatus", "VERIFIED");
            attestation.put("observationId", observationEvidence.path("observationId").asText());
            attestation.put("signedAt",
                    EvidenceTrustTestFixtures.NOW.plusSeconds(5L + index).toString());
            attestation.put("keyId", KEY_ID);
            attestation.put("algorithm", "Ed25519");
            attestation.put("independentlyVerifiable", true);
            resealObservation(response, index, sourceFixture.keyPair());
            predecessor = observationEvidence.path("observationId").asText();
            entry.put("appendedAt",
                    EvidenceTrustTestFixtures.NOW.plusSeconds(30L + index).toString());
        }
        range.put("hasMore", false);
        range.put("observedAt", OBSERVED_AT.toString());
        evidence.put("status", retainedEvidence.path("status").asText());
        evidence.set("caseTrends", retainedEvidence.path("caseTrends").deepCopy());
        evidence.set("correlationSignals",
                retainedEvidence.path("correlationSignals").deepCopy());
        evidence.put("causalityStatus", "NOT_PROVEN");
        evidence.putArray("diagnostics");
        evidence.put("evaluatedAt", OBSERVED_AT.toString());

        ObjectNode outer = response.putObject("attestation");
        outer.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_ATTESTATION_V1);
        outer.put("signatureStatus", "VERIFIED");
        outer.put("signedAt", EvidenceTrustTestFixtures.NOW.plusSeconds(20).toString());
        outer.put("keyId", KEY_ID);
        outer.put("algorithm", "Ed25519");
        outer.put("independentlyVerifiable", true);
        resealEnvelope(response, sourceFixture.keyPair());
        return new Fixture(response, sourceFixture.key(), sourceFixture.keySet(),
                sourceFixture.keyPair());
    }

    static void resealObservation(ObjectNode response, int index, KeyPair keyPair) {
        ObjectNode observation = (ObjectNode) response.at(
                "/evidence/range/entries/" + index + "/observation");
        ObjectNode evidence = (ObjectNode) observation.path("evidence");
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        observation.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode attestation = (ObjectNode) observation.path("attestation");
        attestation.put("observationId", evidence.path("observationId").asText());
        attestation.put("observationFingerprint", evidenceFingerprint);
        attestation.put("sourceEvidenceFingerprint",
                evidence.path("source").path("evidenceFingerprint").asText());
        attestation.put("sourceAttestationFingerprint",
                evidence.path("source").path("attestationFingerprint").asText());
        attestation.put("signature", sign(observationSignatureMaterial(attestation), keyPair));
        observation.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(attestation));
    }

    static void resealEnvelope(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode range = (ObjectNode) evidence.path("range");
        ArrayNode entries = (ArrayNode) range.path("entries");
        entries.forEach(value -> ((ObjectNode) value).put("entryFingerprint",
                EvidenceVerificationSupport.sha256(without(value, "entryFingerprint"))));
        ObjectNode first = (ObjectNode) entries.get(0);
        ObjectNode last = (ObjectNode) entries.get(entries.size() - 1);
        range.put("floorObservationId",
                first.at("/observation/evidence/observationId").asText());
        range.put("floorEntryFingerprint", first.path("entryFingerprint").asText());
        ObjectNode head = (ObjectNode) range.path("head");
        head.put("latestSequence", last.path("sequence").asLong());
        head.put("latestObservationId",
                last.at("/observation/evidence/observationId").asText());
        head.put("latestEntryFingerprint", last.path("entryFingerprint").asText());
        head.put("updatedAt", last.path("appendedAt").asText());
        head.put("headFingerprint",
                EvidenceVerificationSupport.sha256(without(head, "headFingerprint")));
        range.put("rangeFingerprint",
                EvidenceVerificationSupport.sha256(without(range, "rangeFingerprint")));

        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("requestFingerprint", evidence.path("requestFingerprint").asText());
        identity.put("rangeFingerprint", range.path("rangeFingerprint").asText());
        String trendId = "stability-cross-retention-trend-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
        response.put("trendAnalysisId", trendId);
        evidence.put("trendAnalysisId", trendId);
        ObjectNode outer = (ObjectNode) response.path("attestation");
        outer.put("trendAnalysisId", trendId);
        outer.put("requestFingerprint", evidence.path("requestFingerprint").asText());
        outer.put("rangeFingerprint", range.path("rangeFingerprint").asText());
        ArrayNode refs = outer.putArray("observationRefs");
        entries.forEach(value -> {
            ObjectNode ref = refs.addObject();
            ref.put("sequence", value.path("sequence").asLong());
            ref.put("observationId", value.at("/observation/evidence/observationId").asText());
            ref.put("observationFingerprint",
                    value.at("/observation/evidenceFingerprint").asText());
            ref.put("observationAttestationFingerprint",
                    value.at("/observation/attestationFingerprint").asText());
            ref.put("entryFingerprint", value.path("entryFingerprint").asText());
        });
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        response.put("evidenceFingerprint", evidenceFingerprint);
        outer.put("evidenceFingerprint", evidenceFingerprint);
        outer.put("signature", sign(outerSignatureMaterial(outer), keyPair));
    }

    static void resealOuter(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        response.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode outer = (ObjectNode) response.path("attestation");
        outer.put("evidenceFingerprint", evidenceFingerprint);
        outer.put("signature", sign(outerSignatureMaterial(outer), keyPair));
    }

    private static String observationId(JsonNode evidence) {
        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("scopeFingerprint", evidence.path("scopeFingerprint").asText());
        identity.set("suiteRef", evidence.path("suiteRef").deepCopy());
        identity.put("sourceRequestFingerprint",
                evidence.path("sourceRequestFingerprint").asText());
        identity.put("stabilityRunId", evidence.path("source").path("stabilityRunId").asText());
        identity.put("sourceEvidenceFingerprint",
                evidence.path("source").path("evidenceFingerprint").asText());
        identity.put("sourceAttestationFingerprint",
                evidence.path("source").path("attestationFingerprint").asText());
        return "stability-observation-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
    }

    private static JsonNode observationSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "observationId", "observationFingerprint",
                "sourceEvidenceFingerprint", "sourceAttestationFingerprint", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static JsonNode outerSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "trendAnalysisId", "requestFingerprint",
                "evidenceFingerprint", "rangeFingerprint", "observationRefs", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static JsonNode without(JsonNode value, String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }

    private static String sign(JsonNode material, KeyPair keyPair) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(EvidenceVerificationSupport.sha256(material)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    record Fixture(
            ObjectNode response,
            EvidenceVerificationKey key,
            EvidenceVerificationKeySet keySet,
            KeyPair keyPair
    ) {
        TestSuiteStabilityCrossRetentionTrendAnalysis analysis() {
            return TestSuiteStabilityCrossRetentionTrendAnalysis.from(response);
        }

        ObjectNode copyResponse() {
            return response.deepCopy();
        }
    }
}
