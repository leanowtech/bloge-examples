package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict codec for the {@code X-BLOGE-Test-Envelope} wire header. */
public final class TestControlHeaderCodec {
    /** HTTP header carrying the strict test-control envelope. */
    public static final String ENVELOPE_HEADER = "X-BLOGE-Test-Envelope";
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "purpose", "scenario", "worldModel", "correlationId", "functionControl");
    private static final Set<String> REFERENCE_FIELDS = Set.of("id", "revision", "fingerprint");
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(TestControlProtocolLimits.MAX_JSON_DEPTH)
                    .maxStringLength(TestControlProtocolLimits.MAX_STRING_CHARS)
                    .maxNameLength(TestControlProtocolLimits.MAX_STRING_CHARS)
                    .build())
            .build();
    private static final ObjectMapper JSON = new ObjectMapper(FACTORY);

    private TestControlHeaderCodec() {
    }

    /** Encodes one canonical URL-safe base64 header value.
     * @param envelope exact envelope to encode
     * @return base64url header value
     */
    public static String encode(TestControlEnvelope envelope) {
        if (envelope == null) throw invalid();
        ObjectNode root = JSON.createObjectNode();
        root.put("purpose", envelope.purpose());
        if (envelope.scenario() != null) root.set("scenario", reference(envelope.scenario()));
        if (envelope.worldModel() != null) root.set("worldModel", reference(envelope.worldModel()));
        root.put("correlationId", envelope.correlationId());
        if (envelope.functionControl() != null) {
            root.set("functionControl", reference(envelope.functionControl()));
        }
        try {
            byte[] json = JSON.writeValueAsBytes(root);
            if (json.length > TestControlProtocolLimits.MAX_DECODED_ENVELOPE_BYTES) throw invalid();
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            if (encoded.length() > TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES) throw invalid();
            return encoded;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /** Decodes and validates one header value without retaining its input bytes.
     * @param encoded base64url header value
     * @return immutable decoded envelope
     */
    public static TestControlEnvelope decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES
                || !BASE64_URL.matcher(encoded).matches()) throw invalid();
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        if (bytes.length > TestControlProtocolLimits.MAX_DECODED_ENVELOPE_BYTES
                || !encoded.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))) {
            throw invalid();
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            try (var parser = FACTORY.createParser(json)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw invalid();
            rejectUnknown(root, ENVELOPE_FIELDS);
            String purpose = requiredText(root, "purpose");
            String correlationId = requiredText(root, "correlationId");
            boolean hasScenario = root.has("scenario");
            boolean hasWorld = root.has("worldModel");
            if (hasScenario == hasWorld) throw invalid();
            return new TestControlEnvelope(purpose,
                    hasScenario ? reference(root.get("scenario")) : null,
                    hasWorld ? reference(root.get("worldModel")) : null,
                    correlationId,
                    root.has("functionControl") ? reference(root.get("functionControl")) : null);
            }
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static ObjectNode reference(TestControlAssetReference value) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", value.id());
        node.put("revision", value.revision());
        node.put("fingerprint", value.fingerprint());
        return node;
    }

    private static TestControlAssetReference reference(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        rejectUnknown(value, REFERENCE_FIELDS);
        JsonNode revision = value.get("revision");
        if (!value.has("id") || !value.has("fingerprint") || revision == null
                || !revision.isIntegralNumber() || !revision.canConvertToLong()) throw invalid();
        return new TestControlAssetReference(requiredText(value, "id"), revision.asLong(),
                requiredText(value, "fingerprint"));
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > TestControlProtocolLimits.MAX_STRING_CHARS
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue().trim();
    }

    private static void rejectUnknown(JsonNode value, Set<String> allowed) {
        Set<String> seen = new HashSet<>();
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name) || !seen.add(name)) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-control envelope header");
    }
}
