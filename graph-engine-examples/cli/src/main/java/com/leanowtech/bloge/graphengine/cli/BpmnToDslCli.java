package com.leanowtech.bloge.graphengine.cli;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Main entry point for the standalone BPMN conversion CLI.
 */
public final class BpmnToDslCli {

    private static final String USAGE = """
            Usage:
              bloge-bpmn <command> [options] <input...>

            Commands:
              convert   Convert BPMN XML or JSON to Bloge DSL
              validate  Validate a BPMN file and report diagnostics only

            Options:
              --format <xml|json>        Force input format (default: auto-detect by extension)
              --output <file|dir>        Output path; default: stdout (single file) or <input>.bloge (batch)
              --mapping <json-file>      Operator mapping config (default: ./bpmn-operator-mapping.json if present)
              --strict                   Treat WARN diagnostics as errors
              --no-source-comments       Omit BPMN source-mapping comments in output
              --no-doc-comments          Omit BPMN <documentation> comments in output
              --expression-mode <mode>   auto | manual | hybrid (default: auto)

            Exit codes:
              0  Success
              1  Translation/validation errors
              2  Tool error (bad args, file not found, parse failure)
            """;

    private BpmnToDslCli() {
    }

    /**
     * Runs the CLI and exits the current JVM with the resulting exit code.
     *
     * @param args raw command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the CLI using caller-provided output streams. This is the primary test seam.
     *
     * @param args raw command-line arguments
     * @param out stdout-like stream
     * @param err stderr-like stream
     * @return CLI exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");
        try {
            CliArgs cliArgs = CliArgs.parse(args);
            return switch (cliArgs.command()) {
                case CONVERT -> new ConvertRunner(out, err).run(cliArgs);
                case VALIDATE -> new ValidateRunner(err).run(cliArgs);
            };
        } catch (CliUsageException exception) {
            PrintStream usageStream = exception.usageOnly() ? out : err;
            if (!exception.getMessage().isBlank()) {
                usageStream.println(exception.getMessage());
                usageStream.println();
            }
            usageStream.print(USAGE);
            return exception.usageOnly() ? 0 : 2;
        }
    }
}
