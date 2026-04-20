package com.leanowtech.bloge.graphengine.cli;

import com.leanowtech.bloge.bpmn.api.BpmnTranslator;
import com.leanowtech.bloge.bpmn.diagnostic.TranslationDiagnostic;
import com.leanowtech.bloge.bpmn.model.BpmnProcess;
import com.leanowtech.bloge.bpmn.parser.BpmnJsonParser;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Executes the {@code validate} command without producing DSL output.
 */
public final class ValidateRunner {

    private final PrintStream err;
    private final DiagnosticPrinter diagnosticPrinter;
    private final BpmnJsonParser jsonParser = new BpmnJsonParser();

    /**
     * Creates a runner that writes diagnostics to the supplied stream.
     *
     * @param err stderr-like stream used for diagnostics and tool errors
     */
    public ValidateRunner(PrintStream err) {
        this.err = Objects.requireNonNull(err, "err must not be null");
        this.diagnosticPrinter = new DiagnosticPrinter(err);
    }

    /**
     * Runs the validation workflow and returns the CLI exit code.
     *
     * @param args parsed CLI arguments
     * @return {@code 0} on success, {@code 1} when validation errors exist, {@code 2} on tool failure
     */
    public int run(CliArgs args) {
        Objects.requireNonNull(args, "args must not be null");
        try {
            List<CliExecutionSupport.ResolvedInput> inputs = CliExecutionSupport.resolveInputs(args.inputs());
            if (inputs.isEmpty()) {
                err.println("No BPMN inputs found.");
                return 2;
            }

            int exitCode = 0;
            for (CliExecutionSupport.ResolvedInput input : inputs) {
                try {
                    List<TranslationDiagnostic> diagnostics = validate(input.file(), args);
                    diagnosticPrinter.printAll(diagnostics);
                    boolean hasErrors = diagnostics.stream().anyMatch(diagnostic ->
                            diagnostic.severity() == com.leanowtech.bloge.bpmn.diagnostic.TranslationDiagnosticSeverity.ERROR);
                    if (hasErrors) {
                        exitCode = CliExecutionSupport.combineExitCode(exitCode, 1);
                    }
                } catch (Exception exception) {
                    CliExecutionSupport.printToolError(err, input.file(), exception);
                    exitCode = CliExecutionSupport.combineExitCode(exitCode, 2);
                }
            }
            return exitCode;
        } catch (Exception exception) {
            CliExecutionSupport.printToolError(err, null, exception);
            return 2;
        }
    }

    private List<TranslationDiagnostic> validate(Path inputFile, CliArgs args) throws Exception {
        CliArgs.Format format = FormatDetector.detect(inputFile, args.format());
        BpmnTranslator translator = new BpmnTranslator(
                CliExecutionSupport.loadMappingConfig(args, format),
                CliExecutionSupport.toTranslationOptions(args)
        );
        if (format == CliArgs.Format.JSON) {
            BpmnProcess process = jsonParser.parse(inputFile);
            return translator.validate(process);
        }
        try (var inputStream = Files.newInputStream(inputFile)) {
            return translator.validate(inputStream);
        }
    }
}
