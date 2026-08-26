package com.leanowtech.bloge.gateway.testing.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestControlEnvelopeCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyAndOptionalFunctionReferencesUseTheSameEnvelopeProtocol() throws Exception {
        JsonNode fixture = MAPPER.readTree(getClass().getResourceAsStream(
                "/protocol/test-control-envelope-v1-compat.json"));
        TestControlHeaderCodec codec = new TestControlHeaderCodec();

        TestControlHeaders legacy = parse(codec, fixture.get("legacy"));
        TestControlHeaders withFunction = parse(codec, fixture.get("withFunctionControl"));

        assertThat(legacy.envelope().functionControl()).isNull();
        assertThat(legacy.envelope().worldModel()).isNotNull();
        assertThat(withFunction.envelope().functionControl().id()).isEqualTo("function-control-a");
        assertThat(withFunction.envelope().functionControl().revision()).isEqualTo(2);
        assertThat(withFunction.envelope().functionControl().fingerprint()).startsWith("sha256:");
    }

    @Test
    void envelopeSchemaIsExactReferenceOnlyAndRejectsInlinePlanShape() throws Exception {
        JsonNode schema = MAPPER.readTree(getClass().getResourceAsStream(
                "/protocol/test-control-envelope-v1.schema.json"));
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").has("functionControl")).isTrue();
        assertThat(schema.at("/$defs/exactReference/additionalProperties").asBoolean()).isFalse();
        List<String> referenceFields = new ArrayList<>();
        schema.at("/$defs/exactReference/properties").fieldNames().forEachRemaining(referenceFields::add);
        assertThat(referenceFields).containsExactlyInAnyOrder("id", "revision", "fingerprint");
        assertThat(schema.at("/properties/purpose/minLength").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/purpose/maxLength").asInt())
                .isEqualTo(TestControlProtocolLimits.MAX_STRING_CHARS);
        assertThat(schema.at("/properties/correlationId/maxLength").asInt())
                .isEqualTo(TestControlProtocolLimits.MAX_STRING_CHARS);
        assertThat(schema.at("/$defs/exactReference/properties/id/maxLength").asInt())
                .isEqualTo(TestControlProtocolLimits.MAX_STRING_CHARS);
        assertThatThrownBy(() -> parse(new TestControlHeaderCodec(), MAPPER.readTree(
                "{\"purpose\":\"GRAPH_CONTRACT_TEST\",\"worldModel\":{\"id\":\"w\","
                        + "\"revision\":1,\"fingerprint\":\"sha256:"
                        + "a".repeat(64) + "\"},\"functionControl\":{\"rules\":[]},"
                        + "\"correlationId\":\"c\"}")))
                .isInstanceOf(TestControlProtocolException.class);
    }

    @Test
    void capabilityLimitsComeFromTheSameAuthoritativeFunctionDescriptor() {
        Map<String, Integer> limits = IntegrationCapabilities.current().limits();
        assertThat(limits).isEqualTo(
                FunctionControlLimits.CURRENT.capabilityMap(
                        TestControlProtocolLimits.MAX_DECODED_ENVELOPE_BYTES));
        assertThatThrownBy(() -> limits.put("functionRules", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TestControlHeaders parse(TestControlHeaderCodec codec, JsonNode envelope) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                envelope.toString().getBytes(StandardCharsets.UTF_8));
        return codec.parse(Map.of(TestControlHeaderCodec.ENVELOPE_HEADER, java.util.List.of(encoded)));
    }
}
