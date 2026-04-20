package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.util.List;
import java.util.Objects;

/**
 * Internal compile outcome for one immutable product-layer graph version.
 *
 * @param executionMode detected execution mode
 * @param runtimeName namespaced runtime artifact name used by the underlying engine
 * @param declaredRootName original top-level DSL root name
 * @param contentHash source-content hash
 * @param compiledArtifactRef runtime artifact reference/hash when available
 * @param metadata derived version metadata
 * @param diagnostics collected lint/compile diagnostics
 * @param graph graph artifact for {@link GraphExecutionMode#GRAPH}
 * @param sessionGraph session artifact for {@link GraphExecutionMode#SESSION}
 * @param stateMachine state-machine artifact for {@link GraphExecutionMode#STATE_MACHINE}
 */
public record VersionCompileResult(
        GraphExecutionMode executionMode,
        String runtimeName,
        String declaredRootName,
        String contentHash,
        String compiledArtifactRef,
        GraphVersionMetadata metadata,
        List<GraphEngineDiagnostic> diagnostics,
        Graph graph,
        SessionGraph sessionGraph,
        StateMachineDef stateMachine
) {
    public VersionCompileResult {
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

    /**
     * Returns whether compilation produced a publishable artifact.
     *
     * @return {@code true} when no diagnostic has error severity
     */
    public boolean valid() {
        return diagnostics.stream().noneMatch(GraphEngineDiagnostic::error);
    }
}
