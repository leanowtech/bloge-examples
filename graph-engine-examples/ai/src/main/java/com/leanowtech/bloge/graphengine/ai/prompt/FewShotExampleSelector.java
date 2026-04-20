package com.leanowtech.bloge.graphengine.ai.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the curated few-shot markdown catalog and selects the examples most relevant to one
 * natural-language request.
 */
public final class FewShotExampleSelector {

    private static final Pattern BLOGE_BLOCK = Pattern.compile("```bloge\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern TITLE_PREFIX = Pattern.compile("^\\d+\\.\\s*");

    private final List<FewShotExample> catalog;

    /**
     * Creates a selector backed by the supplied markdown catalog.
     *
     * @param fewShotMarkdown contents of {@code few-shot-examples.md}
     */
    public FewShotExampleSelector(String fewShotMarkdown) {
        if (fewShotMarkdown == null || fewShotMarkdown.isBlank()) {
            throw new IllegalArgumentException("fewShotMarkdown must not be blank");
        }
        this.catalog = parseCatalog(fewShotMarkdown);
    }

    /**
     * Returns the parsed catalog.
     *
     * @return immutable example catalog
     */
    public List<FewShotExample> catalog() {
        return catalog;
    }

    /**
     * Selects up to {@code limit} examples that best match the supplied user request.
     *
     * @param naturalLanguageRequest user request
     * @param limit maximum number of examples to return
     * @return ordered few-shot examples
     */
    public List<FewShotExample> select(String naturalLanguageRequest, int limit) {
        Objects.requireNonNull(naturalLanguageRequest, "naturalLanguageRequest");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        if (limit == 0 || catalog.isEmpty()) {
            return List.of();
        }
        Set<String> requestTokens = tokenize(naturalLanguageRequest);
        if (requestTokens.isEmpty()) {
            return catalog.stream().limit(limit).toList();
        }
        return catalog.stream()
                .sorted(Comparator
                        .comparingInt((FewShotExample example) -> score(example, requestTokens))
                        .reversed()
                        .thenComparing(FewShotExample::title))
                .limit(limit)
                .toList();
    }

    private static int score(FewShotExample example, Set<String> requestTokens) {
        Set<String> headingTokens = tokenize(example.category() + " " + example.title());
        Set<String> summaryTokens = tokenize(example.summary());
        int matches = phraseBonus(example, requestTokens);
        for (String token : requestTokens) {
            if (headingTokens.contains(token)) {
                matches += 3;
            } else if (summaryTokens.contains(token)) {
                matches++;
            }
        }
        return matches;
    }

    private static List<FewShotExample> parseCatalog(String markdown) {
        String normalized = markdown.replace("\r\n", "\n");
        String[] sections = normalized.split("\n##\\s+");
        List<FewShotExample> examples = new ArrayList<>();
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i];
            int newline = section.indexOf('\n');
            if (newline < 0) {
                continue;
            }
            String heading = TITLE_PREFIX.matcher(section.substring(0, newline).trim()).replaceFirst("");
            String body = section.substring(newline + 1);
            Matcher codeMatcher = BLOGE_BLOCK.matcher(body);
            if (!codeMatcher.find()) {
                continue;
            }
            String[] titleParts = heading.split("\\s+—\\s+", 2);
            String category = titleParts.length > 1 ? titleParts[0].trim() : heading;
            String title = titleParts.length > 1 ? titleParts[1].trim() : heading;
            String summary = summarize(body.substring(0, codeMatcher.start()));
            String dslSource = codeMatcher.group(1).strip();
            examples.add(new FewShotExample(category, title, summary, dslSource));
        }
        return List.copyOf(examples);
    }

    private static String summarize(String prose) {
        return prose.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.startsWith("-") ? line.substring(1).trim() : line)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static int phraseBonus(FewShotExample example, Set<String> requestTokens) {
        String heading = (example.category() + " " + example.title()).toLowerCase(Locale.ROOT);
        if (requestTokens.contains("state") && requestTokens.contains("machine") && heading.contains("state machine")) {
            return 8;
        }
        if (requestTokens.contains("session") && heading.contains("session")) {
            return 6;
        }
        if (requestTokens.contains("loop") && heading.contains("loop")) {
            return 6;
        }
        if (requestTokens.contains("foreach") && heading.contains("foreach")) {
            return 6;
        }
        return 0;
    }
}
