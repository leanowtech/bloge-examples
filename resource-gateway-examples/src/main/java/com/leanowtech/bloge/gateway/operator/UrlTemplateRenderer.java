package com.leanowtech.bloge.gateway.operator;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders URL templates by replacing {@code {paramName}} placeholders with actual values.
 *
 * <p>Example:
 * <pre>{@code
 * var renderer = new UrlTemplateRenderer();
 * String url = renderer.render(
 *     "https://api.example.com/users/{userId}/profile",
 *     Map.of("userId", "u1")
 * );
 * // url == "https://api.example.com/users/u1/profile"
 * }</pre>
 */
public class UrlTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * Replaces all {@code {paramName}} placeholders in the template with corresponding
     * values from the provided map.
     *
     * @param urlTemplate the URL template with placeholders
     * @param pathValues  a map from placeholder names to their replacement values
     * @return the rendered URL with all placeholders substituted
     * @throws IllegalArgumentException if a placeholder has no corresponding value in the map
     */
    public String render(String urlTemplate, Map<String, String> pathValues) {
        if (urlTemplate == null) {
            throw new IllegalArgumentException("urlTemplate must not be null");
        }
        if (pathValues == null || pathValues.isEmpty()) {
            return urlTemplate;
        }

        Matcher matcher = PLACEHOLDER.matcher(urlTemplate);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = pathValues.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                    "No value provided for URL placeholder '{%s}' in template: %s".formatted(key, urlTemplate)
                );
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
