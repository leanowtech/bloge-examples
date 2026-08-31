package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded matcher for equivalent H2/PostgreSQL string-literal CHECK clauses. */
public final class CheckConstraintDefinition {
    private CheckConstraintDefinition() { }

    /** Accepts only an exact literal set expressed as IN, PostgreSQL ANY/ARRAY, or equality. */
    public static boolean exactLiteralSet(String checkClause, String targetColumn,
                                          Set<String> allowedLiterals) {
        if (checkClause == null || targetColumn == null || allowedLiterals == null
                || allowedLiterals.isEmpty()) return false;
        String clause = stripOuterParens(checkClause.replaceAll("\\s+", ""));
        String stringCast = "(?:::(?:text|varchar|charactervarying))*";
        String arrayStringCast = "(?:::(?:text|varchar|charactervarying)\\[\\])*";
        String column = "\\(*\\\"?" + Pattern.quote(targetColumn) + "\\\"?\\)*" + stringCast;
        String literal = "'([^']*)'" + stringCast;

        Matcher in = Pattern.compile("(?i)^(" + column + ")in\\((.*)\\)$").matcher(clause);
        if (in.matches() && exact(parseLiteralList(in.group(2), literal), allowedLiterals)) return true;

        Matcher any = Pattern.compile("(?i)^(" + column + ")=any\\(*array\\[(.*?)\\](?:(?:"
                + arrayStringCast + ")|\\))*(?:\\))$").matcher(clause);
        if (any.matches() && exact(parseLiteralList(any.group(2), literal), allowedLiterals)) return true;

        Matcher equality = Pattern.compile("(?i)^(" + column + ")=(" + literal + ")$").matcher(clause);
        return equality.matches() && allowedLiterals.size() == 1
                && allowedLiterals.contains(equality.group(3));
    }

    private static List<String> parseLiteralList(String body, String literalPattern) {
        if (body.isEmpty()) return List.of();
        Pattern pattern = Pattern.compile(literalPattern, Pattern.CASE_INSENSITIVE);
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < body.length()) {
            Matcher literal = pattern.matcher(body);
            literal.region(offset, body.length());
            if (!literal.lookingAt()) return List.of();
            values.add(literal.group(1));
            offset = literal.end();
            if (offset == body.length()) break;
            if (body.charAt(offset) != ',') return List.of();
            offset++;
        }
        return values;
    }

    private static boolean exact(List<String> actual, Set<String> expected) {
        return actual.size() == expected.size() && new HashSet<>(actual).equals(expected);
    }

    /** Removes only parentheses that enclose the complete expression. */
    public static String stripOuterParens(String clause) {
        while (clause.startsWith("(") && clause.endsWith(")") && enclosesWholeClause(clause)) {
            clause = clause.substring(1, clause.length() - 1);
        }
        return clause;
    }

    private static boolean enclosesWholeClause(String clause) {
        int depth = 0;
        for (int index = 0; index < clause.length(); index++) {
            char current = clause.charAt(index);
            if (current == '(') depth++;
            if (current == ')' && --depth == 0 && index < clause.length() - 1) return false;
            if (depth < 0) return false;
        }
        return depth == 0;
    }
}
