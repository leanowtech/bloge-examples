package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Feature token tamper resistance, expiry, scope binding, and key rotation. */
class FeatureValueTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] oldSecret = secret(1);
    private final byte[] currentSecret = secret(2);

    @Test
    void acceptsExactBindingAndRotationWindowButRejectsEveryMutation() {
        FeatureValueTokenService oldIssuer = service("old", Map.of("old", oldSecret), NOW);
        String token = oldIssuer.issue("responsibility.party", object("orderId", "O-1"),
                mapper.valueToTree("none"), SCOPE);
        FeatureValueTokenService rotatingVerifier = service(
                "current", Map.of("old", oldSecret, "current", currentSecret), NOW);

        assertThat(rotatingVerifier.verify(token, "responsibility.party", object("orderId", "O-1"),
                mapper.valueToTree("none"), SCOPE).nonce()).isNotBlank();
        assertInvalid(() -> rotatingVerifier.verify(token, "responsibility.party",
                object("orderId", "O-2"), mapper.valueToTree("none"), SCOPE));
        assertInvalid(() -> rotatingVerifier.verify(token, "responsibility.party",
                object("orderId", "O-1"), mapper.valueToTree("driver"), SCOPE));
        assertInvalid(() -> rotatingVerifier.verify(token, "cancel.withinFree",
                object("orderId", "O-1"), mapper.valueToTree("none"), SCOPE));
        assertInvalid(() -> rotatingVerifier.verify(token, "responsibility.party",
                object("orderId", "O-1"), mapper.valueToTree("none"), SCOPE + "-other"));
        assertInvalid(() -> rotatingVerifier.verify(token + "x", "responsibility.party",
                object("orderId", "O-1"), mapper.valueToTree("none"), SCOPE));
    }

    @Test
    void rejectsExpiredAndUnknownKeyTokensWithoutExplainingWhichCheckFailed() {
        String token = service("old", Map.of("old", oldSecret), NOW).issue(
                "responsibility.party", object("orderId", "O-1"), mapper.valueToTree("none"), SCOPE);

        assertInvalid(() -> service("current", Map.of("current", currentSecret), NOW).verify(
                token, "responsibility.party", object("orderId", "O-1"),
                mapper.valueToTree("none"), SCOPE));
        assertInvalid(() -> service("old", Map.of("old", oldSecret), NOW.plusSeconds(331)).verify(
                token, "responsibility.party", object("orderId", "O-1"),
                mapper.valueToTree("none"), SCOPE));
    }

    @Test
    void parsesRotationConfigurationAndRejectsPartialOrShortSecrets() {
        String ring = "old=" + java.util.Base64.getEncoder().encodeToString(oldSecret)
                + ",current=" + java.util.Base64.getEncoder().encodeToString(currentSecret);

        InMemoryFeatureTokenKeyProvider provider =
                InMemoryFeatureTokenKeyProvider.fromConfiguration("current", ring);

        assertThat(provider.active().keyId()).isEqualTo("current");
        assertThat(provider.verifySecret("old")).isPresent();
        assertThatThrownBy(() -> InMemoryFeatureTokenKeyProvider.fromConfiguration("", ring))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InMemoryFeatureTokenKeyProvider.fromConfiguration(
                "weak", "weak=" + java.util.Base64.getEncoder().encodeToString(new byte[8])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FeatureValueTokenService service(String active, Map<String, byte[]> keys, Instant time) {
        return new FeatureValueTokenService(mapper,
                new InMemoryFeatureTokenKeyProvider(active, keys),
                Clock.fixed(time, ZoneOffset.UTC), new SecureRandom());
    }

    private JsonNode object(String key, String value) {
        return mapper.valueToTree(Map.of(key, value));
    }

    private static byte[] secret(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return value;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("FEATURE_TOKEN_INVALID");
    }
}
