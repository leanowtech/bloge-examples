package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only independent reference for Gate A Protocol Canonicalization v1.
 *
 * <p>This class deliberately does not reuse production JSON or fingerprint code. Its input
 * boundary is a byte array, so malformed UTF-8 and a UTF-8 BOM are rejected before the JSON
 * parser can observe a value. The value model preserves only the JSON types needed by the
 * protocol and the writer implements the compact JCS/ECMAScript representation used by the
 * vectors.</p>
 */
final class CapabilityStudioCanonicalizationReference {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final char UTF8_BOM = '\ufeff';

    private CapabilityStudioCanonicalizationReference() {}

    static JsonValue parseUtf8(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            throw failure("UTF8_BOM_REJECTED");
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return parseText(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException error) {
            throw failure("INVALID_UTF8", error);
        }
    }

    static JsonValue parseText(String source) {
        if (!source.isEmpty() && source.charAt(0) == UTF8_BOM) {
            throw failure("UTF8_BOM_REJECTED");
        }
        return new Parser(source).parse();
    }

    static String canonicalize(JsonValue value) {
        var output = new StringBuilder();
        writeCanonical(value, output);
        return output.toString();
    }

    static Fingerprint documentFingerprint(
            String domain,
            JsonValue value,
            String selfField) {
        validateAsciiDomain(domain);
        JsonValue canonicalValue = value;
        if (selfField != null) {
            if (!(value instanceof JsonObject object) || !object.values().containsKey(selfField)) {
                throw failure("SELF_FIELD_MISSING");
            }
            var copy = new LinkedHashMap<>(object.values());
            copy.put(selfField, JsonNull.INSTANCE);
            canonicalValue = new JsonObject(copy);
        }
        String canonical = canonicalize(canonicalValue);
        var domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        var canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        var input = new ByteArrayOutputStream(domainBytes.length + 1 + canonicalBytes.length);
        input.writeBytes(domainBytes);
        input.write(0);
        input.writeBytes(canonicalBytes);
        return new Fingerprint(canonical, sha256(input.toByteArray()));
    }

    static String rawFingerprint(byte[] bytes) {
        return sha256(bytes);
    }

    private static void validateAsciiDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            throw failure("INVALID_ASCII_DOMAIN");
        }
        for (int index = 0; index < domain.length(); index++) {
            char character = domain.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw failure("INVALID_ASCII_DOMAIN");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + hex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JRE must provide SHA-256", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        var output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(value & 0x0f, 16));
        }
        return output.toString();
    }

    private static void writeCanonical(JsonValue value, StringBuilder output) {
        switch (value) {
            case JsonNull ignored -> output.append("null");
            case JsonBoolean booleanValue -> output.append(booleanValue.value());
            case JsonNumber number -> output.append(number.ecmascriptText());
            case JsonString string -> writeString(string.value(), output);
            case JsonArray array -> {
                output.append('[');
                for (int index = 0; index < array.values().size(); index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    writeCanonical(array.values().get(index), output);
                }
                output.append(']');
            }
            case JsonObject object -> {
                output.append('{');
                var entries = object.values().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .toList();
                for (int index = 0; index < entries.size(); index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    var entry = entries.get(index);
                    writeString(entry.getKey(), output);
                    output.append(':');
                    writeCanonical(entry.getValue(), output);
                }
                output.append('}');
            }
        }
    }

    private static void writeString(String value, StringBuilder output) {
        rejectLoneSurrogates(value);
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u00");
                        output.append(Character.forDigit((character >>> 4) & 0x0f, 16));
                        output.append(Character.forDigit(character & 0x0f, 16));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void rejectLoneSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw failure("LONE_SURROGATE");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw failure("LONE_SURROGATE");
            }
        }
    }

    static record Fingerprint(String canonical, String documentFingerprint) {}

    sealed interface JsonValue permits JsonNull, JsonBoolean, JsonNumber, JsonString, JsonArray, JsonObject {}

    enum JsonNull implements JsonValue {
        INSTANCE
    }

    record JsonBoolean(boolean value) implements JsonValue {}

    record JsonNumber(double value) implements JsonValue {
        JsonNumber(String lexical) {
            this(parseNumber(lexical));
        }

        private static double parseNumber(String lexical) {
            double parsed;
            try {
                parsed = Double.parseDouble(lexical);
            } catch (NumberFormatException error) {
                throw failure("NON_FINITE_NUMBER", error);
            }
            if (!Double.isFinite(parsed)) {
                throw failure("NON_FINITE_NUMBER");
            }
            if (parsed == Math.rint(parsed) && Math.abs(parsed) > MAX_SAFE_INTEGER) {
                throw failure("UNSAFE_INTEGER");
            }
            return parsed;
        }

        String ecmascriptText() {
            if (value == 0.0d) {
                return "0";
            }
            if (!Double.isFinite(value)) {
                throw failure("NON_FINITE_NUMBER");
            }
            String javaText = Double.toString(value);
            return toEcmascriptNumber(javaText);
        }
    }

    record JsonString(String value) implements JsonValue {
        JsonString {
            rejectLoneSurrogates(value);
        }
    }

    record JsonArray(List<JsonValue> values) implements JsonValue {
        JsonArray {
            values = List.copyOf(values);
        }
    }

    record JsonObject(Map<String, JsonValue> values) implements JsonValue {
        JsonObject {
            values = Map.copyOf(values);
        }
    }

    private static String toEcmascriptNumber(String javaText) {
        boolean negative = javaText.startsWith("-");
        String unsigned = negative ? javaText.substring(1) : javaText;
        int exponentMarker = Math.max(unsigned.indexOf('E'), unsigned.indexOf('e'));
        int exponent = 0;
        String digits;
        int decimalPosition;
        if (exponentMarker >= 0) {
            exponent = Integer.parseInt(unsigned.substring(exponentMarker + 1));
            String mantissa = unsigned.substring(0, exponentMarker);
            int decimal = mantissa.indexOf('.');
            digits = decimal < 0 ? mantissa : mantissa.substring(0, decimal) + mantissa.substring(decimal + 1);
            decimalPosition = (decimal < 0 ? mantissa.length() : decimal) + exponent;
        } else {
            int decimal = unsigned.indexOf('.');
            digits = decimal < 0 ? unsigned : unsigned.substring(0, decimal) + unsigned.substring(decimal + 1);
            decimalPosition = decimal < 0 ? unsigned.length() : decimal;
        }
        digits = stripTrailingFractionZeros(digits);
        while (digits.length() > 1 && digits.charAt(0) == '0') {
            digits = digits.substring(1);
            decimalPosition--;
        }
        int scientificExponent = decimalPosition - 1;
        boolean scientific = scientificExponent < -6 || scientificExponent >= 21;
        String result;
        if (scientific) {
            var mantissa = new StringBuilder();
            mantissa.append(digits.charAt(0));
            if (digits.length() > 1) {
                mantissa.append('.').append(digits.substring(1));
            }
            result = mantissa + "e" + (scientificExponent >= 0 ? "+" : "") + scientificExponent;
        } else if (decimalPosition <= 0) {
            result = "0." + "0".repeat(-decimalPosition) + digits;
        } else if (decimalPosition >= digits.length()) {
            result = digits + "0".repeat(decimalPosition - digits.length());
        } else {
            result = digits.substring(0, decimalPosition) + "." + digits.substring(decimalPosition);
        }
        return negative ? "-" + result : result;
    }

    private static String stripTrailingFractionZeros(String digits) {
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static IllegalArgumentException failure(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static IllegalArgumentException failure(String reason, Throwable cause) {
        return new IllegalArgumentException(reason, cause);
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private JsonValue parse() {
            skipWhitespace();
            JsonValue value = parseValue();
            skipWhitespace();
            if (index != source.length()) {
                throw error("TRAILING_CONTENT");
            }
            return value;
        }

        private JsonValue parseValue() {
            if (index >= source.length()) {
                throw error("INVALID_TOKEN");
            }
            return switch (source.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonString(parseString());
                case 't' -> parseLiteral("true", new JsonBoolean(true));
                case 'f' -> parseLiteral("false", new JsonBoolean(false));
                case 'n' -> parseLiteral("null", JsonNull.INSTANCE);
                case '-' -> new JsonNumber(parseNumberLexical());
                default -> {
                    char character = source.charAt(index);
                    if (character >= '0' && character <= '9') {
                        yield new JsonNumber(parseNumberLexical());
                    }
                    throw error("INVALID_TOKEN");
                }
            };
        }

        private JsonObject parseObject() {
            index++;
            var values = new LinkedHashMap<String, JsonValue>();
            skipWhitespace();
            if (consumeIf('}')) {
                return new JsonObject(values);
            }
            while (true) {
                if (index >= source.length() || source.charAt(index) != '"') {
                    throw error("OBJECT_KEY_REQUIRED");
                }
                String key = parseString();
                if (values.containsKey(key)) {
                    throw error("DUPLICATE_KEY");
                }
                skipWhitespace();
                expect(':', "COLON_REQUIRED");
                skipWhitespace();
                values.put(key, parseValue());
                skipWhitespace();
                if (consumeIf('}')) {
                    return new JsonObject(values);
                }
                expect(',', "OBJECT_SEPARATOR_REQUIRED");
                skipWhitespace();
            }
        }

        private JsonArray parseArray() {
            index++;
            var values = new ArrayList<JsonValue>();
            skipWhitespace();
            if (consumeIf(']')) {
                return new JsonArray(values);
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (consumeIf(']')) {
                    return new JsonArray(values);
                }
                expect(',', "ARRAY_SEPARATOR_REQUIRED");
                skipWhitespace();
            }
        }

        private String parseString() {
            expect('"', "STRING_REQUIRED");
            var value = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') {
                    String result = value.toString();
                    rejectLoneSurrogates(result);
                    return result;
                }
                if (character < 0x20) {
                    throw error("UNESCAPED_CONTROL_CHARACTER");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= source.length()) {
                    throw error("UNTERMINATED_STRING");
                }
                char escape = source.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw error("INVALID_STRING_ESCAPE");
                }
            }
            throw error("UNTERMINATED_STRING");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw error("INVALID_STRING_ESCAPE");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(source.charAt(index++), 16);
                if (digit < 0) {
                    throw error("INVALID_STRING_ESCAPE");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private String parseNumberLexical() {
            int start = index;
            consumeIf('-');
            if (consumeIf('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index))) {
                    throw error("INVALID_NUMBER");
                }
            } else {
                if (!consumeDigits()) {
                    throw error("INVALID_NUMBER");
                }
            }
            if (consumeIf('.')) {
                if (!consumeDigits()) {
                    throw error("INVALID_NUMBER");
                }
            }
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (!consumeIf('+')) {
                    consumeIf('-');
                }
                if (!consumeDigits()) {
                    throw error("INVALID_NUMBER");
                }
            }
            return source.substring(start, index);
        }

        private boolean consumeDigits() {
            int start = index;
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
                index++;
            }
            return index > start;
        }

        private JsonValue parseLiteral(String literal, JsonValue value) {
            if (!source.startsWith(literal, index)) {
                throw error("INVALID_LITERAL");
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character != ' ' && character != '\t' && character != '\n' && character != '\r') {
                    return;
                }
                index++;
            }
        }

        private boolean consumeIf(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected, String reason) {
            if (!consumeIf(expected)) {
                throw error(reason);
            }
        }

        private IllegalArgumentException error(String reason) {
            return failure(reason + " at offset " + index);
        }
    }
}
