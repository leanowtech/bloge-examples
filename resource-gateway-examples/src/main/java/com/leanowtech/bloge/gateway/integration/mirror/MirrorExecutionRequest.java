package com.leanowtech.bloge.gateway.integration.mirror;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict public command for executing one previously compiled mirror plan.
 *
 * <p>Enterprise scope, purpose, fixture selection, and runtime policy are deliberately absent:
 * they are resolved from authenticated server state and the sealed plan. The context is detached
 * recursively so a caller cannot mutate an admitted request while its fingerprint is being used
 * for durable idempotency.</p>
 *
 * @param schemaVersion execution-command protocol version
 * @param requestId stable caller idempotency identity inside one enterprise scope
 * @param planId previously persisted mirror plan identity
 * @param expectedPlanFingerprint exact plan generation reviewed by the caller
 * @param context business input context; server-owned BLOGE scope keys are added after admission
 */
public record MirrorExecutionRequest(
        String schemaVersion,
        String requestId,
        String planId,
        String expectedPlanFingerprint,
        Map<String, Object> context
) {
    /** Current protected execution-command protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorExecutionRequest.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates immutable plan coordinates and recursively detaches JSON-compatible context. */
    public MirrorExecutionRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported mirror execution request schemaVersion");
        }
        requestId = identifier(requestId, "requestId");
        planId = identifier(planId, "planId");
        expectedPlanFingerprint = required(expectedPlanFingerprint,
                "expectedPlanFingerprint");
        if (!FINGERPRINT.matcher(expectedPlanFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "expectedPlanFingerprint must be canonical SHA-256");
        }
        context = immutableMap(context == null ? Map.of() : context, "context");
    }

    /** Keeps business context values out of generic logs. */
    @Override
    public String toString() {
        return "MirrorExecutionRequest[requestId=" + requestId + ", planId=" + planId
                + ", expectedPlanFingerprint=" + expectedPlanFingerprint
                + ", contextEntries=" + context.size() + "]";
    }

    private static Map<String, Object> immutableMap(Map<?, ?> values, String path) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(path + " keys must be non-blank strings");
            }
            result.put(text, immutableValue(value, path + "." + text));
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, path);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> result = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                result.add(immutableValue(list.get(index), path + "[" + index + "]"));
            }
            return java.util.Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException(path + " contains a non-JSON value");
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
