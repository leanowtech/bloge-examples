package com.leanowtech.bloge.gateway.visual.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard for the generic visual package boundary.
 *
 * <p>Decision D18 keeps gateway-specific execution and resource descriptor types behind adapters.
 * Visual services may depend on visual-owned ports, but not on resource-gateway showcase,
 * resource, or operator implementation packages.</p>
 */
class VisualRuntimeBoundaryTest {

    private static final Pattern NON_VISUAL_GATEWAY_IMPORT =
            Pattern.compile("^import com\\.leanowtech\\.bloge\\.gateway\\.(?!visual\\.).*;");

    @Test
    void visualPackageDoesNotImportGatewayImplementationTypes() throws IOException {
        assertThat(forbiddenImportsUnder("src/main/java/com/leanowtech/bloge/gateway/visual"))
                .isEmpty();
    }

    private static List<String> forbiddenImportsUnder(String relativePath) throws IOException {
        Path root = Path.of(relativePath);
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(VisualRuntimeBoundaryTest::forbiddenImports)
                    .toList();
        }
    }

    private static Stream<String> forbiddenImports(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> NON_VISUAL_GATEWAY_IMPORT.matcher(line).matches())
                    .map(line -> path + ": " + line);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect " + path, ex);
        }
    }
}
