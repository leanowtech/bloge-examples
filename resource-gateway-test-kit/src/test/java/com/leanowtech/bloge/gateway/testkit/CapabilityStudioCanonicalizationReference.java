package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
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
            if (!sameDecimalValue(lexical, CapabilityStudioCanonicalizationReference.ecmascriptText(parsed))) {
                throw failure("NUMBER_NOT_EXACT");
            }
            return parsed;
        }

        String ecmascriptText() {
            if (value == Math.rint(value) && Math.abs(value) > MAX_SAFE_INTEGER) {
                throw failure("UNSAFE_INTEGER");
            }
            return CapabilityStudioCanonicalizationReference.ecmascriptText(value);
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

    private static String ecmascriptText(double value) {
        if (value == 0.0d) {
            return "0";
        }
        if (!Double.isFinite(value)) {
            throw failure("NON_FINITE_NUMBER");
        }

        boolean negative = value < 0.0d;
        double magnitude = negative ? -value : value;
        Rational exact = Rational.of(magnitude);
        int decimalExponent = new BigDecimal(magnitude).precision()
                - new BigDecimal(magnitude).scale() - 1;
        RoundingInterval interval = RoundingInterval.of(magnitude, exact);

        for (int significantDigits = 1; significantDigits <= 17; significantDigits++) {
            DecimalCandidate best = null;
            for (int exponent = decimalExponent - 1; exponent <= decimalExponent + 1; exponent++) {
                DecimalCandidate candidate = shortestCandidate(
                        exact, interval, significantDigits, exponent);
                if (candidate != null && (best == null || closer(candidate, best, exact))) {
                    best = candidate;
                }
            }
            if (best != null) {
                String digits = stripTrailingFractionZeros(best.significand.toString());
                String result = formatDecimal(digits, best.decimalExponent);
                return negative ? "-" + result : result;
            }
        }
        throw new AssertionError("binary64 value has no ECMAScript decimal representation");
    }

    private static String stripTrailingFractionZeros(String digits) {
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static String formatDecimal(String digits, int decimalExponent) {
        boolean scientific = decimalExponent < -6 || decimalExponent >= 21;
        if (scientific) {
            return digits.length() == 1
                    ? digits + "e" + (decimalExponent >= 0 ? "+" : "") + decimalExponent
                    : digits.charAt(0) + "." + digits.substring(1)
                            + "e" + (decimalExponent >= 0 ? "+" : "") + decimalExponent;
        }
        int decimalPosition = decimalExponent + 1;
        if (decimalPosition <= 0) {
            return "0." + "0".repeat(-decimalPosition) + digits;
        }
        if (decimalPosition >= digits.length()) {
            return digits + "0".repeat(decimalPosition - digits.length());
        }
        return digits.substring(0, decimalPosition) + "." + digits.substring(decimalPosition);
    }

    private static boolean sameDecimalValue(String left, String right) {
        DecimalRational leftValue = DecimalRational.parse(left);
        DecimalRational rightValue = DecimalRational.parse(right);
        return leftValue.numerator.multiply(rightValue.denominator)
                .equals(rightValue.numerator.multiply(leftValue.denominator));
    }

    private static DecimalCandidate shortestCandidate(
            Rational exact,
            RoundingInterval interval,
            int significantDigits,
            int decimalExponent) {
        int decimalScale = decimalExponent - significantDigits + 1;
        BigInteger factorNumerator = decimalScale >= 0
                ? BigInteger.TEN.pow(decimalScale)
                : BigInteger.ONE;
        BigInteger factorDenominator = decimalScale >= 0
                ? BigInteger.ONE
                : BigInteger.TEN.pow(-decimalScale);
        BigInteger minimum = BigInteger.TEN.pow(significantDigits - 1);
        BigInteger maximum = BigInteger.TEN.pow(significantDigits).subtract(BigInteger.ONE);

        BigInteger lowerNumerator = interval.lower.numerator.multiply(factorDenominator);
        BigInteger lowerDenominator = interval.lower.denominator.multiply(factorNumerator);
        BigInteger upperNumerator = interval.upper.numerator.multiply(factorDenominator);
        BigInteger upperDenominator = interval.upper.denominator.multiply(factorNumerator);
        BigInteger lower = ceilDiv(lowerNumerator, lowerDenominator);
        BigInteger upper = upperNumerator.divide(upperDenominator);
        if (!interval.lowerInclusive && lowerNumerator.mod(lowerDenominator).signum() == 0) {
            lower = lower.add(BigInteger.ONE);
        }
        if (!interval.upperInclusive && upperNumerator.mod(upperDenominator).signum() == 0) {
            upper = upper.subtract(BigInteger.ONE);
        }
        lower = lower.max(minimum);
        upper = upper.min(maximum);
        if (lower.compareTo(upper) > 0) {
            return null;
        }

        BigInteger targetNumerator = exact.numerator.multiply(factorDenominator);
        BigInteger targetDenominator = exact.denominator.multiply(factorNumerator);
        BigInteger floor = targetNumerator.divide(targetDenominator);
        BigInteger best = null;
        for (BigInteger candidate : new BigInteger[]{floor, floor.add(BigInteger.ONE)}) {
            if (candidate.compareTo(lower) >= 0 && candidate.compareTo(upper) <= 0
                    && (best == null || closer(candidate, best, exact, factorNumerator, factorDenominator))) {
                best = candidate;
            }
        }
        return best == null
                ? null
                : new DecimalCandidate(best, decimalExponent, factorNumerator, factorDenominator);
    }

    private static boolean closer(DecimalCandidate candidate, DecimalCandidate current, Rational exact) {
        BigInteger candidateDistance = distance(
                candidate.significand, exact, candidate.factorNumerator, candidate.factorDenominator);
        BigInteger currentDistance = distance(
                current.significand, exact, current.factorNumerator, current.factorDenominator);
        int comparison = candidateDistance.compareTo(currentDistance);
        return comparison < 0 || (comparison == 0
                && candidate.significand.testBit(0) == false && current.significand.testBit(0));
    }

    private static boolean closer(
            BigInteger candidate,
            BigInteger current,
            Rational exact,
            BigInteger factorNumerator,
            BigInteger factorDenominator) {
        BigInteger candidateDistance = distance(candidate, exact, factorNumerator, factorDenominator);
        BigInteger currentDistance = distance(current, exact, factorNumerator, factorDenominator);
        int comparison = candidateDistance.compareTo(currentDistance);
        return comparison < 0 || (comparison == 0
                && candidate.testBit(0) == false && current.testBit(0));
    }

    private static BigInteger distance(
            BigInteger candidate,
            Rational exact,
            BigInteger factorNumerator,
            BigInteger factorDenominator) {
        return candidate.multiply(factorNumerator).multiply(exact.denominator)
                .subtract(exact.numerator.multiply(factorDenominator)).abs();
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] result = numerator.divideAndRemainder(denominator);
        return result[1].signum() == 0 ? result[0] : result[0].add(BigInteger.ONE);
    }

    private record DecimalCandidate(
            BigInteger significand,
            int decimalExponent,
            BigInteger factorNumerator,
            BigInteger factorDenominator) {}

    private record Rational(BigInteger numerator, BigInteger denominator) {
        private static Rational of(double value) {
            long bits = Double.doubleToRawLongBits(value);
            long fraction = bits & 0x000f_ffffffffffffL;
            int exponentBits = (int) ((bits >>> 52) & 0x7ff);
            long significand = exponentBits == 0 ? fraction : (1L << 52) | fraction;
            int binaryExponent = exponentBits == 0 ? -1074 : exponentBits - 1023 - 52;
            BigInteger numerator = BigInteger.valueOf(significand);
            return binaryExponent >= 0
                    ? new Rational(numerator.shiftLeft(binaryExponent), BigInteger.ONE)
                    : new Rational(numerator, BigInteger.ONE.shiftLeft(-binaryExponent));
        }
    }

    private record RoundingInterval(
            Rational lower,
            Rational upper,
            boolean lowerInclusive,
            boolean upperInclusive) {
        private static RoundingInterval of(double value, Rational exact) {
            Rational previous = Rational.of(Math.nextDown(value));
            Rational next = value == Double.MAX_VALUE
                    ? new Rational(
                            exact.numerator.add(BigInteger.ONE.shiftLeft(970).multiply(exact.denominator)),
                            exact.denominator)
                    : Rational.of(Math.nextUp(value));
            boolean inclusive = (Double.doubleToRawLongBits(value) & 1L) == 0;
            return new RoundingInterval(midpoint(previous, exact), midpoint(exact, next), inclusive, inclusive);
        }

        private static Rational midpoint(Rational left, Rational right) {
            return new Rational(
                    left.numerator.multiply(right.denominator)
                            .add(right.numerator.multiply(left.denominator)),
                    left.denominator.multiply(right.denominator).shiftLeft(1));
        }
    }

    private record DecimalRational(BigInteger numerator, BigInteger denominator) {
        private static DecimalRational parse(String lexical) {
            String[] parts = lexical.split("[eE]", 2);
            String mantissa = parts[0];
            int exponent = parts.length == 1 ? 0 : Integer.parseInt(parts[1]);
            boolean negative = mantissa.charAt(0) == '-';
            String unsigned = negative ? mantissa.substring(1) : mantissa;
            int decimalPoint = unsigned.indexOf('.');
            int fractionLength = decimalPoint < 0 ? 0 : unsigned.length() - decimalPoint - 1;
            String digits = (decimalPoint < 0
                    ? unsigned
                    : unsigned.substring(0, decimalPoint) + unsigned.substring(decimalPoint + 1))
                    .replaceFirst("^0+", "");
            if (digits.isEmpty()) {
                return new DecimalRational(BigInteger.ZERO, BigInteger.ONE);
            }
            BigInteger numerator = new BigInteger(digits);
            if (negative) {
                numerator = numerator.negate();
            }
            int scale = fractionLength - exponent;
            return scale >= 0
                    ? new DecimalRational(numerator, BigInteger.TEN.pow(scale))
                    : new DecimalRational(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }
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
            if (source.startsWith("NaN", index)
                    || source.startsWith("Infinity", index)
                    || source.startsWith("-Infinity", index)) {
                throw error("NON_FINITE_NUMBER");
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
