package com.leanowtech.bloge.graphengine.cli;

import com.leanowtech.bloge.bpmn.api.ExpressionTranslationMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable CLI argument model for the BPMN conversion tool.
 *
 * @param command selected sub-command
 * @param inputs input files or directories
 * @param outputPath explicit output file or directory, when supplied
 * @param format forced format override or {@link Format#AUTO}
 * @param mappingFile explicit mapping file, or {@code null} to use default lookup behavior
 * @param strict whether warnings should become fatal
 * @param sourceComments whether source comments should be emitted into DSL output
 * @param docComments whether BPMN documentation comments should be emitted into DSL output
 * @param expressionMode expression translation mode
 */
public record CliArgs(
        Command command,
        List<Path> inputs,
        Path outputPath,
        Format format,
        Path mappingFile,
        boolean strict,
        boolean sourceComments,
        boolean docComments,
        ExpressionTranslationMode expressionMode
) {

    /**
     * Supported top-level commands.
     */
    public enum Command {
        CONVERT,
        VALIDATE;

        private static Command parse(String token) throws CliUsageException {
            return switch (token) {
                case "convert" -> CONVERT;
                case "validate" -> VALIDATE;
                default -> throw new CliUsageException("Unknown command: " + token);
            };
        }
    }

    /**
     * Supported BPMN source formats.
     */
    public enum Format {
        AUTO,
        XML,
        JSON;

        private static Format parse(String token) throws CliUsageException {
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "xml" -> XML;
                case "json" -> JSON;
                default -> throw new CliUsageException("Unsupported format: " + token);
            };
        }
    }

    public CliArgs {
        command = Objects.requireNonNull(command, "command must not be null");
        inputs = List.copyOf(Objects.requireNonNullElse(inputs, List.of()));
        format = Objects.requireNonNullElse(format, Format.AUTO);
        expressionMode = Objects.requireNonNullElse(expressionMode, ExpressionTranslationMode.AUTO);
    }

    /**
     * Parses raw command-line arguments without depending on a third-party CLI framework.
     *
     * @param args raw command-line arguments
     * @return parsed and validated arguments
     * @throws CliUsageException when the arguments are invalid or usage was requested
     */
    public static CliArgs parse(String[] args) throws CliUsageException {
        if (args == null || args.length == 0) {
            throw new CliUsageException("Missing command.");
        }

        if (isHelpToken(args[0])) {
            throw CliUsageException.forUsageOnly();
        }

        Command command = Command.parse(args[0]);
        List<Path> inputs = new ArrayList<>();
        Path outputPath = null;
        Path mappingFile = null;
        Format format = Format.AUTO;
        boolean strict = false;
        boolean sourceComments = true;
        boolean docComments = true;
        ExpressionTranslationMode expressionMode = ExpressionTranslationMode.AUTO;

        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (isHelpToken(token)) {
                throw CliUsageException.forUsageOnly();
            }

            switch (token) {
                case "--format" -> format = Format.parse(requiredValue(args, ++index, token));
                case "--output" -> outputPath = Path.of(requiredValue(args, ++index, token));
                case "--mapping" -> mappingFile = Path.of(requiredValue(args, ++index, token));
                case "--strict" -> strict = true;
                case "--no-source-comments" -> sourceComments = false;
                case "--no-doc-comments" -> docComments = false;
                case "--expression-mode" -> expressionMode = parseExpressionMode(requiredValue(args, ++index, token));
                default -> {
                    if (token.startsWith("-")) {
                        throw new CliUsageException("Unknown option: " + token);
                    }
                    inputs.add(Path.of(token));
                }
            }
        }

        if (inputs.isEmpty()) {
            throw new CliUsageException("At least one input path is required.");
        }
        if (command == Command.VALIDATE && outputPath != null) {
            throw new CliUsageException("--output is only supported by the convert command.");
        }

        return new CliArgs(
                command,
                inputs,
                outputPath,
                format,
                mappingFile,
                strict,
                sourceComments,
                docComments,
                expressionMode
        );
    }

    private static boolean isHelpToken(String token) {
        return "--help".equals(token) || "-h".equals(token) || "help".equals(token);
    }

    private static String requiredValue(String[] args, int index, String option) throws CliUsageException {
        if (index >= args.length) {
            throw new CliUsageException("Missing value for " + option + ".");
        }
        return args[index];
    }

    private static ExpressionTranslationMode parseExpressionMode(String token) throws CliUsageException {
        try {
            return ExpressionTranslationMode.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CliUsageException("Unsupported expression mode: " + token);
        }
    }
}
