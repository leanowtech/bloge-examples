package com.leanowtech.bloge.gateway.operator.streaming;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.stream.NodeChannel;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.util.List;
import java.util.Map;

/**
 * Mock streaming operator that emits citation/source-reference frames for the
 * AI-enriched search demo.
 *
 * <p>Produces a small fixed set of citation entries so that the SSE streaming
 * pipeline can be validated end-to-end without connecting to a real search index
 * or knowledge base. The citations are deterministic and include:
 * <ul>
 *   <li>{@code id} — citation identifier (e.g. {@code "cite-1"})</li>
 *   <li>{@code title} — source document title</li>
 *   <li>{@code url} — source URL</li>
 *   <li>{@code relevance} — a synthetic relevance score between 0 and 1</li>
 * </ul>
 */
@BlogeOperator(
    value = "MockCitationStreamingOperator",
    description = "Emits citation frames for the AI-enriched search demo",
    owner = "gateway-examples",
    tags = {"streaming", "citation", "search", "demo"}
)
public class MockCitationStreamingOperator implements StreamingOperator<Map<String, Object>, Map<String, Object>> {

    private static final List<Map<String, Object>> CITATIONS = List.of(
            Map.of("id", "cite-1", "title", "Introduction to Resource Gateways",
                    "url", "https://example.com/resource-gateways", "relevance", 0.95),
            Map.of("id", "cite-2", "title", "DAG Orchestration Patterns",
                    "url", "https://example.com/dag-patterns", "relevance", 0.87),
            Map.of("id", "cite-3", "title", "Streaming Aggregation Best Practices",
                    "url", "https://example.com/streaming-agg", "relevance", 0.72)
    );

    /**
     * Emits citation frames one at a time from a fixed set.
     *
     * @param input  the operator input (query is available but not used for citations)
     * @param output the node channel to write citation frames to
     * @param ctx    the operator context
     * @throws Exception if an error occurs during emission
     */
    @Override
    public void execute(Map<String, Object> input, NodeChannel<Map<String, Object>> output, OperatorContext ctx) throws Exception {
        for (Map<String, Object> citation : CITATIONS) {
            output.send(citation);
        }
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.IDEMPOTENT;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.READ_ONLY;
    }
}
