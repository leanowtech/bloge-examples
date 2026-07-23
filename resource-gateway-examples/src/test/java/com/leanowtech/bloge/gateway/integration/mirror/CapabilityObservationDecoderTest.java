package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.CapabilityObservationDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityObservationDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityObservationDecoder decoder =
            new CapabilityObservationDecoder(mapper);
    private final CapabilityObservationEnvelope envelope = envelope();

    @Test
    void decodesExactClosedObservation() throws Exception {
        assertThat(decoder.decode(
                mapper.writeValueAsBytes(envelope),
                CapabilityObservationTestFixtures.identity("org-a")))
                .isEqualTo(envelope);
    }

    @Test
    void rejectsDuplicateRootUnknownNestedAndTrailingTokens() throws Exception {
        String json = mapper.writeValueAsString(envelope);
        assertMalformed(() -> decoder.decode(
                json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",")
                        .getBytes(StandardCharsets.UTF_8),
                CapabilityObservationTestFixtures.identity("org-a")));

        ObjectNode unknown = mapper.valueToTree(envelope);
        ((ObjectNode) unknown.path("material")).put("rawPayload", "secret");
        assertMalformed(() -> decoder.decode(
                bytes(unknown),
                CapabilityObservationTestFixtures.identity("org-a")));

        assertMalformed(() -> decoder.decode(
                (json + "{}").getBytes(StandardCharsets.UTF_8),
                CapabilityObservationTestFixtures.identity("org-a")));
    }

    @Test
    void rejectsWrongVersionOversizedAndExcessivelyDeepBodies() throws Exception {
        ObjectNode wrongVersion = mapper.valueToTree(envelope);
        wrongVersion.put("schemaVersion", "resourceGateway.capabilityObservation.v2");
        assertMalformed(() -> decoder.decode(
                bytes(wrongVersion),
                CapabilityObservationTestFixtures.identity("org-a")));

        assertMalformed(() -> decoder.decode(
                new byte[CapabilityObservationDecoder.MAXIMUM_BYTES + 1],
                CapabilityObservationTestFixtures.identity("org-a")));

        ObjectNode deep = mapper.valueToTree(envelope);
        ObjectNode cursor = mapper.createObjectNode();
        deep.set("unexpected", cursor);
        for (int index = 0;
             index < CapabilityObservationDecoder.MAXIMUM_DEPTH + 2;
             index++) {
            ObjectNode next = mapper.createObjectNode();
            cursor.set("next", next);
            cursor = next;
        }
        assertMalformed(() -> decoder.decode(
                bytes(deep),
                CapabilityObservationTestFixtures.identity("org-a")));
    }

    private CapabilityObservationEnvelope envelope() {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        return CapabilityObservationTestFixtures.envelope(
                mapper,
                new InMemoryVisualEvidenceSigner(),
                capability,
                "observation-decoder");
    }

    private byte[] bytes(ObjectNode value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertMalformed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.OBSERVATION_REQUEST_MALFORMED");
                    assertThat(failure.problem().details())
                            .containsEntry(
                                    "maximumBytes",
                                    CapabilityObservationDecoder.MAXIMUM_BYTES)
                            .containsEntry(
                                    "maximumDepth",
                                    CapabilityObservationDecoder.MAXIMUM_DEPTH)
                            .containsEntry(
                                    "maximumNodes",
                                    CapabilityObservationDecoder.MAXIMUM_NODES);
                });
    }
}
