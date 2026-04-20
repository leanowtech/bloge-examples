package com.leanowtech.bloge.graphengine.service;

import java.util.List;

/**
 * Product-layer inventory entry for one registered operator, combining
 * registry metadata, annotation details, schema information, and usage
 * data from visible graph definitions and versions.
 *
 * @param name                   operator registration name
 * @param description            human-readable description from annotations
 * @param owner                  owning team or domain from annotations
 * @param tags                   discovery tags or capability labels
 * @param inputType              raw input class name
 * @param outputType             raw output class name
 * @param inputSchema            serialized input schema (JSON)
 * @param outputSchema           serialized output schema (JSON)
 * @param usageExample           optional DSL snippet showing usage
 * @param constraintsDescription optional planning constraints description
 * @param usage                  cross-definition usage summary
 */
public record OperatorInventoryEntry(
        String name,
        String description,
        String owner,
        List<String> tags,
        String inputType,
        String outputType,
        String inputSchema,
        String outputSchema,
        String usageExample,
        String constraintsDescription,
        OperatorUsageSummary usage
) {
    public OperatorInventoryEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description.strip();
        owner = owner == null ? "" : owner.strip();
        tags = tags == null ? List.of() : List.copyOf(tags);
        inputType = inputType == null ? "" : inputType;
        outputType = outputType == null ? "" : outputType;
        inputSchema = inputSchema == null ? "{}" : inputSchema;
        outputSchema = outputSchema == null ? "{}" : outputSchema;
        usageExample = usageExample == null ? "" : usageExample.strip();
        constraintsDescription = constraintsDescription == null ? "" : constraintsDescription.strip();
        usage = usage == null ? OperatorUsageSummary.EMPTY : usage;
    }
}
