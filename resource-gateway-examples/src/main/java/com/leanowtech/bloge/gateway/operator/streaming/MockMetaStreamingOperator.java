package com.leanowtech.bloge.gateway.operator.streaming;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.stream.NodeChannel;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.util.Map;

/**
 * Mock streaming operator that emits a single metadata frame describing the search
 * result set.
 *
 * <p>In a real system this would query an index to obtain facet counts, category
 * breakdowns, and total hit counts. Here it produces deterministic output derived
 * from the query string so that tests can assert on the result without network calls.
 *
 * <p>The emitted map contains:
 * <ul>
 *   <li>{@code query} — the original search query</li>
 *   <li>{@code totalResults} — a synthetic result count</li>
 *   <li>{@code categories} — a fixed list of matching categories</li>
 *   <li>{@code timestamp} — the current epoch millis</li>
 * </ul>
 */
@BlogeOperator(
    value = "MockMetaStreamingOperator",
    description = "Emits a single search-metadata frame for AI-enriched search demo",
    owner = "gateway-examples",
    tags = {"streaming", "search", "metadata", "demo"}
)
public class MockMetaStreamingOperator implements StreamingOperator<Map<String, Object>, Map<String, Object>> {

    /**
     * Emits one metadata frame and closes the channel.
     *
     * @param input  the operator input containing the search query
     * @param output the node channel to write the metadata frame to
     * @param ctx    the operator context
     * @throws Exception if an error occurs during emission
     */
    @Override
    public void execute(Map<String, Object> input, NodeChannel<Map<String, Object>> output, OperatorContext ctx) throws Exception {
        String query = input != null ? String.valueOf(input.getOrDefault("query", "")) : "";
        output.send(Map.of(
                "query", query,
                "totalResults", query.length() * 17,
                "categories", java.util.List.of("technology", "science", "business"),
                "timestamp", System.currentTimeMillis()
        ));
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
