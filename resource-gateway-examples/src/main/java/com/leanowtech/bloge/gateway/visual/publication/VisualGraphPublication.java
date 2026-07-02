package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * @param artifactKind immutable artifact kind: EXECUTABLE or DESIGN
 * @param draft frozen draft snapshot
 * @param operatorSnapshots frozen operator definitions used by the draft
 * @param operatorFingerprints frozen operator fingerprints keyed by node id
 * @param visualLayout frozen visual layout
 * @param dsl generated executable BLOGE DSL
 * @param validation validation report captured at publish time
 * @param generation DSL generation result captured at publish time
 * @param dependencyReport publish-time dependency report frozen with the artifact
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
        String artifactKind,
        GraphDraft draft,
        List<OperatorDefinition> operatorSnapshots,
        Map<String, String> operatorFingerprints,
        Map<String, Object> visualLayout,
        String dsl,
        VisualValidationResult validation,
        DslGenerationResult generation,
        GraphDraftDependencyReport dependencyReport
) {
    public static final String ARTIFACT_EXECUTABLE = "EXECUTABLE";
    public static final String ARTIFACT_DESIGN = "DESIGN";

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
        artifactKind = normalizeArtifactKind(artifactKind);
        operatorSnapshots = operatorSnapshots == null ? List.of() : List.copyOf(operatorSnapshots);
        operatorFingerprints = operatorFingerprints == null ? Map.of() : new LinkedHashMap<>(operatorFingerprints);
        visualLayout = visualLayout == null ? Map.of() : new LinkedHashMap<>(visualLayout);
        dsl = dsl == null ? "" : dsl;
        validation = validation == null ? new VisualValidationResult(true, List.of()) : validation;
        generation = generation == null ? new DslGenerationResult(true, dsl, List.of()) : generation;
        dependencyReport = dependencyReport == null ? GraphDraftDependencyReport.empty() : dependencyReport;
    }

    /**
     * Backward-compatible constructor for callers that do not freeze dependency reports.
     */
    public VisualGraphPublication(String schemaVersion,
                                  String publicationId,
                                  String draftId,
                                  long draftRevision,
                                  String graphName,
                                  String tenantId,
                                  String namespace,
                                  String environment,
                                  Instant createdAt,
                                  String artifactKind,
                                  GraphDraft draft,
                                  List<OperatorDefinition> operatorSnapshots,
                                  Map<String, String> operatorFingerprints,
                                  Map<String, Object> visualLayout,
                                  String dsl,
                                  VisualValidationResult validation,
                                  DslGenerationResult generation) {
        this(schemaVersion, publicationId, draftId, draftRevision, graphName, tenantId, namespace, environment,
                createdAt, artifactKind, draft, operatorSnapshots, operatorFingerprints, visualLayout, dsl,
                validation, generation, GraphDraftDependencyReport.empty());
    }

    /**
     * Backward-compatible constructor for legacy executable publication callers.
     */
    public VisualGraphPublication(String schemaVersion,
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
                                  DslGenerationResult generation) {
        this(schemaVersion, publicationId, draftId, draftRevision, graphName, tenantId, namespace, environment,
                createdAt, ARTIFACT_EXECUTABLE, draft, operatorSnapshots, operatorFingerprints, visualLayout, dsl,
                validation, generation, GraphDraftDependencyReport.empty());
    }

    /**
     * Builds a publication from validated/generated draft state.
     */
    public static VisualGraphPublication from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation,
                                              DslGenerationResult generation) {
        return from(draft, operatorSnapshots, validation, generation, GraphDraftDependencyReport.empty());
    }

    /**
     * Builds a publication from validated/generated draft state.
     *
     * @param draft stored draft snapshot
     * @param operatorSnapshots frozen operator snapshots
     * @param validation publish-time validation and readiness
     * @param generation publish-time DSL generation result
     * @param dependencyReport publish-time dependency report
     * @return immutable publication artifact
     */
    public static VisualGraphPublication from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation,
                                              DslGenerationResult generation,
                                              GraphDraftDependencyReport dependencyReport) {
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
                ARTIFACT_EXECUTABLE,
                draft,
                operatorSnapshots,
                draft.operatorFingerprints(),
                draft.visualLayout(),
                generation.dsl(),
                validation,
                generation,
                dependencyReport
        );
    }

    /**
     * Builds a non-executable design publication from validated draft state.
     */
    public static VisualGraphPublication design(GraphDraft draft,
                                                List<OperatorDefinition> operatorSnapshots,
                                                VisualValidationResult validation,
                                                DslGenerationResult generation) {
        return design(draft, operatorSnapshots, validation, generation, GraphDraftDependencyReport.empty());
    }

    /**
     * Builds a non-executable design publication from validated draft state.
     *
     * @param draft stored draft snapshot
     * @param operatorSnapshots frozen operator snapshots
     * @param validation publish-time validation and readiness
     * @param generation publish-time generation diagnostics
     * @param dependencyReport publish-time dependency report
     * @return immutable design artifact
     */
    public static VisualGraphPublication design(GraphDraft draft,
                                                List<OperatorDefinition> operatorSnapshots,
                                                VisualValidationResult validation,
                                                DslGenerationResult generation,
                                                GraphDraftDependencyReport dependencyReport) {
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
                ARTIFACT_DESIGN,
                draft,
                operatorSnapshots,
                draft.operatorFingerprints(),
                draft.visualLayout(),
                generation == null ? "" : generation.dsl(),
                validation,
                generation == null ? new DslGenerationResult(false, "", List.of()) : generation,
                dependencyReport
        );
    }

    /**
     * @return true when this artifact is executable and can be exposed as a subgraph operator
     */
    public boolean executable() {
        return ARTIFACT_EXECUTABLE.equals(artifactKind);
    }

    /**
     * @return true when this artifact freezes a validated but non-executable design
     */
    public boolean designArtifact() {
        return ARTIFACT_DESIGN.equals(artifactKind);
    }

    /**
     * Returns a copy with immutable repository identity values.
     */
    public VisualGraphPublication withIdentity(String newPublicationId, Instant newCreatedAt) {
        return new VisualGraphPublication(schemaVersion, newPublicationId, draftId, draftRevision, graphName,
                tenantId, namespace, environment, newCreatedAt, artifactKind, draft, operatorSnapshots,
                operatorFingerprints, visualLayout, dsl, validation, generation, dependencyReport);
    }

    private static String normalizeArtifactKind(String value) {
        if (value == null || value.isBlank()) {
            return ARTIFACT_EXECUTABLE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
