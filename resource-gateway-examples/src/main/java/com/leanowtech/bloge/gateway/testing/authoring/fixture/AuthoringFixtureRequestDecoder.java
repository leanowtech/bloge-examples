package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.SaveRequest;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringFixtureCapability;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Strict bounded decoder for payload-bearing authoring-fixture commands.
 */
public final class AuthoringFixtureRequestDecoder {

    public static final int MAXIMUM_REQUEST_BYTES =
            AuthoringFixtureCapability.MAXIMUM_REQUEST_BYTES;
    private static final int MAXIMUM_TOKENS =
            AuthoringFixtureService.MAXIMUM_PAYLOAD_NODES * 4;
    private static final List<String> REQUIRED_FIELDS = List.of(
            "schemaVersion",
            "fixtureId",
            "expectedFixtureRevision",
            "sourceKind",
            "assetKind",
            "assetRef",
            "classification",
            "retentionDays",
            "redactionPaths",
            "payload");

    private final ObjectMapper mapper;

    public AuthoringFixtureRequestDecoder() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAXIMUM_REQUEST_BYTES)
                .maxNestingDepth(
                        AuthoringFixtureService.MAXIMUM_PAYLOAD_DEPTH + 8)
                .maxTokenCount(MAXIMUM_TOKENS)
                .maxNameLength(1_024)
                .maxStringLength(
                        AuthoringFixtureService.MAXIMUM_PAYLOAD_BYTES)
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
                    "RG.AUTHORING.FIXTURE_REQUEST_REQUIRED",
                    "Fixture save request is required.",
                    400,
                    "/"));
        }
        if (source.length > MAXIMUM_REQUEST_BYTES) {
            return DecodeResult.failed(limitFailure());
        }
        try {
            JsonNode root = mapper.readTree(source);
            if (!hasRequiredFields(root)) {
                return DecodeResult.failed(new DecodeFailure(
                        "RG.AUTHORING.FIXTURE_REQUEST_INVALID",
                        "Fixture save request must contain every required protocol field.",
                        400,
                        "/"));
            }
            return DecodeResult.decoded(
                    mapper.treeToValue(root, SaveRequest.class));
        } catch (JsonProcessingException invalid) {
            if (isResourceLimit(invalid)) {
                return DecodeResult.failed(limitFailure());
            }
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.FIXTURE_PARSE_FAILED",
                    "Fixture save request JSON is malformed or cannot be decoded.",
                    400,
                    "/"));
        } catch (IOException unreadable) {
            return DecodeResult.failed(new DecodeFailure(
                    "RG.AUTHORING.FIXTURE_PARSE_FAILED",
                    "Fixture save request could not be read.",
                    400,
                    "/"));
        }
    }

    private static boolean hasRequiredFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        for (String field : REQUIRED_FIELDS) {
            if (!root.has(field)
                    || (!"payload".equals(field) && root.get(field).isNull())) {
                return false;
            }
        }
        return true;
    }

    private static DecodeFailure limitFailure() {
        return new DecodeFailure(
                "RG.AUTHORING.FIXTURE_REQUEST_LIMIT_EXCEEDED",
                "Fixture save request exceeds a bounded JSON resource limit.",
                413,
                "/payload");
    }

    private static boolean isResourceLimit(
            JsonProcessingException exception) {
        if (exception instanceof StreamConstraintsException) {
            return true;
        }
        String message = exception.getOriginalMessage() == null
                ? ""
                : exception.getOriginalMessage().toLowerCase(Locale.ROOT);
        return message.contains("nesting depth")
                || message.contains("token count")
                || message.contains("document length")
                || message.contains("string length")
                || message.contains("name length");
    }

    public record DecodeResult(
            SaveRequest request,
            DecodeFailure failure
    ) {
        public static DecodeResult decoded(SaveRequest request) {
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
