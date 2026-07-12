package com.leanowtech.bloge.gateway.visual.runtime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conservative, bounded sanitizer for payloads persisted with visual run history.
 */
public final class VisualPayloadSanitizer {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_COLLECTION_SIZE = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "accesstoken", "refreshtoken", "authorization",
            "cookie", "setcookie", "apikey", "credential", "privatekey", "email", "phone", "ssn",
            "nationalid", "passport"
    );

    private VisualPayloadSanitizer() {
    }

    public static Capture capture(Map<String, Object> context, Object output, Map<String, Object> results) {
        State state = new State();
        Map<String, Object> sanitizedContext = asStringMap(sanitize(context == null ? Map.of() : context,
                "/context", 0, state));
        Object sanitizedOutput = sanitize(output, "/output", 0, state);
        Map<String, Object> sanitizedResults = asStringMap(sanitize(results == null ? Map.of() : results,
                "/results", 0, state));
        return new Capture(sanitizedContext, sanitizedOutput, sanitizedResults,
                new VisualPayloadRedactionManifest("", state.redactedPaths.size(), state.truncated,
                        state.redactedPaths));
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
            if (text.length() <= MAX_STRING_LENGTH) {
                return text.toString();
            }
            state.truncated = true;
            return text.subSequence(0, MAX_STRING_LENGTH).toString();
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
            VisualPayloadRedactionManifest redaction
    ) {
    }

    private static final class State {
        private final List<String> redactedPaths = new ArrayList<>();
        private boolean truncated;
    }
}
