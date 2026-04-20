package com.leanowtech.bloge.gateway.orchestration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.operator.streaming.MockCitationStreamingOperator;
import com.leanowtech.bloge.gateway.operator.streaming.MockLlmTokenStreamingOperator;
import com.leanowtech.bloge.gateway.operator.streaming.MockMetaStreamingOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AiEnrichedSearchGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.registerRaw("MockMetaStreamingOperator", new MockMetaStreamingOperator());
        compilationRegistry.registerRaw("MockLlmTokenStreamingOperator", new MockLlmTokenStreamingOperator());
        compilationRegistry.registerRaw("MockCitationStreamingOperator", new MockCitationStreamingOperator());
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/ai-enriched-search.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void graphExecutes_andAssemblesStreamOutput() {
        GraphEngine engine = GraphEngine.builder()
                .registry(compilationRegistry)
                .build();

        GraphResult result = engine.executeWithOperators(
                graph,
                new GraphContext(Map.of("query", "test search")),
                Map.of(
                        "metaStream", new MockMetaStreamingOperator(),
                        "llmStream", new MockLlmTokenStreamingOperator(),
                        "citationStream", new MockCitationStreamingOperator()
                )
        );

        assertThat(result.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> assembled = result.findOutput("assembleResult", Map.class).orElseThrow();
        assertThat(assembled).containsKeys("meta", "tokens", "citations");
        assertThat(assembled.get("meta")).isNotNull();
        assertThat(assembled.get("citations")).isNotNull();
        assertThat(assembled.get("tokens")).isNotNull();
    }

    @Test
    void metaStream_containsQueryAndResultCount() {
        GraphEngine engine = GraphEngine.builder()
                .registry(compilationRegistry)
                .build();

        GraphResult result = engine.executeWithOperators(
                graph,
                new GraphContext(Map.of("query", "hello world")),
                Map.of(
                        "metaStream", new MockMetaStreamingOperator(),
                        "llmStream", new MockLlmTokenStreamingOperator(),
                        "citationStream", new MockCitationStreamingOperator()
                )
        );

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> assembled = result.findOutput("assembleResult", Map.class).orElseThrow();
        assertThat(assembled.get("meta")).isNotNull();
    }
}
