package com.leanowtech.bloge.graphengine.ai.prompt;

/**
 * One curated few-shot BLOGE example used to guide generation.
 *
 * @param category broad example category
 * @param title human-readable example title
 * @param summary short prose summary taken from the prompt catalog
 * @param dslSource raw BLOGE source for the example
 */
public record FewShotExample(
        String category,
        String title,
        String summary,
        String dslSource
) {
    public FewShotExample {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        summary = summary == null ? "" : summary.strip();
    }
}
