package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphArtifactFingerprintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameGraphIsStableAndDefinitionSourceChangesFingerprint() {
        Graph base = graph().withDefinitionSource(new GraphDefinitionSource(
                "1.0.0", "bloge-dsl-json", "{\"name\":\"fingerprinted\"}"));
        Graph changed = base.withDefinitionSource(new GraphDefinitionSource(
                "1.0.0", "bloge-dsl-json", "{\"name\":\"fingerprinted-v2\"}"));

        assertThat(GraphArtifactFingerprint.of(mapper, base))
                .isEqualTo(GraphArtifactFingerprint.of(mapper, base))
                .isNotEqualTo(GraphArtifactFingerprint.of(mapper, changed));
    }

    @Test
    void directEdgeCompletionSemanticsChangeFingerprint() {
        Graph base = graph();
        Graph completionRequired = new Graph(base.name(), base.nodes(),
                List.of(new Edge.Direct("first", "second", true)), base.sourceNodes(),
                base.terminalNodes(), base.schemaValidationLevel(), base.embeddedOperators(),
                base.declaredInputSchema(), base.declaredOutputSchema(), base.sagaConfig(),
                base.definitionSource(), base.streamingOutputNodeId(), base.streamingInputs());

        assertThat(GraphArtifactFingerprint.of(mapper, base))
                .isNotEqualTo(GraphArtifactFingerprint.of(mapper, completionRequired));
    }

    private static Graph graph() {
        Operator<Object, Object> identity = (input, context) -> input;
        return new GraphBuilder("fingerprinted")
                .node("first", identity)
                .node("second", identity).dependsOn("first")
                .build();
    }
}
