package com.leanowtech.bloge.gateway.testkit;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-only strict JSON parser for Gate A boundary.
 *
 * <p>Rejects: duplicate object members, trailing commas, non-finite numbers (NaN, Infinity),
 * invalid Unicode (lone surrogates), invalid UTF-8 sequences.</p>
 *
 * <p>Bounds enforcement: nesting depth, total node count, string length, number token length.</p>
 *
 * <p>Root must be a JSON object; trailing non-whitespace after the root value is rejected.</p>
 *
 * <p>Package-private.</p>
 */
final class StrictJsonParser {

    // ── Parse limits ────────────────────────────────────────────────────
    /** Maximum nesting depth of JSON containers. */
    static final int MAX_DEPTH = 256;

    /** Maximum total JSON value nodes (objects + arrays + scalars). */
    static final int MAX_NODES = 100_000;

    /** Maximum character length of any single JSON string (unescaped). */
    static final int MAX_STRING_LENGTH = 1_048_576; // 1 MiB

    /** Maximum character length of a number token. */
    static final int MAX_NUMBER_LENGTH = 10_240;

    private final String json;
    private int pos;
    private int nodeCount;

    private StrictJsonParser(String json) {
        this.json = json;
        this.pos = 0;
        this.nodeCount = 0;
    }

    static Map<String, Object> parse(String json) {
        StrictJsonParser p = new StrictJsonParser(json);
        Object root = p.parseValue();
        if (!(root instanceof Map)) {
            throw new CapabilityStudioGateAException("ROOT_MUST_BE_OBJECT");
        }
        p.skipWhitespace();
        if (p.pos < p.json.length()) {
            throw new CapabilityStudioGateAException("TRAILING_CONTENT");
        }
        return (Map<String, Object>) root;
    }

    static Map<String, Object> parse(byte[] raw) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return parse(decoder.decode(ByteBuffer.wrap(raw)).toString());
        } catch (CharacterCodingException e) {
            throw new CapabilityStudioGateAException("UTF8_DECODE_ERROR", e);
        }
    }

    private char current() {
        return pos < json.length() ? json.charAt(pos) : '\0';
    }

    private char peek() {
        return pos + 1 < json.length() ? json.charAt(pos + 1) : '\0';
    }

    private void advance() {
        if (pos < json.length()) {
            pos++;
        }
    }

    private void skipWhitespace() {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                break;
            }
        }
    }

    private void countNode() {
        if (++nodeCount > MAX_NODES) {
            throw new CapabilityStudioGateAException("NODE_BUDGET_EXCEEDED");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parseValue() {
        return parseValue(0);
    }

    @SuppressWarnings("unchecked")
    private <T> T parseValue(int depth) {
        skipWhitespace();
        if (pos >= json.length()) {
            throw new CapabilityStudioGateAException("UNEXPECTED_EOF");
        }
        char c = current();
        if (c == '{') {
            return (T) parseObject(depth);
        } else if (c == '[') {
            return (T) parseArray(depth);
        } else if (c == '"') {
            return (T) parseString();
        } else if (c == 't' || c == 'f') {
            return (T) parseBoolean();
        } else if (c == 'n') {
            return (T) parseNull();
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return (T) parseNumber();
        } else {
            throw new CapabilityStudioGateAException("INVALID_START:" + (int) c);
        }
    }

    private Map<String, Object> parseObject(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new CapabilityStudioGateAException("DEPTH_LIMIT_EXCEEDED");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        countNode();
        advance();
        skipWhitespace();
        if (current() == '}') {
            advance();
            return result;
        }
        while (true) {
            skipWhitespace();
            if (current() != '"') {
                throw new CapabilityStudioGateAException("OBJECT_KEY_MUST_BE_STRING");
            }
            String key = parseString();
            skipWhitespace();
            if (current() != ':') {
                throw new CapabilityStudioGateAException("OBJECT_MISSING_COLON");
            }
            advance();
            skipWhitespace();
            Object value = parseValue(depth + 1);
            if (result.containsKey(key)) {
                throw new CapabilityStudioGateAException("DUPLICATE_MEMBER:" + key);
            }
            result.put(key, value);
            skipWhitespace();
            if (current() == '}') {
                advance();
                return result;
            } else if (current() == ',') {
                advance();
                skipWhitespace();
                if (current() == '}') {
                    throw new CapabilityStudioGateAException("TRAILING_OR_INVALID");
                }
            } else {
                throw new CapabilityStudioGateAException("TRAILING_OR_INVALID");
            }
        }
    }

    private List<Object> parseArray(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new CapabilityStudioGateAException("DEPTH_LIMIT_EXCEEDED");
        }
        List<Object> result = new ArrayList<>();
        countNode();
        advance();
        skipWhitespace();
        if (current() == ']') {
            advance();
            return result;
        }
        while (true) {
            Object value = parseValue(depth + 1);
            result.add(value);
            skipWhitespace();
            if (current() == ']') {
                advance();
                return result;
            } else if (current() == ',') {
                advance();
                skipWhitespace();
                if (current() == ']') {
                    throw new CapabilityStudioGateAException("TRAILING_OR_INVALID");
                }
            } else {
                throw new CapabilityStudioGateAException("TRAILING_OR_INVALID");
            }
        }
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        countNode();
        advance(); // skip opening quote

        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') {
                advance();
                return sb.toString();
            } else if (c == '\\') {
                advance();
                if (pos >= json.length()) {
                    throw new CapabilityStudioGateAException("UNCLOSED_STRING");
                }
                char escaped = json.charAt(pos);
                switch (escaped) {
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u': {
                        if (pos + 4 > json.length()) {
                            throw new CapabilityStudioGateAException("INVALID_UNICODE_ESCAPE");
                        }
                        String hex1 = json.substring(pos + 1, pos + 5);
                        int cp1;
                        try {
                            cp1 = Integer.parseInt(hex1, 16);
                        } catch (NumberFormatException e) {
                            throw new CapabilityStudioGateAException("INVALID_UNICODE_ESCAPE");
                        }
                        boolean isFirstHigh = (cp1 >= 0xD800 && cp1 <= 0xDBFF);
                        boolean isFirstLow  = (cp1 >= 0xDC00 && cp1 <= 0xDFFF);
                        if (isFirstHigh || isFirstLow) {
                            int afterFirst = pos + 5;
                            if (afterFirst + 6 > json.length()) {
                                throw new CapabilityStudioGateAException("INVALID_SURROGATE:" + hex1);
                            }
                            if (json.charAt(afterFirst)     == '\\'
                                    && json.charAt(afterFirst + 1) == 'u') {
                                String hex2;
                                try {
                                    hex2 = json.substring(afterFirst + 2, afterFirst + 6);
                                } catch (Exception e) {
                                    throw new CapabilityStudioGateAException("INVALID_SURROGATE:" + hex1);
                                }
                                int cp2;
                                try {
                                    cp2 = Integer.parseInt(hex2, 16);
                                } catch (NumberFormatException e) {
                                    throw new CapabilityStudioGateAException("INVALID_SURROGATE:" + hex1);
                                }
                                boolean isSecondLow = (cp2 >= 0xDC00 && cp2 <= 0xDFFF);
                                if (isFirstHigh && isSecondLow) {
                                    int supplementary = ((cp1 - 0xD800) << 10) + (cp2 - 0xDC00) + 0x10000;
                                    sb.appendCodePoint(supplementary);
                                    if (sb.length() > MAX_STRING_LENGTH) {
                                        throw new CapabilityStudioGateAException("STRING_LENGTH_EXCEEDED:" + sb.length());
                                    }
                                    pos = afterFirst + 6;
                                    continue;
                                }
                                throw new CapabilityStudioGateAException("INVALID_SURROGATE:" + hex1);
                            }
                            throw new CapabilityStudioGateAException("INVALID_SURROGATE:" + hex1);
                        }
                        sb.appendCodePoint(cp1);
                        if (sb.length() > MAX_STRING_LENGTH) {
                            throw new CapabilityStudioGateAException("STRING_LENGTH_EXCEEDED:" + sb.length());
                        }
                        pos += 4;
                        break;
                    }
                    default:
                        throw new CapabilityStudioGateAException("INVALID_ESCAPE:" + escaped);
                }
                if (sb.length() > MAX_STRING_LENGTH) {
                    throw new CapabilityStudioGateAException("STRING_LENGTH_EXCEEDED:" + sb.length());
                }
                advance();
            } else if (c < 0x20) {
                throw new CapabilityStudioGateAException("INVALID_CONTROL:" + (int) c);
            } else if (c >= 0xD800 && c <= 0xDBFF) {
                char next = peek();
                if (next >= 0xDC00 && next <= 0xDFFF) {
                    int supplementary = ((c - 0xD800) << 10) + (next - 0xDC00) + 0x10000;
                    sb.appendCodePoint(supplementary);
                    if (sb.length() > MAX_STRING_LENGTH) {
                        throw new CapabilityStudioGateAException("STRING_LENGTH_EXCEEDED:" + sb.length());
                    }
                    advance();
                    advance();
                } else {
                    throw new CapabilityStudioGateAException("INVALID_SURROGATE_CHAR");
                }
            } else if (c >= 0xDC00 && c <= 0xDFFF) {
                throw new CapabilityStudioGateAException("INVALID_SURROGATE_CHAR");
            } else {
                sb.append(c);
                if (sb.length() > MAX_STRING_LENGTH) {
                    throw new CapabilityStudioGateAException("STRING_LENGTH_EXCEEDED:" + sb.length());
                }
                advance();
            }
        }
        throw new CapabilityStudioGateAException("UNCLOSED_STRING");
    }

    private Boolean parseBoolean() {
        countNode();
        if (current() == 't') {
            if (pos + 4 <= json.length() && json.substring(pos, pos + 4).equals("true")) {
                pos += 4;
                return Boolean.TRUE;
            }
        } else if (current() == 'f') {
            if (pos + 5 <= json.length() && json.substring(pos, pos + 5).equals("false")) {
                pos += 5;
                return Boolean.FALSE;
            }
        }
        throw new CapabilityStudioGateAException("INVALID_BOOLEAN");
    }

    private Object parseNull() {
        countNode();
        if (pos + 4 <= json.length() && json.substring(pos, pos + 4).equals("null")) {
            pos += 4;
            return null;
        }
        throw new CapabilityStudioGateAException("INVALID_NULL");
    }

    private Number parseNumber() {
        countNode();
        int start = pos;
        if (current() == '-') {
            advance();
        }
        if (current() == '0') {
            advance();
        } else if (current() >= '1' && current() <= '9') {
            while (current() >= '0' && current() <= '9') {
                advance();
            }
        } else {
            throw new CapabilityStudioGateAException("INVALID_NUMBER_START");
        }
        if (current() == '.') {
            advance();
            if (!(current() >= '0' && current() <= '9')) {
                throw new CapabilityStudioGateAException("INVALID_FRACTION");
            }
            while (current() >= '0' && current() <= '9') {
                advance();
            }
        }
        if (current() == 'e' || current() == 'E') {
            advance();
            if (current() == '+' || current() == '-') {
                advance();
            }
            if (!(current() >= '0' && current() <= '9')) {
                throw new CapabilityStudioGateAException("INVALID_EXPONENT");
            }
            while (current() >= '0' && current() <= '9') {
                advance();
            }
        }
        if (pos - start > MAX_NUMBER_LENGTH) {
            throw new CapabilityStudioGateAException("NUMBER_LENGTH_EXCEEDED");
        }
        String numStr = json.substring(start, pos);
        if (numStr.equals("-") || numStr.equals(".")) {
            throw new CapabilityStudioGateAException("INVALID_NUMBER:" + numStr);
        }
        try {
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                double d = Double.parseDouble(numStr);
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new CapabilityStudioGateAException("NON_FINITE:" + numStr);
                }
                return Double.valueOf(d);
            } else {
                return Long.parseLong(numStr);
            }
        } catch (NumberFormatException e) {
            throw new CapabilityStudioGateAException("INVALID_NUMBER:" + numStr);
        }
    }
}
