package com.leanowtech.bloge.gateway.visual.authoring.link;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Versioned, structured Author route; callers must not reinterpret it as an arbitrary URL. */
public record AuthoringLinkDescriptor(
        String schemaVersion,
        Resolution resolution,
        Route route,
        AuthoringLinkResolveRequest.ExactSubjectRef sourceRef,
        AuthoringLinkResolveRequest.ReturnCoordinate returnCoordinate
) {
    public static final String SCHEMA_VERSION = "bloge.authoringLinkDescriptor.v1";

    public AuthoringLinkDescriptor {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported AuthoringLinkDescriptor schemaVersion");
        }
        resolution = Objects.requireNonNull(resolution, "resolution");
        route = Objects.requireNonNull(route, "route");
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        returnCoordinate = Objects.requireNonNull(returnCoordinate, "returnCoordinate");
    }

    public enum Resolution {
        READ_ONLY_SOURCE,
        EXISTING_DRAFT
    }

    public record Route(
            String path,
            String workspace,
            String authorMode,
            Map<String, String> query
    ) {
        public Route {
            path = path == null ? "" : path.trim();
            if (!"/author/".equals(path)) {
                throw new IllegalArgumentException("Author route path must be /author/");
            }
            workspace = required(workspace, "route.workspace");
            if (!"v2".equals(workspace)) {
                throw new IllegalArgumentException("route.workspace must be v2");
            }
            authorMode = required(authorMode, "route.authorMode").toLowerCase(Locale.ROOT);
            if (!"compose".equals(authorMode)) {
                throw new IllegalArgumentException("route.authorMode must be compose");
            }
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            if (query != null) {
                query.forEach((key, value) -> copy.put(required(key, "route.query.key"),
                        required(value, "route.query.value")));
            }
            if (copy.containsKey("returnUrl") || copy.containsKey("showcaseHref")
                    || copy.values().stream().anyMatch(value -> value.contains("/showcase"))) {
                throw new IllegalArgumentException("route.query contains a forbidden URL");
            }
            query = Map.copyOf(copy);
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return normalized;
        }
    }
}
