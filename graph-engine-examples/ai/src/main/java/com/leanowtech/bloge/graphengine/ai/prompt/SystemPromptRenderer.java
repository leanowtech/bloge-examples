package com.leanowtech.bloge.graphengine.ai.prompt;

/**
 * Renders the final system prompt passed to the LLM provider.
 */
public final class SystemPromptRenderer {

    private SystemPromptRenderer() {
    }

    /**
     * Renders one system prompt from the assembled context.
     *
     * @param syntaxReference syntax reference text
     * @param operatorCatalog operator catalog exposed to the model
     * @param fewShotExamples selected few-shot examples
     * @return rendered prompt
     */
    public static String render(String syntaxReference,
                                java.util.List<OperatorCatalogEntry> operatorCatalog,
                                java.util.List<FewShotExample> fewShotExamples) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are a BLOGE DSL expert.
                Generate exactly one valid .bloge definition that satisfies the user's request.
                Return only raw .bloge source with no markdown fences, headings, or explanations.
                Use only the syntax and operators described below unless you intentionally choose remote execution.
                When using remote execution, set execution_mode = remote and worker_topic = "..." on the node.

                <bloge-syntax-reference>
                """);
        prompt.append(syntaxReference.strip()).append("\n</bloge-syntax-reference>\n\n");

        prompt.append("<available-operators>\n");
        for (OperatorCatalogEntry entry : operatorCatalog) {
            prompt.append("- name: ").append(entry.name()).append('\n');
            if (!entry.description().isBlank()) {
                prompt.append("  description: ").append(entry.description()).append('\n');
            }
            if (!entry.owner().isBlank()) {
                prompt.append("  owner: ").append(entry.owner()).append('\n');
            }
            if (!entry.tags().isEmpty()) {
                prompt.append("  tags: ").append(String.join(", ", entry.tags())).append('\n');
            }
            if (!entry.promptHint().isBlank()) {
                prompt.append("  prompt_hint: ").append(entry.promptHint()).append('\n');
            }
            if (!entry.constraintsDescription().isBlank()) {
                prompt.append("  constraints: ").append(entry.constraintsDescription()).append('\n');
            }
            prompt.append("  input_schema: ").append(entry.inputSchema()).append('\n');
            prompt.append("  output_schema: ").append(entry.outputSchema()).append('\n');
            if (!entry.usageExample().isBlank()) {
                prompt.append("  usage_example: ").append(entry.usageExample().replace('\n', ' ')).append('\n');
            }
        }
        prompt.append("</available-operators>\n");

        if (!fewShotExamples.isEmpty()) {
            prompt.append("\n<few-shot-examples>\n");
            for (FewShotExample example : fewShotExamples) {
                prompt.append("### ").append(example.category()).append(" — ").append(example.title()).append('\n');
                if (!example.summary().isBlank()) {
                    prompt.append(example.summary()).append('\n');
                }
                prompt.append("```bloge\n")
                        .append(example.dslSource())
                        .append("\n```\n");
            }
            prompt.append("</few-shot-examples>\n");
        }
        return prompt.toString().strip();
    }
}
