package com.leanowtech.bloge.gateway.visual.publication;

import java.util.Locale;

/**
 * Request body for publishing a stored visual graph draft.
 *
 * @param expectedRevision draft revision observed by the publisher; zero keeps
 *                         legacy unguarded publish semantics
 * @param ackWarnings true when the publisher already reviewed non-blocking validation warnings
 * @param artifactKind publication artifact kind; EXECUTABLE keeps legacy semantics,
 *                     DESIGN freezes a non-executable design artifact
 */
public record VisualGraphPublishRequest(long expectedRevision, boolean ackWarnings, String artifactKind) {
    public static final String ARTIFACT_EXECUTABLE = "EXECUTABLE";
    public static final String ARTIFACT_DESIGN = "DESIGN";

    /**
     * Creates a publish request.
     */
    public VisualGraphPublishRequest {
        expectedRevision = Math.max(0, expectedRevision);
        artifactKind = normalizeArtifactKind(artifactKind);
    }

    /**
     * Backward-compatible constructor for executable publication requests.
     *
     * @param expectedRevision draft revision observed by the publisher
     * @param ackWarnings true when non-blocking validation warnings were reviewed
     */
    public VisualGraphPublishRequest(long expectedRevision, boolean ackWarnings) {
        this(expectedRevision, ackWarnings, ARTIFACT_EXECUTABLE);
    }

    /**
     * Backward-compatible constructor for callers that only guard by revision.
     *
     * @param expectedRevision draft revision observed by the publisher
     */
    public VisualGraphPublishRequest(long expectedRevision) {
        this(expectedRevision, false);
    }

    /**
     * @return true when this request asks for a non-executable design artifact
     */
    public boolean designArtifact() {
        return ARTIFACT_DESIGN.equals(artifactKind);
    }

    /**
     * @return true when this request asks for an executable publication
     */
    public boolean executableArtifact() {
        return ARTIFACT_EXECUTABLE.equals(artifactKind);
    }

    /**
     * @param value artifact kind
     * @return whether the artifact kind is supported
     */
    public static boolean supportedArtifactKind(String value) {
        String normalized = normalizeArtifactKind(value);
        return ARTIFACT_EXECUTABLE.equals(normalized) || ARTIFACT_DESIGN.equals(normalized);
    }

    private static String normalizeArtifactKind(String value) {
        if (value == null || value.isBlank()) {
            return ARTIFACT_EXECUTABLE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
