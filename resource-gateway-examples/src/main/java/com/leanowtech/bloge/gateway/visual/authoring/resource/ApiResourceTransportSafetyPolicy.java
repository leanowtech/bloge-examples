package com.leanowtech.bloge.gateway.visual.authoring.resource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared transport-header policy for API Resource authoring and projection.
 *
 * <p>Header names are compared in ASCII lowercase, while their original
 * spelling remains available to the runtime.  Keeping this policy in one
 * deep module prevents Connection defaults, API Resource bindings and API-key
 * metadata from drifting into different security boundaries.</p>
 */
public final class ApiResourceTransportSafetyPolicy {
    private static final Pattern HEADER_TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "proxy-authenticate", "cookie", "set-cookie",
            "host", "content-length", "connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "forwarded");

    private ApiResourceTransportSafetyPolicy() {
    }

    /** Returns whether a name is syntactically valid and outside platform boundaries. */
    public static boolean isAllowedHeaderName(String header) {
        if (header == null || !HEADER_TOKEN.matcher(header).matches()) {
            return false;
        }
        String normalized = normalize(header);
        return !RESERVED_HEADERS.contains(normalized) && !normalized.startsWith("x-forwarded-");
    }

    /** Rejects one header name with a stable validation message. */
    public static void requireAllowedHeaderName(String header) {
        if (!isAllowedHeaderName(header)) {
            throw new IllegalArgumentException("header is reserved or invalid");
        }
    }

    /** Validates non-secret defaults and rejects a declared API-key collision. */
    public static void requireSafeDefaults(Map<String, String> defaults, String apiKeyHeader) {
        if (defaults == null) {
            return;
        }
        String apiKey = normalizeOptional(apiKeyHeader);
        for (String header : defaults.keySet()) {
            requireAllowedHeaderName(header);
            if (apiKey != null && apiKey.equals(normalize(header))) {
                throw new IllegalArgumentException("connection default header conflicts with api-key header");
            }
        }
    }

    /** Validates an API-key header without accepting a platform-reserved name. */
    public static void requireSafeApiKeyHeader(String apiKeyHeader) {
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            requireAllowedHeaderName(apiKeyHeader);
        }
    }

    /** Case-insensitive header comparison used by all callers. */
    public static String normalize(String header) {
        return header.toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String header) {
        return header == null || header.isBlank() ? null : normalize(header);
    }
}
