package com.leanowtech.bloge.gateway.testing.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Version-independent business interpretation of a logical resource response. */
public record ResponseSemantics(
        SuccessCondition successCondition,
        ErrorClassification errorClassification,
        Idempotency idempotency,
        Retryability retryability
) {
    private static final int MAX_EXPRESSION_LENGTH = 2_048;

    public ResponseSemantics {
        successCondition = successCondition == null ? SuccessCondition.unknown() : successCondition;
        errorClassification = errorClassification == null
                ? ErrorClassification.unknown() : errorClassification;
        idempotency = idempotency == null ? Idempotency.UNKNOWN : idempotency;
        retryability = retryability == null ? Retryability.UNKNOWN : retryability;
    }

    public static ResponseSemantics unknown() {
        return new ResponseSemantics(SuccessCondition.unknown(), ErrorClassification.unknown(),
                Idempotency.UNKNOWN, Retryability.UNKNOWN);
    }

    public static ResponseSemantics confirmed(String successExpression,
                                              Map<String, List<String>> errorCategories,
                                              Idempotency idempotency,
                                              Retryability retryability) {
        return new ResponseSemantics(SuccessCondition.confirmed(successExpression),
                ErrorClassification.confirmed(errorCategories), idempotency, retryability);
    }

    /** @return true when any business semantic is unknown, merely projected, or otherwise unconfirmed */
    public boolean requiresReview() {
        return successCondition.knowledge() != Knowledge.CONFIRMED
                || errorClassification.knowledge() != Knowledge.CONFIRMED
                || idempotency == Idempotency.UNKNOWN
                || retryability == Retryability.UNKNOWN;
    }

    /** Whether a semantic value is absent, directly projected, or explicitly confirmed. */
    public enum Knowledge { UNKNOWN, PROJECTED, CONFIRMED }

    public enum Idempotency { UNKNOWN, IDEMPOTENT, NON_IDEMPOTENT }

    public enum Retryability { UNKNOWN, RETRYABLE, NON_RETRYABLE, CONDITIONAL }

    public record SuccessCondition(Knowledge knowledge, String expression) {
        public SuccessCondition {
            knowledge = knowledge == null ? Knowledge.UNKNOWN : knowledge;
            expression = expression == null ? "" : expression.trim();
            if (knowledge == Knowledge.UNKNOWN && !expression.isEmpty()
                    || knowledge != Knowledge.UNKNOWN && expression.isEmpty()
                    || expression.length() > MAX_EXPRESSION_LENGTH) {
                throw LogicalResourceContractException.invalid();
            }
        }

        public static SuccessCondition unknown() {
            return new SuccessCondition(Knowledge.UNKNOWN, "");
        }

        public static SuccessCondition confirmed(String expression) {
            return new SuccessCondition(Knowledge.CONFIRMED, expression);
        }

        public static SuccessCondition projected(String expression) {
            return new SuccessCondition(Knowledge.PROJECTED, expression);
        }
    }

    public record ErrorClassification(Knowledge knowledge, Map<String, List<String>> categories) {
        public ErrorClassification {
            knowledge = knowledge == null ? Knowledge.UNKNOWN : knowledge;
            TreeMap<String, List<String>> normalized = new TreeMap<>();
            if (categories != null) {
                categories.forEach((category, codes) -> {
                    String normalizedCategory = normalizedToken(category);
                    List<String> normalizedCodes = codes == null ? List.of() : codes.stream()
                            .map(ErrorClassification::normalizedToken)
                            .distinct()
                            .sorted()
                            .toList();
                    normalized.put(normalizedCategory, normalizedCodes);
                });
            }
            if (knowledge == Knowledge.UNKNOWN && !normalized.isEmpty()) {
                throw LogicalResourceContractException.invalid();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            normalized.forEach((key, value) -> copy.put(key,
                    Collections.unmodifiableList(new ArrayList<>(value))));
            categories = Collections.unmodifiableMap(copy);
        }

        public static ErrorClassification unknown() {
            return new ErrorClassification(Knowledge.UNKNOWN, Map.of());
        }

        public static ErrorClassification confirmed(Map<String, List<String>> categories) {
            return new ErrorClassification(Knowledge.CONFIRMED, categories);
        }

        private static String normalizedToken(String value) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw LogicalResourceContractException.invalid();
            }
            return value.trim();
        }
    }
}
