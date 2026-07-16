package com.leanowtech.bloge.gateway.testing.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Architecture guard preventing governed test providers from entering production runtime paths. */
class ExecutionServicesBoundaryTest {

    @Test
    void governedTestServicesAreReferencedOnlyByTheTestingSubsystem() throws IOException {
        Path gateway = Path.of("src/main/java/com/leanowtech/bloge/gateway");
        Path testing = gateway.resolve("testing");
        try (Stream<Path> files = Files.walk(gateway)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(testing))
                    .flatMap(ExecutionServicesBoundaryTest::governedServiceReferences)
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    private static Stream<String> governedServiceReferences(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line.contains("GovernedExecutionServices"))
                    .map(line -> path + ": " + line.trim());
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to inspect " + path, failure);
        }
    }
}
