package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.spring.annotation.BlogeOperator;

/**
 * Spring-managed operator that turns the routing decision into a responder-friendly draft.
 */
@BlogeOperator(
        value = "SpringReplyDraftOperator",
        description = "Drafts a support response after the Spring starter routes a ticket",
        owner = "examples",
        tags = {"spring", "starter", "response"}
)
public class SpringReplyDraftOperator
{

    /** Input assembled from the upstream routing node. */
    public record ReplyDraftRequest(String ticketId, String queue, int priorityScore, boolean vip) {
    }

    /** Final payload returned to the controller and visible in actuator diagnostics. */
    public record ReplyDraft(String ticketId, String queue, String owner, String responseTemplate) {
    }

    /**
     * Creates the support-agent draft that the adapted BLOGE operator returns.
     */
    public ReplyDraft draft(ReplyDraftRequest input) {
        String owner = input.vip() ? "vip-desk" : "standard-desk";
        String responseTemplate = input.priorityScore() >= 80
                ? "Escalate immediately and keep the customer updated every 15 minutes."
                : "Acknowledge the request and respond from the queued support lane.";
        return new ReplyDraft(input.ticketId(), input.queue(), owner, responseTemplate);
    }
}
