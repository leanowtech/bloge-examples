package com.leanowtech.bloge.gateway.visual.runtime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative, bounded sanitizer for payloads persisted with visual run history.
 */
public final class VisualPayloadSanitizer {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_COLLECTION_SIZE = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[a-z0-9._~+/=-]+");
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|secret|token|authorization|api[_-]?key)(\\s*[:=]\\s*)"
                    + "(?:(?:bearer|basic)\\s+)?([^\\s,;&]+)");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "accesstoken", "refreshtoken", "authorization",
            "cookie", "setcookie", "apikey", "credential", "privatekey", "email", "phone", "ssn",
            "nationalid", "passport"
    );

    private VisualPayloadSanitizer() {
    }

    public static Capture capture(Map<String, Object> context, Object output, Map<String, Object> results) {
        return capture(context, output, results, Map.of());
    }

    public static Capture capture(Map<String, Object> context,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts) {
        State state = new State();
        Map<String, Object> sanitizedContext = asStringMap(sanitize(context == null ? Map.of() : context,
                "/context", 0, state));
        Object sanitizedOutput = sanitize(output, "/output", 0, state);
        Map<String, Object> sanitizedResults = asStringMap(sanitize(results == null ? Map.of() : results,
                "/results", 0, state));
        Map<String, List<VisualNodeExecutionAttempt>> sanitizedAttempts = sanitizeAttempts(nodeAttempts, state);
        return new Capture(sanitizedContext, sanitizedOutput, sanitizedResults, sanitizedAttempts,
                new VisualPayloadRedactionManifest("", state.redactedPaths.size(), state.truncated,
                        state.redactedPaths));
    }

    private static Map<String, List<VisualNodeExecutionAttempt>> sanitizeAttempts(
            Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
            State state) {
        if (nodeAttempts == null || nodeAttempts.isEmpty()) {
            return Map.of();
        }
        Map<String, List<VisualNodeExecutionAttempt>> sanitized = new LinkedHashMap<>();
        nodeAttempts.forEach((nodeId, attempts) -> {
            List<VisualNodeExecutionAttempt> safeAttempts = attempts == null ? List.of() : attempts;
            List<VisualNodeExecutionAttempt> captured = new ArrayList<>();
            for (int index = 0; index < Math.min(safeAttempts.size(), MAX_COLLECTION_SIZE); index++) {
                VisualNodeExecutionAttempt attempt = safeAttempts.get(index);
                if (attempt == null) {
                    continue;
                }
                String basePath = "/nodeAttempts/" + escape(nodeId) + "/" + index;
                captured.add(new VisualNodeExecutionAttempt(
                        attempt.attempt(),
                        sanitize(attempt.input(), basePath + "/input", 0, state),
                        sanitize(attempt.output(), basePath + "/output", 0, state),
                        attempt.status(), attempt.startedAt(), attempt.elapsedMs(), attempt.errorType(),
                        String.valueOf(sanitize(attempt.errorMessage(), basePath + "/errorMessage", 0, state))
                ));
            }
            if (safeAttempts.size() > MAX_COLLECTION_SIZE) {
                state.truncated = true;
            }
            sanitized.put(nodeId, List.copyOf(captured));
        });
        return sanitized;
    }

    private static Object sanitize(Object value, String path, int depth, State state) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            state.truncated = true;
            return "[TRUNCATED]";
        }
        if (value instanceof CharSequence text) {
            String sanitized = sanitizeText(text.toString());
            if (!sanitized.equals(text.toString())) {
                state.redactedPaths.add(path);
            }
            if (sanitized.length() <= MAX_STRING_LENGTH) {
                return sanitized;
            }
            state.truncated = true;
            return sanitized.substring(0, MAX_STRING_LENGTH);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_SIZE) {
                    state.truncated = true;
                    break;
                }
                String key = String.valueOf(entry.getKey());
                String childPath = path + "/" + escape(key);
                if (sensitiveKey(key)) {
                    sanitized.put(key, REDACTED);
                    state.redactedPaths.add(childPath);
                } else {
                    sanitized.put(key, sanitize(entry.getValue(), childPath, depth + 1, state));
                }
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            int index = 0;
            for (Object item : iterable) {
                if (index >= MAX_COLLECTION_SIZE) {
                    state.truncated = true;
                    break;
                }
                sanitized.add(sanitize(item, path + "/" + index, depth + 1, state));
                index++;
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            int length = Math.min(Array.getLength(value), MAX_COLLECTION_SIZE);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitize(Array.get(value, i), path + "/" + i, depth + 1, state));
            }
            if (Array.getLength(value) > MAX_COLLECTION_SIZE) {
                state.truncated = true;
            }
            return sanitized;
        }
        return String.valueOf(value);
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
    }

    private static String sanitizeText(String value) {
        String sanitized = LABELED_SECRET.matcher(value).replaceAll("$1$2" + REDACTED);
        return AUTHORIZATION_VALUE.matcher(sanitized).replaceAll("$1 " + REDACTED);
    }

    private static Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    public record Capture(
            Map<String, Object> context,
            Object output,
            Map<String, Object> results,
            Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
            VisualPayloadRedactionManifest redaction
    ) {
    }

    private static final class State {
        private final List<String> redactedPaths = new ArrayList<>();
        private boolean truncated;
    }
}
