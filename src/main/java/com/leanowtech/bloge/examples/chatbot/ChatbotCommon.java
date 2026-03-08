package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.operator.Operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared data structures and base operators for all chatbot examples.
 *
 * <p>Contains records used across customer-service, e-commerce and IT-helpdesk domains,
 * plus a common {@code INPUT_PARSER} and {@code FALLBACK_RESPONDER} operator.
 *
 * <p>Graph design note: branch target nodes must NOT add {@code .dependsOn(branchSource)}
 * because the engine already creates a dependency via the {@code ConditionalEdge}.
 * Adding a second edge would cause non-selected (SKIPPED) branches to be
 * erroneously enqueued when the {@code DirectEdge} decrements their pending-dep
 * counter to zero.
 */
@SuppressWarnings("preview")
public final class ChatbotCommon {

    private ChatbotCommon() {}

    // ── Records ───────────────────────────────────────────────────────────────

    public record ChatMessage(String role, String content, long timestamp) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, System.currentTimeMillis());
        }
        public static ChatMessage bot(String content) {
            return new ChatMessage("bot", content, System.currentTimeMillis());
        }
    }

    public record ChatHistory(List<ChatMessage> messages) {
        public static ChatHistory empty() {
            return new ChatHistory(List.of());
        }
        public ChatHistory append(ChatMessage message) {
            var updated = new ArrayList<>(messages);
            updated.add(message);
            return new ChatHistory(List.copyOf(updated));
        }
    }

    /** Normalised user message produced by the input-parsing stage. */
    public record ParsedInput(String normalizedText, String language, List<String> entities) {}

    /** Fully classified intent with confidence and extracted slots. */
    public record IntentResult(String intent, double confidence, Map<String, String> slots) {}

    /**
     * Final bot reply for a single conversation round.
     *
     * @param text            reply text to show the user
     * @param intent          intent that triggered this response
     * @param resolved        {@code true} when the conversation is complete
     * @param updatedHistory  history with the current round appended
     */
    public record BotResponse(String text, String intent, boolean resolved,
                              ChatHistory updatedHistory) {}

    /**
     * Per-round context passed to solvers via the {@link com.leanowtech.bloge.core.context.GraphContext}.
     *
     * @param userMessage current user input
     * @param sessionId   stable session identifier
     * @param history     conversation history up to (but not including) this round
     */
    public record RoundInput(String userMessage, String sessionId, ChatHistory history) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Constructs a {@link BotResponse} and appends the current round to the history.
     */
    public static BotResponse makeBotResponse(String text, String intent,
                                               boolean resolved, RoundInput roundInput) {
        ChatHistory prev = roundInput != null ? roundInput.history() : ChatHistory.empty();
        String userMsg = roundInput != null ? roundInput.userMessage() : "";
        ChatHistory updated = prev
                .append(ChatMessage.user(userMsg))
                .append(ChatMessage.bot(text));
        return new BotResponse(text, intent, resolved, updated);
    }

    // ── Shared operators ──────────────────────────────────────────────────────

    /**
     * Normalises the user message and extracts simple keyword entities.
     * Used as the first node in every Plan-A single-round graph.
     */
    public static final Operator<RoundInput, ParsedInput> INPUT_PARSER = (input, ctx) -> {
        String msg = input.userMessage().toLowerCase().trim();
        List<String> entities = new ArrayList<>();
        if (msg.contains("order"))     entities.add("order");
        if (msg.contains("password"))  entities.add("password");
        if (msg.contains("product"))   entities.add("product");
        if (msg.contains("invoice"))   entities.add("invoice");
        if (msg.contains("ticket"))    entities.add("ticket");
        return new ParsedInput(msg, "en", List.copyOf(entities));
    };

    /**
     * Fallback responder used as the {@code otherwise} branch target.
     * Returns {@code resolved=false} so the external loop continues the conversation.
     */
    public static final Operator<RoundInput, BotResponse> FALLBACK_RESPONDER = (input, ctx) ->
            makeBotResponse(
                    "I'm sorry, I didn't understand that. Could you please rephrase?",
                    "fallback", false, input);
}
