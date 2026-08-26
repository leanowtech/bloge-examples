package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestControlHeaderCodecTest {
    private static final String FP = "sha256:" + "a".repeat(64);

    @Test
    void roundTripsLegacyPrimaryEnvelopeAndOptionalFunctionReference() {
        TestControlEnvelope input = new TestControlEnvelope("TEST_EXECUTION",
                new TestControlAssetReference("scenario-1", 3, FP), null, "corr-1",
                new TestControlAssetReference("function-1", 7, FP));

        TestControlEnvelope decoded = TestControlHeaderCodec.decode(
                TestControlHeaderCodec.encode(input));

        assertThat(decoded).isEqualTo(input);
    }

    @Test
    void rejectsUnknownDuplicateAndInlineFields() {
        assertThatThrownBy(() -> TestControlHeaderCodec.decode(encoded(
                "{\"purpose\":\"TEST_EXECUTION\",\"scenario\":{},\"correlationId\":\"c\",\"inline\":{}}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestControlHeaderCodec.decode(encoded(
                "{\"purpose\":\"TEST_EXECUTION\",\"purpose\":\"x\",\"scenario\":{},\"correlationId\":\"c\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongReferenceAndOversizedHeader() {
        assertThatThrownBy(() -> new TestControlAssetReference("x", 1, "sha256:bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestControlHeaderCodec.decode("A".repeat(
                TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedUtf8EvenWhenByteShapeIsJson() {
        byte[] prefix = ("{\"purpose\":\"TEST_EXECUTION\",\"scenario\":{"
                + "\"id\":\"scenario-1\",\"revision\":1,\"fingerprint\":\"" + FP
                + "\"},\"correlationId\":\"c").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xc3;
        malformed[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, malformed, prefix.length + 2, suffix.length);

        assertThatThrownBy(() -> TestControlHeaderCodec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(malformed)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String encoded(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
