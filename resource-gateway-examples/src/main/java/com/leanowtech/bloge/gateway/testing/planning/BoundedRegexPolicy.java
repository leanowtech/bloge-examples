package com.leanowtech.bloge.gateway.testing.planning;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Defines the auditable, bounded regular-expression subset accepted by fixture selectors.
 *
 * <p>Java regular expressions are backtracking programs rather than inert data. The test-control
 * protocol therefore excludes grouping, alternation, look-around, embedded flags, and
 * backreferences, which are common sources of super-linear behavior. Character classes, anchors,
 * escaped literals, and ordinary quantifiers remain available. Input length is bounded separately
 * by the runtime matcher.</p>
 */
public final class BoundedRegexPolicy {

    /** Maximum serialized pattern length admitted by v1. */
    public static final int MAX_PATTERN_LENGTH = 256;

    /** Maximum candidate-input length admitted by v1. */
    public static final int MAX_INPUT_LENGTH = 4096;

    private BoundedRegexPolicy() {
    }

    /**
     * Returns a diagnostic when the expression is invalid or outside the safe v1 subset.
     *
     * @param expression user-supplied expression
     * @return empty string when accepted, otherwise a stable human-readable diagnostic
     */
    public static String rejectionReason(String expression) {
        if (expression == null || expression.isEmpty()) {
            return "regular expression must not be empty";
        }
        if (expression.length() > MAX_PATTERN_LENGTH) {
            return "regular expression exceeds " + MAX_PATTERN_LENGTH + " characters";
        }
        boolean escaped = false;
        boolean inClass = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (escaped) {
                if (Character.isDigit(current) || current == 'k') {
                    return "backreferences are not allowed";
                }
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[') {
                inClass = true;
                continue;
            }
            if (current == ']') {
                inClass = false;
                continue;
            }
            if (!inClass && (current == '(' || current == ')' || current == '|')) {
                return "grouping, look-around, embedded flags, and alternation are not allowed";
            }
        }
        try {
            Pattern.compile(expression);
            return "";
        } catch (PatternSyntaxException exception) {
            return "invalid regular expression: " + exception.getDescription();
        }
    }
}
