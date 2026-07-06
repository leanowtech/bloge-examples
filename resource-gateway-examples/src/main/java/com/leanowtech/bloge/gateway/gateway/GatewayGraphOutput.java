package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Terminal output selected from a resource graph and validated against its graph contract.
 *
 * @param graphName resource graph name
 * @param outputNode selected terminal output node
 * @param output terminal payload returned by the graph
 * @param diagnostics output-node and output-schema diagnostics
 */
public record GatewayGraphOutput(
        String graphName,
        String outputNode,
        Object output,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a terminal output envelope.
     */
    public GatewayGraphOutput {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * @return true when a terminal output exists and satisfies the graph output schema
     */
    public boolean valid() {
        return output != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
