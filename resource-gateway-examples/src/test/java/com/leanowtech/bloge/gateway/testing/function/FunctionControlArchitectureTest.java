package com.leanowtech.bloge.gateway.testing.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-boundary guard for the function-control deep module. */
class FunctionControlArchitectureTest {

    @Test
    void functionControlModuleDoesNotImportAdjacentControlMechanisms() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/leanowtech/bloge/gateway/testing/function");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::read)
                .collect(Collectors.joining("\n"));
        String fixtureRule = "Fixture" + "Rule";
        String selectorResolver = "Selector" + "Resolver";
        String nodeSpec = "Node" + "Spec";
        assertThat(source).doesNotContain(fixtureRule, selectorResolver, nodeSpec);
        assertThat(source).doesNotContain("FunctionControlAuditHook", "recordControlledInvocation");
        assertThat(source).doesNotContain("Thread.sleep", "System.currentTimeMillis",
                "System.nanoTime");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError("unable to inspect function control source", failure);
        }
    }
}
