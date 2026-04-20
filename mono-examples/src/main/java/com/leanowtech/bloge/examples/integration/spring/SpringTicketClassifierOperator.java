package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.util.Locale;

/**
 * Spring-managed operator that classifies a support ticket into the queue the application should use.
 */
@BlogeOperator(
        value = "SpringTicketClassifierOperator",
        description = "Classifies a support ticket inside the Spring Boot starter example",
        owner = "examples",
        tags = {"spring", "starter", "triage"},
        promptHint = "Choose when a Spring-managed intake flow needs queue selection plus a "
                + "reusable priority score from ticket text and customer tier.",
        usageExample = """
                node classifyTicket : SpringTicketClassifierOperator {
                  input {
                    ticketId = ctx.ticketId
                    message = ctx.message
                    customerTier = ctx.customerTier
                  }
                }
                """,
        constraintsDescription = "message and customerTier must be available. The heuristic "
                + "prefers VIP routing first, then billing cues, then the general support queue."
)
public class SpringTicketClassifierOperator
{

    /** Caller payload injected from the graph context. */
    public record SpringTicketRequest(String ticketId, String message, String customerTier) {
    }

    /** Queue selection plus the priority score that downstream operators can reuse. */
    public record ClassifiedTicket(String queue, int priorityScore, boolean vip) {
    }

    /**
     * Applies the domain routing heuristic that the registry adapter exposes as a BLOGE operator.
     */
    public ClassifiedTicket classify(SpringTicketRequest input) {
        String normalizedMessage = input.message().toLowerCase(Locale.ROOT);
        boolean vip = "vip".equalsIgnoreCase(input.customerTier());
        boolean urgent = normalizedMessage.contains("urgent") || normalizedMessage.contains("outage");
        boolean billing = normalizedMessage.contains("refund") || normalizedMessage.contains("invoice");

        String queue = vip
                ? "vip-escalation"
                : billing
                ? "billing-support"
                : "general-support";
        int priorityScore = (vip ? 70 : 30) + (urgent ? 20 : 0) + (billing ? 10 : 0);
        return new ClassifiedTicket(queue, priorityScore, vip);
    }
}
