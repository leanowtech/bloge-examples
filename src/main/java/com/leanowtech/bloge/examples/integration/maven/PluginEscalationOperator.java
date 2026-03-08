package com.leanowtech.bloge.examples.integration.maven;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

/**
 * Second operator in the Maven plugin example so the generated metadata includes multiple shapes.
 */
@BlogeOperator(
        value = "PluginEscalationOperator",
        description = "Turns the exported priority metadata into a routing decision",
        owner = "examples",
        tags = {"maven-plugin", "lint"}
)
public class PluginEscalationOperator
        implements Operator<PluginEscalationOperator.EscalationRequest, PluginEscalationOperator.EscalationPlan> {

    /** Input assembled from the lint-friendly DSL resource. */
    public record EscalationRequest(String ticketId, String priority, int score) {
    }

    /** Output that Studio metadata consumers can render in example catalogs. */
    public record EscalationPlan(String queue, String note) {
    }

    @Override
    public EscalationPlan execute(EscalationRequest input, OperatorContext ctx) {
        String queue = "high".equalsIgnoreCase(input.priority()) ? "manual-escalation" : "standard-review";
        return new EscalationPlan(queue, "Escalate ticket %s with score %d".formatted(input.ticketId(), input.score()));
    }
}
