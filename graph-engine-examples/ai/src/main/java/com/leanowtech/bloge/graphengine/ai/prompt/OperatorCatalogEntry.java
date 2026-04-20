package com.leanowtech.bloge.graphengine.ai.prompt;

import java.util.List;

/**
 * Prompt-facing summary of one available operator.
 *
 * @param name operator registration name
 * @param description human-readable description when known
 * @param owner owning team or domain when known
 * @param tags discovery tags or capability labels
 * @param promptHint optional LLM selection hint
 * @param usageExample optional DSL snippet showing usage
 * @param constraintsDescription optional planning constraints
 * @param inputSchema serialized input schema summary
 * @param outputSchema serialized output schema summary
 */
public record OperatorCatalogEntry(
        String name,
        String description,
        String owner,
        List<String> tags,
        String promptHint,
        String usageExample,
        String constraintsDescription,
        String inputSchema,
        String outputSchema
) {
    public OperatorCatalogEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description.strip();
        owner = owner == null ? "" : owner.strip();
        tags = tags == null ? List.of() : List.copyOf(tags);
        promptHint = promptHint == null ? "" : promptHint.strip();
        usageExample = usageExample == null ? "" : usageExample.strip();
        constraintsDescription = constraintsDescription == null ? "" : constraintsDescription.strip();
        inputSchema = inputSchema == null ? "{}" : inputSchema;
        outputSchema = outputSchema == null ? "{}" : outputSchema;
    }
}
