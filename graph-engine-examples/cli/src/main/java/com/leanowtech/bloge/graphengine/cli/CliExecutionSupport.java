package com.leanowtech.bloge.graphengine.cli;

import com.leanowtech.bloge.bpmn.api.ExpressionTranslationMode;
import com.leanowtech.bloge.bpmn.api.TranslationOptions;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingConfig;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingConfigLoader;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingRule;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Shared file-system, mapping, and option helpers used by the CLI runners.
 */
final class CliExecutionSupport {

    private static final String JSON_DEFAULT_MAPPING_RESOURCE = "/bpmn-json-import-defaults.json";
    private static final Path DEFAULT_MAPPING_FILE = Path.of("bpmn-operator-mapping.json");

    private CliExecutionSupport() {
    }

    static TranslationOptions toTranslationOptions(CliArgs args) {
        return new TranslationOptions(
                args.sourceComments(),
                args.docComments(),
                args.strict(),
                (Duration) null,
                Objects.requireNonNullElse(args.expressionMode(), ExpressionTranslationMode.AUTO)
        );
    }

    static List<ResolvedInput> resolveInputs(List<Path> declaredInputs) throws IOException {
        List<ResolvedInput> resolved = new ArrayList<>();
        for (Path declaredInput : declaredInputs) {
            if (!Files.exists(declaredInput)) {
                throw new IOException("Input not found: " + declaredInput);
            }
            if (Files.isDirectory(declaredInput)) {
                try (Stream<Path> stream = Files.walk(declaredInput)) {
                    stream.filter(Files::isRegularFile)
                            .filter(FormatDetector::isSupportedInput)
                            .sorted(Comparator.naturalOrder())
                            .forEach(path -> resolved.add(new ResolvedInput(
                                    declaredInput,
                                    path,
                                    declaredInput.relativize(path),
                                    true
                            )));
                }
            } else if (Files.isRegularFile(declaredInput)) {
                resolved.add(new ResolvedInput(
                        declaredInput,
                        declaredInput,
                        declaredInput.getFileName(),
                        false
                ));
            }
        }
        return List.copyOf(resolved);
    }

    static boolean isBatchMode(List<Path> declaredInputs) {
        if (declaredInputs.size() != 1) {
            return true;
        }
        return Files.isDirectory(declaredInputs.getFirst());
    }

    static Path resolveConvertOutputPath(CliArgs args, ResolvedInput input, boolean batchMode) {
        if (!batchMode && args.outputPath() == null) {
            throw new IllegalStateException("stdout mode does not have an output path");
        }

        if (!batchMode) {
            Path outputPath = args.outputPath();
            if (outputPath != null && Files.isDirectory(outputPath)) {
                return outputPath.resolve(toOutputFileName(input.file()));
            }
            return outputPath;
        }

        if (args.outputPath() == null) {
            return input.file().resolveSibling(toOutputFileName(input.file()));
        }

        Path relativePath = input.fromDirectory()
                ? input.relativePath()
                : Path.of(toOutputFileName(input.file()));
        Path relativeDir = relativePath.getParent() == null ? Path.of("") : relativePath.getParent();
        return args.outputPath()
                .resolve(relativeDir)
                .resolve(toOutputFileName(relativePath));
    }

    static OperatorMappingConfig loadMappingConfig(CliArgs args, CliArgs.Format format) throws IOException {
        OperatorMappingConfig effective = format == CliArgs.Format.JSON
                ? loadJsonDefaults()
                : OperatorMappingConfig.EMPTY;

        Path explicitMappingFile = args.mappingFile();
        if (explicitMappingFile != null) {
            return merge(effective, new OperatorMappingConfigLoader().load(explicitMappingFile));
        }
        if (Files.isRegularFile(DEFAULT_MAPPING_FILE)) {
            return merge(effective, new OperatorMappingConfigLoader().load(DEFAULT_MAPPING_FILE));
        }
        return effective;
    }

    static int combineExitCode(int current, int candidate) {
        return Math.max(current, candidate);
    }

    static void writeDsl(Path outputFile, String dsl) throws IOException {
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputFile, dsl);
    }

    static void printToolError(PrintStream err, Path input, Exception exception) {
        Objects.requireNonNull(err, "err must not be null");
        String prefix = input == null ? "Error" : input + ": error";
        err.println(prefix + " " + exception.getMessage());
    }

    private static OperatorMappingConfig loadJsonDefaults() throws IOException {
        try (InputStream inputStream = CliExecutionSupport.class.getResourceAsStream(JSON_DEFAULT_MAPPING_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing JSON BPMN import defaults: " + JSON_DEFAULT_MAPPING_RESOURCE);
            }
            return new OperatorMappingConfigLoader().load(inputStream);
        }
    }

    private static OperatorMappingConfig merge(OperatorMappingConfig base, OperatorMappingConfig overrides) {
        List<OperatorMappingRule> rules = new ArrayList<>(overrides.mappings());
        rules.addAll(base.mappings());
        Map<String, com.leanowtech.bloge.bpmn.mapping.DefaultMappingRule> defaults =
                new LinkedHashMap<>(base.defaults());
        defaults.putAll(overrides.defaults());
        return new OperatorMappingConfig(rules, defaults);
    }

    private static String toOutputFileName(Path path) {
        String fileName = Objects.requireNonNull(path.getFileName(), "path must point to a file").toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String stem = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        return stem + ".bloge";
    }

    /**
     * Expanded input metadata used for output path resolution.
     *
     * @param declaredInput original file or directory argument
     * @param file concrete file to process
     * @param relativePath path relative to the declared directory, or file name for direct file inputs
     * @param fromDirectory whether the file came from recursive directory traversal
     */
    record ResolvedInput(Path declaredInput, Path file, Path relativePath, boolean fromDirectory) {
    }
}
