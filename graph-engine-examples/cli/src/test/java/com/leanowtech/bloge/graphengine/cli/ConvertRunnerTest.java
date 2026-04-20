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
 * Tests for {@link ConvertRunner}.
 */
class ConvertRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void convertXmlSingleFileWritesDslToStdout() throws Exception {
        Path input = copyResource("bpmn/simple-sequential.bpmn", tempDir.resolve("simple-sequential.bpmn"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ConvertRunner(printStream(out), printStream(err))
                .run(CliArgs.parse(new String[]{"convert", input.toString()}));

        assertEquals(0, exitCode);
        assertTrue(utf8(out).contains("graph simpleSequential"));
        assertTrue(utf8(err).contains("[WARN] UNMAPPED_OPERATOR"));
    }

    @Test
    void convertJsonSingleFileUsesBundledDefaultMappings() throws Exception {
        Path input = copyResource("bpmn/json/small-sequential.json", tempDir.resolve("small-sequential.json"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ConvertRunner(printStream(out), printStream(err))
                .run(CliArgs.parse(new String[]{"convert", input.toString()}));

        assertEquals(0, exitCode);
        assertTrue(utf8(out).contains("graph simpleSequentialJson"));
        assertTrue(utf8(out).contains("serviceCall"));
        assertEquals("", utf8(err));
    }

    @Test
    void convertMissingInputReturnsToolError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ConvertRunner(printStream(out), printStream(err))
                .run(CliArgs.parse(new String[]{"convert", tempDir.resolve("missing.bpmn").toString()}));

        assertEquals(2, exitCode);
        assertTrue(utf8(err).contains("Input not found"));
    }

    @Test
    void convertStrictModePromotesWarningsToExitCodeOne() throws Exception {
        Path input = copyResource("bpmn/simple-sequential.bpmn", tempDir.resolve("strict-simple-sequential.bpmn"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ConvertRunner(printStream(out), printStream(err))
                .run(CliArgs.parse(new String[]{"convert", "--strict", input.toString()}));

        assertEquals(1, exitCode);
        assertTrue(utf8(out).contains("graph simpleSequential"));
        assertTrue(utf8(err).contains("[ERROR] POTENTIAL_DATA_LOSS"));
    }

    @Test
    void convertDirectoryWritesOutputsIntoRequestedDirectory() throws Exception {
        Path inputDir = tempDir.resolve("input");
        Path nestedDir = inputDir.resolve("nested");
        Files.createDirectories(nestedDir);
        copyResource("bpmn/simple-sequential.bpmn", inputDir.resolve("simple-sequential.bpmn"));
        copyResource("bpmn/json/small-sequential.json", nestedDir.resolve("small-sequential.json"));
        Path outputDir = tempDir.resolve("output");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = new ConvertRunner(printStream(out), printStream(err))
                .run(CliArgs.parse(new String[]{
                        "convert",
                        "--output",
                        outputDir.toString(),
                        inputDir.toString()
                }));

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputDir.resolve("simple-sequential.bloge")));
        assertTrue(Files.exists(outputDir.resolve("nested").resolve("small-sequential.bloge")));
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

    private PrintStream printStream(ByteArrayOutputStream outputStream) {
        return new PrintStream(outputStream, true, StandardCharsets.UTF_8);
    }

    private String utf8(ByteArrayOutputStream outputStream) {
        return outputStream.toString(StandardCharsets.UTF_8);
    }
}
