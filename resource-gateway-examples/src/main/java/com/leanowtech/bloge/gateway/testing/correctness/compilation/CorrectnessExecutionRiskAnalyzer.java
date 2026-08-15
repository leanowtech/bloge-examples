package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.ExecutionRiskSummary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ExhaustionPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.UnmatchedPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure risk-summary phase shared by compilation and governed-run admission. */
final class CorrectnessExecutionRiskAnalyzer {

    RiskAnalysis analyze(ScenarioDraftSetV2 draftSet, ExactAssetRef source) {
        Objects.requireNonNull(draftSet, "draftSet");
        Objects.requireNonNull(source, "source");
        int real = 0;
        int controlled = 0;
        int faults = 0;
        int denied = 0;
        int fallback = 0;
        int transport = 0;
        boolean logicalClock = false;
        for (ScenarioDraftV2 scenario : draftSet.scenarios()) {
            for (ControlledDependencyV2 dependency : scenario.dependencies()) {
                BehaviorKind kind = dependency.behavior().kind();
                if (kind == BehaviorKind.REAL || kind == BehaviorKind.OBSERVE) real++;
                else controlled++;
                if (kind == BehaviorKind.ERROR || kind == BehaviorKind.TIMEOUT) faults++;
                if (kind == BehaviorKind.MUST_NOT_CALL) denied++;
                if (dependency.consumption().onExhausted() == ExhaustionPolicy.FALLBACK_TO_REAL
                        || dependency.consumption().onUnmatched() == UnmatchedPolicy.ALLOW_REAL) {
                    fallback++;
                }
                if (dependency.behavior().boundary() == BehaviorBoundary.TRANSPORT) transport++;
                if (kind == BehaviorKind.DELAY || kind == BehaviorKind.TIMEOUT) logicalClock = true;
            }
        }
        List<String> codes = new ArrayList<>();
        if (real > 0) codes.add("REAL_DEPENDENCY");
        if (fallback > 0) codes.add("FALLBACK_TO_REAL");
        if (faults > 0) codes.add("CONTROLLED_FAULT");
        if (transport > 0) codes.add("TRANSPORT_BOUNDARY");
        ExecutionRiskSummary summary = new ExecutionRiskSummary(
                real, controlled, faults, denied, fallback, transport, logicalClock, codes);

        List<Diagnostic> diagnostics = new ArrayList<>();
        if (summary.realDependencyCount() > 0) {
            diagnostics.add(Diagnostic.warning(
                    "RG.CORRECTNESS.REAL_DEPENDENCY_PRESENT", source, "/scenarios",
                    "correctness.compilation.realDependencyPresent"));
        }
        if (summary.fallbackToRealCount() > 0) {
            diagnostics.add(Diagnostic.warning(
                    "RG.CORRECTNESS.FALLBACK_TO_REAL_PRESENT", source, "/scenarios",
                    "correctness.compilation.fallbackToRealPresent"));
        }
        return new RiskAnalysis(summary, diagnostics);
    }

    record RiskAnalysis(ExecutionRiskSummary summary, List<Diagnostic> diagnostics) {
        RiskAnalysis {
            Objects.requireNonNull(summary, "summary");
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
