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
 * @param actor user or system actor producing this publication
 * @param changeSource UI or integration source producing this publication
 * @param changeSummary human-readable publication summary
 * @param reason optional publication or warning-review reason
 */
public record VisualGraphPublishRequest(long expectedRevision,
                                        boolean ackWarnings,
                                        String artifactKind,
                                        String actor,
                                        String changeSource,
                                        String changeSummary,
                                        String reason) {
    public static final String ARTIFACT_EXECUTABLE = "EXECUTABLE";
    public static final String ARTIFACT_DESIGN = "DESIGN";

    /**
     * Creates a publish request.
     */
    public VisualGraphPublishRequest {
        expectedRevision = Math.max(0, expectedRevision);
        artifactKind = normalizeArtifactKind(artifactKind);
        actor = actor == null ? "" : actor.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        changeSummary = changeSummary == null ? "" : changeSummary.trim();
        reason = reason == null ? "" : reason.trim();
    }

    /**
     * Backward-compatible constructor for callers that specify artifact kind but no audit metadata.
     *
     * @param expectedRevision draft revision observed by the publisher
     * @param ackWarnings true when non-blocking validation warnings were reviewed
     * @param artifactKind publication artifact kind
     */
    public VisualGraphPublishRequest(long expectedRevision, boolean ackWarnings, String artifactKind) {
        this(expectedRevision, ackWarnings, artifactKind, "", "", "", "");
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
     * @return normalized publication audit metadata
     */
    public VisualGraphPublication.PublicationMetadata publicationMetadata() {
        return VisualGraphPublication.PublicationMetadata.of(actor, changeSource, changeSummary, reason);
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
