package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Product-layer validation view for one stored graph version snapshot.
 *
 * @param versionId version identifier that was validated
 * @param executionMode detected execution mode
 * @param runtimeName namespaced runtime artifact name derived for execution
 * @param declaredRootName original top-level DSL root name
 * @param contentHash authoritative source hash
 * @param compiledArtifactRef runtime artifact reference/hash when compilation succeeded
 * @param metadata derived version metadata
 * @param diagnostics lint/compile diagnostics
 * @param valid whether the version is publishable without blocking diagnostics
 */
public record VersionValidationResult(
        String versionId,
        GraphExecutionMode executionMode,
        String runtimeName,
        String declaredRootName,
        String contentHash,
        String compiledArtifactRef,
        GraphVersionMetadata metadata,
        List<GraphEngineDiagnostic> diagnostics,
        boolean valid
) {
    public VersionValidationResult {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must not be blank");
        }
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        if (runtimeName == null || runtimeName.isBlank()) {
            throw new IllegalArgumentException("runtimeName must not be blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        metadata = Objects.requireNonNullElse(metadata, new GraphVersionMetadata(null, null, null, null, null, null, null));
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
