package com.leanowtech.bloge.graphengine.cli;

import com.leanowtech.bloge.bpmn.api.BpmnTranslator;
import com.leanowtech.bloge.bpmn.api.TranslationResult;
import com.leanowtech.bloge.bpmn.model.BpmnProcess;
import com.leanowtech.bloge.bpmn.parser.BpmnJsonParser;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Executes the {@code convert} command against one or more BPMN files.
 */
public final class ConvertRunner {

    private final PrintStream out;
    private final PrintStream err;
    private final DiagnosticPrinter diagnosticPrinter;
    private final BpmnJsonParser jsonParser = new BpmnJsonParser();

    /**
     * Creates a runner that writes DSL to {@code out} and diagnostics to {@code err}.
     *
     * @param out stdout-like stream used for DSL output
     * @param err stderr-like stream used for diagnostics and tool errors
     */
    public ConvertRunner(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
        this.diagnosticPrinter = new DiagnosticPrinter(err);
    }

    /**
     * Runs the conversion workflow and returns the CLI exit code.
     *
     * @param args parsed CLI arguments
     * @return {@code 0} on success, {@code 1} when translation errors exist, {@code 2} on tool failure
     */
    public int run(CliArgs args) {
        Objects.requireNonNull(args, "args must not be null");
        try {
            List<CliExecutionSupport.ResolvedInput> inputs = CliExecutionSupport.resolveInputs(args.inputs());
            if (inputs.isEmpty()) {
                err.println("No BPMN inputs found.");
                return 2;
            }

            boolean batchMode = CliExecutionSupport.isBatchMode(args.inputs());
            if (batchMode && args.outputPath() != null && java.nio.file.Files.exists(args.outputPath())
                    && !java.nio.file.Files.isDirectory(args.outputPath())) {
                err.println("Batch conversion requires --output to be a directory.");
                return 2;
            }

            int exitCode = 0;
            for (CliExecutionSupport.ResolvedInput input : inputs) {
                try {
                    TranslationResult<String> result = translate(input.file(), args);
                    diagnosticPrinter.printAll(result.diagnostics());

                    if (batchMode || args.outputPath() != null) {
                        Path outputFile = CliExecutionSupport.resolveConvertOutputPath(args, input, batchMode);
                        CliExecutionSupport.writeDsl(outputFile, result.result());
                    } else {
                        out.print(result.result());
                    }

                    if (result.hasErrors()) {
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

    private TranslationResult<String> translate(Path inputFile, CliArgs args) throws Exception {
        CliArgs.Format format = FormatDetector.detect(inputFile, args.format());
        BpmnTranslator translator = new BpmnTranslator(
                CliExecutionSupport.loadMappingConfig(args, format),
                CliExecutionSupport.toTranslationOptions(args)
        );
        if (format == CliArgs.Format.JSON) {
            BpmnProcess process = jsonParser.parse(inputFile);
            return translator.translateToDsl(process);
        }
        return translator.translateToDsl(inputFile);
    }
}
