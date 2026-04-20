package com.leanowtech.bloge.graphengine.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads bundled AI prompt resources from the classpath.
 */
public final class PromptResourceLoader {

    private PromptResourceLoader() {
    }

    /**
     * Loads one required prompt resource.
     *
     * @param resourcePath classpath path such as {@code ai/bloge-dsl-syntax-reference.md}
     * @return resource contents as UTF-8 text
     */
    public static String loadRequired(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        try (InputStream stream = PromptResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Prompt resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load prompt resource: " + resourcePath, exception);
        }
    }
}
