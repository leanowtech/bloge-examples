package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

final class TestSecretAuthorityProtocolTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");
    static final String AUTHORITY_ID = "test-secret-authority.example";
    static final String AUTHORITY_GENERATION = "policy-generation-7";
    static final String KEY_ID = "secret-key-7";
    static final String ALIAS = "payment-key";
    static final String REFERENCE = "vault://test/payments/key@v3";
    static final String VERSION = "version-3";
    static final String VALUE = "runtime-secret-value";

    private TestSecretAuthorityProtocolTestFixtures() {
    }

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static TestSecretResolutionContext context() {
        return new TestSecretResolutionContext("", "tenant-a", "org-a", "project-a",
                "test", "region-a", "SERVICE", "ci-runner", "release-bot",
                "TEST_EXECUTION", Set.of("quality-engineers"), "CONFIDENTIAL",
                "grant-7", "TEST_EXECUTION", fingerprint('a'), fingerprint('b'),
                "fixture-payment", 7, fingerprint('c'), Map.of(ALIAS, REFERENCE));
    }

    static TestSecretAuthorityRequest request(ObjectMapper objectMapper) {
        return TestSecretAuthorityRequest.create(objectMapper, context(), "secret-request-7",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), NOW);
    }

    static TestSecretAuthorityResponse response(
            ObjectMapper objectMapper,
            KeyPair signer,
            TestSecretAuthorityRequest request,
            TestSecretAuthorityResponse.Decision decision,
            String failureCode) {
        return response(objectMapper, signer, request, decision, failureCode,
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, KEY_ID,
                NOW, NOW.plusSeconds(30), VALUE);
    }

    static TestSecretAuthorityResponse response(
            ObjectMapper objectMapper,
            KeyPair signer,
            TestSecretAuthorityRequest request,
            TestSecretAuthorityResponse.Decision decision,
            String failureCode,
            String challenge,
            String authorityId,
            String authorityGeneration,
            String keyId,
            Instant issuedAt,
            Instant expiresAt,
            String value) {
        Map<String, TestSecretAuthorityResponse.SecretMaterial> secrets;
        if (decision == TestSecretAuthorityResponse.Decision.AUTHORIZED) {
            String binding = ResolvedTestSecrets.bindingFingerprint(objectMapper,
                    request.contextFingerprint(), authorityId, authorityGeneration,
                    ALIAS, REFERENCE, VERSION);
            secrets = Map.of(ALIAS, new TestSecretAuthorityResponse.SecretMaterial(
                    ALIAS, REFERENCE, VERSION, binding, value));
        } else {
            secrets = Map.of();
        }
        TestSecretAuthorityResponse.Material material =
                new TestSecretAuthorityResponse.Material(
                        TestSecretAuthorityResponse.SCHEMA_VERSION,
                        request.requestId(), challenge, request.requestFingerprint(),
                        request.contextFingerprint(), decision, failureCode, authorityId,
                        authorityGeneration, "decision-7", issuedAt, expiresAt, secrets);
        String materialFingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, material, 2 * 1024 * 1024);
        String signature = sign(signer, materialFingerprint);
        return new TestSecretAuthorityResponse(material.schemaVersion(), material.requestId(),
                material.challenge(), material.requestFingerprint(),
                material.contextFingerprint(), material.decision(), material.failureCode(),
                material.authorityId(), material.authorityGeneration(), material.decisionId(),
                material.issuedAt(), material.expiresAt(), material.secrets(),
                materialFingerprint,
                new TestSecretAuthorityResponse.SignatureBlock(
                        keyId, "Ed25519", signature));
    }

    static String fingerprint(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private static String sign(KeyPair keyPair, String materialFingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
