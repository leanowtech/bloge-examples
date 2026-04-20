package com.leanowtech.bloge.examples.resources;

import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ExampleDslResourcesParseTest {

    @TestFactory
    Stream<DynamicTest> allExampleDslResourcesParse() throws IOException {
        Path resourcesRoot = resolveResourcesRoot();
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            return files
                    .filter(path -> path.toString().endsWith(".bloge"))
                    .sorted(Comparator.naturalOrder())
                    .map(path -> DynamicTest.dynamicTest(
                            resourcesRoot.relativize(path).toString(),
                            () -> assertDoesNotThrow(() -> new Parser(new Lexer(Files.readString(path)).tokenize()).parseAst())
                    ))
                    .toList()
                    .stream();
        }
    }

    private static Path resolveResourcesRoot() {
        Path moduleRoot = Path.of("src", "main", "resources", "bloge");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }

        Path repoRoot = Path.of("bloge-examples", "src", "main", "resources", "bloge");
        if (Files.isDirectory(repoRoot)) {
            return repoRoot;
        }

        throw new IllegalStateException("Could not locate bloge-examples DSL resources");
    }
}
