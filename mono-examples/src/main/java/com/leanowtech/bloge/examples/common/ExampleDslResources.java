package com.leanowtech.bloge.examples.common;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.dsl.resolver.ClasspathGraphResolver;
import com.leanowtech.bloge.dsl.resolver.GraphResolver;

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

    /**
     * Compiles a DSL resource whose source may import sibling classpath resources.
     *
     * <p>The source location is set to the classpath resource so relative imports such as
     * {@code import "./payment-flow" as paymentFlow} resolve beside the root resource.</p>
     *
     * @param resourcePath absolute resource path such as {@code /bloge/modular/checkout.bloge}
     * @param registry     operator registry used for operator resolution during compilation
     * @return compiled graph definition
     */
    public static Graph loadGraphWithClasspathImports(String resourcePath, OperatorRegistry registry) {
        return loadGraph(resourcePath, registry, new ClasspathGraphResolver());
    }

    /**
     * Compiles a DSL resource with an explicit import resolver.
     *
     * @param resourcePath absolute resource path such as {@code /bloge/modular/checkout.bloge}
     * @param registry     operator registry used for operator resolution during compilation
     * @param resolver     graph resolver used by DSL {@code import} declarations
     * @return compiled graph definition
     */
    public static Graph loadGraph(String resourcePath, OperatorRegistry registry, GraphResolver resolver) {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        return new GraphLoader(registry)
                .withGraphResolver(resolver)
                .withSourceLocation("classpath:" + normalizedPath)
                .load(readResource(resourcePath));
    }
}
