package com.leanowtech.bloge.gateway.testing.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestControlHeaderCodecTest {
    private static final String FINGERPRINT = "sha256:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final TestControlHeaderCodec codec = new TestControlHeaderCodec();

    @Test
    void parsesScenarioEnvelopeAndPreservesControlPlan() {
        TestControlHeaders result = codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"contract-test\",\"scenario\":{\"id\":\"scenario-1\",\"revision\":2,\"fingerprint\":\""
                        + FINGERPRINT + "\"},\"correlationId\":\"corr-1\"}")));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.hasControlPlan()).isTrue();
        assertThat(result.envelope().purpose()).isEqualTo("contract-test");
        assertThat(result.envelope().scenario().id()).isEqualTo("scenario-1");
        assertThat(result.envelope().scenario().revision()).isEqualTo(2);
        assertThat(result.envelope().worldModel()).isNull();
        assertThat(result.envelope().correlationId()).isEqualTo("corr-1");
    }

    @Test
    void parsesWorldModelEnvelope() {
        TestControlHeaders result = codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"simulation\",\"worldModel\":{\"id\":\"world-1\",\"revision\":7,\"fingerprint\":\""
                        + FINGERPRINT + "\"},\"correlationId\":\"corr-2\"}")));

        assertThat(result.envelope().worldModel().id()).isEqualTo("world-1");
        assertThat(result.envelope().referencesScenario()).isFalse();
        assertThat(result.envelope().assetReference().fingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void ignoresNonControlHeadersAndDistinguishesNoPlan() {
        TestControlHeaders result = codec.parse(Map.of(
                "Content-Type", List.of("application/json"),
                "X-Request-Id", List.of("request-1")));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.hasControlPlan()).isFalse();
        assertThat(result).isEqualTo(TestControlHeaders.empty());
    }

    @Test
    void headerNamesAreCaseInsensitive() {
        TestControlHeaders result = codec.parse(headers(
                "x-bloge-test-fidelity", "transport",
                "X-BLOGE-TEST-SCOPE", "graph"));

        assertThat(result.fidelityToken()).isEqualTo("transport");
        assertThat(result.scopeToken()).isEqualTo("graph");
    }

    @Test
    void rejectsRepeatedHeaderValuesAndCaseVariants() {
        assertReason(TestControlProtocolReason.DUPLICATE_HEADER, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, List.of("one", "two"))));

        Map<String, List<String>> caseVariants = new LinkedHashMap<>();
        caseVariants.put(TestControlHeaderCodec.ENVELOPE_HEADER, List.of("one"));
        caseVariants.put("x-bloge-test-envelope", List.of("two"));
        assertReason(TestControlProtocolReason.DUPLICATE_HEADER, () -> codec.parse(caseVariants));
    }

    @Test
    void rejectsPaddingIllegalCharactersAndNonCanonicalBase64url() {
        assertReason(TestControlProtocolReason.INVALID_BASE64URL, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, "eA==")));
        assertReason(TestControlProtocolReason.INVALID_BASE64URL, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, "eA+")));
        assertReason(TestControlProtocolReason.NON_CANONICAL_BASE64URL, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, "AB")));
    }

    @Test
    void rejectsEmptyNullAndNonAsciiControlValues() {
        assertReason(TestControlProtocolReason.EMPTY_HEADER, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, "")));
        Map<String, List<String>> nullValue = new LinkedHashMap<>();
        nullValue.put(TestControlHeaderCodec.INLINE_HEADER, Collections.singletonList(null));
        assertReason(TestControlProtocolReason.NULL_HEADER_VALUE, () -> codec.parse(nullValue));
        assertReason(TestControlProtocolReason.HEADER_NOT_ASCII, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, "é")));
    }

    @Test
    void rejectsInvalidUtf8() {
        String invalidUtf8 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{(byte) 0xc3, 0x28});

        assertReason(TestControlProtocolReason.INVALID_UTF8, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, invalidUtf8)));
    }

    @Test
    void rejectsDuplicateAndUnknownEnvelopeFields() {
        assertReason(TestControlProtocolReason.JSON_DUPLICATE_FIELD, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"a\",\"purpose\":\"b\",\"scenario\":{\"id\":\"s\",\"revision\":1,\"fingerprint\":\""
                        + FINGERPRINT + "\"},\"correlationId\":\"c\"}"))));
        assertReason(TestControlProtocolReason.JSON_UNKNOWN_FIELD, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"a\",\"scenario\":{\"id\":\"s\",\"revision\":1,\"fingerprint\":\""
                        + FINGERPRINT + "\"},\"correlationId\":\"c\",\"extra\":true}"))));
        assertReason(TestControlProtocolReason.JSON_UNKNOWN_FIELD, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"a\",\"scenario\":{\"id\":\"s\",\"revision\":1,\"fingerprint\":\""
                        + FINGERPRINT + "\",\"extra\":true},\"correlationId\":\"c\"}"))));
    }

    @Test
    void requiresExactlyOneAssetReference() {
        String reference = "{\"id\":\"s\",\"revision\":1,\"fingerprint\":\"" + FINGERPRINT + "\"}";
        String common = "\"purpose\":\"a\",\"correlationId\":\"c\"";
        assertReason(TestControlProtocolReason.ASSET_REFERENCE_CARDINALITY, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, encode("{" + common + "}"))));
        assertReason(TestControlProtocolReason.ASSET_REFERENCE_CARDINALITY, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{" + common + ",\"scenario\":" + reference + ",\"worldModel\":" + reference + "}"))));
    }

    @Test
    void validatesRevisionAndFingerprint() {
        assertReason(TestControlProtocolReason.INVALID_REVISION, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                envelopeWithReference("{\"id\":\"s\",\"revision\":0,\"fingerprint\":\"" + FINGERPRINT + "\"}"))));
        assertReason(TestControlProtocolReason.INVALID_FINGERPRINT, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                envelopeWithReference("{\"id\":\"s\",\"revision\":1,\"fingerprint\":\"sha256:ABC\"}"))));
    }

    @Test
    void rejectsEncodedAndDecodedOversizeValues() {
        assertReason(TestControlProtocolReason.ENCODED_VALUE_TOO_LARGE, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER,
                "A".repeat(TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES + 1))));

        String oversizedEnvelope = "{\"purpose\":\"" + "p".repeat(2_048)
                + "\",\"scenario\":{\"id\":\"" + "i".repeat(2_048)
                + "\",\"revision\":1,\"fingerprint\":\"" + FINGERPRINT
                + "\"},\"correlationId\":\"c\"}";
        assertReason(TestControlProtocolReason.DECODED_VALUE_TOO_LARGE, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER, encode(oversizedEnvelope))));
    }

    @Test
    void rejectsDepthContainerAndStringLimits() {
        String nested = "{\"v\":".repeat(TestControlProtocolLimits.MAX_JSON_DEPTH + 1)
                + "null" + "}".repeat(TestControlProtocolLimits.MAX_JSON_DEPTH + 1);
        assertReason(TestControlProtocolReason.JSON_DEPTH_EXCEEDED, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode(nested))));

        String members = "{" + IntStream.range(0, TestControlProtocolLimits.MAX_CONTAINER_ENTRIES + 1)
                .mapToObj(index -> "\"k" + index + "\":true")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow() + "}";
        assertReason(TestControlProtocolReason.JSON_CONTAINER_TOO_LARGE, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode(members))));

        String arrayElements = "[" + IntStream.range(0, TestControlProtocolLimits.MAX_CONTAINER_ENTRIES + 1)
                .mapToObj(Integer::toString)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow() + "]";
        assertReason(TestControlProtocolReason.JSON_CONTAINER_TOO_LARGE, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode(arrayElements))));

        String longString = "{\"value\":\"" + "x".repeat(TestControlProtocolLimits.MAX_STRING_CHARS + 1) + "\"}";
        assertReason(TestControlProtocolReason.JSON_STRING_TOO_LONG, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode(longString))));
    }

    @Test
    void rejectsMalformedJsonAndNonObjectInline() {
        assertReason(TestControlProtocolReason.MALFORMED_JSON, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode("{\"broken\":"))));
        assertReason(TestControlProtocolReason.INLINE_NOT_OBJECT, () -> codec.parse(headers(
                TestControlHeaderCodec.INLINE_HEADER, encode("[true]"))));
    }

    @Test
    void parsesBoundedTokensWithoutEmbeddingGovernanceEnums() {
        TestControlHeaders result = codec.parse(headers(
                TestControlHeaderCodec.FIDELITY_HEADER, "descriptor.transport-v1",
                TestControlHeaderCodec.SCOPE_HEADER, "node-1"));

        assertThat(result.fidelity()).contains("descriptor.transport-v1");
        assertThat(result.scope()).contains("node-1");
        assertReason(TestControlProtocolReason.INVALID_TOKEN, () -> codec.parse(headers(
                TestControlHeaderCodec.FIDELITY_HEADER, "not a token")));
    }

    @Test
    void doesNotLeakRawHeaderOrInlineValuesInErrors() {
        String secret = "top-secret-business-payload";
        String raw = encode("{\"purpose\":\"" + secret + "\"}");

        assertThatThrownBy(() -> codec.parse(headers(TestControlHeaderCodec.ENVELOPE_HEADER, raw)))
                .isInstanceOf(TestControlProtocolException.class)
                .hasMessageNotContaining(secret)
                .hasMessageNotContaining(raw);
    }

    @Test
    void parsedValueDoesNotObserveLaterMapMutationOrExposeMutableInlineTree() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(TestControlHeaderCodec.INLINE_HEADER, List.of(encode("{\"fixture\":{\"value\":1}}")));
        TestControlHeaders result = codec.parse(headers);
        headers.put(TestControlHeaderCodec.INLINE_HEADER, List.of(encode("{\"fixture\":{\"value\":2}}")));

        JsonNode copy = result.inline().payload();
        ((ObjectNode) copy.get("fixture")).put("value", 99);

        assertThat(result.inline().payload().at("/fixture/value").asInt()).isEqualTo(1);
        assertThat(result.inline().canonicalJson()).contains("\"value\":1");
    }

    @Test
    void canonicalizesNestedObjectKeysButPreservesArrayOrder() {
        TestInlineControl first = inline("{\"z\":{\"b\":2,\"a\":[{\"d\":4,\"c\":3},{\"e\":5}]},\"a\":1}");
        TestInlineControl sameMeaning = inline("{\"a\":1,\"z\":{\"a\":[{\"c\":3,\"d\":4},{\"e\":5}],\"b\":2}}");
        TestInlineControl differentArrayOrder = inline("{\"a\":1,\"z\":{\"a\":[{\"e\":5},{\"d\":4,\"c\":3}],\"b\":2}}");

        assertThat(first.canonicalJson()).isEqualTo(
                "{\"a\":1,\"z\":{\"a\":[{\"c\":3,\"d\":4},{\"e\":5}],\"b\":2}}");
        assertThat(first.canonicalJson()).isEqualTo(sameMeaning.canonicalJson());
        assertThat(first).isEqualTo(sameMeaning);
        assertThat(first.hashCode()).isEqualTo(sameMeaning.hashCode());
        assertThat(first).isNotEqualTo(differentArrayOrder);
        assertThat(first.canonicalJson()).isNotEqualTo(differentArrayOrder.canonicalJson());
    }

    @Test
    void rejectsMissingRequiredEnvelopeFieldsAndWrongTypes() {
        assertReason(TestControlProtocolReason.MISSING_REQUIRED_FIELD, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":\"a\",\"scenario\":{\"id\":\"s\",\"revision\":1,\"fingerprint\":\""
                        + FINGERPRINT + "\"}}"))));
        assertReason(TestControlProtocolReason.INVALID_FIELD_TYPE, () -> codec.parse(headers(
                TestControlHeaderCodec.ENVELOPE_HEADER,
                encode("{\"purpose\":1,\"scenario\":{\"id\":\"s\",\"revision\":1,\"fingerprint\":\""
                        + FINGERPRINT + "\"},\"correlationId\":\"c\"}"))));
    }

    private static String envelopeWithReference(String reference) {
        return encode("{\"purpose\":\"a\",\"scenario\":" + reference + ",\"correlationId\":\"c\"}");
    }

    private TestInlineControl inline(String json) {
        return codec.parse(headers(TestControlHeaderCodec.INLINE_HEADER, encode(json))).inline();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, List<String>> headers(String... values) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], List.of(values[index + 1]));
        }
        return result;
    }

    private static Map<String, List<String>> headers(String name, List<String> values) {
        return Map.of(name, values);
    }

    private static void assertReason(
            TestControlProtocolReason reason,
            ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(TestControlProtocolException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reason));
    }
}
