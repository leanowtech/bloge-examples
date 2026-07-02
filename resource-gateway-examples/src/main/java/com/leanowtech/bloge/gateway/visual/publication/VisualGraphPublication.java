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
 * @param publicationMetadata audit metadata explaining this publication
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
        GraphDraftDependencyReport dependencyReport,
        PublicationMetadata publicationMetadata
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphPublication.v1";
    public static final String ARTIFACT_EXECUTABLE = "EXECUTABLE";
    public static final String ARTIFACT_DESIGN = "DESIGN";

    /**
     * Creates a publication artifact.
     */
    public VisualGraphPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
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
        publicationMetadata = (publicationMetadata == null ? PublicationMetadata.empty() : publicationMetadata)
                .storedFor(artifactKind, draftId, draftRevision);
    }

    /**
     * Backward-compatible constructor for callers that do not freeze publication metadata.
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
                                  DslGenerationResult generation,
                                  GraphDraftDependencyReport dependencyReport) {
        this(schemaVersion, publicationId, draftId, draftRevision, graphName, tenantId, namespace, environment,
                createdAt, artifactKind, draft, operatorSnapshots, operatorFingerprints, visualLayout, dsl,
                validation, generation, dependencyReport, PublicationMetadata.empty());
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
        return from(draft, operatorSnapshots, validation, generation, dependencyReport, PublicationMetadata.empty());
    }

    /**
     * Builds a publication from validated/generated draft state.
     *
     * @param draft stored draft snapshot
     * @param operatorSnapshots frozen operator snapshots
     * @param validation publish-time validation and readiness
     * @param generation publish-time DSL generation result
     * @param dependencyReport publish-time dependency report
     * @param publicationMetadata publication audit metadata
     * @return immutable publication artifact
     */
    public static VisualGraphPublication from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation,
                                              DslGenerationResult generation,
                                              GraphDraftDependencyReport dependencyReport,
                                              PublicationMetadata publicationMetadata) {
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
                dependencyReport,
                publicationMetadata
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
        return design(draft, operatorSnapshots, validation, generation, dependencyReport,
                PublicationMetadata.empty());
    }

    /**
     * Builds a non-executable design publication from validated draft state.
     *
     * @param draft stored draft snapshot
     * @param operatorSnapshots frozen operator snapshots
     * @param validation publish-time validation and readiness
     * @param generation publish-time generation diagnostics
     * @param dependencyReport publish-time dependency report
     * @param publicationMetadata publication audit metadata
     * @return immutable design artifact
     */
    public static VisualGraphPublication design(GraphDraft draft,
                                                List<OperatorDefinition> operatorSnapshots,
                                                VisualValidationResult validation,
                                                DslGenerationResult generation,
                                                GraphDraftDependencyReport dependencyReport,
                                                PublicationMetadata publicationMetadata) {
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
                dependencyReport,
                publicationMetadata
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
                operatorFingerprints, visualLayout, dsl, validation, generation, dependencyReport,
                publicationMetadata);
    }

    private static String normalizeArtifactKind(String value) {
        if (value == null || value.isBlank()) {
            return ARTIFACT_EXECUTABLE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Audit metadata supplied by the control-plane caller for one publication.
     *
     * @param actor user or system actor that initiated publication
     * @param changeSource UI, API, import, or promotion source
     * @param changeSummary concise human-readable summary
     * @param reason optional operator-facing reason for publication or warning acknowledgement
     */
    public record PublicationMetadata(
            String actor,
            String changeSource,
            String changeSummary,
            String reason
    ) {
        /**
         * Creates normalized metadata.
         */
        public PublicationMetadata {
            actor = actor == null ? "" : actor.trim();
            changeSource = changeSource == null ? "" : changeSource.trim();
            changeSummary = changeSummary == null ? "" : changeSummary.trim();
            reason = reason == null ? "" : reason.trim();
        }

        public static PublicationMetadata empty() {
            return new PublicationMetadata("", "", "", "");
        }

        public static PublicationMetadata of(String actor,
                                             String changeSource,
                                             String changeSummary,
                                             String reason) {
            return new PublicationMetadata(actor, changeSource, changeSummary, reason);
        }

        PublicationMetadata storedFor(String artifactKind, String draftId, long draftRevision) {
            String kind = artifactKind == null || artifactKind.isBlank()
                    ? ARTIFACT_EXECUTABLE
                    : artifactKind.trim().toUpperCase(Locale.ROOT);
            String source = draftId == null || draftId.isBlank()
                    ? "visual draft"
                    : "%s@%d".formatted(draftId.trim(), Math.max(0, draftRevision));
            return new PublicationMetadata(
                    defaultString(actor, "visual-canvas"),
                    defaultString(changeSource, "api"),
                    defaultString(changeSummary, "Published %s visual graph artifact from %s."
                            .formatted(kind, source)),
                    reason
            );
        }

        private static String defaultString(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
