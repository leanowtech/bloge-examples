package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringSourceMap;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic carrying both generated canonical and editable authoring locations.
 */
public record AuthoringDiagnostic(
        String level,
        String code,
        String message,
        String authoringPath,
        String canonicalPath,
        int offset,
        List<Fix> fixes,
        Map<String, Object> metadata
) {
    public AuthoringDiagnostic {
        level = normalized(level, "INFO").toUpperCase();
        code = normalized(code, "RG.AUTHORING.INFO");
        message = normalized(message, "");
        authoringPath = normalizedPath(authoringPath);
        canonicalPath = canonicalPath == null || canonicalPath.isBlank()
                ? "" : normalizedPath(canonicalPath);
        offset = Math.max(-1, offset);
        fixes = fixes == null ? List.of() : List.copyOf(fixes);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static AuthoringDiagnostic error(String code,
                                            String message,
                                            String authoringPath) {
        return compiler("ERROR", code, message, authoringPath, -1, Map.of());
    }

    public static AuthoringDiagnostic error(String code,
                                            String message,
                                            String authoringPath,
                                            int offset) {
        return compiler("ERROR", code, message, authoringPath, offset, Map.of());
    }

    public static AuthoringDiagnostic warning(String code,
                                              String message,
                                              String authoringPath) {
        return compiler("WARNING", code, message, authoringPath, -1, Map.of());
    }

    public static AuthoringDiagnostic compiler(String level,
                                               String code,
                                               String message,
                                               String authoringPath,
                                               int offset,
                                               Map<String, Object> metadata) {
        String path = normalizedPath(authoringPath);
        return new AuthoringDiagnostic(level, code, message, path, "", offset,
                List.of(new Fix("OPEN_FIELD", "Review field", path)), metadata);
    }

    public static AuthoringDiagnostic fromCanonical(VisualDiagnostic diagnostic,
                                                    AuthoringSourceMap sourceMap) {
        String canonicalPath = diagnostic == null ? "/" : normalizedPath(diagnostic.target());
        AuthoringSourceMap.Entry source = sourceMap == null
                ? null
                : sourceMap.resolveCanonicalPath(canonicalPath).orElse(null);
        String authoringPath = source == null ? "/" : source.authoringPath();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (diagnostic != null) {
            metadata.putAll(diagnostic.metadata());
        }
        if (source != null) {
            metadata.put("origin", source.origin());
            if (!source.evidenceRef().isBlank()) {
                metadata.put("evidenceRef", source.evidenceRef());
            }
        }
        return new AuthoringDiagnostic(
                diagnostic == null ? "ERROR" : diagnostic.level(),
                diagnostic == null ? "RG.AUTHORING.CANONICAL_VALIDATION_FAILED" : diagnostic.code(),
                diagnostic == null ? "Canonical validation failed." : diagnostic.message(),
                authoringPath,
                canonicalPath,
                -1,
                List.of(new Fix("OPEN_FIELD", "Review field", authoringPath)),
                metadata
        );
    }

    public boolean error() {
        return "ERROR".equals(level);
    }

    public record Fix(String kind, String label, String target) {
        public Fix {
            kind = normalized(kind, "OPEN_FIELD");
            label = normalized(label, "Review field");
            target = normalizedPath(target);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizedPath(String value) {
        String path = normalized(value, "/");
        return path.startsWith("/") ? path : "/" + path;
    }
}
