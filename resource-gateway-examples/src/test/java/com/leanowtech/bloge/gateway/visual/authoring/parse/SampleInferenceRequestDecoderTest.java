package com.leanowtech.bloge.gateway.visual.authoring.parse;

import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleSchemaInferencer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInferenceRequestDecoderTest {

    private final SampleInferenceRequestDecoder decoder = new SampleInferenceRequestDecoder();

    @Test
    void decodesStrictInferenceJson() {
        var result = decoder.decode("""
                {
                  "schemaVersion": "bloge.visualSampleInferenceRequest.v1",
                  "target": {
                    "assetKind": "OPERATOR",
                    "assetRef": "support:classify",
                    "portDirection": "INPUT",
                    "portName": "ticket"
                  },
                  "samples": [{"id": "t-1"}],
                  "options": {
                    "suggestEnums": true,
                    "suggestFormats": true,
                    "persistPayload": false
                  },
                  "idempotencyKey": "request-1"
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isTrue();
        assertThat(result.request().target().authoringPath())
                .isEqualTo("/operators/support:classify/input/ticket");
    }

    @Test
    void rejectsDuplicateUnknownAndTrailingContent() {
        assertParseFailure("""
                {"schemaVersion":"bloge.visualSampleInferenceRequest.v1",
                 "schemaVersion":"bloge.visualSampleInferenceRequest.v1"}
                """);
        assertParseFailure("""
                {"schemaVersion":"bloge.visualSampleInferenceRequest.v1",
                 "unexpected":true}
                """);
        assertParseFailure("""
                {"schemaVersion":"bloge.visualSampleInferenceRequest.v1"} {}
                """);
    }

    @Test
    void preservesMissingSchemaVersionForProtocolValidation() {
        var result = decoder.decode("""
                {
                  "target": {
                    "assetKind": "OPERATOR",
                    "assetRef": "support:classify",
                    "portDirection": "INPUT",
                    "portName": "ticket"
                  },
                  "samples": [{"id": "t-1"}],
                  "options": {
                    "suggestEnums": true,
                    "suggestFormats": true,
                    "persistPayload": false
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.successful()).isTrue();
        assertThat(result.request().schemaVersion()).isEmpty();
    }

    @Test
    void rejectsOversizedSourceBeforeBinding() {
        byte[] source = new byte[SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES + 1];
        java.util.Arrays.fill(source, (byte) 'x');

        var result = decoder.decode(source);

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().status()).isEqualTo(413);
        assertThat(result.failure().code())
                .isEqualTo("RG.AUTHORING.INFERENCE_REQUEST_LIMIT_EXCEEDED");
    }

    private void assertParseFailure(String source) {
        var result = decoder.decode(source.getBytes(StandardCharsets.UTF_8));
        assertThat(result.successful()).isFalse();
        assertThat(result.failure().code())
                .isEqualTo("RG.AUTHORING.INFERENCE_PARSE_FAILED");
    }
}
