package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput.MaterializedFixture;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledBehavior;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ExhaustionPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GeneratedValueRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ReplayMaterialRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.UnmatchedPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lowers governed values and controlled dependencies into existing Fixture rules. */
final class CorrectnessFixtureRuleLowerer {

    FixtureRule lowerRule(
            ControlledDependencyV2 source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (!source.selector().functionRef().isBlank()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FUNCTION_SELECTOR_UNSUPPORTED", scenarioSetRef,
                    path + "/selector/functionRef",
                    "correctness.compilation.functionSelectorUnsupported"));
        }
        if (source.consumption().onExhausted() == ExhaustionPolicy.REPEAT_LAST) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPEAT_LAST_UNSUPPORTED", scenarioSetRef,
                    path + "/consumption/onExhausted",
                    "correctness.compilation.repeatLastUnsupported"));
        }
        if (source.behavior().boundary() == BehaviorBoundary.TRANSPORT
                && source.behavior().kind() != BehaviorKind.REAL
                && source.behavior().kind() != BehaviorKind.OBSERVE) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.TRANSPORT_LOWERING_UNSUPPORTED", scenarioSetRef,
                    path + "/behavior/boundary",
                    "correctness.compilation.transportLoweringUnsupported"));
        }
        FixtureRule.Behavior behavior = lowerBehavior(
                source.behavior(), fixtures, scenarioSetRef, path + "/behavior", diagnostics);
        if (hasErrors(diagnostics)) return null;

        Map<String, Object> pathEquals = new LinkedHashMap<>();
        source.selector().pathMatches().forEach(match ->
                pathEquals.put(match.path(), match.expected()));
        FixtureRule.Selector selector = new FixtureRule.Selector(
                source.selector().graphPath(), source.selector().nodeId(),
                source.selector().operatorRef(), source.selector().resourceRef(),
                source.selector().functionRef(), List.of(), List.of(),
                invocationKind(source), source.selector().attempts(),
                source.selector().occurrences(), source.selector().correlationKey(),
                new FixtureRule.Match(
                        null, pathEquals, List.of(), List.of(), Map.of(), "", Map.of()));
        FixtureRule.Consumption consumption = new FixtureRule.Consumption(
                source.consumption().required(), source.consumption().minUses(),
                source.consumption().maxUses(),
                source.consumption().onExhausted() == ExhaustionPolicy.FALLBACK_TO_REAL
                        ? FixtureRule.ExhaustedAction.FALLBACK_TO_REAL
                        : FixtureRule.ExhaustedAction.FAIL,
                source.consumption().onUnmatched() == UnmatchedPolicy.ALLOW_REAL
                        ? FixtureRule.UnmatchedAction.ALLOW_REAL
                        : FixtureRule.UnmatchedAction.FAIL);
        return new FixtureRule(
                "", source.dependencyId(), selector, behavior, consumption,
                FixtureRule.SchemaCheck.strict());
    }

    Object resolveValue(
            ValueSource source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (source instanceof InlineValue inline) return inline.value();
        if (source instanceof FixtureVariantRef fixtureRef) {
            MaterializedFixture fixture = fixtures.get(fixtureRef.fixtureAssetRef());
            if (fixture == null
                    || !fixture.descriptor().variantKey().equals(fixtureRef.variantKey())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.FIXTURE_VARIANT_UNAVAILABLE", scenarioSetRef,
                        path, "correctness.compilation.fixtureVariantUnavailable"));
                return Map.of();
            }
            return fixture.payload();
        }
        if (source instanceof GeneratedValueRef) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.GENERATOR_LOWERING_UNSUPPORTED", scenarioSetRef,
                    path, "correctness.compilation.generatorLoweringUnsupported"));
            return Map.of();
        }
        if (source instanceof ReplayMaterialRef) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPLAY_INPUT_UNSUPPORTED", scenarioSetRef,
                    path, "correctness.compilation.replayInputUnsupported"));
            return Map.of();
        }
        diagnostics.add(Diagnostic.error(
                "RG.CORRECTNESS.VALUE_SOURCE_UNKNOWN", scenarioSetRef,
                path, "correctness.compilation.valueSourceUnknown"));
        return Map.of();
    }

    private FixtureRule.Behavior lowerBehavior(
            ControlledBehavior source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        FixtureRule.DoubleBoundary boundary = FixtureRule.DoubleBoundary.valueOf(
                source.boundary().name());
        Object value = null;
        if (source.kind() == BehaviorKind.RETURN || source.kind() == BehaviorKind.DELAY) {
            value = resolveValue(source.value(), fixtures, scenarioSetRef, path + "/value", diagnostics);
        }
        if ((source.kind() == BehaviorKind.DELAY || source.kind() == BehaviorKind.TIMEOUT)
                && source.delayMs() < 1) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.LOGICAL_DELAY_INVALID", scenarioSetRef,
                    path + "/delayMs", "correctness.compilation.logicalDelayInvalid"));
        }
        return switch (source.kind()) {
            case REAL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.REAL, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case RETURN -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.RETURN, boundary, value, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case ERROR -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.THROW, boundary, null, "", null, Map.of(),
                    source.errorCode(), "CONTROLLED_ERROR", "", null, List.of(), "");
            case DELAY -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DELAY, boundary, value, "", null, Map.of(),
                    "", "", "", Duration.ofMillis(source.delayMs()), List.of(), "");
            case TIMEOUT -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.TIMEOUT, boundary, null, "", null, Map.of(),
                    source.errorCode().isBlank() ? "TEST_TIMEOUT" : source.errorCode(),
                    "TIMEOUT", "", Duration.ofMillis(source.delayMs()), List.of(), "");
            case REPLAY -> replayBehavior(source, scenarioSetRef, path, diagnostics);
            case OBSERVE -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.SPY, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case MUST_NOT_CALL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DENY, boundary, null, "", null, Map.of(),
                    source.errorCode().isBlank()
                            ? "CORRECTNESS_MUST_NOT_CALL" : source.errorCode(),
                    "DENIED_INVOCATION", "", null, List.of(), "");
        };
    }

    private static FixtureRule.Behavior replayBehavior(
            ControlledBehavior source,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (!(source.value() instanceof ReplayMaterialRef replay)
                || !"REPLAY_PAYLOAD".equals(replay.replayMaterialRef().kind())) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPLAY_REFERENCE_INVALID", scenarioSetRef,
                    path + "/value", "correctness.compilation.replayReferenceInvalid"));
            return FixtureRule.Behavior.real();
        }
        ExactAssetRef ref = replay.replayMaterialRef();
        String canonical = new ReplayPayloadRef(
                ref.id(), ref.revision(), ref.fingerprint()).canonical();
        return new FixtureRule.Behavior(
                FixtureRule.BehaviorKind.REPLAY, FixtureRule.DoubleBoundary.NODE,
                null, "", null, Map.of(), "", "", "", null, List.of(), canonical);
    }

    private static InvocationSite.InvocationKind invocationKind(ControlledDependencyV2 source) {
        if (!source.selector().resourceRef().isBlank()) {
            return InvocationSite.InvocationKind.RESOURCE;
        }
        if (!source.selector().functionRef().isBlank()) {
            return InvocationSite.InvocationKind.FUNCTION;
        }
        return InvocationSite.InvocationKind.PRIMARY;
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value ->
                value.severity() == DiagnosticSeverity.ERROR);
    }
}
