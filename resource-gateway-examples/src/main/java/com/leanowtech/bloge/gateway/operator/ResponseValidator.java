package com.leanowtech.bloge.gateway.operator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates an HTTP response against a {@link ResponseProtocol} to determine
 * whether the call succeeded or failed.
 *
 * <p>Uses pattern matching on the sealed {@code ResponseProtocol} hierarchy to dispatch
 * to the appropriate validation logic. For {@link ResponseProtocol.BlgeExpression}, delegates
 * to {@link BlgeExpressionEvaluator} for custom expression evaluation.
 */
public class ResponseValidator {

    private final BlgeExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    /**
     * @param evaluator the bloge expression evaluator (needed for {@code BlgeExpression} protocol)
     */
    public ResponseValidator(BlgeExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Result of response validation.
     *
     * @param success      whether the response is considered successful
     * @param errorMessage human-readable error message, or {@code null} if successful
     */
    public record ValidationResult(boolean success, String errorMessage) {

        /** Successful validation result. */
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        /** Failed validation result with an error message. */
        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * Validates the given HTTP response against the specified protocol.
     *
     * @param httpResponse the HTTP response to validate
     * @param protocol     the response protocol that defines success criteria
     * @return a {@link ValidationResult} indicating success or failure
     */
    public ValidationResult validate(HttpResponseOutput httpResponse, ResponseProtocol protocol) {
        return switch (protocol) {
            case ResponseProtocol.HttpStatus _ ->
                validateHttpStatus(httpResponse);

            case ResponseProtocol.BodyCode bodyCode ->
                validateBodyCode(httpResponse, bodyCode);

            case ResponseProtocol.BodyFlag bodyFlag ->
                validateBodyFlag(httpResponse, bodyFlag);

            case ResponseProtocol.StatusCodes statusCodes ->
                validateStatusCodes(httpResponse, statusCodes);

            case ResponseProtocol.BlgeExpression blgeExpr ->
                validateBlgeExpression(httpResponse, blgeExpr);
        };
    }

    private ValidationResult validateHttpStatus(HttpResponseOutput response) {
        if (response.isSuccess()) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail("HTTP %d".formatted(response.statusCode()));
    }

    private ValidationResult validateBodyCode(HttpResponseOutput response, ResponseProtocol.BodyCode bodyCode) {
        Object parsed = parseBody(response.body());
        if (parsed == null) {
            return ValidationResult.fail("Empty or unparseable response body");
        }

        Object codeValue = extractPath(parsed, bodyCode.codePath());
        if (codeValue == null) {
            return ValidationResult.fail("Code field '%s' not found in response body".formatted(bodyCode.codePath()));
        }

        if (bodyCode.successValues().contains(codeValue)) {
            return ValidationResult.ok();
        }

        // Try numeric comparison: the JSON parser may return Integer while successValues has Long etc.
        boolean matched = bodyCode.successValues().stream().anyMatch(sv -> numericEquals(sv, codeValue));
        if (matched) {
            return ValidationResult.ok();
        }

        String errorMessage = bodyCode.messagePath() != null
            ? String.valueOf(extractPath(parsed, bodyCode.messagePath()))
            : "code=%s".formatted(codeValue);
        return ValidationResult.fail(errorMessage);
    }

    private ValidationResult validateBodyFlag(HttpResponseOutput response, ResponseProtocol.BodyFlag bodyFlag) {
        Object parsed = parseBody(response.body());
        if (parsed == null) {
            return ValidationResult.fail("Empty or unparseable response body");
        }

        Object flagValue = extractPath(parsed, bodyFlag.flagPath());
        if (flagValue instanceof Boolean b && b) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail("Flag '%s' is not true (was: %s)".formatted(bodyFlag.flagPath(), flagValue));
    }

    private ValidationResult validateStatusCodes(HttpResponseOutput response, ResponseProtocol.StatusCodes statusCodes) {
        if (statusCodes.successCodes().contains(response.statusCode())) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail("HTTP %d not in accepted codes %s".formatted(
            response.statusCode(), statusCodes.successCodes()));
    }

    private ValidationResult validateBlgeExpression(HttpResponseOutput response, ResponseProtocol.BlgeExpression blgeExpr) {
        Map<String, Object> context = buildExpressionContext(response);

        boolean success = evaluator.evaluateBoolean(blgeExpr.successExpr(), context);
        if (success) {
            return ValidationResult.ok();
        }

        String message = blgeExpr.messageExpr() != null
            ? evaluator.evaluateString(blgeExpr.messageExpr(), context)
            : "Custom expression evaluated to false";
        return ValidationResult.fail(message != null ? message : "Custom expression evaluated to false");
    }

    private Map<String, Object> buildExpressionContext(HttpResponseOutput response) {
        Map<String, Object> context = new HashMap<>();
        context.put("statusCode", response.statusCode());
        context.put("headers", response.headers());

        Object parsed = parseBody(response.body());
        context.put("body", parsed != null ? parsed : Map.of());
        return context;
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object extractPath(Object root, String dotPath) {
        if (dotPath == null || dotPath.isBlank()) {
            return root;
        }
        String[] segments = dotPath.split("\\.");
        Object current = root;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static boolean numericEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        // Also try string comparison for mixed types like "0" vs 0
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
