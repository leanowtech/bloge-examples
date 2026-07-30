package com.leanowtech.bloge.gateway.visual.authoring.parse;

import java.util.List;
import java.util.Set;

/**
 * Bounded parser for the non-executable compact type grammar.
 */
public final class CompactTypeParser {

    public static final String GRAMMAR_VERSION = "bloge.compactType.v1";
    public static final int MAX_SOURCE_LENGTH = 256;
    public static final int MAX_ARRAY_DEPTH = 8;

    private static final Set<String> PRIMITIVES = Set.of(
            "any", "unknown", "string", "number", "integer", "boolean",
            "object", "json", "date", "datetime"
    );

    public ParseResult parse(String source) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) {
            return ParseResult.invalid(issue("Compact type is required.", 0));
        }
        if (value.length() > MAX_SOURCE_LENGTH) {
            return ParseResult.invalid(issue(
                    "Compact type exceeds the %d character limit.".formatted(MAX_SOURCE_LENGTH),
                    MAX_SOURCE_LENGTH));
        }

        int cursor = 0;
        if (!identifierStart(value.charAt(cursor))) {
            return ParseResult.invalid(issue("Compact type must begin with an identifier.", cursor));
        }
        cursor++;
        while (cursor < value.length() && identifierPart(value.charAt(cursor))) {
            cursor++;
        }
        String baseName = value.substring(0, cursor);
        int arrayDepth = 0;
        while (cursor + 1 < value.length()
                && value.charAt(cursor) == '['
                && value.charAt(cursor + 1) == ']') {
            arrayDepth++;
            if (arrayDepth > MAX_ARRAY_DEPTH) {
                return ParseResult.invalid(issue(
                        "Compact type array nesting exceeds the %d level limit.".formatted(MAX_ARRAY_DEPTH),
                        cursor));
            }
            cursor += 2;
        }
        boolean nullable = false;
        if (cursor < value.length() && value.charAt(cursor) == '?') {
            nullable = true;
            cursor++;
        }
        if (cursor != value.length()) {
            return ParseResult.invalid(issue(
                    "Unsupported compact type token at offset %d.".formatted(cursor),
                    cursor));
        }
        return ParseResult.valid(new TypeExpression(
                baseName,
                PRIMITIVES.contains(baseName),
                arrayDepth,
                nullable
        ));
    }

    public static boolean primitive(String name) {
        return name != null && PRIMITIVES.contains(name);
    }

    public static List<String> primitives() {
        return PRIMITIVES.stream().sorted().toList();
    }

    private static ParseIssue issue(String message, int offset) {
        return new ParseIssue("RG.AUTHORING.TYPE_INVALID", message, Math.max(0, offset));
    }

    private static boolean identifierStart(char value) {
        return value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean identifierPart(char value) {
        return identifierStart(value) || value >= '0' && value <= '9';
    }

    public record TypeExpression(
            String baseName,
            boolean primitive,
            int arrayDepth,
            boolean nullable
    ) {
        public String canonicalText() {
            return baseName + "[]".repeat(arrayDepth) + (nullable ? "?" : "");
        }
    }

    public record ParseIssue(
            String code,
            String message,
            int offset
    ) {
    }

    public record ParseResult(
            boolean valid,
            TypeExpression expression,
            List<ParseIssue> issues
    ) {
        public ParseResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
            valid = valid && expression != null && issues.isEmpty();
        }

        static ParseResult valid(TypeExpression expression) {
            return new ParseResult(true, expression, List.of());
        }

        static ParseResult invalid(ParseIssue issue) {
            return new ParseResult(false, null, List.of(issue));
        }
    }
}
