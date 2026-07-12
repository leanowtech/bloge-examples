package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedJwtIntegrationIdentityResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
    private static KeyPair rsa;
    private static KeyPair ed25519;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        rsa = rsaGenerator.generateKeyPair();
        ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void verifiesServerOwnedClaimsAndReturnsNonSecretAuditIdentifiers() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("rsa-2026-07", "RS256", rsa)),
                Set.of()));

        IntegrationIdentityResolver.Resolution verified = resolver.resolveVerified(
                token("RS256", "rsa-2026-07", rsa, claims("token-001"))).orElseThrow();

        assertThat(verified.credentialId()).isEqualTo("rsa-2026-07");
        assertThat(verified.tokenId()).isEqualTo("token-001");
        assertThat(verified.identity()).extracting(IntegrationWorkloadIdentity::identityId,
                        IntegrationWorkloadIdentity::tenantId, IntegrationWorkloadIdentity::organizationId,
                        IntegrationWorkloadIdentity::projectId, IntegrationWorkloadIdentity::environmentId,
                        IntegrationWorkloadIdentity::actorId)
                .containsExactly("aneke-workload", "tenant-a", "knowledge-governance", "tool-studio", "prod",
                        "aneke-sync");
        assertThat(verified.identity().allowedPurposes()).containsExactlyInAnyOrder("CHANGE_SYNC", "PAYLOAD_REPLAY");
        assertThat(verified.identity().groups()).containsExactlyInAnyOrder("knowledge-owners", "tool-authors");
        assertThat(verified.identity().clearance()).isEqualTo("CONFIDENTIAL");
        assertThat(verified.identity().hasClearanceAtLeast("INTERNAL")).isTrue();
        assertThat(resolver.descriptor().properties())
                .containsEntry("trustedKeyCount", 1)
                .containsEntry("tokenRevocationSupported", true)
                .containsEntry("organizationGroupClaimsSupported", true)
                .containsEntry("issuerAttestedDelegationGrantSupported", true);
    }

    @Test
    void acceptsRsaAndEdDsaKeysDuringAZeroDowntimeRotationWindow() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(
                key("rsa-old", "RS256", rsa), key("ed-new", "EdDSA", ed25519)), Set.of()));

        assertThat(resolver.resolve(token("RS256", "rsa-old", rsa, claims("old-token")))).isPresent();
        assertThat(resolver.resolve(token("EdDSA", "ed-new", ed25519, claims("new-token")))).isPresent();
        assertThat(resolver.descriptor().properties())
                .containsEntry("trustedKeyCount", 2)
                .containsEntry("keyRotationSupported", true);
    }

    @Test
    void rejectsSignatureAlgorithmKeyAndTrustBoundaryAttacks() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("trusted", "RS256", rsa)),
                Set.of()));
        String valid = token("RS256", "trusted", rsa, claims("valid"));

        assertThat(resolver.resolve(tamperSignature(valid))).isEmpty();
        assertThat(resolver.resolve(token("EdDSA", "trusted", ed25519, claims("alg-confusion")))).isEmpty();
        assertThat(resolver.resolve(token("RS256", "unknown", rsa, claims("unknown-key")))).isEmpty();
        assertThat(resolver.resolve(token("none", "trusted", rsa, claims("none")))).isEmpty();

        Map<String, Object> wrongIssuer = claims("wrong-issuer");
        wrongIssuer.put("iss", "https://attacker.example");
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, wrongIssuer))).isEmpty();
        Map<String, Object> wrongAudience = claims("wrong-audience");
        wrongAudience.put("aud", "another-service");
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, wrongAudience))).isEmpty();
    }

    @Test
    void rejectsExpiredFutureAndExcessivelyLongLivedTokens() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("trusted", "RS256", rsa)),
                Set.of()));

        Map<String, Object> expired = claims("expired");
        expired.put("iat", NOW.minusSeconds(600).getEpochSecond());
        expired.put("exp", NOW.minusSeconds(60).getEpochSecond());
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, expired))).isEmpty();

        Map<String, Object> future = claims("future");
        future.put("iat", NOW.plusSeconds(120).getEpochSecond());
        future.put("nbf", NOW.plusSeconds(120).getEpochSecond());
        future.put("exp", NOW.plusSeconds(300).getEpochSecond());
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, future))).isEmpty();

        Map<String, Object> longLived = claims("long-lived");
        longLived.put("exp", NOW.plusSeconds(901).getEpochSecond());
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, longLived))).isEmpty();
    }

    @Test
    void rejectsRevokedKeysTokensAndInvalidDelegationChains() {
        IntegrationJwtTrustStore.VerificationKey revokedKey = new IntegrationJwtTrustStore.VerificationKey(
                "revoked", "RS256", rsa.getPublic(), Instant.MIN, Instant.MAX, true, true);
        SignedJwtIntegrationIdentityResolver revokedKeyResolver = resolver(store(List.of(revokedKey), Set.of()));
        assertThat(revokedKeyResolver.resolve(token("RS256", "revoked", rsa, claims("key-token")))).isEmpty();

        SignedJwtIntegrationIdentityResolver revokedTokenResolver = resolver(store(
                List.of(key("trusted", "RS256", rsa)), Set.of("revoked-token")));
        assertThat(revokedTokenResolver.resolve(
                token("RS256", "trusted", rsa, claims("revoked-token")))).isEmpty();

        Map<String, Object> selfDelegated = claims("self-delegated");
        selfDelegated.put("delegated_by", "aneke-sync");
        assertThat(revokedTokenResolver.resolve(token("RS256", "trusted", rsa, selfDelegated))).isEmpty();
    }

    @Test
    void verifiesIssuerAttestedDelegationGrantScopeAndExpiry() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("trusted", "RS256", rsa)),
                Set.of()));
        Map<String, Object> delegated = claims("delegated-token");
        delegated.put("delegated_by", "alice@example.com");
        delegated.put("delegation_grant_id", "grant-2026-001");
        delegated.put("delegation_exp", NOW.plusSeconds(240).getEpochSecond());
        delegated.put("delegation_purposes", List.of("CHANGE_SYNC", "PAYLOAD_REPLAY", "GOVERNANCE_GATE_FEEDBACK"));

        IntegrationWorkloadIdentity identity = resolver.resolve(
                token("RS256", "trusted", rsa, delegated)).orElseThrow();

        assertThat(identity.delegatedBy()).isEqualTo("alice@example.com");
        assertThat(identity.delegationGrantId()).isEqualTo("grant-2026-001");
        assertThat(identity.activeAt(NOW)).isTrue();

        Map<String, Object> missingGrant = claims("missing-grant");
        missingGrant.put("delegated_by", "alice@example.com");
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, missingGrant))).isEmpty();

        Map<String, Object> narrowedGrant = new LinkedHashMap<>(delegated);
        narrowedGrant.put("jti", "narrowed-grant");
        narrowedGrant.put("delegation_purposes", List.of("CHANGE_SYNC"));
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, narrowedGrant))).isEmpty();

        Map<String, Object> expiredGrant = new LinkedHashMap<>(delegated);
        expiredGrant.put("jti", "expired-grant");
        expiredGrant.put("delegation_exp", NOW.minusSeconds(31).getEpochSecond());
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, expiredGrant))).isEmpty();
    }

    @Test
    void reportsIdentityAuthorityOutageSeparatelyFromInvalidCredentials() {
        IntegrationJwtTrustStore unavailable = new IntegrationJwtTrustStore() {
            @Override
            public java.util.Optional<VerificationKey> find(String keyId) {
                throw new IntegrationIdentityProviderUnavailableException("jwks unavailable");
            }

            @Override
            public boolean isTokenRevoked(String tokenId) {
                throw new IntegrationIdentityProviderUnavailableException("revocations unavailable");
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(1, 1, 0, 0);
            }
        };
        SignedJwtIntegrationIdentityResolver resolver = resolver(unavailable);

        assertThat(resolver.resolveAttempt(token("RS256", "trusted", rsa, claims("outage"))).outcome())
                .isEqualTo(IntegrationIdentityResolver.ResolutionOutcome.PROVIDER_UNAVAILABLE);
        assertThat(resolver.resolveAttempt("not-a-jwt").outcome())
                .isEqualTo(IntegrationIdentityResolver.ResolutionOutcome.INVALID);
    }

    @Test
    void configuredTrustStoreParsesPublicKeysAndExplicitRevocationWithoutPrivateMaterial() {
        String keyJson = "[{\"keyId\":\"rsa-a\",\"algorithm\":\"RS256\",\"publicKeyBase64\":\""
                + Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()) + "\"},"
                + "{\"keyId\":\"ed-b\",\"algorithm\":\"EdDSA\",\"publicKeyBase64\":\""
                + Base64.getEncoder().encodeToString(ed25519.getPublic().getEncoded()) + "\"}]";

        ConfiguredIntegrationJwtTrustStore trustStore = ConfiguredIntegrationJwtTrustStore.fromJson(
                JSON, keyJson, Set.of("rsa-a"), Set.of("token-r"));

        assertThat(trustStore.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::trustedKeyCount,
                        IntegrationJwtTrustStore.Snapshot::activeKeyCount,
                        IntegrationJwtTrustStore.Snapshot::revokedKeyCount,
                        IntegrationJwtTrustStore.Snapshot::revokedTokenCount)
                .containsExactly(2, 1, 1, 1);
        assertThat(trustStore.isTokenRevoked("token-r")).isTrue();
        assertThat(trustStore.find("rsa-a").orElseThrow().publicKey().getEncoded())
                .containsExactly(rsa.getPublic().getEncoded());
    }

    @Test
    void rejectsWeakKeysDuplicateJsonAndIncompleteScopeClaims() throws Exception {
        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        KeyPair weak = weakGenerator.generateKeyPair();
        assertThatThrownBy(() -> key("weak", "RS256", weak))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048-8192 bits");

        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("trusted", "RS256", rsa)),
                Set.of()));
        String duplicateHeader = encode("{\"alg\":\"RS256\",\"alg\":\"RS256\",\"kid\":\"trusted\"}"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(resolver.resolve(duplicateHeader + "." + encode(JSON.writeValueAsBytes(claims("duplicate")))
                + ".invalid")).isEmpty();

        Map<String, Object> missingProject = claims("missing-project");
        missingProject.remove("project_id");
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, missingProject))).isEmpty();
        Map<String, Object> invalidPurposes = claims("invalid-purpose");
        invalidPurposes.put("purposes", List.of("CHANGE_SYNC", "../../admin"));
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, invalidPurposes))).isEmpty();
    }

    @Test
    void rejectsAmbiguousHeadersAudienceAndTimeWindows() {
        SignedJwtIntegrationIdentityResolver resolver = resolver(store(List.of(key("trusted", "RS256", rsa)),
                Set.of()));

        Map<String, Object> mixedAudience = claims("mixed-audience");
        mixedAudience.put("aud", List.of("resource-gateway", 7));
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, mixedAudience))).isEmpty();

        Map<String, Object> invalidWindow = claims("invalid-window");
        invalidWindow.put("nbf", NOW.plusSeconds(20).getEpochSecond());
        invalidWindow.put("exp", NOW.plusSeconds(10).getEpochSecond());
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, invalidWindow))).isEmpty();

        Map<String, Object> numericDelegation = claims("numeric-delegation");
        numericDelegation.put("delegated_by", 42);
        assertThat(resolver.resolve(token("RS256", "trusted", rsa, numericDelegation))).isEmpty();

        assertThat(resolver.resolve(tokenWithHeader(
                Map.of("alg", "RS256", "kid", "trusted", "typ", "JWT", "crit", "exp"),
                rsa, claims("bad-crit")))).isEmpty();
        assertThat(resolver.resolve(tokenWithHeader(
                Map.of("alg", "RS256", "kid", "trusted", "typ", "JWT", "b64", "true"),
                rsa, claims("bad-b64")))).isEmpty();
    }

    private static SignedJwtIntegrationIdentityResolver resolver(IntegrationJwtTrustStore trustStore) {
        return new SignedJwtIntegrationIdentityResolver(JSON, "https://iam.example/", "resource-gateway",
                trustStore, Duration.ofSeconds(30), Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConfiguredIntegrationJwtTrustStore store(List<IntegrationJwtTrustStore.VerificationKey> keys,
                                                             Set<String> revokedTokenIds) {
        return new ConfiguredIntegrationJwtTrustStore(keys, revokedTokenIds);
    }

    private static IntegrationJwtTrustStore.VerificationKey key(String keyId, String algorithm, KeyPair pair) {
        return new IntegrationJwtTrustStore.VerificationKey(keyId, algorithm, pair.getPublic(), Instant.MIN,
                Instant.MAX, true, false);
    }

    private static Map<String, Object> claims(String tokenId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://iam.example/");
        claims.put("aud", List.of("resource-gateway", "tool-studio"));
        claims.put("sub", "aneke-workload");
        claims.put("jti", tokenId);
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(5).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("tenant_id", "tenant-a");
        claims.put("organization_id", "knowledge-governance");
        claims.put("project_id", "tool-studio");
        claims.put("environment_id", "prod");
        claims.put("region", "ap-southeast-1");
        claims.put("actor_type", "WORKLOAD");
        claims.put("actor_id", "aneke-sync");
        claims.put("groups", List.of("knowledge-owners", "tool-authors"));
        claims.put("clearance", "CONFIDENTIAL");
        claims.put("purposes", List.of("CHANGE_SYNC", "PAYLOAD_REPLAY"));
        return claims;
    }

    private static String token(String algorithm, String keyId, KeyPair pair, Map<String, Object> claims) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", algorithm);
            header.put("kid", keyId);
            header.put("typ", "JWT");
            String encodedHeader = encode(JSON.writeValueAsBytes(header));
            String encodedClaims = encode(JSON.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            if (algorithm.equals("none")) {
                return signingInput + ".unsigned";
            }
            Signature signer = Signature.getInstance(algorithm.equals("RS256") ? "SHA256withRSA" : "Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signer.sign());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String tokenWithHeader(Map<String, Object> header,
                                          KeyPair pair,
                                          Map<String, Object> claims) {
        try {
            String encodedHeader = encode(JSON.writeValueAsBytes(header));
            String encodedClaims = encode(JSON.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(pair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signer.sign());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String tamperSignature(String token) {
        String[] segments = token.split("\\.", -1);
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        signature[0] ^= 0x01;
        return segments[0] + "." + segments[1] + "." + encode(signature);
    }
}
