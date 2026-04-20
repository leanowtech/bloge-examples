package com.leanowtech.bloge.graphengine.ai.prompt;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OperatorCatalogBuilderTest {

    @Test
    void buildReadsSpringOperatorPromptMetadata() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("classifyTicket", new TicketClassifierOperator());

        List<OperatorCatalogEntry> entries = new OperatorCatalogBuilder(registry).build();

        assertEquals(1, entries.size());
        OperatorCatalogEntry entry = entries.getFirst();
        assertEquals("classifyTicket", entry.name());
        assertEquals("Routes support tickets into queues.", entry.description());
        assertEquals("support-platform", entry.owner());
        assertEquals(List.of("routing", "triage"), entry.tags());
        assertEquals("Choose when the workflow needs ticket routing.", entry.promptHint());
        assertEquals("node classify : classifyTicket {}", entry.usageExample());
        assertEquals("Requires ticket.subject and ticket.body.", entry.constraintsDescription());
        assertFalse(entry.inputSchema().isBlank());
        assertFalse(entry.outputSchema().isBlank());
    }

    @BlogeOperator(
            value = "classifyTicket",
            description = "Routes support tickets into queues.",
            owner = "support-platform",
            tags = {"routing", "triage"},
            promptHint = "Choose when the workflow needs ticket routing.",
            usageExample = "node classify : classifyTicket {}",
            constraintsDescription = "Requires ticket.subject and ticket.body."
    )
    private static final class TicketClassifierOperator implements Operator<String, String> {
        @Override
        public String execute(String input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return input;
        }
    }
}
