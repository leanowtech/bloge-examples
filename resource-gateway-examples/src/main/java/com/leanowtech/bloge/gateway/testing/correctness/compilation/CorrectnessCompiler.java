package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.ExecutionRiskSummary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure deterministic phase orchestrator from frozen authoring state to testing v1 assets. */
public final class CorrectnessCompiler {

    public static final String COMPILER_VERSION = "bloge.correctnessCompiler.v1";

    private final CorrectnessCompilationValidator validator;
    private final CorrectnessExecutionRiskAnalyzer riskAnalyzer;
    private final CorrectnessScenarioLowerer lowerer;
    private final CorrectnessCompilationCanonicalizer canonicalizer;

    public CorrectnessCompiler(
            ObjectMapper mapper,
            AssertionSetCompiler assertionCompiler,
            AssertionEvaluatorProfile evaluatorProfile
    ) {
        ObjectMapper exactMapper = Objects.requireNonNull(mapper, "mapper");
        this.validator = new CorrectnessCompilationValidator(exactMapper);
        this.riskAnalyzer = new CorrectnessExecutionRiskAnalyzer();
        this.lowerer = new CorrectnessScenarioLowerer(
                exactMapper,
                Objects.requireNonNull(assertionCompiler, "assertionCompiler"),
                Objects.requireNonNull(evaluatorProfile, "evaluatorProfile"),
                COMPILER_VERSION);
        this.canonicalizer = new CorrectnessCompilationCanonicalizer(
                exactMapper, COMPILER_VERSION);
    }

    /** Compiles an already authorized frozen input and returns only its payload-free report. */
    public CorrectnessCompilationReport compileReport(FrozenCompilationInput input) {
        return compile(input).report();
    }

    CompiledCorrectnessPlan compile(FrozenCompilationInput input) {
        Objects.requireNonNull(input, "input");
        CompilationCoordinate coordinate = input.coordinate();
        CorrectnessCompilationValidator.ValidationResult validation = validator.validate(input);
        List<Diagnostic> diagnostics = new ArrayList<>(validation.diagnostics());

        CorrectnessExecutionRiskAnalyzer.RiskAnalysis risk = riskAnalyzer.analyze(
                input.scenarioDraftSet(), coordinate.scenarioDraftSetRef());
        ExecutionRiskSummary riskSummary = risk.summary();
        diagnostics.addAll(risk.diagnostics());
        if (hasErrors(diagnostics)) {
            return canonicalizer.blocked(coordinate, diagnostics, riskSummary);
        }

        CorrectnessScenarioLowerer.LoweringResult lowered = lowerer.lower(
                input, validation.fixtures(), validation.assertionSets());
        diagnostics.addAll(lowered.diagnostics());
        if (!lowered.publishable() || hasErrors(diagnostics)) {
            return canonicalizer.blocked(coordinate, diagnostics, riskSummary);
        }
        return canonicalizer.complete(
                coordinate, lowered.fixtureRegistrations(), lowered.suiteRegistration(),
                lowered.suiteRef(), lowered.sourceMap(), diagnostics, riskSummary);
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value ->
                value.severity() == DiagnosticSeverity.ERROR);
    }
}
