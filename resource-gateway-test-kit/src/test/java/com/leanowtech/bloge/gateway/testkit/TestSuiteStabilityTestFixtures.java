package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

final class TestSuiteStabilityTestFixtures {
    static final String SUITE_ID = "orders-suite";
    static final long SUITE_REVISION = 7;
    static final String SUITE_FINGERPRINT = fingerprint('a');
    static final String CLIENT_REQUEST_ID = "stability-ci-42";
    static final String STABILITY_RUN_ID = "stability-" + "2".repeat(64);
    static final Instant SIGNED_AT = EvidenceTrustTestFixtures.NOW;

    private TestSuiteStabilityTestFixtures() {
    }

    static Fixture fixture() {
        EvidenceTrustTestFixtures.Fixture trust = EvidenceTrustTestFixtures.fixture();
        ObjectNode response = response(fingerprint('1'), trust.evidence());
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, "evidence-key-a", "Ed25519",
                Base64.getEncoder().encodeToString(trust.evidence().getPublic().getEncoded()),
                SIGNED_AT.minusSeconds(600), "ACTIVE", "test-evidence-authority");
        return new Fixture(response, key,
                EvidenceVerificationKeySet.fromPayload(longLivedKeySet(trust.evidence())),
                trust.evidence());
    }

    static ObjectNode response(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = EvidenceTrustTestFixtures.JSON.createObjectNode();
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1);
        response.put("stabilityRunId", STABILITY_RUN_ID);
        ObjectNode evidence = response.putObject("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V1);
        evidence.put("stabilityRunId", STABILITY_RUN_ID);
        evidence.put("clientRequestId", CLIENT_REQUEST_ID);
        ObjectNode suite = evidence.putObject("suiteRef");
        suite.put("suiteId", SUITE_ID);
        suite.put("revision", SUITE_REVISION);
        suite.put("fingerprint", SUITE_FINGERPRINT);
        ObjectNode target = evidence.putObject("target");
        target.put("kind", "GRAPH");
        target.put("id", "orders");
        target.put("fingerprint", fingerprint('b'));
        evidence.put("requestedAttempts", 3);
        evidence.put("status", "STABLE");
        ArrayNode attempts = evidence.putArray("attempts");
        for (int attempt = 1; attempt <= 3; attempt++) {
            ObjectNode value = attempts.addObject();
            value.put("attempt", attempt);
            value.put("status", "VERIFIED");
            value.put("suiteRunId", "suite-run-" + attempt);
            value.put("aggregateEvidenceFingerprint", fingerprint((char) ('3' + attempt)));
            value.put("suiteStatus", "PASSED");
            value.put("startedAt", SIGNED_AT.minusSeconds(180L - attempt * 40L).toString());
            value.put("completedAt", SIGNED_AT.minusSeconds(179L - attempt * 40L).toString());
            value.put("diagnosticCode", "");
        }
        ObjectNode caseResult = evidence.putArray("caseResults").addObject();
        caseResult.put("caseId", "golden");
        caseResult.put("caseType", "GOLDEN");
        ObjectNode fixture = caseResult.putObject("fixtureBundleRef");
        fixture.put("fixtureBundleId", "orders-fixture");
        fixture.put("revision", 2);
        fixture.put("fingerprint", fingerprint('c'));
        caseResult.put("status", "STABLE_PASS");
        ArrayNode observations = caseResult.putArray("observations");
        for (int attempt = 1; attempt <= 3; attempt++) {
            ObjectNode value = observations.addObject();
            value.put("attempt", attempt);
            value.put("status", "VERIFIED");
            value.put("runId", "child-run-" + attempt);
            value.put("evidenceFingerprint", fingerprint('f'));
            value.put("evidenceStatus", "PASSED");
            value.put("evidenceClass", "CERTIFIABLE");
            value.put("fixtureBundleFingerprint", fingerprint('c'));
            value.put("planFingerprint", fingerprint('d'));
            value.put("semanticResultFingerprint", fingerprint('e'));
            value.put("diagnosticCode", "");
        }
        caseResult.put("distinctVerifiedOutcomes", 1);
        caseResult.putArray("diagnosticCodes");
        ObjectNode promotion = evidence.putObject("promotion");
        promotion.put("status", "ELIGIBLE");
        promotion.putArray("reasons");
        promotion.put("stableCases", 1);
        promotion.put("flakyCases", 0);
        promotion.put("consistentFailureCases", 0);
        promotion.put("inconclusiveCases", 0);
        promotion.put("allAttemptsVerified", true);
        ObjectNode quarantine = evidence.putObject("quarantine");
        quarantine.put("status", "NOT_REQUIRED");
        quarantine.putArray("caseIds");
        quarantine.put("reason", "");
        evidence.put("startedAt", SIGNED_AT.minusSeconds(140).toString());
        evidence.put("completedAt", SIGNED_AT.minusSeconds(59).toString());
        evidence.putArray("diagnostics");
        evidence.putObject("metadata").put("pipeline", "nightly");

        ObjectNode attestation = response.putObject("attestation");
        attestation.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V1);
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("stabilityRunId", STABILITY_RUN_ID);
        attestation.set("suiteRef", suite.deepCopy());
        attestation.put("requestFingerprint", requestFingerprint);
        attestation.put("signedAt", SIGNED_AT.toString());
        attestation.put("keyId", "evidence-key-a");
        attestation.put("algorithm", "Ed25519");
        attestation.put("independentlyVerifiable", true);
        seal(response, signingKey, true);
        return response;
    }

    static void seal(ObjectNode response, KeyPair keyPair, boolean synchronizeSources) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode attestation = (ObjectNode) response.path("attestation");
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        response.put("evidenceFingerprint", evidenceFingerprint);
        attestation.put("evidenceFingerprint", evidenceFingerprint);
        if (synchronizeSources) {
            ArrayNode sources = attestation.putArray("sourceSuiteEvidenceRefs");
            evidence.path("attempts").forEach(attempt -> {
                ObjectNode source = sources.addObject();
                source.put("attempt", attempt.path("attempt").asInt());
                source.put("suiteRunId", attempt.path("suiteRunId").asText());
                source.put("aggregateEvidenceFingerprint",
                        attempt.path("aggregateEvidenceFingerprint").asText());
            });
        }
        attestation.put("signature", sign(keyPair,
                EvidenceVerificationSupport.sha256(signatureMaterial(attestation))));
    }

    static void makeFlaky(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        ((ObjectNode) result.path("observations").get(1))
                .put("semanticResultFingerprint", fingerprint('9'));
        result.put("status", "FLAKY");
        result.put("distinctVerifiedOutcomes", 2);
        evidence.put("status", "FLAKY");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons").add("FLAKY_CASE_OBSERVED");
        promotion.put("stableCases", 0);
        promotion.put("flakyCases", 1);
        ObjectNode quarantine = (ObjectNode) evidence.path("quarantine");
        quarantine.put("status", "REQUIRED");
        quarantine.putArray("caseIds").add("golden");
        quarantine.put("reason", "FLAKY_CASE_OBSERVED");
        seal(response, keyPair, false);
    }

    private static ObjectNode signatureMaterial(ObjectNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        material.put("schemaVersion", attestation.path("schemaVersion").asText());
        material.put("stabilityRunId", attestation.path("stabilityRunId").asText());
        material.set("suiteRef", attestation.path("suiteRef").deepCopy());
        material.put("requestFingerprint", attestation.path("requestFingerprint").asText());
        material.put("evidenceFingerprint", attestation.path("evidenceFingerprint").asText());
        material.set("sourceSuiteEvidenceRefs",
                attestation.path("sourceSuiteEvidenceRefs").deepCopy());
        material.put("signedAt", attestation.path("signedAt").asText());
        return material;
    }

    private static ObjectNode longLivedKeySet(KeyPair keyPair) {
        Instant createdAt = SIGNED_AT.minusSeconds(600);
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", "test-evidence-authority");
        material.put("generatedAt", SIGNED_AT.minusSeconds(300).toString());
        material.put("expiresAt", "2099-01-01T00:00:00Z");
        material.put("activeKeyId", "evidence-key-a");
        material.put("policyCompleteness", "COMPLETE");
        ObjectNode key = material.putArray("keys").addObject();
        key.put("keyId", "evidence-key-a");
        key.put("algorithm", "Ed25519");
        key.put("encodedPublicKey",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        key.put("createdAt", createdAt.toString());
        key.put("notBefore", createdAt.toString());
        key.putNull("notAfter");
        key.put("state", "ACTIVE");
        key.put("providerKeyVersion", "version-a");
        ArrayNode events = material.putArray("events");
        addLifecycleEvent(events.addObject(), 1, "CREATED", createdAt);
        addLifecycleEvent(events.addObject(), 2, "ACTIVATED", createdAt);
        String fingerprint = EvidenceVerificationSupport.sha256(material);
        ObjectNode snapshot = material.deepCopy();
        snapshot.put("snapshotFingerprint", fingerprint);
        ObjectNode attestation = snapshot.putObject("attestation");
        attestation.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        attestation.put("materialFingerprint", fingerprint);
        attestation.put("algorithm", "Ed25519");
        attestation.put("keyId", "evidence-key-a");
        attestation.put("signedAt", SIGNED_AT.minusSeconds(299).toString());
        attestation.put("signature", sign(keyPair, fingerprint));
        return snapshot;
    }

    private static void addLifecycleEvent(
            ObjectNode event,
            long sequence,
            String type,
            Instant occurredAt) {
        event.put("sequence", sequence);
        event.put("eventId", type.toLowerCase(java.util.Locale.ROOT) + ":evidence-key-a");
        event.put("keyId", "evidence-key-a");
        event.put("type", type);
        event.put("occurredAt", occurredAt.toString());
        event.put("effectiveAt", occurredAt.toString());
        event.putNull("revocationMode");
        event.putNull("invalidFrom");
        event.put("reasonCode", "KEY_" + type);
    }

    private static String sign(KeyPair keyPair, String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    record Fixture(ObjectNode response, EvidenceVerificationKey key,
                   EvidenceVerificationKeySet keySet, KeyPair keyPair) {
        TestSuiteStabilityRun run() {
            return TestSuiteStabilityRun.from(response.deepCopy());
        }

        ObjectNode copyResponse() {
            return response.deepCopy();
        }
    }
}
