package com.leanowtech.bloge.gateway.visual.golden;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit regression fixture bound to one immutable visual graph publication.
 *
 * @param schemaVersion golden case schema version
 * @param caseId case id
 * @param publicationId immutable publication id
 * @param name display name
 * @param description optional description
 * @param outputNode optional output node override
 * @param context submitted run context
 * @param expectedOutput expected selected graph output
 * @param createdAt case creation timestamp
 */
public record VisualGraphGoldenCase(
        String schemaVersion,
        String caseId,
        String publicationId,
        String name,
        String description,
        String outputNode,
        Map<String, Object> context,
        Object expectedOutput,
        Instant createdAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphGoldenCase.v1";

    /**
     * Creates a golden case.
     */
    public VisualGraphGoldenCase {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        caseId = caseId == null ? "" : caseId;
        publicationId = publicationId == null ? "" : publicationId;
        name = name == null || name.isBlank() ? "Golden case" : name.trim();
        description = description == null ? "" : description;
        outputNode = outputNode == null ? "" : outputNode;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * @param newCaseId case id
     * @param newCreatedAt creation timestamp
     * @return copy with repository identity
     */
    public VisualGraphGoldenCase withIdentity(String newCaseId, Instant newCreatedAt) {
        return new VisualGraphGoldenCase(schemaVersion, newCaseId, publicationId, name, description,
                outputNode, context, expectedOutput, newCreatedAt);
    }
}
