package com.leanowtech.bloge.examples.common;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads DSL examples from the classpath so example code can stay focused on graph behaviour.
 */
public final class ExampleDslResources {

    private ExampleDslResources() {
    }

    /**
     * Reads a UTF-8 resource from the example module classpath.
     *
     * @param resourcePath absolute resource path such as {@code /bloge/hello-world.bloge}
     * @return the resource contents as a string
     */
    public static String readResource(String resourcePath) {
        try (InputStream stream = ExampleDslResources.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing example DSL resource: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read example DSL resource: " + resourcePath, ioException);
        }
    }

    /**
     * Compiles a DSL resource into a graph using the supplied operator registry.
     *
     * @param resourcePath absolute resource path such as {@code /bloge/hello-world.bloge}
     * @param registry     operator registry used for operator resolution during compilation
     * @return compiled graph definition
     */
    public static Graph loadGraph(String resourcePath, OperatorRegistry registry) {
        return new GraphLoader(registry).load(readResource(resourcePath));
    }
}
