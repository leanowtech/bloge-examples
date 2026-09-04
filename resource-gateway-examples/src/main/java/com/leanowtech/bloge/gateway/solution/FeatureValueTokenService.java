package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/** Issues and verifies payload-free, scope-bound HMAC tokens for evaluated Feature values. */
public final class FeatureValueTokenService {
    private static final int MAX_CANONICAL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TOKEN_BYTES = 16 * 1024;
    private static final long TTL_SECONDS = 300;
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final ObjectMapper mapper;
    private final FeatureTokenKeyProvider keys;
    private final Clock clock;
    private final SecureRandom random;

    /** Creates a production token authority with injectable clock and random sources. */
    public FeatureValueTokenService(
            ObjectMapper mapper, FeatureTokenKeyProvider keys, Clock clock, SecureRandom random) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Signs fingerprints of inputs and value; raw business values never enter the token. */
    public String issue(String featureRef, JsonNode inputs, JsonNode value, String scope) {
        FeatureTokenKeyProvider.SigningKey key = keys.active();
        ObjectNode header = mapper.createObjectNode().put("alg", "HS256").put("kid", key.keyId());
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        ObjectNode payload = mapper.createObjectNode()
                .put("featureRef", required(featureRef))
                .put("inputsFp", fingerprint(inputs))
                .put("valueFp", fingerprint(value))
                .put("scope", required(scope))
                .put("iat", clock.instant().getEpochSecond())
                .put("ttl", TTL_SECONDS)
                .put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        try {
            String signingInput = encode(mapper.writeValueAsBytes(header)) + "."
                    + encode(mapper.writeValueAsBytes(payload));
            return signingInput + "." + encode(mac(key.secret(), signingInput));
        } catch (Exception failure) {
            throw invalid();
        }
    }

    /** Verifies signature, freshness, and all semantic bindings without exposing the failed field. */
    public VerifiedToken verify(
            String token, String featureRef, JsonNode inputs, JsonNode value, String scope) {
        try {
            if (token == null || token.length() > MAX_TOKEN_BYTES) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw invalid();
            JsonNode header = mapper.readTree(decode(parts[0]));
            JsonNode payload = mapper.readTree(decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())) throw invalid();
            byte[] secret = keys.verifySecret(header.path("kid").asText())
                    .orElseThrow(FeatureValueTokenService::invalid);
            byte[] expected = mac(secret, parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, decode(parts[2]))) throw invalid();
            long now = clock.instant().getEpochSecond();
            long issuedAt = payload.path("iat").asLong(Long.MIN_VALUE);
            long ttl = payload.path("ttl").asLong(-1);
            if (ttl <= 0 || ttl > TTL_SECONDS || issuedAt > now + CLOCK_SKEW_SECONDS
                    || now > Math.addExact(issuedAt, ttl + CLOCK_SKEW_SECONDS)) throw invalid();
            if (!required(featureRef).equals(payload.path("featureRef").asText())
                    || !fingerprint(inputs).equals(payload.path("inputsFp").asText())
                    || !fingerprint(value).equals(payload.path("valueFp").asText())
                    || !required(scope).equals(payload.path("scope").asText())
                    || payload.path("nonce").asText().isBlank()) throw invalid();
            return new VerifiedToken(payload.path("nonce").asText(), issuedAt, ttl);
        } catch (SolutionContractException expected) {
            throw expected;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private String fingerprint(JsonNode value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper, value == null ? mapper.nullNode() : value, MAX_CANONICAL_BYTES);
    }

    private static byte[] mac(byte[] secret, String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static String required(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw invalid();
        return normalized;
    }

    private static SolutionContractException invalid() {
        return new SolutionContractException(
                "FEATURE_TOKEN_INVALID", "The Feature evaluation token is invalid.");
    }

    /** Minimal verified coordinate used later as a replay key, never a business payload. */
    public record VerifiedToken(String nonce, long issuedAt, long ttl) { }
}
