package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

final class TestSuiteStabilityAuthorityTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    static final String AUTHORITY_ID = "iam.example";
    static final String KEY_ID = "iam-key-1";
    static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private TestSuiteStabilityAuthorityTestFixtures() {
    }

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static TestSuiteStabilityJobRecord job() {
        TestSuiteStabilityExecutionRequest request =
                new TestSuiteStabilityExecutionRequest(
                        "", new TestSuiteExecutionRequest.SuiteRef(
                        "suite.checkout", 7, FINGERPRINT),
                        "stability-request-1", 3,
                        Map.of("pipeline", "release", "payload", "business-secret"));
        TestSuiteStabilityJobPrincipal principal = new TestSuiteStabilityJobPrincipal(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "SERVICE", "studio-ci", "release-bot", "TEST_EXECUTION",
                "correlation-secret", Set.of("payments", "release"),
                "CONFIDENTIAL", "grant-17");
        return new TestSuiteStabilityJobRecord(
                "stability-job-1", request, FINGERPRINT, "CONFIDENTIAL", principal,
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                TestSuiteStabilityJobRecord.Status.RUNNING, 0, NOW, NOW.plusSeconds(300),
                NOW.minusSeconds(60), NOW, NOW.plusSeconds(3600), "", "", "", "", "",
                "sha256:" + "b".repeat(64));
    }

    static TestSuiteStabilityAuthorityRequest request(ObjectMapper objectMapper) {
        return TestSuiteStabilityAuthorityRequest.create(
                objectMapper, job(), "authz-request-1",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), NOW);
    }

    static TestSuiteStabilityAuthorityResponse response(
            ObjectMapper objectMapper,
            KeyPair keyPair,
            TestSuiteStabilityAuthorityRequest request,
            TestSuiteStabilityAuthorityResponse.Decision decision,
            String failureCode) {
        return response(objectMapper, keyPair, request, decision, failureCode,
                request.challenge(), AUTHORITY_ID, KEY_ID, NOW, NOW.plusSeconds(30));
    }

    static TestSuiteStabilityAuthorityResponse response(
            ObjectMapper objectMapper,
            KeyPair keyPair,
            TestSuiteStabilityAuthorityRequest request,
            TestSuiteStabilityAuthorityResponse.Decision decision,
            String failureCode,
            String challenge,
            String authorityId,
            String keyId,
            Instant issuedAt,
            Instant expiresAt) {
        TestSuiteStabilityAuthorityResponse.Material material =
                new TestSuiteStabilityAuthorityResponse.Material(
                        TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION,
                        request.requestId(), challenge, request.jobId(),
                        request.authorizationRequestFingerprint(), request.principalFingerprint(),
                        decision, failureCode, authorityId, "policy-42", "decision-123",
                        issuedAt, expiresAt);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityAuthorityResponse(
                material.schemaVersion(), material.requestId(), material.challenge(),
                material.jobId(), material.authorizationRequestFingerprint(),
                material.principalFingerprint(), material.decision(), material.failureCode(),
                material.authorityId(), material.policyRevision(), material.decisionId(),
                material.issuedAt(), material.expiresAt(), fingerprint,
                new TestSuiteStabilityAuthorityResponse.SignatureBlock(
                        keyId, "Ed25519", sign(keyPair, fingerprint)));
    }

    private static String sign(KeyPair keyPair, String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
