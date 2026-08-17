package com.leanowtech.bloge.gateway.visual.reference;

import java.util.Objects;

/** Versioned bounded search input for metadata-only reference candidates. */
public record SearchRequest(
        String schemaVersion,
        String kind,
        String query,
        String cursor,
        int limit,
        ReferenceScope scope,
        String lifecycle,
        String compatibleWith
) {
    public static final String SCHEMA_VERSION = "bloge.referenceSearchRequest.v1";
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_CURSOR_LENGTH = 4096;

    public SearchRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported SearchRequest schemaVersion: " + schemaVersion);
        }
        kind = normalize(kind);
        query = normalize(query);
        cursor = normalize(cursor);
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query exceeds " + MAX_QUERY_LENGTH + " characters");
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("cursor exceeds " + MAX_CURSOR_LENGTH + " characters");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        scope = Objects.requireNonNull(scope, "scope");
        lifecycle = enumFilter(lifecycle, ReferenceCandidate.Lifecycle.class, "lifecycle");
        compatibleWith = enumFilter(
                compatibleWith, ReferenceCandidate.Compatibility.class, "compatibleWith");
    }

    public SearchRequest(String kind, String query, String cursor, int limit, ReferenceScope scope) {
        this(SCHEMA_VERSION, kind, query, cursor, limit, scope, "", "");
    }

    public SearchRequest(String kind,
                         String query,
                         String cursor,
                         int limit,
                         ReferenceScope scope,
                         String lifecycle,
                         String compatibleWith) {
        this(SCHEMA_VERSION, kind, query, cursor, limit, scope, lifecycle, compatibleWith);
    }

    public SearchRequest(String kind, String query, ReferenceScope scope) {
        this(SCHEMA_VERSION, kind, query, "", DEFAULT_LIMIT, scope, "", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static <E extends Enum<E>> String enumFilter(String value, Class<E> type, String field) {
        String normalized = normalize(value).toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            Enum.valueOf(type, normalized);
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " is not supported");
        }
    }
}
