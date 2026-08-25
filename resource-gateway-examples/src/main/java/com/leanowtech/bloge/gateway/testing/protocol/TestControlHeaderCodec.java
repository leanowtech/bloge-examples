package com.leanowtech.bloge.gateway.testing.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure parser for the stage-zero BLOGE test-control header protocol. */
public final class TestControlHeaderCodec {
    public static final String ENVELOPE_HEADER = "X-BLOGE-Test-Envelope";
    public static final String FIDELITY_HEADER = "X-BLOGE-Test-Fidelity";
    public static final String SCOPE_HEADER = "X-BLOGE-Test-Scope";
    public static final String INLINE_HEADER = "X-BLOGE-Test-Inline";

    private static final Set<String> CONTROL_HEADERS = Set.of(
            ENVELOPE_HEADER.toLowerCase(Locale.ROOT),
            FIDELITY_HEADER.toLowerCase(Locale.ROOT),
            SCOPE_HEADER.toLowerCase(Locale.ROOT),
            INLINE_HEADER.toLowerCase(Locale.ROOT));
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("purpose", "scenario", "worldModel", "correlationId");
    private static final Set<String> REFERENCE_FIELDS = Set.of("id", "revision", "fingerprint");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(TestControlProtocolLimits.MAX_JSON_DEPTH)
                    .maxStringLength(TestControlProtocolLimits.MAX_DECODED_INLINE_BYTES)
                    .maxNameLength(TestControlProtocolLimits.MAX_DECODED_INLINE_BYTES)
                    .maxNumberLength(TestControlProtocolLimits.MAX_DECODED_INLINE_BYTES)
                    .build())
            .build();
    private static final ObjectMapper JSON = new ObjectMapper(JSON_FACTORY);

    /** Parse a case-insensitive HTTP header map without retaining the input map. */
    public TestControlHeaders parse(Map<String, List<String>> headers) {
        if (headers == null) {
            throw failure(TestControlProtocolReason.INVALID_INPUT);
        }

        Map<String, String> values = collectControlValues(headers);
        TestControlEnvelope envelope = values.containsKey(normalized(ENVELOPE_HEADER))
                ? parseEnvelope(values.get(normalized(ENVELOPE_HEADER)) )
                : null;
        String fidelity = values.containsKey(normalized(FIDELITY_HEADER))
                ? parseToken(values.get(normalized(FIDELITY_HEADER)))
                : null;
        String scope = values.containsKey(normalized(SCOPE_HEADER))
                ? parseToken(values.get(normalized(SCOPE_HEADER)))
                : null;
        TestInlineControl inline = values.containsKey(normalized(INLINE_HEADER))
                ? parseInline(values.get(normalized(INLINE_HEADER)))
                : null;
        return new TestControlHeaders(envelope, fidelity, scope, inline);
    }

    public static TestControlHeaders parseHeaders(Map<String, List<String>> headers) {
        return new TestControlHeaderCodec().parse(headers);
    }

    private Map<String, String> collectControlValues(Map<String, List<String>> headers) {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            String normalizedName = normalized(name);
            if (!CONTROL_HEADERS.contains(normalizedName)) {
                continue;
            }
            if (values.containsKey(normalizedName)) {
                throw failure(TestControlProtocolReason.DUPLICATE_HEADER);
            }
            List<String> headerValues = entry.getValue();
            if (headerValues == null) {
                throw failure(TestControlProtocolReason.NULL_HEADER_VALUE);
            }
            if (headerValues.size() != 1) {
                throw failure(TestControlProtocolReason.DUPLICATE_HEADER);
            }
            String value = headerValues.getFirst();
            if (value == null) {
                throw failure(TestControlProtocolReason.NULL_HEADER_VALUE);
            }
            if (value.isEmpty()) {
                throw failure(TestControlProtocolReason.EMPTY_HEADER);
            }
            values.put(normalizedName, value);
        }
        return Map.copyOf(values);
    }

    private TestControlEnvelope parseEnvelope(String encoded) {
        JsonNode root = parseJson(encoded, TestControlProtocolLimits.MAX_DECODED_ENVELOPE_BYTES);
        if (!root.isObject()) {
            throw failure(TestControlProtocolReason.JSON_ROOT_NOT_OBJECT);
        }
        rejectUnknownFields(root, ENVELOPE_FIELDS);

        String purpose = requiredText(root, "purpose");
        String correlationId = requiredText(root, "correlationId");
        boolean hasScenario = root.has("scenario");
        boolean hasWorldModel = root.has("worldModel");
        if (hasScenario == hasWorldModel) {
            throw failure(TestControlProtocolReason.ASSET_REFERENCE_CARDINALITY);
        }
        TestAssetReference scenario = hasScenario ? parseReference(root.get("scenario")) : null;
        TestAssetReference worldModel = hasWorldModel ? parseReference(root.get("worldModel")) : null;
        try {
            return new TestControlEnvelope(purpose, scenario, worldModel, correlationId);
        } catch (IllegalArgumentException exception) {
            throw failure(TestControlProtocolReason.ASSET_REFERENCE_CARDINALITY);
        }
    }

    private TestAssetReference parseReference(JsonNode reference) {
        if (reference == null || !reference.isObject()) {
            throw failure(TestControlProtocolReason.INVALID_FIELD_TYPE);
        }
        rejectUnknownFields(reference, REFERENCE_FIELDS);
        String id = requiredText(reference, "id");
        JsonNode revisionNode = required(reference, "revision");
        if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.asLong() <= 0) {
            throw failure(TestControlProtocolReason.INVALID_REVISION);
        }
        String fingerprint = requiredText(reference, "fingerprint");
        if (!fingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw failure(TestControlProtocolReason.INVALID_FINGERPRINT);
        }
        try {
            return new TestAssetReference(id, revisionNode.asLong(), fingerprint);
        } catch (IllegalArgumentException exception) {
            throw failure(TestControlProtocolReason.INVALID_FINGERPRINT);
        }
    }

    private TestInlineControl parseInline(String encoded) {
        JsonNode root = parseJson(encoded, TestControlProtocolLimits.MAX_DECODED_INLINE_BYTES);
        if (!root.isObject()) {
            throw failure(TestControlProtocolReason.INLINE_NOT_OBJECT);
        }
        return new TestInlineControl(root);
    }

    private String parseToken(String value) {
        if (value.codePointCount(0, value.length()) > TestControlProtocolLimits.MAX_TOKEN_CHARS
                || !TOKEN.matcher(value).matches()) {
            throw failure(TestControlProtocolReason.INVALID_TOKEN);
        }
        return value;
    }

    private JsonNode parseJson(String encoded, int maxDecodedBytes) {
        byte[] decoded = decodeBase64Url(encoded, maxDecodedBytes);
        String json = decodeUtf8(decoded);
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null) {
                throw failure(TestControlProtocolReason.MALFORMED_JSON);
            }
            if (parser.nextToken() != null) {
                throw failure(TestControlProtocolReason.MALFORMED_JSON);
            }
            validateJsonShape(root, 1);
            return root;
        } catch (TestControlProtocolException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw failure(TestControlProtocolReason.JSON_DEPTH_EXCEEDED);
        } catch (IOException exception) {
            if (isDuplicateFieldFailure(exception)) {
                throw failure(TestControlProtocolReason.JSON_DUPLICATE_FIELD);
            }
            throw failure(TestControlProtocolReason.MALFORMED_JSON);
        }
    }

    private byte[] decodeBase64Url(String encoded, int maxDecodedBytes) {
        if (encoded.codePointCount(0, encoded.length()) > TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES) {
            throw failure(TestControlProtocolReason.ENCODED_VALUE_TOO_LARGE);
        }
        for (int index = 0; index < encoded.length(); index++) {
            if (encoded.charAt(index) > 0x7f) {
                throw failure(TestControlProtocolReason.HEADER_NOT_ASCII);
            }
        }
        if (!BASE64URL.matcher(encoded).matches()) {
            throw failure(TestControlProtocolReason.INVALID_BASE64URL);
        }
        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw failure(TestControlProtocolReason.INVALID_BASE64URL);
        }
        if (decoded.length > maxDecodedBytes) {
            throw failure(TestControlProtocolReason.DECODED_VALUE_TOO_LARGE);
        }
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!canonical.equals(encoded)) {
            throw failure(TestControlProtocolReason.NON_CANONICAL_BASE64URL);
        }
        return decoded;
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw failure(TestControlProtocolReason.INVALID_UTF8);
        }
    }

    private void validateJsonShape(JsonNode node, int depth) {
        if (depth > TestControlProtocolLimits.MAX_JSON_DEPTH) {
            throw failure(TestControlProtocolReason.JSON_DEPTH_EXCEEDED);
        }
        if (node.isTextual()) {
            if (node.textValue().codePointCount(0, node.textValue().length())
                    > TestControlProtocolLimits.MAX_STRING_CHARS) {
                throw failure(TestControlProtocolReason.JSON_STRING_TOO_LONG);
            }
            return;
        }
        if (node.isObject()) {
            if (node.size() > TestControlProtocolLimits.MAX_CONTAINER_ENTRIES) {
                throw failure(TestControlProtocolReason.JSON_CONTAINER_TOO_LARGE);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().codePointCount(0, field.getKey().length())
                        > TestControlProtocolLimits.MAX_STRING_CHARS) {
                    throw failure(TestControlProtocolReason.JSON_STRING_TOO_LONG);
                }
                validateJsonShape(field.getValue(), depth + 1);
            }
            return;
        }
        if (node.isArray()) {
            if (node.size() > TestControlProtocolLimits.MAX_CONTAINER_ENTRIES) {
                throw failure(TestControlProtocolReason.JSON_CONTAINER_TOO_LARGE);
            }
            for (JsonNode child : node) {
                validateJsonShape(child, depth + 1);
            }
        }
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw failure(TestControlProtocolReason.JSON_UNKNOWN_FIELD);
            }
        }
    }

    private String requiredText(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().codePointCount(0, value.textValue().length())
                > TestControlProtocolLimits.MAX_STRING_CHARS) {
            throw failure(TestControlProtocolReason.INVALID_FIELD_TYPE);
        }
        return value.textValue();
    }

    private JsonNode required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw failure(TestControlProtocolReason.MISSING_REQUIRED_FIELD);
        }
        return value;
    }

    private boolean isDuplicateFieldFailure(IOException exception) {
        return exception.getClass().getName().contains("JsonParseException")
                && exception.getMessage() != null
                && exception.getMessage().contains("Duplicate");
    }

    private static String normalized(String headerName) {
        return headerName.toLowerCase(Locale.ROOT);
    }

    private static TestControlProtocolException failure(TestControlProtocolReason reason) {
        return new TestControlProtocolException(reason);
    }
}
