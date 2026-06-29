package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable published visual graph artifact.
 *
 * @param schemaVersion publication schema version
 * @param publicationId immutable publication id
 * @param draftId source draft id
 * @param draftRevision source draft revision
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment authoring environment
 * @param createdAt publication timestamp
 * @param draft frozen draft snapshot
 * @param operatorSnapshots frozen operator definitions used by the draft
 * @param operatorFingerprints frozen operator fingerprints keyed by node id
 * @param visualLayout frozen visual layout
 * @param dsl generated executable BLOGE DSL
 * @param validation validation report captured at publish time
 * @param generation DSL generation result captured at publish time
 */
public record VisualGraphPublication(
        String schemaVersion,
        String publicationId,
        String draftId,
        long draftRevision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        Instant createdAt,
        GraphDraft draft,
        List<OperatorDefinition> operatorSnapshots,
        Map<String, String> operatorFingerprints,
        Map<String, Object> visualLayout,
        String dsl,
        VisualValidationResult validation,
        DslGenerationResult generation
) {
    /**
     * Creates a publication artifact.
     */
    public VisualGraphPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualGraphPublication.v1"
                : schemaVersion;
        publicationId = publicationId == null ? "" : publicationId;
        draftId = draftId == null ? "" : draftId;
        draftRevision = Math.max(0, draftRevision);
        graphName = graphName == null || graphName.isBlank() ? "visualGraph" : graphName;
        tenantId = tenantId == null || tenantId.isBlank() ? "demo-tenant" : tenantId;
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        environment = environment == null || environment.isBlank() ? "local" : environment;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        operatorSnapshots = operatorSnapshots == null ? List.of() : List.copyOf(operatorSnapshots);
        operatorFingerprints = operatorFingerprints == null ? Map.of() : new LinkedHashMap<>(operatorFingerprints);
        visualLayout = visualLayout == null ? Map.of() : new LinkedHashMap<>(visualLayout);
        dsl = dsl == null ? "" : dsl;
        validation = validation == null ? new VisualValidationResult(true, List.of()) : validation;
        generation = generation == null ? new DslGenerationResult(true, dsl, List.of()) : generation;
    }

    /**
     * Builds a publication from validated/generated draft state.
     */
    public static VisualGraphPublication from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation,
                                              DslGenerationResult generation) {
        return new VisualGraphPublication(
                "",
                "",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                operatorSnapshots,
                draft.operatorFingerprints(),
                draft.visualLayout(),
                generation.dsl(),
                validation,
                generation
        );
    }

    /**
     * Returns a copy with immutable repository identity values.
     */
    public VisualGraphPublication withIdentity(String newPublicationId, Instant newCreatedAt) {
        return new VisualGraphPublication(schemaVersion, newPublicationId, draftId, draftRevision, graphName,
                tenantId, namespace, environment, newCreatedAt, draft, operatorSnapshots, operatorFingerprints,
                visualLayout, dsl, validation, generation);
    }
}
