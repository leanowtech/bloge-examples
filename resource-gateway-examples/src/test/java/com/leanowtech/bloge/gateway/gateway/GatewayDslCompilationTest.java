package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.CompilationMode;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Compiles every {@code .bloge} file in {@code src/main/resources/bloge/gateway/}
 * and verifies each produces a valid {@link Graph}.
 *
 * <p>Uses LENIENT compilation mode so that stub operators (which the test can't
 * fully configure) don't abort compilation. The important contract verified here
 * is that the DSL syntax is valid and produces a non-empty graph.
 */
class GatewayDslCompilationTest {

    private static GraphLoader graphLoader;

    @BeforeAll
    static void setUp() {
        var registry = new DefaultOperatorRegistry();
        // Register stub operators for all operator types referenced in the .bloge files.
        registry.registerRaw("httpResource", new StubOperator());
        registry.registerRaw("MockMetaStreamingOperator", new StubOperator());
        registry.registerRaw("MockLlmTokenStreamingOperator", new StubOperator());
        registry.registerRaw("MockCitationStreamingOperator", new StubOperator());
        graphLoader = new GraphLoader(registry);
        graphLoader.withCompilationMode(CompilationMode.LENIENT);
    }

    @TestFactory
    Stream<DynamicTest> allBlogeFilesCompile() throws IOException {
        Path blogeDir = Path.of("src/main/resources/bloge/gateway");
        assertThat(Files.isDirectory(blogeDir))
                .as("bloge/gateway directory must exist")
                .isTrue();

        return Files.list(blogeDir)
                .filter(p -> p.toString().endsWith(".bloge"))
                .sorted()
                .map(file -> dynamicTest("compile " + file.getFileName(), () -> {
                    String dsl = Files.readString(file);
                    var result = graphLoader.loadWithDiagnostics(dsl);
                    assertThat(result.graph())
                            .as("Graph from %s must not be null", file.getFileName())
                            .isNotNull();
                    assertThat(result.graph().nodes())
                            .as("Nodes in %s must not be empty", file.getFileName())
                            .isNotEmpty();
                }));
    }

    /** Stub operator that satisfies registry lookups without real implementations. */
    private static class StubOperator implements com.leanowtech.bloge.core.operator.Operator<Object, Object> {
        @Override
        public Object execute(Object input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return null;
        }
    }
}
