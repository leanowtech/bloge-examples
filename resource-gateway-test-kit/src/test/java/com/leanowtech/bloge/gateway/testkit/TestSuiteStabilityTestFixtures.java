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
        return fixture(response, trust.evidence());
    }

    static Fixture statisticalFixture() {
        EvidenceTrustTestFixtures.Fixture trust = EvidenceTrustTestFixtures.fixture();
        ObjectNode response = statisticalResponse(fingerprint('1'), trust.evidence());
        return fixture(response, trust.evidence());
    }

    static Fixture rateFixture() {
        EvidenceTrustTestFixtures.Fixture trust = EvidenceTrustTestFixtures.fixture();
        ObjectNode response = rateResponse(fingerprint('1'), trust.evidence());
        return fixture(response, trust.evidence());
    }

    static Fixture sequentialFixture() {
        EvidenceTrustTestFixtures.Fixture trust = EvidenceTrustTestFixtures.fixture();
        ObjectNode response = sequentialResponse(fingerprint('1'), trust.evidence());
        return fixture(response, trust.evidence());
    }

    private static Fixture fixture(ObjectNode response, KeyPair keyPair) {
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, "evidence-key-a", "Ed25519",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                SIGNED_AT.minusSeconds(600), "ACTIVE", "test-evidence-authority");
        return new Fixture(response, key,
                EvidenceVerificationKeySet.fromPayload(longLivedKeySet(keyPair)), keyPair);
    }

    static ObjectNode response(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = EvidenceTrustTestFixtures.JSON.createObjectNode();
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2);
        response.put("stabilityRunId", STABILITY_RUN_ID);
        ObjectNode evidence = response.putObject("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V2);
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
            value.put("sourcePromotionStatus", "ELIGIBLE");
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
        promotion.put("allSourceSuitesPromotionEligible", true);
        ObjectNode quarantine = evidence.putObject("quarantine");
        quarantine.put("status", "NOT_REQUIRED");
        quarantine.putArray("caseIds");
        quarantine.put("reason", "");
        evidence.put("startedAt", SIGNED_AT.minusSeconds(140).toString());
        evidence.put("completedAt", SIGNED_AT.minusSeconds(59).toString());
        evidence.putArray("diagnostics");
        evidence.putObject("metadata").put("pipeline", "nightly");

        ObjectNode attestation = response.putObject("attestation");
        attestation.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V2);
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

    static ObjectNode statisticalResponse(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = response(requestFingerprint, signingKey);
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V3);
        evidence.put("requestedAttempts", 29);
        ArrayNode attempts = evidence.putArray("attempts");
        ArrayNode observations = (ArrayNode) evidence.at("/caseResults/0/observations");
        observations.removeAll();
        for (int attempt = 1; attempt <= 29; attempt++) {
            Instant startedAt = SIGNED_AT.minusSeconds((30L - attempt) * 2L);
            ObjectNode source = attempts.addObject();
            source.put("attempt", attempt);
            source.put("status", "VERIFIED");
            source.put("suiteRunId", "suite-run-statistical-" + attempt);
            source.put("aggregateEvidenceFingerprint", attemptFingerprint(attempt));
            source.put("suiteStatus", "PASSED");
            source.put("sourcePromotionStatus", "ELIGIBLE");
            source.put("startedAt", startedAt.toString());
            source.put("completedAt", startedAt.plusSeconds(1).toString());
            source.put("diagnosticCode", "");

            ObjectNode observation = observations.addObject();
            observation.put("attempt", attempt);
            observation.put("status", "VERIFIED");
            observation.put("runId", "child-run-statistical-" + attempt);
            observation.put("evidenceFingerprint", fingerprint('f'));
            observation.put("evidenceStatus", "PASSED");
            observation.put("evidenceClass", "CERTIFIABLE");
            observation.put("fixtureBundleFingerprint", fingerprint('c'));
            observation.put("planFingerprint", fingerprint('d'));
            observation.put("semanticResultFingerprint", fingerprint('e'));
            observation.put("diagnosticCode", "");
        }
        ObjectNode policy = evidence.putObject("statisticalAssessment").putObject("policy");
        TestSuiteStabilityStatisticalPolicy statisticalPolicy =
                TestSuiteStabilityStatisticalPolicy.exactBinomial(9_500, 1_000);
        policy.put("model", statisticalPolicy.model().name());
        policy.put("claimScope", statisticalPolicy.claimScope().name());
        policy.put("stoppingRule", statisticalPolicy.stoppingRule().name());
        policy.put("censoringPolicy", statisticalPolicy.censoringPolicy().name());
        policy.put("confidenceLevelBps", statisticalPolicy.confidenceLevelBps());
        policy.put("maximumInstabilityRateBps",
                statisticalPolicy.maximumInstabilityRateBps());
        ObjectNode assessment = (ObjectNode) evidence.path("statisticalAssessment");
        assessment.put("requiredAttempts", 29);
        assessment.put("observedAttempts", 29);
        assessment.put("verifiedAttempts", 29);
        assessment.put("censoredAttempts", 0);
        assessment.put("observedInstabilityEvents", 0);
        assessment.put("achievedConfidenceBps",
                statisticalPolicy.achievedConfidenceBps(29));
        assessment.put("status", "SATISFIED");
        assessment.put("stopReason", "FIXED_HORIZON_REACHED");
        ArrayNode assumptions = assessment.putArray("assumptions");
        TestSuiteStabilityRun.STATISTICAL_MODEL_ASSUMPTIONS.forEach(assumptions::add);
        ((ObjectNode) evidence.path("promotion"))
                .put("statisticalConfidenceSatisfied", true);
        evidence.put("startedAt", SIGNED_AT.minusSeconds(58).toString());
        evidence.put("completedAt", SIGNED_AT.minusSeconds(1).toString());
        ((ObjectNode) response.path("attestation")).put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V3);
        seal(response, signingKey, true);
        return response;
    }

    static ObjectNode rateResponse(String requestFingerprint, KeyPair signingKey) {
        return rateResponse(requestFingerprint, signingKey, 60, 2);
    }

    static ObjectNode rateStableResponse(String requestFingerprint, KeyPair signingKey) {
        return rateResponse(requestFingerprint, signingKey, 30, 0);
    }

    static ObjectNode sequentialResponse(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = rateResponse(requestFingerprint, signingKey, 57, 0);
        return upgradeToAnytime(response, signingKey, 100, 0, false, 57);
    }

    static ObjectNode sequentialMaximumResponse(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = rateResponse(requestFingerprint, signingKey, 60, 2);
        return upgradeToAnytime(response, signingKey, 60, 1, false, null);
    }

    static ObjectNode sequentialCensoredResponse(String requestFingerprint, KeyPair signingKey) {
        ObjectNode response = rateResponse(requestFingerprint, signingKey, 2, 0);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode attempt = (ObjectNode) evidence.path("attempts").get(1);
        attempt.put("status", "INCONCLUSIVE");
        attempt.put("diagnosticCode", "SOURCE_EVIDENCE_INCOMPLETE");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        ObjectNode observation = (ObjectNode) result.path("observations").get(1);
        observation.put("status", "INCONCLUSIVE");
        observation.put("diagnosticCode", "SOURCE_EVIDENCE_INCOMPLETE");
        result.put("status", "INCONCLUSIVE");
        result.putArray("diagnosticCodes").add("SOURCE_EVIDENCE_INCOMPLETE");
        evidence.put("status", "INCONCLUSIVE");
        evidence.putArray("diagnostics").add("SOURCE_EVIDENCE_INCOMPLETE");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons")
                .add("STABILITY_EVIDENCE_INCOMPLETE")
                .add("STATISTICAL_CONFIDENCE_INCONCLUSIVE");
        promotion.put("stableCases", 0);
        promotion.put("flakyCases", 0);
        promotion.put("inconclusiveCases", 1);
        promotion.put("allAttemptsVerified", false);
        promotion.put("allSourceSuitesPromotionEligible", false);
        promotion.put("statisticalConfidenceSatisfied", false);
        ObjectNode quarantine = (ObjectNode) evidence.path("quarantine");
        quarantine.put("status", "UNDETERMINED");
        quarantine.putArray("caseIds");
        quarantine.put("reason", "STABILITY_EVIDENCE_INCOMPLETE");
        return upgradeToAnytime(response, signingKey, 100, 0, true, null);
    }

    static ObjectNode sequentialLateCrossingResponse(
            String requestFingerprint,
            KeyPair signingKey) {
        ObjectNode response = rateResponse(requestFingerprint, signingKey, 58, 0);
        return upgradeToAnytime(response, signingKey, 100, 0, false, 58);
    }

    private static ObjectNode upgradeToAnytime(
            ObjectNode response,
            KeyPair signingKey,
            int requestedAttempts,
            int observedEvents,
            boolean censored,
            Integer reportedCrossing) {
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V5);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V5);
        evidence.put("requestedAttempts", requestedAttempts);
        TestSuiteStabilityStatisticalPolicy policy =
                TestSuiteStabilityStatisticalPolicy.anytimeValidEProcess(9_500, 1_000, 500);
        ObjectNode assessment = (ObjectNode) evidence.path("statisticalAssessment");
        ObjectNode policyNode = (ObjectNode) assessment.path("policy");
        policyNode.put("model", policy.model().name());
        policyNode.put("stoppingRule", policy.stoppingRule().name());
        policyNode.put("alternativeInstabilityRateBps",
                policy.alternativeInstabilityRateBps());
        int observedAttempts = evidence.path("attempts").size();
        int verifiedAttempts = censored ? observedAttempts - 1 : observedAttempts;
        int comparisons = Math.max(0, verifiedAttempts - 1);
        assessment.put("requiredAttempts", policy.minimumRequiredAttempts());
        assessment.put("observedAttempts", observedAttempts);
        assessment.put("verifiedAttempts", verifiedAttempts);
        assessment.put("censoredAttempts", censored ? 1 : 0);
        assessment.put("observedInstabilityEvents", observedEvents);
        assessment.put("achievedConfidenceBps", censored ? 0
                : policy.sequentialAchievedConfidenceBps(observedAttempts - 1, observedEvents));
        assessment.put("comparisonAttempts", comparisons);
        assessment.remove("upperInstabilityRateBps");
        if (reportedCrossing == null) {
            assessment.remove("firstBoundaryCrossingAttempt");
        } else {
            assessment.put("firstBoundaryCrossingAttempt", reportedCrossing);
        }
        assessment.put("status", censored ? "INCONCLUSIVE"
                : reportedCrossing == null ? "REJECTED" : "SATISFIED");
        assessment.put("stopReason", censored ? "CENSORING_OBSERVED"
                : reportedCrossing == null
                ? "MAXIMUM_HORIZON_REACHED" : "E_VALUE_THRESHOLD_REACHED");
        ArrayNode assumptions = assessment.putArray("assumptions");
        TestSuiteStabilityRun.ANYTIME_VALID_MODEL_ASSUMPTIONS.forEach(assumptions::add);
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        if (!censored && reportedCrossing == null) {
            promotion.put("statisticalConfidenceSatisfied", false);
            ((ArrayNode) promotion.path("reasons")).add("STATISTICAL_CONFIDENCE_REJECTED");
        }
        ((ObjectNode) response.path("attestation")).put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V5);
        seal(response, signingKey, true);
        return response;
    }

    private static ObjectNode rateResponse(
            String requestFingerprint,
            KeyPair signingKey,
            int attemptCount,
            int variantAttempt) {
        ObjectNode response = response(requestFingerprint, signingKey);
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V4);
        evidence.put("requestedAttempts", attemptCount);
        evidence.put("status", variantAttempt == 0 ? "STABLE" : "FLAKY");
        ArrayNode attempts = evidence.putArray("attempts");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        ArrayNode observations = result.putArray("observations");
        for (int attempt = 1; attempt <= attemptCount; attempt++) {
            Instant startedAt = SIGNED_AT.minusSeconds((attemptCount + 1L - attempt) * 2L);
            ObjectNode source = attempts.addObject();
            source.put("attempt", attempt);
            source.put("status", "VERIFIED");
            source.put("suiteRunId", "suite-run-rate-" + attempt);
            source.put("aggregateEvidenceFingerprint", attemptFingerprint(attempt));
            source.put("suiteStatus", "PASSED");
            source.put("sourcePromotionStatus", "ELIGIBLE");
            source.put("startedAt", startedAt.toString());
            source.put("completedAt", startedAt.plusSeconds(1).toString());
            source.put("diagnosticCode", "");

            ObjectNode observation = observations.addObject();
            observation.put("attempt", attempt);
            observation.put("status", "VERIFIED");
            observation.put("runId", "child-run-rate-" + attempt);
            observation.put("evidenceFingerprint", fingerprint('f'));
            observation.put("evidenceStatus", "PASSED");
            observation.put("evidenceClass", "CERTIFIABLE");
            observation.put("fixtureBundleFingerprint", fingerprint('c'));
            observation.put("planFingerprint", fingerprint('d'));
            observation.put("semanticResultFingerprint",
                    attempt == variantAttempt ? fingerprint('9') : fingerprint('e'));
            observation.put("diagnosticCode", "");
        }
        result.put("status", variantAttempt == 0 ? "STABLE_PASS" : "FLAKY");
        result.put("distinctVerifiedOutcomes", variantAttempt == 0 ? 1 : 2);
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", variantAttempt == 0 ? "ELIGIBLE" : "BLOCKED");
        ArrayNode reasons = promotion.putArray("reasons");
        if (variantAttempt != 0) {
            reasons.add("FLAKY_CASE_OBSERVED");
        }
        promotion.put("stableCases", variantAttempt == 0 ? 1 : 0);
        promotion.put("flakyCases", variantAttempt == 0 ? 0 : 1);
        promotion.put("statisticalConfidenceSatisfied", true);
        ObjectNode quarantine = (ObjectNode) evidence.path("quarantine");
        quarantine.put("status", variantAttempt == 0 ? "NOT_REQUIRED" : "REQUIRED");
        ArrayNode caseIds = quarantine.putArray("caseIds");
        if (variantAttempt != 0) {
            caseIds.add("golden");
        }
        quarantine.put("reason", variantAttempt == 0 ? "" : "FLAKY_CASE_OBSERVED");

        TestSuiteStabilityStatisticalPolicy policy =
                TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(
                        9_500, 1_000);
        ObjectNode assessment = evidence.putObject("statisticalAssessment");
        ObjectNode policyNode = assessment.putObject("policy");
        policyNode.put("model", policy.model().name());
        policyNode.put("claimScope", policy.claimScope().name());
        policyNode.put("stoppingRule", policy.stoppingRule().name());
        policyNode.put("censoringPolicy", policy.censoringPolicy().name());
        policyNode.put("confidenceLevelBps", policy.confidenceLevelBps());
        policyNode.put("maximumInstabilityRateBps", policy.maximumInstabilityRateBps());
        assessment.put("requiredAttempts", 30);
        int comparisons = attemptCount - 1;
        int observedEvents = variantAttempt == 0 ? 0 : 1;
        assessment.put("observedAttempts", attemptCount);
        assessment.put("verifiedAttempts", attemptCount);
        assessment.put("censoredAttempts", 0);
        assessment.put("observedInstabilityEvents", observedEvents);
        assessment.put("achievedConfidenceBps",
                policy.achievedConfidenceBps(comparisons, observedEvents));
        assessment.put("comparisonAttempts", comparisons);
        assessment.put("upperInstabilityRateBps",
                policy.upperInstabilityRateBps(comparisons, observedEvents));
        assessment.put("status", "SATISFIED");
        assessment.put("stopReason", "FIXED_HORIZON_REACHED");
        ArrayNode assumptions = assessment.putArray("assumptions");
        TestSuiteStabilityRun.BASELINE_CONDITIONAL_MODEL_ASSUMPTIONS
                .forEach(assumptions::add);
        evidence.put("startedAt", SIGNED_AT.minusSeconds(attemptCount * 2L).toString());
        evidence.put("completedAt", SIGNED_AT.minusSeconds(1).toString());
        ((ObjectNode) response.path("attestation")).put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V4);
        seal(response, signingKey, true);
        return response;
    }

    static void makeStatisticalFlaky(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        ((ObjectNode) result.path("observations").get(1))
                .put("semanticResultFingerprint", fingerprint('9'));
        result.put("status", "FLAKY");
        result.put("distinctVerifiedOutcomes", 2);
        evidence.put("status", "FLAKY");
        ObjectNode assessment = (ObjectNode) evidence.path("statisticalAssessment");
        assessment.put("observedInstabilityEvents", 1);
        assessment.put("achievedConfidenceBps", 0);
        assessment.put("status", "REJECTED");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons")
                .add("FLAKY_CASE_OBSERVED")
                .add("STATISTICAL_CONFIDENCE_REJECTED");
        promotion.put("stableCases", 0);
        promotion.put("flakyCases", 1);
        promotion.put("statisticalConfidenceSatisfied", false);
        ObjectNode quarantine = (ObjectNode) evidence.path("quarantine");
        quarantine.put("status", "REQUIRED");
        quarantine.putArray("caseIds").add("golden");
        quarantine.put("reason", "FLAKY_CASE_OBSERVED");
        seal(response, keyPair, false);
    }

    static void makeStatisticalCensored(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode attempt = (ObjectNode) evidence.path("attempts").get(1);
        attempt.put("status", "INCONCLUSIVE");
        attempt.put("diagnosticCode", "SOURCE_EVIDENCE_INCOMPLETE");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        ObjectNode observation = (ObjectNode) result.path("observations").get(1);
        observation.put("status", "INCONCLUSIVE");
        observation.put("diagnosticCode", "SOURCE_EVIDENCE_INCOMPLETE");
        result.put("status", "INCONCLUSIVE");
        result.putArray("diagnosticCodes").add("SOURCE_EVIDENCE_INCOMPLETE");
        evidence.put("status", "INCONCLUSIVE");
        evidence.putArray("diagnostics").add("SOURCE_EVIDENCE_INCOMPLETE");
        ObjectNode assessment = (ObjectNode) evidence.path("statisticalAssessment");
        assessment.put("verifiedAttempts", 28);
        assessment.put("censoredAttempts", 1);
        assessment.put("achievedConfidenceBps", 0);
        assessment.put("status", "INCONCLUSIVE");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons")
                .add("STABILITY_EVIDENCE_INCOMPLETE")
                .add("STATISTICAL_CONFIDENCE_INCONCLUSIVE");
        promotion.put("stableCases", 0);
        promotion.put("inconclusiveCases", 1);
        promotion.put("allAttemptsVerified", false);
        promotion.put("allSourceSuitesPromotionEligible", false);
        promotion.put("statisticalConfidenceSatisfied", false);
        ObjectNode quarantine = (ObjectNode) evidence.path("quarantine");
        quarantine.put("status", "UNDETERMINED");
        quarantine.putArray("caseIds");
        quarantine.put("reason", "STABILITY_EVIDENCE_INCOMPLETE");
        seal(response, keyPair, true);
    }

    static void makeStatisticalConsistentFailure(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode result = (ObjectNode) evidence.path("caseResults").get(0);
        result.path("observations").forEach(value ->
                ((ObjectNode) value).put("evidenceStatus", "ASSERTION_FAILED"));
        result.put("status", "CONSISTENT_FAILURE");
        evidence.put("status", "CONSISTENT_FAILURE");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons").add("CONSISTENT_TEST_FAILURE");
        promotion.put("stableCases", 0);
        promotion.put("consistentFailureCases", 1);
        promotion.put("statisticalConfidenceSatisfied", true);
        seal(response, keyPair, false);
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
                if (attempt.has("sourcePromotionStatus")) {
                    source.put("sourcePromotionStatus",
                            attempt.path("sourcePromotionStatus").asText());
                    if (attempt.has("sourcePromotionReasons")) {
                        source.set("sourcePromotionReasons",
                                attempt.path("sourcePromotionReasons").deepCopy());
                    }
                }
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

    static void blockSourcePromotion(ObjectNode response, KeyPair keyPair) {
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        ObjectNode attempt = (ObjectNode) evidence.path("attempts").get(1);
        attempt.put("sourcePromotionStatus", "BLOCKED");
        attempt.putArray("sourcePromotionReasons").add("NO_CERTIFIABLE_CASES");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons").add("SOURCE_SUITE_PROMOTION_BLOCKED");
        promotion.put("allSourceSuitesPromotionEligible", false);
        seal(response, keyPair, true);
    }

    static void downgradeToLegacyV1(ObjectNode response, KeyPair keyPair) {
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1);
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V1);
        evidence.path("attempts").forEach(value -> {
            ObjectNode attempt = (ObjectNode) value;
            attempt.remove("sourcePromotionStatus");
            attempt.remove("sourcePromotionReasons");
        });
        ((ObjectNode) evidence.path("promotion"))
                .remove("allSourceSuitesPromotionEligible");
        ((ObjectNode) response.path("attestation")).put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V1);
        seal(response, keyPair, true);
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

    private static String attemptFingerprint(int attempt) {
        return "sha256:" + String.format(java.util.Locale.ROOT, "%064x", attempt);
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
