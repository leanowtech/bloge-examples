package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.List;

/**
 * Audit metadata describing the sanitizer applied to captured run payloads.
 */
public record VisualPayloadRedactionManifest(
        String profile,
        int redactedCount,
        boolean truncated,
        List<String> redactedPaths
) {
    public static final String DEFAULT_PROFILE = "default-sensitive-data@1";

    public VisualPayloadRedactionManifest {
        profile = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile;
        redactedCount = Math.max(0, redactedCount);
        redactedPaths = redactedPaths == null ? List.of() : List.copyOf(redactedPaths);
    }

    public static VisualPayloadRedactionManifest empty() {
        return new VisualPayloadRedactionManifest("", 0, false, List.of());
    }
}
