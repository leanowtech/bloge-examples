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
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;

import java.io.IOException;
import java.util.Locale;

/**
 * Strict JSON-only decoder that rejects oversized sample payloads before object binding.
 */
public final class SampleInferenceRequestDecoder {

    public static final int MAXIMUM_APPLY_REQUEST_BYTES = 4 * 1_048_576;
    private static final int MAXIMUM_TOKENS = 100_000;

    private final ObjectMapper mapper;

    public SampleInferenceRequestDecoder() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAXIMUM_APPLY_REQUEST_BYTES)
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
        DecodedValue<SampleInferenceRequest> decoded = decode(
                source,
                SampleInferenceRequest.class,
                SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES,
                "RG.AUTHORING.INFERENCE_REQUEST_REQUIRED",
                "Sample inference request is required."
        );
        return decoded.failure() == null
                ? DecodeResult.decoded(decoded.value())
                : DecodeResult.failed(decoded.failure());
    }

    public ApplyDecodeResult decodeApply(byte[] source) {
        DecodedValue<SampleInferenceApplyRequest> decoded = decode(
                source,
                SampleInferenceApplyRequest.class,
                MAXIMUM_APPLY_REQUEST_BYTES,
                "RG.AUTHORING.INFERENCE_APPLY_REQUEST_REQUIRED",
                "Sample inference apply request is required."
        );
        return decoded.failure() == null
                ? ApplyDecodeResult.decoded(decoded.value())
                : ApplyDecodeResult.failed(decoded.failure());
    }

    private <T> DecodedValue<T> decode(byte[] source,
                                       Class<T> type,
                                       int maximumBytes,
                                       String requiredCode,
                                       String requiredMessage) {
        if (source == null || source.length == 0) {
            return DecodedValue.failed(new DecodeFailure(
                    requiredCode,
                    requiredMessage,
                    400,
                    "/"
            ));
        }
        if (source.length > maximumBytes) {
            return DecodedValue.failed(limitFailure(
                    "Sample inference request exceeds the %d byte limit."
                            .formatted(maximumBytes)));
        }
        try {
            return DecodedValue.decoded(mapper.readValue(source, type));
        } catch (JsonProcessingException exception) {
            if (resourceLimitFailure(exception)) {
                return DecodedValue.failed(limitFailure(safeReason(exception)));
            }
            JsonLocation location = exception.getLocation();
            String suffix = location == null ? "" : " at line %d, column %d"
                    .formatted(location.getLineNr(), location.getColumnNr());
            return DecodedValue.failed(new DecodeFailure(
                    "RG.AUTHORING.INFERENCE_PARSE_FAILED",
                    "Sample inference request could not be parsed" + suffix + ".",
                    400,
                    "/"
            ));
        } catch (IOException exception) {
            return DecodedValue.failed(new DecodeFailure(
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

    public record ApplyDecodeResult(
            SampleInferenceApplyRequest request,
            DecodeFailure failure
    ) {
        public static ApplyDecodeResult decoded(SampleInferenceApplyRequest request) {
            return new ApplyDecodeResult(request, null);
        }

        public static ApplyDecodeResult failed(DecodeFailure failure) {
            return new ApplyDecodeResult(null, failure);
        }

        public boolean successful() {
            return request != null && failure == null;
        }
    }

    private record DecodedValue<T>(
            T value,
            DecodeFailure failure
    ) {
        private static <T> DecodedValue<T> decoded(T value) {
            return new DecodedValue<>(value, null);
        }

        private static <T> DecodedValue<T> failed(DecodeFailure failure) {
            return new DecodedValue<>(null, failure);
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
