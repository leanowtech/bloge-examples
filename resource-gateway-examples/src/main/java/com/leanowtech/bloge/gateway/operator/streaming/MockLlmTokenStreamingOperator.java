package com.leanowtech.bloge.gateway.operator.streaming;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.stream.NodeChannel;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.util.Map;

/**
 * Mock streaming operator that emits LLM-style answer tokens derived from the
 * search query string.
 *
 * <p>Each word in the query is emitted as a separate token chunk with a short
 * inter-token delay to simulate real LLM token generation. This keeps the operator
 * deterministic and lightweight so that integration tests can validate the full
 * streaming pipeline without requiring an actual language model.
 *
 * <p>Each emitted chunk contains:
 * <ul>
 *   <li>{@code token} — a single word from the generated answer</li>
 *   <li>{@code index} — zero-based token position</li>
 *   <li>{@code final} — whether this is the last token</li>
 * </ul>
 */
@BlogeOperator(
    value = "MockLlmTokenStreamingOperator",
    description = "Emits LLM answer tokens derived from the search query for demo/test purposes",
    owner = "gateway-examples",
    tags = {"streaming", "llm", "token", "demo"}
)
public class MockLlmTokenStreamingOperator implements StreamingOperator<Map<String, Object>, Map<String, Object>> {

    private static final String[] PREFIX_TOKENS = {
            "Based", "on", "the", "search", "query,"
    };

    /**
     * Emits token chunks: a fixed prefix followed by each word of the query string.
     *
     * @param input  the operator input containing the search query
     * @param output the node channel to write token chunks to
     * @param ctx    the operator context
     * @throws Exception if an error occurs during emission
     */
    @Override
    public void execute(Map<String, Object> input, NodeChannel<Map<String, Object>> output, OperatorContext ctx) throws Exception {
        String query = input != null ? String.valueOf(input.getOrDefault("query", "")) : "";
        String[] queryWords = query.isBlank() ? new String[]{"(empty)"} : query.split("\\s+");

        String[] allTokens = new String[PREFIX_TOKENS.length + queryWords.length];
        System.arraycopy(PREFIX_TOKENS, 0, allTokens, 0, PREFIX_TOKENS.length);
        System.arraycopy(queryWords, 0, allTokens, PREFIX_TOKENS.length, queryWords.length);

        for (int i = 0; i < allTokens.length; i++) {
            boolean last = (i == allTokens.length - 1);
            output.send(Map.of(
                    "token", allTokens[i],
                    "index", i,
                    "final", last
            ));
            // Brief pause to simulate LLM generation latency
            if (!last) {
                Thread.sleep(5);
            }
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
