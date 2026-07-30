package com.leanowtech.bloge.gateway.visual.authoring.compile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic mapping from generated canonical JSON pointers back to editable authoring paths.
 */
public record AuthoringSourceMap(List<Entry> entries) {

    public AuthoringSourceMap {
        entries = entries == null ? List.of() : entries.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Entry::canonicalPath)
                        .thenComparing(Entry::authoringPath))
                .toList();
    }

    public Optional<Entry> resolveCanonicalPath(String canonicalPath) {
        String target = normalizedPath(canonicalPath);
        return entries.stream()
                .filter(entry -> prefixMatch(target, entry.canonicalPath()))
                .max(Comparator.comparingInt(entry -> entry.canonicalPath().length()));
    }

    private static boolean prefixMatch(String target, String prefix) {
        return "/".equals(prefix)
                || target.equals(prefix)
                || target.startsWith(prefix + "/");
    }

    private static String normalizedPath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    /**
     * One source ownership entry.
     */
    public record Entry(
            String authoringPath,
            String canonicalPath,
            String origin,
            String evidenceRef
    ) {
        public Entry {
            authoringPath = normalizedPath(authoringPath);
            canonicalPath = normalizedPath(canonicalPath);
            origin = origin == null || origin.isBlank() ? "DECLARED" : origin.trim().toUpperCase();
            evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
        }
    }

    /**
     * Mutable build helper kept private to compiler assembly.
     */
    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        public Builder add(String authoringPath, String canonicalPath) {
            entries.add(new Entry(authoringPath, canonicalPath, "DECLARED", ""));
            return this;
        }

        public Builder add(String authoringPath,
                           String canonicalPath,
                           String origin,
                           String evidenceRef) {
            entries.add(new Entry(authoringPath, canonicalPath, origin, evidenceRef));
            return this;
        }

        public AuthoringSourceMap build() {
            return new AuthoringSourceMap(entries);
        }
    }
}
