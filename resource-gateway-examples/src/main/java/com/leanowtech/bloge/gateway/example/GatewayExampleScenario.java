package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;
import java.util.Map;

/**
 * Public scenario metadata consumed by the Resource Gateway Showcase UI.
 *
 * @param graphName executable BLOGE graph name
 * @param title human-readable scenario title
 * @param graphFile classpath graph file that defines the behavior
 * @param pattern orchestration pattern demonstrated by the scenario
 * @param description concise explanation of the business flow
 * @param concepts DSL/runtime concepts that the scenario demonstrates
 * @param sampleInput browser-editable sample input
 * @param samplePresets browser-selectable sample cases
 * @param run recipe for invoking the existing gateway endpoint
 * @param decisionTable optional decision-table metadata for matrix-oriented scenarios
 * @param diagramPath API path that returns the visual layout
 * @param inputSchema formal graph context schema consumed by the runtime graph
 * @param outputSchema formal public output schema produced by the runtime graph
 */
public record GatewayExampleScenario(
        String graphName,
        String title,
        String graphFile,
        String pattern,
        String description,
        List<String> concepts,
        Map<String, Object> sampleInput,
        List<GatewayExamplePreset> samplePresets,
        GatewayExampleRun run,
        GatewayDecisionTable decisionTable,
        String diagramPath,
        SchemaEnvelope inputSchema,
        SchemaEnvelope outputSchema
) {
    public GatewayExampleScenario(String graphName,
                                  String title,
                                  String graphFile,
                                  String pattern,
                                  String description,
                                  List<String> concepts,
                                  Map<String, Object> sampleInput,
                                  List<GatewayExamplePreset> samplePresets,
                                  GatewayExampleRun run,
                                  GatewayDecisionTable decisionTable,
                                  String diagramPath) {
        this(graphName, title, graphFile, pattern, description, concepts, sampleInput, samplePresets, run,
                decisionTable, diagramPath, null, null);
    }

    /**
     * Creates scenario metadata.
     */
    public GatewayExampleScenario {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must not be blank");
        }
        title = (title == null || title.isBlank()) ? graphName : title;
        graphFile = (graphFile == null || graphFile.isBlank()) ? graphName + ".bloge" : graphFile;
        pattern = (pattern == null || pattern.isBlank()) ? "Graph orchestration" : pattern;
        description = description == null ? "" : description;
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        sampleInput = sampleInput == null ? Map.of() : Map.copyOf(sampleInput);
        samplePresets = samplePresets == null ? List.of() : List.copyOf(samplePresets);
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        diagramPath = (diagramPath == null || diagramPath.isBlank())
                ? "/api/gateway/examples/scenarios/" + graphName + "/diagram"
                : diagramPath;
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        outputSchema = outputSchema == null ? SchemaEnvelope.opaque() : outputSchema;
    }

    GatewayExampleScenario withSchemas(SchemaEnvelope inputSchema, SchemaEnvelope outputSchema) {
        return new GatewayExampleScenario(
                graphName,
                title,
                graphFile,
                pattern,
                description,
                concepts,
                sampleInput,
                samplePresets,
                run,
                decisionTable,
                diagramPath,
                inputSchema,
                outputSchema
        );
    }
}
