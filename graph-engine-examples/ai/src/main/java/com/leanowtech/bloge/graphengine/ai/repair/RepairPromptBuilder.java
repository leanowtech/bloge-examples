package com.leanowtech.bloge.graphengine.ai.repair;

/**
 * Renders user prompts for the initial generation call and later repair retries.
 */
public final class RepairPromptBuilder {

    private RepairPromptBuilder() {
    }

    /**
     * Builds the initial user prompt sent to the model.
     *
     * @param naturalLanguageRequest workflow goal in plain language
     * @return prompt text
     */
    public static String buildGenerationPrompt(String naturalLanguageRequest) {
        if (naturalLanguageRequest == null || naturalLanguageRequest.isBlank()) {
            throw new IllegalArgumentException("naturalLanguageRequest must not be blank");
        }
        return """
                Generate one BLOGE workflow definition for the following request.
                Return only raw .bloge source.

                User request:
                """ + naturalLanguageRequest.strip();
    }

    /**
     * Builds a repair prompt that asks the model to fix one invalid DSL candidate.
     *
     * @param naturalLanguageRequest original workflow goal
     * @param previousDsl previous DSL candidate
     * @param formattedDiagnostics diagnostics rendered for the model
     * @return repair prompt
     */
    public static String buildRepairPrompt(String naturalLanguageRequest,
                                           String previousDsl,
                                           String formattedDiagnostics) {
        if (naturalLanguageRequest == null || naturalLanguageRequest.isBlank()) {
            throw new IllegalArgumentException("naturalLanguageRequest must not be blank");
        }
        if (previousDsl == null || previousDsl.isBlank()) {
            throw new IllegalArgumentException("previousDsl must not be blank");
        }
        if (formattedDiagnostics == null || formattedDiagnostics.isBlank()) {
            throw new IllegalArgumentException("formattedDiagnostics must not be blank");
        }
        return """
                The previous BLOGE workflow candidate is invalid.
                Fix it so it satisfies the original request and all diagnostics below.
                Return only corrected raw .bloge source.

                Original request:
                """ + naturalLanguageRequest.strip() + """

                Previous candidate:
                ```bloge
                """ + previousDsl.strip() + """
                ```

                Diagnostics:
                """ + formattedDiagnostics.strip();
    }
}
