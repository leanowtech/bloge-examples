package com.leanowtech.bloge.examples.integration.maven;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.util.Locale;

/**
 * Example operator used by the Maven plugin profile when exporting operator metadata.
 */
@BlogeOperator(
        value = "PluginPriorityOperator",
        description = "Scores a ticket so the Maven plugin example can export typed metadata",
        owner = "examples",
        tags = {"maven-plugin", "metadata"}
)
public class PluginPriorityOperator
        implements Operator<PluginPriorityOperator.PriorityRequest, PluginPriorityOperator.PriorityDecision> {

    /** Metadata-friendly request type for the plugin export. */
    public record PriorityRequest(String ticketId, String message) {
    }

    /** Metadata-friendly output type for the plugin export. */
    public record PriorityDecision(String priority, int score) {
    }

    @Override
    public PriorityDecision execute(PriorityRequest input, OperatorContext ctx) {
        String normalized = input.message().toLowerCase(Locale.ROOT);
        boolean urgent = normalized.contains("urgent") || normalized.contains("outage");
        return new PriorityDecision(urgent ? "high" : "normal", urgent ? 95 : 55);
    }
}
