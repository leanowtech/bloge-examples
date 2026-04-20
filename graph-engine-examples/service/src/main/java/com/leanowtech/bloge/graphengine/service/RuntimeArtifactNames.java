package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionHasher;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.DslGraphDefinitionCodec;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives deterministic runtime artifact names from stable product-layer
 * identifiers and rewrites compiled artifacts to use those names.
 *
 * <p>The durable runtime registry is shared and not tenant-aware, so product
 * definitions cannot safely publish raw DSL root names directly. This helper
 * gives each product definition or version a stable, collision-resistant
 * runtime-facing name while preserving the original DSL source in metadata.</p>
 */
public final class RuntimeArtifactNames {

    private RuntimeArtifactNames() {
    }

    /**
     * Returns the stable graph runtime name shared by all versions of one product definition.
     *
     * @param definition owning product definition
     * @return deterministic runtime graph name
     */
    public static String graphRuntimeName(GraphDefinition definition) {
        return "ge-" + shortId(definition.definitionId());
    }

    /**
     * Returns the version-pinned runtime name used for session definitions.
     *
     * @param versionId immutable version identifier
     * @return deterministic session runtime name
     */
    public static String sessionRuntimeName(String versionId) {
        return "ges-" + shortId(versionId);
    }

    /**
     * Returns the version-pinned runtime name used for state-machine definitions.
     *
     * @param versionId immutable version identifier
     * @return deterministic state-machine runtime name
     */
    public static String stateMachineRuntimeName(String versionId) {
        return "gesm-" + shortId(versionId);
    }

    /**
     * Returns the runtime artifact name for the given execution mode.
     *
     * @param definition owning product definition
     * @param versionId immutable version identifier
     * @param mode detected execution mode
     * @return deterministic runtime artifact name
     */
    public static String runtimeName(GraphDefinition definition, String versionId, GraphExecutionMode mode) {
        return switch (mode) {
            case GRAPH -> graphRuntimeName(definition);
            case SESSION -> sessionRuntimeName(versionId);
            case STATE_MACHINE -> stateMachineRuntimeName(versionId);
        };
    }

    /**
     * Rewrites a compiled graph so the durable runtime publishes and restores it
     * by a namespaced runtime-facing name rather than the raw DSL root name.
     *
     * @param graph compiled graph
     * @param runtimeName product-derived runtime name
     * @param graphVersion semantic version string
     * @param originalAst parsed top-level graph AST
     * @param jsonCodec JSON codec used for definition-source serialization
     * @return rewritten graph artifact
     */
    public static Graph renameGraph(Graph graph,
                                    String runtimeName,
                                    String graphVersion,
                                    AstNode.GraphDef originalAst,
                                    JsonCodec jsonCodec) {
        AstNode.GraphDef rewrittenAst = new AstNode.GraphDef(
                runtimeName,
                originalAst.members(),
                originalAst.inputSchema(),
                originalAst.outputSchema(),
                originalAst.description(),
                originalAst.line(),
                originalAst.column()
        );
        GraphDefinitionSource definitionSource = new GraphDefinitionSource(
                graphVersion,
                DslGraphDefinitionCodec.FORMAT,
                jsonCodec.serialize(rewrittenAst)
        );
        return new Graph(
                runtimeName,
                graph.nodes(),
                graph.edges(),
                graph.sourceNodes(),
                graph.terminalNodes(),
                graph.schemaValidationLevel(),
                graph.embeddedOperators(),
                graph.declaredInputSchema(),
                graph.declaredOutputSchema(),
                definitionSource
        );
    }

    /**
     * Rewrites a compiled session graph to a version-pinned runtime name.
     *
     * @param sessionGraph compiled session graph
     * @param runtimeName version-pinned runtime name
     * @return rewritten session graph
     */
    public static SessionGraph renameSessionGraph(SessionGraph sessionGraph, String runtimeName) {
        return new SessionGraph(
                runtimeName,
                sessionGraph.idleTimeout(),
                sessionGraph.timeoutPolicyRef(),
                sessionGraph.maxTotalRounds(),
                sessionGraph.maxHistorySize(),
                sessionGraph.phases(),
                sessionGraph.phaseIndex(),
                sessionGraph.contentHash()
        );
    }

    /**
     * Rewrites a compiled state-machine definition to a version-pinned runtime name.
     *
     * @param stateMachine compiled state-machine definition
     * @param runtimeName version-pinned runtime name
     * @return rewritten state-machine definition
     */
    public static StateMachineDef renameStateMachine(StateMachineDef stateMachine, String runtimeName) {
        return new StateMachineDef(
                runtimeName,
                stateMachine.states(),
                stateMachine.initialStateId(),
                stateMachine.terminalStateIds(),
                stateMachine.maxTransitions(),
                stateMachine.maxStateVisits(),
                stateMachine.globalTimeout(),
                stateMachine.globalTransitions()
        );
    }

    /**
     * Builds migration-hint metadata explaining the product/runtime name mapping.
     *
     * @param runtimeName product-derived runtime name
     * @param declaredRootName raw DSL root name
     * @return immutable hints map
     */
    public static Map<String, Object> namingHints(String runtimeName, String declaredRootName) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("runtimeName", runtimeName);
        if (declaredRootName != null && !declaredRootName.isBlank()) {
            hints.put("declaredRootName", declaredRootName);
        }
        return Map.copyOf(hints);
    }

    private static String shortId(String value) {
        String normalized = value == null || value.isBlank()
                ? GraphDefinitionHasher.sha256Hex("missing")
                : GraphDefinitionHasher.sha256Hex(value);
        return normalized.substring(0, 12);
    }
}
