package com.leanowtech.bloge.gateway.visual.authoring.parse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleSchemaInferencer;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;

import java.io.IOException;
import java.util.Locale;

/**
 * Strict JSON-only decoder that rejects oversized sample payloads before object binding.
 */
public final class SampleInferenceRequestDecoder {

    private static final int MAXIMUM_TOKENS = 100_000;

    private final ObjectMapper mapper;

    public SampleInferenceRequestDecoder() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES)
                .maxNestingDepth(SampleSchemaInferencer.MAXIMUM_DEPTH + 8)
                .maxTokenCount(MAXIMUM_TOKENS)
                .maxNameLength(SampleSchemaInferencer.MAXIMUM_FIELD_NAME_LENGTH)
                .maxStringLength(SampleSchemaInferencer.MAXIMUM_STRING_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = new ObjectMapper(factory)
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public DecodeResult decode(byte[] source) {
        if (source == null || source.length == 0) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.INFERENCE_REQUEST_REQUIRED",
                    "Sample inference request is required.",
                    400,
                    "/"
            ));
        }
        if (source.length > SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES) {
            return DecodeResult.failed(limitFailure(
                    "Sample inference request exceeds the %d byte limit."
                            .formatted(SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES)));
        }
        try {
            return DecodeResult.decoded(mapper.readValue(source, SampleInferenceRequest.class));
        } catch (JsonProcessingException exception) {
            if (resourceLimitFailure(exception)) {
                return DecodeResult.failed(limitFailure(safeReason(exception)));
            }
            JsonLocation location = exception.getLocation();
            String suffix = location == null ? "" : " at line %d, column %d"
                    .formatted(location.getLineNr(), location.getColumnNr());
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.INFERENCE_PARSE_FAILED",
                    "Sample inference request could not be parsed" + suffix + ".",
                    400,
                    "/"
            ));
        } catch (IOException exception) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.INFERENCE_PARSE_FAILED",
                    "Sample inference request could not be read.",
                    400,
                    "/"
            ));
        }
    }

    private static DecodeFailure limitFailure(String reason) {
        return new DecodeFailure(
                "RG.AUTHORING.INFERENCE_REQUEST_LIMIT_EXCEEDED",
                safeReason(reason),
                413,
                "/samples"
        );
    }

    private static boolean resourceLimitFailure(JsonProcessingException exception) {
        if (exception instanceof StreamConstraintsException) {
            return true;
        }
        String message = exception.getOriginalMessage() == null
                ? "" : exception.getOriginalMessage().toLowerCase(Locale.ROOT);
        return message.contains("nesting depth")
                || message.contains("token count")
                || message.contains("maximum")
                || message.contains("document length");
    }

    private static String safeReason(JsonProcessingException exception) {
        return safeReason(exception.getOriginalMessage());
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "Sample inference request exceeds a resource limit.";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }

    public record DecodeResult(
            SampleInferenceRequest request,
            DecodeFailure failure
    ) {
        public static DecodeResult decoded(SampleInferenceRequest request) {
            return new DecodeResult(request, null);
        }

        public static DecodeResult failed(DecodeFailure failure) {
            return new DecodeResult(null, failure);
        }

        public boolean successful() {
            return request != null && failure == null;
        }
    }

    public record DecodeFailure(
            String code,
            String message,
            int status,
            String authoringPath
    ) {
    }
}
