package com.leanowtech.bloge.gateway.visual.authoring.parse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded parser for Quick authoring function signatures.
 */
public final class FunctionSignatureParser {

    public static final String GRAMMAR_VERSION = "bloge.functionSignature.v1";
    public static final int MAX_SOURCE_LENGTH = 1024;
    public static final int MAX_PARAMETERS = 64;

    private final CompactTypeParser typeParser;

    public FunctionSignatureParser() {
        this(new CompactTypeParser());
    }

    public FunctionSignatureParser(CompactTypeParser typeParser) {
        this.typeParser = typeParser == null ? new CompactTypeParser() : typeParser;
    }

    public ParseResult parse(String source) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) {
            return ParseResult.invalid(issue("Function signature is required.", 0));
        }
        if (value.length() > MAX_SOURCE_LENGTH) {
            return ParseResult.invalid(issue(
                    "Function signature exceeds the %d character limit.".formatted(MAX_SOURCE_LENGTH),
                    MAX_SOURCE_LENGTH));
        }

        Cursor cursor = new Cursor(value);
        List<ParseIssue> issues = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        cursor.skipWhitespace();
        if (!cursor.consume('(')) {
            return ParseResult.invalid(issue("Function signature must begin with '('.", cursor.position()));
        }
        cursor.skipWhitespace();
        if (!cursor.peek(')')) {
            while (!cursor.end()) {
                if (parameters.size() >= MAX_PARAMETERS) {
                    issues.add(issue(
                            "Function signature exceeds the %d parameter limit.".formatted(MAX_PARAMETERS),
                            cursor.position()));
                    break;
                }
                int parameterOffset = cursor.position();
                boolean variadic = cursor.consume("...");
                String name = cursor.identifier();
                if (name.isBlank()) {
                    issues.add(issue("Function parameter name is required.", cursor.position()));
                    break;
                }
                boolean optional = cursor.consume('?');
                cursor.skipWhitespace();
                if (!cursor.consume(':')) {
                    issues.add(issue("Function parameter '%s' must declare ':' and a type.".formatted(name),
                            cursor.position()));
                    break;
                }
                cursor.skipWhitespace();
                int typeOffset = cursor.position();
                String typeSource = cursor.readUntil(',', ')').trim();
                if (typeSource.indexOf('=') >= 0) {
                    issues.add(issue("Default value expressions are not supported in function signatures.",
                            typeOffset + typeSource.indexOf('=')));
                }
                CompactTypeParser.ParseResult typeResult = typeParser.parse(typeSource);
                if (!typeResult.valid()) {
                    CompactTypeParser.ParseIssue typeIssue = typeResult.issues().getFirst();
                    issues.add(issue(typeIssue.message(), typeOffset + typeIssue.offset()));
                }
                if (!names.add(name)) {
                    issues.add(issue("Function signature declares duplicate parameter '%s'.".formatted(name),
                            parameterOffset));
                }
                if (variadic && optional) {
                    issues.add(issue("Variadic parameter '%s' cannot also be optional.".formatted(name),
                            parameterOffset));
                }
                if (typeResult.valid()) {
                    parameters.add(new Parameter(name, typeResult.expression(), optional, variadic));
                }
                cursor.skipWhitespace();
                if (cursor.consume(',')) {
                    if (variadic) {
                        issues.add(issue("Variadic parameter '%s' must be the final parameter.".formatted(name),
                                parameterOffset));
                    }
                    cursor.skipWhitespace();
                    continue;
                }
                break;
            }
        }
        cursor.skipWhitespace();
        if (!cursor.consume(')')) {
            issues.add(issue("Function signature parameter list must end with ')'.", cursor.position()));
        }
        cursor.skipWhitespace();
        if (!cursor.consume("->")) {
            issues.add(issue("Function signature must declare '->' and a return type.", cursor.position()));
        }
        cursor.skipWhitespace();
        int returnOffset = cursor.position();
        String returnSource = cursor.remaining().trim();
        CompactTypeParser.ParseResult returnType = typeParser.parse(returnSource);
        if (!returnType.valid()) {
            CompactTypeParser.ParseIssue typeIssue = returnType.issues().getFirst();
            issues.add(issue(typeIssue.message(), returnOffset + typeIssue.offset()));
        }
        if (!issues.isEmpty()) {
            return new ParseResult(false, null, List.copyOf(issues));
        }

        Signature signature = new Signature(parameters, returnType.expression());
        return new ParseResult(true, signature, List.of());
    }

    private static ParseIssue issue(String message, int offset) {
        return new ParseIssue("RG.AUTHORING.SIGNATURE_INVALID", message, Math.max(0, offset));
    }

    public record Parameter(
            String name,
            CompactTypeParser.TypeExpression type,
            boolean optional,
            boolean variadic
    ) {
        String canonicalText() {
            return (variadic ? "..." : "") + name + (optional ? "?" : "")
                    + ": " + type.canonicalText();
        }
    }

    public record Signature(
            List<Parameter> parameters,
            CompactTypeParser.TypeExpression returns
    ) {
        public Signature {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        public String normalized() {
            return "(" + parameters.stream()
                    .map(Parameter::canonicalText)
                    .collect(java.util.stream.Collectors.joining(", "))
                    + ") -> " + returns.canonicalText();
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
            Signature signature,
            List<ParseIssue> issues
    ) {
        public ParseResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
            valid = valid && signature != null && issues.isEmpty();
        }

        static ParseResult invalid(ParseIssue issue) {
            return new ParseResult(false, null, List.of(issue));
        }
    }

    private static final class Cursor {
        private final String value;
        private int position;

        private Cursor(String value) {
            this.value = value;
        }

        private int position() {
            return position;
        }

        private boolean end() {
            return position >= value.length();
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(value.charAt(position))) {
                position++;
            }
        }

        private boolean peek(char expected) {
            return !end() && value.charAt(position) == expected;
        }

        private boolean consume(char expected) {
            if (!peek(expected)) {
                return false;
            }
            position++;
            return true;
        }

        private boolean consume(String expected) {
            if (!value.startsWith(expected, position)) {
                return false;
            }
            position += expected.length();
            return true;
        }

        private String identifier() {
            skipWhitespace();
            int start = position;
            if (end() || !identifierStart(value.charAt(position))) {
                return "";
            }
            position++;
            while (!end() && identifierPart(value.charAt(position))) {
                position++;
            }
            return value.substring(start, position);
        }

        private String readUntil(char first, char second) {
            int start = position;
            while (!end() && value.charAt(position) != first && value.charAt(position) != second) {
                position++;
            }
            return value.substring(start, position);
        }

        private String remaining() {
            if (end()) {
                return "";
            }
            String remainder = value.substring(position);
            position = value.length();
            return remainder;
        }

        private static boolean identifierStart(char candidate) {
            return candidate == '_' || candidate >= 'A' && candidate <= 'Z'
                    || candidate >= 'a' && candidate <= 'z';
        }

        private static boolean identifierPart(char candidate) {
            return identifierStart(candidate) || candidate >= '0' && candidate <= '9';
        }
    }
}
