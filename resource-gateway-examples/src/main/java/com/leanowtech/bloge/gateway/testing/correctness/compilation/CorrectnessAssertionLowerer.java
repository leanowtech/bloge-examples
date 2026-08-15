package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport.DispositionStatus;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Delegates executable assertion compilation to the authoritative existing compiler. */
final class CorrectnessAssertionLowerer {

    private final AssertionSetCompiler compiler;
    private final AssertionEvaluatorProfile evaluatorProfile;

    CorrectnessAssertionLowerer(
            AssertionSetCompiler compiler,
            AssertionEvaluatorProfile evaluatorProfile
    ) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.evaluatorProfile = Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
    }

    List<CompiledAssertion> lower(
            ScenarioDraftV2 scenario,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        List<CompiledAssertion> result = new ArrayList<>();
        for (ExactAssetRef ref : scenario.assertionSetRefs()) {
            AssertionSet source = assertionSets.get(ref);
            if (source == null) continue;
            AssertionCompilationReport report = compiler.compile(source, evaluatorProfile);
            int loweredIndex = 0;
            for (AssertionCompilationReport.AssertionDisposition disposition
                    : report.dispositions()) {
                if (disposition.status() == DispositionStatus.COMPILED_RUNTIME) {
                    if (loweredIndex >= report.runtimeAssertions().size()) {
                        diagnostics.add(Diagnostic.error(
                                "RG.CORRECTNESS.ASSERTION_LOWERING_INCOMPLETE", ref,
                                "/assertions/" + disposition.assertionId(),
                                "correctness.compilation.assertionLoweringIncomplete"));
                        continue;
                    }
                    result.add(new CompiledAssertion(
                            ref, disposition.assertionId(),
                            report.runtimeAssertions().get(loweredIndex++)));
                } else if (disposition.status() == DispositionStatus.RETAINED_GATE) {
                    // The exact Assertion Set remains in the Publication manifest as gate authority.
                } else {
                    diagnostics.add(Diagnostic.error(
                            disposition.reasonCode().isBlank()
                                    ? "RG.CORRECTNESS.ASSERTION_NOT_RUNTIME"
                                    : disposition.reasonCode(),
                            ref, "/assertions/" + disposition.assertionId(),
                            "correctness.compilation.assertionNotRuntime"));
                }
            }
            if (loweredIndex != report.runtimeAssertions().size()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_LOWERING_UNACCOUNTED", ref,
                        "/assertions",
                        "correctness.compilation.assertionLoweringUnaccounted"));
            }
        }
        return List.copyOf(result);
    }

    record CompiledAssertion(
            ExactAssetRef assertionSetRef,
            String assertionId,
            FixtureBundle.Assertion assertion
    ) {
    }
}
