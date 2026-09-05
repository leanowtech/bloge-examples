package com.leanowtech.bloge.gateway.solution.demo;

import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic local sandbox used by the cancellation-dispute acceptance journey.
 *
 * <p>The ledger models three external systems without network access: order facts, refunds, and
 * human-review tickets. It is example infrastructure, not a production persistence adapter.</p>
 */
@Component
@ConditionalOnProperty(prefix = "gateway.agent-tdd.cancel-dispute-demo", name = "enabled",
        havingValue = "true")
public final class CancelDisputeDemoLedger {
    private final Map<String, OrderFacts> orders = Map.of(
            "O-FREE-NONE", new OrderFacts("none", true),
            "O-DRIVER", new OrderFacts("driver", false),
            "O-PASSENGER", new OrderFacts("passenger", false),
            "O-PLATFORM", new OrderFacts("platform", false));
    private final Map<String, Map<String, Object>> refunds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> tickets = new ConcurrentHashMap<>();

    /** Returns seeded order facts or fails closed for an unknown demo coordinate. */
    public OrderFacts requireOrder(String orderId) {
        OrderFacts facts = orders.get(normalized(orderId));
        if (facts == null) throw new SolutionContractException(
                "REFERENCE_UNRESOLVED", "Demo order is unavailable.");
        return facts;
    }

    /** Applies an idempotent full-waiver result to the demo refund system. */
    public Map<String, Object> waive(String orderId) {
        requireOrder(orderId);
        return refunds.computeIfAbsent(normalized(orderId), ignored -> Map.of(
                "decision", "WAIVED", "status", "COMPLETED"));
    }

    /** Applies an idempotent human-review result to the demo ticket system. */
    public Map<String, Object> escalate(String orderId) {
        requireOrder(orderId);
        return tickets.computeIfAbsent(normalized(orderId), ignored -> Map.of(
                "decision", "ESCALATED", "status", "OPEN"));
    }

    /** Reads a previously applied refund for reconciliation. */
    public Map<String, Object> refund(String orderId) {
        return Map.copyOf(refunds.getOrDefault(normalized(orderId), Map.of()));
    }

    /** Reads a previously opened ticket for reconciliation. */
    public Map<String, Object> ticket(String orderId) {
        return Map.copyOf(tickets.getOrDefault(normalized(orderId), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Seeded responsibility and cancellation-window facts for one demo order. */
    public record OrderFacts(String responsibilityParty, boolean withinFreeWindow) { }
}
