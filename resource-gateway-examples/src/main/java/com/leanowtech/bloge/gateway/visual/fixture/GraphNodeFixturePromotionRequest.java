package com.leanowtech.bloge.gateway.visual.fixture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonPointer;

import java.util.List;
import java.util.Locale;

/**
 * Author request to promote one captured graph-node output into governed Fixture governance.
 *
 * @param schemaVersion wire-contract version
 * @param fixtureAssetId requested governed Fixture id
 * @param classification confidentiality label
 * @param retentionDays bounded retention in days
 * @param redactionPaths JSON paths removed before material persistence
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphNodeFixturePromotionRequest(
        String schemaVersion,
        String fixtureAssetId,
        String classification,
        int retentionDays,
        List<String> redactionPaths
) {
    /** Wire contract published by the Resource Gateway tool-authoring workbench. */
    public static final String SCHEMA_VERSION = "bloge.graphNodeFixturePromote.v1";
    private static final int MAX_PATHS = 128;
    private static final int MAX_PATH_LENGTH = 512;
    private static final java.util.Set<String> ALLOWED_CLASSIFICATIONS =
            java.util.Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    /**
     * Creates and normalizes a promotion request without accepting an unsupported wire version.
     */
    public GraphNodeFixturePromotionRequest {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        fixtureAssetId = fixtureAssetId == null ? "" : fixtureAssetId.trim();
        classification = classification == null ? "" : classification.trim().toUpperCase(Locale.ROOT);
        List<String> normalizedPaths = redactionPaths == null ? List.of() : redactionPaths.stream()
                .distinct()
                .map(path -> path == null ? "" : path.trim())
                .sorted()
                .toList();
        redactionPaths = normalizedPaths;
    }

    /**
     * Enforces the complete transport contract after record construction.
     *
     * @throws IllegalArgumentException when any authoritative request field is unsupported
     */
    void requireValid() {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported graph-node Fixture promotion schemaVersion");
        }
        if (fixtureAssetId.isEmpty() || fixtureAssetId.length() > 160
                || !fixtureAssetId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) {
            throw new IllegalArgumentException("A bounded graph-node Fixture asset id is required");
        }
        if (classification == null || !ALLOWED_CLASSIFICATIONS.contains(classification)) {
            throw new IllegalArgumentException(
                    "Classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED");
        }
        if (retentionDays < 1 || retentionDays > 30) {
            throw new IllegalArgumentException(
                    "Graph-node Fixture promotion requires 1..30 retention days");
        }
        if (redactionPaths.size() > MAX_PATHS || redactionPaths.stream().anyMatch(
                path -> !validRedactionPath(path))) {
            throw new IllegalArgumentException("Redaction paths must be bounded non-root JSON Pointers");
        }
    }

    private static boolean validRedactionPath(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH
                || !path.startsWith("/") || "/".equals(path)) {
            return false;
        }
        try {
            JsonPointer.compile(path);
            return true;
        } catch (IllegalArgumentException invalidPointer) {
            return false;
        }
    }
}
