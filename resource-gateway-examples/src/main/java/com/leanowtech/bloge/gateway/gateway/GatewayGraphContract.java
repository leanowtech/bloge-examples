package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;

/**
 * Formal integration contract for one resource gateway graph.
 *
 * @param schemaVersion contract payload schema version
 * @param graphName BLOGE graph name
 * @param inputSchema required graph context shape before execution
 * @param outputSchema public terminal output shape exposed by gateway endpoints
 * @param outputNodes terminal node ids that may provide the public output
 */
public record GatewayGraphContract(
        String schemaVersion,
        String graphName,
        SchemaEnvelope inputSchema,
        SchemaEnvelope outputSchema,
        List<String> outputNodes
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContract.v1";

    public GatewayGraphContract {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        graphName = graphName == null ? "" : graphName;
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        outputSchema = outputSchema == null ? SchemaEnvelope.opaque() : outputSchema;
        outputNodes = outputNodes == null ? List.of() : List.copyOf(outputNodes);
    }

    public GatewayGraphContract(String graphName,
                                SchemaEnvelope inputSchema,
                                SchemaEnvelope outputSchema,
                                List<String> outputNodes) {
        this(SCHEMA_VERSION, graphName, inputSchema, outputSchema, outputNodes);
    }
}
