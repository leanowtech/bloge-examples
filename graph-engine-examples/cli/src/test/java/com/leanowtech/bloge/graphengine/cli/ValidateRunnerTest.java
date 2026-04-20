package com.leanowtech.bloge.graphengine.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ValidateRunner}.
 */
class ValidateRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void validateCleanDiagramReturnsZero() throws Exception {
        Path input = writeString(tempDir.resolve("clean.bpmn"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="cleanProcess" name="cleanProcess">
                    <startEvent id="start"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow" sourceRef="start" targetRef="end"/>
                  </process>
                </definitions>
                """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ValidateRunner(printStream(err))
                .run(CliArgs.parse(new String[]{"validate", input.toString()}));

        assertEquals(0, exitCode);
        assertEquals("", utf8(err));
    }

    @Test
    void validateWarningsStillReturnZero() throws Exception {
        Path input = copyResource("bpmn/simple-sequential.bpmn", tempDir.resolve("simple-sequential.bpmn"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ValidateRunner(printStream(err))
                .run(CliArgs.parse(new String[]{"validate", input.toString()}));

        assertEquals(0, exitCode);
        assertTrue(utf8(err).contains("[WARN] UNMAPPED_OPERATOR"));
    }

    @Test
    void validateStrictModeReturnsOneWhenWarningsExist() throws Exception {
        Path input = copyResource("bpmn/simple-sequential.bpmn", tempDir.resolve("strict-simple-sequential.bpmn"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ValidateRunner(printStream(err))
                .run(CliArgs.parse(new String[]{"validate", "--strict", input.toString()}));

        assertEquals(1, exitCode);
        assertTrue(utf8(err).contains("[ERROR] POTENTIAL_DATA_LOSS"));
    }

    private Path copyResource(String resourceName, Path target) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Missing test resource: " + resourceName);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(inputStream, target);
            return target;
        }
    }

    private Path writeString(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.writeString(target, content);
    }

    private PrintStream printStream(ByteArrayOutputStream outputStream) {
        return new PrintStream(outputStream, true, StandardCharsets.UTF_8);
    }

    private String utf8(ByteArrayOutputStream outputStream) {
        return outputStream.toString(StandardCharsets.UTF_8);
    }
}
