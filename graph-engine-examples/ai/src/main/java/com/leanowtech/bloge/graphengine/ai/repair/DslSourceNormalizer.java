package com.leanowtech.bloge.graphengine.ai.repair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes raw LLM output into plain BLOGE DSL source.
 */
public final class DslSourceNormalizer {

    private static final Pattern FENCED_BLOCK =
            Pattern.compile("```(?:bloge)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private DslSourceNormalizer() {
    }

    /**
     * Strips markdown fences and trims whitespace from one LLM response.
     *
     * @param candidate raw LLM response content
     * @return normalized DSL source
     */
    public static String normalize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("candidate must not be blank");
        }
        Matcher matcher = FENCED_BLOCK.matcher(candidate);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return candidate.strip();
    }
}
