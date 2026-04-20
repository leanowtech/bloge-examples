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
 * End-to-end tests for {@link BpmnToDslCli#run(String[], PrintStream, PrintStream)}.
 */
class BpmnToDslCliTest {

    @TempDir
    Path tempDir;

    @Test
    void runConvertReturnsZeroAndWritesDslToStdout() throws Exception {
        Path input = copyResource("bpmn/simple-sequential.bpmn", tempDir.resolve("simple-sequential.bpmn"));
        Path mappingFile = writeString(tempDir.resolve("mapping.json"), """
                {
                  "mappings": [],
                  "defaults": {
                    "serviceTask": { "operatorRef": "${taskDefinitionKey}Operator" }
                  }
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BpmnToDslCli.run(
                new String[]{"convert", "--mapping", mappingFile.toString(), input.toString()},
                printStream(out),
                printStream(err)
        );

        assertEquals(0, exitCode);
        assertTrue(utf8(out).contains("graph simpleSequential"));
        assertEquals("", utf8(err));
    }

    @Test
    void runMissingInputReturnsToolError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BpmnToDslCli.run(
                new String[]{"validate", tempDir.resolve("missing.bpmn").toString()},
                printStream(out),
                printStream(err)
        );

        assertEquals(2, exitCode);
        assertTrue(utf8(err).contains("Input not found"));
    }

    @Test
    void runHelpWritesUsageAndReturnsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BpmnToDslCli.run(new String[]{"--help"}, printStream(out), printStream(err));

        assertEquals(0, exitCode);
        assertTrue(utf8(out).contains("bloge-bpmn <command>"));
        assertEquals("", utf8(err));
    }

    @Test
    void runRejectsOutputForValidate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = BpmnToDslCli.run(
                new String[]{"validate", "--output", tempDir.resolve("out").toString(), "input.bpmn"},
                printStream(out),
                printStream(err)
        );

        assertEquals(2, exitCode);
        assertTrue(utf8(err).contains("--output is only supported by the convert command."));
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
