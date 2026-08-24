package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for StrictJsonParser.
 *
 * <p>Covers: UTF-16 surrogate pair handling, node budget via strings,
 * early string length enforcement, and regression guards for duplicate keys,
 * trailing commas, invalid UTF-8.</p>
 */
class StrictJsonParserTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Map<String, Object> parseStr(String json) {
        return StrictJsonParser.parse(json);
    }

    private static Map<String, Object> parseBytes(byte[] raw) {
        return StrictJsonParser.parse(raw);
    }

    private static void assertParseFails(String json, String prefix) {
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(json));
        assertThat(ex.getMessage()).startsWith(prefix);
    }

    private static void assertParseBytesFails(byte[] raw, String prefix) {
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseBytes(raw));
        assertThat(ex.getMessage()).startsWith(prefix);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── Basic valid inputs ───────────────────────────────────────────────

    @Test
    void emptyObject() {
        Map<String, Object> r = parseStr("{}");
        assertThat(r).isEmpty();
    }

    @Test
    void simpleKeyValue() {
        Map<String, Object> r = parseStr("{\"a\":1}");
        assertThat(r).containsEntry("a", 1L);
    }

    // ── Emoji: escaped surrogate pair via JSON backslash-u ─────────────

    @Test
    void acceptsEmojiEscapedSurrogatePair() {
        // JSON escape: backslash-u D83D backslash-u DE00 = GRINNING FACE
        Map<String, Object> r = parseStr("{\"face\":\"\\uD83D\\uDE00\"}");
        assertThat(r.get("face")).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void acceptsMultipleEmojisEscaped() {
        // Two consecutive JSON escape sequences forming supplementary chars
        String json = "{\"a\":\"\\uD83D\\uDE00\\uD83D\\uDE0D\"}";
        Map<String, Object> r = parseStr(json);
        assertThat(r.get("a")).isEqualTo("\uD83D\uDE00\uD83D\uDE0D");
    }

    @Test
    void acceptsMixedEmojiAndAscii() {
        Map<String, Object> r = parseStr("{\"msg\":\"hello \\uD83D\\uDE00 world\"}");
        assertThat(r.get("msg")).isEqualTo("hello \uD83D\uDE00 world");
    }

    // ── Emoji: raw surrogate pair (valid supplementary char) ──────────

    @Test
    void acceptsEmojiViaUtf8Bytes() {
        // The Java string literal contains surrogate pair chars.
        // UTF-8 encoding produces F0 9F 98 80.
        // After CharsetDecoder.decode, the Java String holds surrogate pair.
        String emoji = "\uD83D\uDE00";
        byte[] withEmoji = utf8("{\"face\":\"" + emoji + "\"}");
        Map<String, Object> r = parseBytes(withEmoji);
        assertThat(r.get("face")).isEqualTo(emoji);
    }

    @Test
    void acceptsRawSurrogatePairAsSupplementaryChar() {
        String emoji = "\uD83D\uDE00";
        Map<String, Object> r = parseStr("{\"k\":\"" + emoji + "\"}");
        assertThat(r.get("k")).isEqualTo(emoji);
    }

    @Test
    void surrogatePairInArrayElement() {
        String emoji = "\uD83D\uDE00";
        Map<String, Object> r = parseStr("{\"list\":[\"" + emoji + "\"]}");
        assertThat(r.get("list")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(emoji);
    }

    // ── Lone high surrogate ───────────────────────────────────────────

    @Test
    void rejectsLoneHighSurrogateEscape() {
        // JSON escape: backslash-u D800 = U+D800 (lone high surrogate)
        assertParseFails("{\"k\":\"\\uD800\"}", "INVALID_SURROGATE");
    }

    @Test
    void rejectsLoneHighSurrogateInSourceString() {
        // Raw lone high surrogate character in the Java String passed to parser
        String loneHigh = String.valueOf((char) 0xD800);
        assertParseFails("{\"k\":\"" + loneHigh + "\"}", "INVALID_SURROGATE_CHAR");
    }

    @Test
    void rejectsHighSurrogateFollowedByNonLowEscape() {
        // High surrogate escape followed by non-low-surrogate escape (U+0041 = 'A')
        assertParseFails("{\"k\":\"\\uD800\\u0041\"}", "INVALID_SURROGATE");
    }

    @Test
    void rejectsReversedSurrogateEscape() {
        // Low followed by high in escapes (\uDC00\uD800) is invalid JSON — must be HIGH then LOW
        assertParseFails("{\"k\":\"\\uDC00\\uD800\"}", "INVALID_SURROGATE");
    }

    @Test
    void rejectsTruncatedSecondSurrogateEscape() {
        // \uD800 followed by end-of-input — second escape is truncated, throw INVALID_SURROGATE
        String truncated = "{\"k\":\"\\uD800";
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(truncated));
        assertThat(ex.getMessage()).startsWith("INVALID_SURROGATE");
    }

    // ── Lone low surrogate ────────────────────────────────────────────

    @Test
    void rejectsLoneLowSurrogateEscape() {
        // JSON escape: backslash-u DC00 = U+DC00 (lone low surrogate, no preceding high)
        assertParseFails("{\"k\":\"\\uDC00\"}", "INVALID_SURROGATE");
    }

    @Test
    void rejectsLoneLowSurrogateInSourceString() {
        String loneLow = String.valueOf((char) 0xDC00);
        assertParseFails("{\"k\":\"" + loneLow + "\"}", "INVALID_SURROGATE_CHAR");
    }

    // ── Node budget: strings counted as nodes ────────────────────────

    @Test
    void nodeBudgetExceededByStrings() {
        // 1 root object + 100_000 string elements = 100_001 nodes.
        // MAX_NODES = 100_000, so this must exceed budget.
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":\"test\",\"big\":[");
        sb.append("\"s\""); // first element
        for (int i = 1; i < 100_000; i++) {
            sb.append(",\"s\"");
        }
        sb.append("]}");
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(sb.toString()));
        assertThat(ex.getMessage()).startsWith("NODE_BUDGET_EXCEEDED");
    }

    @Test
    void nodeBudgetOkWithExactlyMaxNodes() {
        // Exactly 100_000 nodes: 1 root + 1 array + 1 schemaVersion + 1 "test" + 99_996 array strings = 100_000
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":\"test\",\"big\":[");
        sb.append("\"s\"");
        for (int i = 1; i < 99_995; i++) {
            sb.append(",\"s\"");
        }
        sb.append("]}");
        Map<String, Object> r = parseStr(sb.toString());
        assertThat(r).containsKey("big");
    }

    // ── String length: early enforcement ───────────────────────────────

    @Test
    void rejectsStringExceedingMaxLength() {
        int over = 10;
        int targetLen = StrictJsonParser.MAX_STRING_LENGTH + over;
        char[] chars = new char[targetLen];
        java.util.Arrays.fill(chars, 'x');
        String longStr = new String(chars);
        String json = "{\"k\":\"" + longStr + "\"}";
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(json));
        assertThat(ex.getMessage()).startsWith("STRING_LENGTH_EXCEEDED");
    }

    @Test
    void longStringFailsBeforeClosingQuote() {
        // String exceeds MAX_STRING_LENGTH before the closing quote.
        // Parser must throw STRING_LENGTH_EXCEEDED, not UNCLOSED_STRING.
        int targetLen = StrictJsonParser.MAX_STRING_LENGTH + 50;
        char[] chars = new char[targetLen];
        java.util.Arrays.fill(chars, 'x');
        String longStr = new String(chars);
        String json = "{\"k\":\"" + longStr; // intentionally missing closing quote
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(json));
        assertThat(ex.getMessage()).startsWith("STRING_LENGTH_EXCEEDED");
    }

    @Test
    void acceptsStringAtExactlyMaxLength() {
        int len = StrictJsonParser.MAX_STRING_LENGTH;
        char[] chars = new char[len];
        java.util.Arrays.fill(chars, 'a');
        String exactStr = new String(chars);
        String json = "{\"k\":\"" + exactStr + "\"}";
        Map<String, Object> r = parseStr(json);
        assertThat((String) r.get("k")).hasSize(len);
    }

    // ── Regression: duplicate key ─────────────────────────────────────

    @Test
    void rejectsDuplicateObjectKey() {
        assertParseFails("{\"a\":1,\"a\":2}", "DUPLICATE_MEMBER");
    }

    @Test
    void acceptsDistinctKeys() {
        Map<String, Object> r = parseStr("{\"a\":1,\"b\":2}");
        assertThat(r).hasSize(2).containsEntry("a", 1L).containsEntry("b", 2L);
    }

    // ── Regression: trailing comma ────────────────────────────────────

    @Test
    void rejectsTrailingCommaObject() {
        assertParseFails("{\"a\":1,}", "TRAILING_OR_INVALID");
    }

    @Test
    void rejectsTrailingCommaArray() {
        assertParseFails("{\"a\":[1,]}", "TRAILING_OR_INVALID");
    }

    @Test
    void acceptsTrailingCommaInValidPosition() {
        Map<String, Object> r = parseStr("{\"a\":[1,2,3]}");
        assertThat(r.get("a")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(1L, 2L, 3L);
    }

    // ── Regression: invalid UTF-8 ────────────────────────────────────

    @Test
    void rejectsInvalidUtf8ContinuationByte() {
        // 0x80 is a UTF-8 continuation byte with no leading byte
        byte[] invalid = utf8("{}");
        invalid[1] = (byte) 0x80;
        assertParseBytesFails(invalid, "UTF8_DECODE_ERROR");
    }

    @Test
    void rejectsOverlongUtf8Encoding() {
        // Encode ASCII 'A' (0x41) using overlong 2-byte sequence 0xC0 0x81
        byte[] overlong = new byte[] {
                0x7B, 0x22, 0x43, 0x30, (byte) 0xC0, (byte) 0x81, 0x22, 0x7D
        };
        assertParseBytesFails(overlong, "UTF8_DECODE_ERROR");
    }

    @Test
    void rejectsTruncatedUtf8Sequence() {
        // 3-byte char start (0xE0) with only 1 continuation byte — truncated
        byte[] truncated = new byte[] {0x7B, 0x22, (byte) 0xE0, (byte) 0x80, 0x22, 0x7D};
        assertParseBytesFails(truncated, "UTF8_DECODE_ERROR");
    }

    // ── Regression: control characters ────────────────────────────────

    @Test
    void rejectsBareControlChar() {
        // U+0001 (SOH) — bare control char in JSON string
        String withControl = "{\"k\":\"" + (char) 0x0001 + "\"}";
        assertParseFails(withControl, "INVALID_CONTROL");
    }

    // ── Regression: non-finite numbers ────────────────────────────────

    @Test
    void rejectsNaN() {
        assertParseFails("{\"k\":NaN}", "INVALID_START");
    }

    @Test
    void rejectsInfinity() {
        assertParseFails("{\"k\":Infinity}", "INVALID_START");
    }

    // ── Regression: unclosed string ───────────────────────────────────

    @Test
    void rejectsUnclosedString() {
        assertParseFails("{\"k\":\"open", "UNCLOSED_STRING");
    }

    // ── Regression: invalid escape ───────────────────────────────────

    @Test
    void rejectsInvalidEscape() {
        assertParseFails("{\"k\":\"\\q\"}", "INVALID_ESCAPE");
    }

    // ── Regression: missing colon / comma ────────────────────────────

    @Test
    void rejectsMissingColon() {
        assertParseFails("{\"k\" 1}", "OBJECT_MISSING_COLON");
    }

    // ── Regression: invalid number ───────────────────────────────────

    @Test
    void rejectsInvalidNumber() {
        // "." is not a valid JSON value start → INVALID_START
        assertParseFails("{\"k\":.}", "INVALID_START");
    }

    // ── Depth limit ─────────────────────────────────────────────────

    @Test
    void rejectsDepthLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"a\":");
        for (int i = 0; i < StrictJsonParser.MAX_DEPTH + 5; i++) {
            sb.append("{\"x\":");
        }
        sb.append("0");
        for (int i = 0; i < StrictJsonParser.MAX_DEPTH + 5; i++) {
            sb.append("}");
        }
        sb.append("}");
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr(sb.toString()));
        assertThat(ex.getMessage()).startsWith("DEPTH_LIMIT_EXCEEDED");
    }

    // ── Root must be object ──────────────────────────────────────────

    @Test
    void rejectsRootNotObject() {
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr("[1,2,3]"));
        assertThat(ex.getMessage()).startsWith("ROOT_MUST_BE_OBJECT");
    }

    @Test
    void rejectsTrailingContent() {
        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> parseStr("{}  extra"));
        assertThat(ex.getMessage()).startsWith("TRAILING_CONTENT");
    }

    // ── Number length limit ──────────────────────────────────────────

    @Test
    void rejectsNumberExceedingMaxLength() {
        char[] digits = new char[StrictJsonParser.MAX_NUMBER_LENGTH + 100];
        java.util.Arrays.fill(digits, '1');
        String longNum = new String(digits);
        assertParseFails("{\"k\":" + longNum + "}", "NUMBER_LENGTH_EXCEEDED");
    }

    // ── Unicode escapes: basic ───────────────────────────────────────

    @Test
    void acceptsUnicodeEscapeBasic() {
        // backslash-u 0041 = 'A'
        Map<String, Object> r = parseStr("{\"k\":\"\\u0041\"}");
        assertThat(r.get("k")).isEqualTo("A");
    }

    @Test
    void acceptsUnicodeEscapeMixed() {
        // Three consecutive unicode escapes
        Map<String, Object> r = parseStr("{\"k\":\"\\u0041\\u0042\\u0043\"}");
        assertThat(r.get("k")).isEqualTo("ABC");
    }
}
