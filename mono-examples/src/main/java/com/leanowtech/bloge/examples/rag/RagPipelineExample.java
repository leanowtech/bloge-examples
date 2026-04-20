package com.leanowtech.bloge.examples.rag;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.operators.ai.AiTypes;
import com.leanowtech.bloge.operators.ai.EmbeddingOperator;
import com.leanowtech.bloge.operators.ai.LlmChatOperator;
import com.leanowtech.bloge.operators.ai.RagRetrieveOperator;
import com.leanowtech.bloge.operators.spi.LlmProvider;
import com.leanowtech.bloge.operators.spi.VectorStoreProvider;
import com.leanowtech.bloge.operators.transform.JsonStringifyOperator;

import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) pipeline example using bloge-common-operators.
 *
 * <h2>Graph layout</h2>
 * <pre>
 *   ragRetrieve  ──► embeds query, searches vector store, returns top-K VectorMatch objects
 *       │
 *   formatContext  ► joins matched document texts into a single context string
 *       │
 *   llmChat        ► calls the LLM with augmented prompt (system context + user query)
 *       │
 *   jsonOutput     ► serialises the LLM answer text to a JSON string
 * </pre>
 *
 * <p>Concrete LLM and vector-store implementations are injected via SPI interfaces
 * ({@link LlmProvider}, {@link VectorStoreProvider}, {@link EmbeddingOperator.EmbeddingProvider})
 * so this example compiles and runs with any backend without pulling extra dependencies.
 *
 * <p>{@link MockLlmProvider}, {@link MockEmbeddingProvider}, and
 * {@link MockVectorStoreProvider} make the example fully self-contained.
 *
 * <h2>Important</h2>
 * <p>Operators passed to {@code .node(id, operator)} in the graph builder are used only for
 * schema introspection at build time; the engine resolves operators at runtime from the
 * {@code operatorMap} parameter of {@link GraphEngine#executeWithOperators}.
 * Always supply the same operator instances in both places.
 */
public class RagPipelineExample {

    static final String K_QUERY = "query";

    // ── Operators ─────────────────────────────────────────────────────────────
    // Defined as constants so the same instances are used in both buildGraph()
    // and operatorMap(), which is required for correct runtime resolution.

    static final Operator<AiTypes.RetrieveInput, AiTypes.RetrieveOutput> RAG_RETRIEVE =
            new RagRetrieveOperator(new MockEmbeddingProvider(), new MockVectorStoreProvider());

    static final Operator<AiTypes.RetrieveOutput, String> FORMAT_CONTEXT =
            (out, opCtx) -> {
                var sb = new StringBuilder();
                for (var match : out.matches()) sb.append(match.text()).append("\n---\n");
                return sb.toString().strip();
            };

    static final Operator<AiTypes.LlmChatInput, AiTypes.LlmChatOutput> LLM_CHAT =
            new LlmChatOperator(new MockLlmProvider());

    static final Operator<Object, String> JSON_OUTPUT =
            new JsonStringifyOperator(JsonCodec.DEFAULT);

    // ── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx    = new GraphContext(Map.of(K_QUERY, "What is retrieval-augmented generation?"));

        GraphResult result = engine.executeWithOperators(buildGraph(), ctx, operatorMap());

        if (!result.errors().isEmpty()) {
            result.errors().forEach(e ->
                    System.err.printf("Node '%s' failed: %s%n", e.nodeId(), e.exception().getMessage()));
        } else {
            System.out.printf("Answer : %s%n", result.results().get("jsonOutput", Object.class));
        }
    }

    // ── Graph definition ─────────────────────────────────────────────────────

    public static Graph buildGraph() {
        return Graph.builder("ragPipeline")

                // 1. Retrieve relevant document chunks from vector store
                .node("ragRetrieve", RAG_RETRIEVE)
                    .input((results, ctx) ->
                            new AiTypes.RetrieveInput("docs", ctx.get(K_QUERY, String.class), 3, 0.5))

                // 2. Assemble retrieved matches into a single context string
                .node("formatContext", FORMAT_CONTEXT)
                    .dependsOn("ragRetrieve")
                    .input((results, ctx) -> results.get("ragRetrieve", AiTypes.RetrieveOutput.class))

                // 3. Call the LLM with augmented prompt
                .node("llmChat", LLM_CHAT)
                    .dependsOn("formatContext")
                    .input((results, ctx) -> new AiTypes.LlmChatInput(
                            "mock-gpt-4",
                            List.of(
                                    new LlmProvider.LlmMessage("system",
                                            "Answer using only the context below.\n\n"
                                            + results.get("formatContext", String.class)),
                                    new LlmProvider.LlmMessage("user",
                                            ctx.get(K_QUERY, String.class))),
                            0.2, 512, null))

                // 4. Serialise the LLM answer to JSON
                .node("jsonOutput", JSON_OUTPUT)
                    .dependsOn("llmChat")
                    .input((results, ctx) -> {
                        var llmOut = results.get("llmChat", AiTypes.LlmChatOutput.class);
                        return llmOut != null ? llmOut.content() : "";
                    })

                .build();
    }

    /**
     * Operator map required by {@link GraphEngine#executeWithOperators}.
     * Keys are node IDs; values are the same operator instances used in {@link #buildGraph()}.
     */
    public static Map<String, Operator<?, ?>> operatorMap() {
        return Map.of(
                "ragRetrieve",   RAG_RETRIEVE,
                "formatContext", FORMAT_CONTEXT,
                "llmChat",       LLM_CHAT,
                "jsonOutput",    JSON_OUTPUT
        );
    }

    // ── Mock SPI implementations (self-contained for demo) ───────────────────

    /** Mock LLM that echoes the user message with a [MockLLM] prefix. */
    static class MockLlmProvider implements LlmProvider {
        @Override
        public LlmResponse chat(LlmRequest request) {
            String userMsg = request.messages().stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(LlmMessage::content)
                    .findFirst().orElse("");
            return new LlmResponse("[MockLLM] Answer to: " + userMsg, "stop",
                    request.messages().size() * 10, 42);
        }

        @Override
        public java.util.stream.Stream<LlmChunk> streamChat(LlmRequest request) {
            return java.util.stream.Stream.of(
                    new LlmChunk("[MockLLM] streaming... ", false),
                    new LlmChunk(request.messages().getLast().content(), true));
        }
    }

    /** Mock embedding provider that returns a fixed vector for any text. */
    static class MockEmbeddingProvider implements EmbeddingOperator.EmbeddingProvider {
        @Override
        public List<float[]> embed(String model, List<String> texts) {
            return texts.stream().map(t -> new float[]{0.1f, 0.2f, 0.3f}).toList();
        }
    }

    /** Mock vector store returning hard-coded matches for any query vector. */
    static class MockVectorStoreProvider implements VectorStoreProvider {
        @Override
        public List<VectorMatch> search(String collection, float[] vector, int topK) {
            return List.of(
                    new VectorMatch("doc1", 0.95f,
                            "RAG stands for Retrieval-Augmented Generation.", Map.of()),
                    new VectorMatch("doc2", 0.90f,
                            "It combines dense retrieval with a generative language model.", Map.of()),
                    new VectorMatch("doc3", 0.85f,
                            "Vector stores retrieve semantically similar documents.", Map.of())
            );
        }

        @Override
        public void upsert(String collection, List<VectorDocument> docs) { /* no-op */ }

        @Override
        public void delete(String collection, List<String> ids) { /* no-op */ }
    }
}
