package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.core.spi.ExpressionFunctionResolver;
import com.leanowtech.bloge.core.spi.FunctionCallSite;
import com.leanowtech.bloge.core.spi.FunctionInvocationContext;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.testing.evidence.TestRunControlEvidenceProjection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionControlRuntimeTest {

    @Test
    void exactArgumentsAreSelectedBeforeWildcardAndNullIsARealReturnValue() {
        FunctionControlRule exact = rule("exact", List.of("known"), "secret-result",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1));
        FunctionControlRule fallback = rule("fallback", null, "fallback-result",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(0, 1));
        FunctionControlRuntime runtime = runtime(List.of(exact, fallback));

        assertThat(invoke(runtime, "known")).isEqualTo("secret-result");
        assertThat(invoke(runtime, "other")).isEqualTo("fallback-result");
        FunctionControlRunEvidence evidence = runtime.finish();
        assertThat(evidence.observations()).hasSize(2);
        assertThat(evidence.toString()).doesNotContain("secret-result", "known");
    }

    @Test
    void returnAndDelayExplicitNullAreReturnedAndFingerprinted() {
        FunctionControlRuntime returning = runtime(List.of(rule("return-null", null, null,
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThat(invoke(returning, "x")).isNull();
        assertThat(returning.finish().observations()).singleElement()
                .extracting(FunctionControlObservation::resultFingerprint)
                .asString().startsWith("sha256:");

        FunctionControlRuntime delaying = runtime(List.of(rule("delay-null", null, null,
                FunctionControlRule.Behavior.DELAY, Duration.ofMillis(1),
                new FunctionControlRule.Consumption(1, 1))));
        assertThat(invoke(delaying, "x")).isNull();
        assertThat(delaying.finish().observations()).singleElement()
                .extracting(FunctionControlObservation::resultFingerprint)
                .asString().startsWith("sha256:");
    }

    @Test
    void allBehaviorsUseGovernedClockAndDeterministicFailures() {
        TimeSource clock = FunctionControlRuntime.logicalClock();
        FunctionControlRuntime delay = runtime(List.of(rule("delay", null, "later",
                FunctionControlRule.Behavior.DELAY, Duration.ofMillis(7),
                new FunctionControlRule.Consumption(1, 1))), clock);
        assertThat(invoke(delay, "x")).isEqualTo("later");
        assertThat(clock.now()).isEqualTo(Instant.EPOCH.plusMillis(7));
        delay.finish();

        FunctionControlRuntime throwing = runtime(List.of(rule("throw", null, null,
                FunctionControlRule.Behavior.THROW, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThatThrownBy(() -> invoke(throwing, "x"))
                .isInstanceOf(FunctionControlException.class)
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.CONTROL_THROW);
        FunctionControlRunEvidence thrownEvidence = throwing.finish();
        assertThat(thrownEvidence.observations()).hasSize(1);

        FunctionControlRuntime timeout = runtime(List.of(rule("timeout", null, null,
                FunctionControlRule.Behavior.TIMEOUT, Duration.ofMillis(3),
                new FunctionControlRule.Consumption(1, 1))));
        assertThatThrownBy(() -> invoke(timeout, "x"))
                .isInstanceOf(FunctionControlException.class)
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.CONTROL_TIMEOUT);
        assertThat(timeout.finish().observations()).hasSize(1);
    }

    @Test
    void runtimeEvidenceProjectionAcceptsAllBehaviorsAndRecordedFailures() {
        FunctionControlRuntime returning = runtime(List.of(rule("return", null, null,
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThat(invoke(returning, "x")).isNull();
        assertProjects(returning.finish());

        FunctionControlRuntime throwing = runtime(List.of(rule("throw", null, null,
                FunctionControlRule.Behavior.THROW, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThatThrownBy(() -> invoke(throwing, "x")).isInstanceOf(FunctionControlException.class);
        assertProjects(throwing.finish());

        FunctionControlRuntime delaying = runtime(List.of(rule("delay", null, null,
                FunctionControlRule.Behavior.DELAY, Duration.ofMillis(1),
                new FunctionControlRule.Consumption(1, 1))));
        assertThat(invoke(delaying, "x")).isNull();
        assertProjects(delaying.finish());

        FunctionControlRuntime timingOut = runtime(List.of(rule("timeout", null, null,
                FunctionControlRule.Behavior.TIMEOUT, Duration.ofMillis(1),
                new FunctionControlRule.Consumption(1, 1))));
        assertThatThrownBy(() -> invoke(timingOut, "x")).isInstanceOf(FunctionControlException.class);
        assertProjects(timingOut.finish());

        FunctionControlRuntime argumentFailure = runtime(List.of(rule("mismatch", List.of("a"),
                "unused", FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(0, 1))));
        assertThatThrownBy(() -> invoke(argumentFailure, "b"))
                .isInstanceOf(FunctionControlException.class);
        assertProjects(argumentFailure.finishAfterFailure());

        TimeSource failingClock = new TimeSource() {
            @Override public Instant now() { return Instant.EPOCH; }
            @Override public void sleep(Duration duration) {
                throw new IllegalStateException("clock-failure");
            }
        };
        FunctionControlRuntime delayFailure = runtime(List.of(rule("delay-failure", null, null,
                FunctionControlRule.Behavior.DELAY, Duration.ofMillis(1),
                new FunctionControlRule.Consumption(1, 1))), failingClock);
        assertThatThrownBy(() -> invoke(delayFailure, "x"))
                .isInstanceOf(FunctionControlException.class);
        assertProjects(delayFailure.finishAfterFailure());
    }

    @Test
    void resolverDelegatesAndResolvedFunctionDriftIsRejected() {
        AtomicBoolean delegated = new AtomicBoolean();
        ExpressionFunctionResolver delegate = (site, function) -> {
            delegated.set(true);
            return function;
        };
        FunctionControlRuntime runtime = runtime(List.of(), delegate);
        assertThat(invoke(runtime, "x")).isEqualTo("real-x");
        assertThat(delegated).isTrue();
        runtime.finish();

        ExpressionFunctionResolver drift = (site, function) -> function("other");
        FunctionControlRuntime driftRuntime = runtime(List.of(), drift);
        assertThatThrownBy(() -> driftRuntime.resolver().resolve(
                new FunctionCallSite("f", 1, 1), function("f")))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.RUNTIME_BINDING_DRIFT");
    }

    @Test
    void consumptionBoundsAndMinimumsFailClosed() {
        FunctionControlRuntime runtime = runtime(List.of(rule("one", null, "ok",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThat(invoke(runtime, "x")).isEqualTo("ok");
        assertThatThrownBy(() -> invoke(runtime, "x"))
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.CONTROL_EXHAUSTED);
        assertThat(runtime.finish().observations()).hasSize(2);
        assertThat(runtime.evidence().consumptions()).singleElement().satisfies(consumption -> {
            assertThat(consumption.minimum()).isEqualTo(1);
            assertThat(consumption.maximum()).isEqualTo(1);
            assertThat(consumption.used()).isEqualTo(1);
            assertThat(consumption.status()).isEqualTo("MAX_REACHED");
        });

        FunctionControlRuntime minimum = runtime(List.of(rule("required", null, "ok",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1))));
        assertThatThrownBy(minimum::finish)
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.MINIMUM_UNCONSUMED);
    }

    @Test
    void argumentMismatchLeavesSanitizedObservationAndNeverCallsRealFunction() {
        AtomicBoolean realCalled = new AtomicBoolean();
        ExpressionFunction real = function("f", args -> {
            realCalled.set(true);
            return "real";
        });
        FunctionControlRuntime runtime = runtime(List.of(rule("only-a", List.of("a"), "stub",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(0, 1))), real, ExpressionFunctionResolver.DIRECT);
        assertThatThrownBy(() -> invoke(runtime, "b"))
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.CONTROL_ARGUMENT_MISMATCH);
        assertThat(realCalled).isFalse();
        assertThat(runtime.finishAfterFailure().observations()).hasSize(1);
    }

    @Test
    void eachRuntimeHasIndependentConsumptionAndEvidence() {
        FunctionControlRule rule = rule("isolated", null, "run-local",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(1, 1));
        FunctionControlRuntime first = runtime(List.of(rule));
        FunctionControlRuntime second = runtime(List.of(rule));
        assertThat(invoke(first, "x")).isEqualTo("run-local");
        assertThat(invoke(second, "x")).isEqualTo("run-local");
        assertThat(first.finish().evidenceFingerprint()).isEqualTo(second.finish().evidenceFingerprint());
    }

    @Test
    void concurrentDifferentScopesProduceStablePayloadFreeEvidence() throws Exception {
        String expected = null;
        for (int iteration = 0; iteration < 20; iteration++) {
            FunctionControlRule rule = rule("parallel", null, "same",
                    FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                    new FunctionControlRule.Consumption(2, 2));
            FunctionControlRuntime runtime = runtime(List.of(rule));
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            List<java.util.concurrent.Future<Object>> calls = List.of(
                    pool.submit(() -> { ready.countDown(); ready.await(); return invoke(runtime, "a"); }),
                    pool.submit(() -> { ready.countDown(); ready.await(); return invoke(runtime, "b"); }));
            assertThat(calls.get(0).get(5, TimeUnit.SECONDS)).isEqualTo("same");
            assertThat(calls.get(1).get(5, TimeUnit.SECONDS)).isEqualTo("same");
            pool.shutdownNow();
            FunctionControlRunEvidence evidence = runtime.finish();
            assertThat(evidence.observations()).hasSize(2).allSatisfy(observation -> {
                assertThat(observation.invocationScopeFingerprint()).startsWith("sha256:");
                assertThat(observation.argumentsFingerprint()).startsWith("sha256:");
            });
            if (expected == null) expected = evidence.evidenceFingerprint();
            assertThat(evidence.evidenceFingerprint()).isEqualTo(expected);
        }
    }

    @Test
    void sameRunConcurrentMaxIsAtomic() throws Exception {
        FunctionControlRuntime runtime = runtime(List.of(rule("one", null, "same",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(0, 1))));
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        List<java.util.concurrent.Future<Object>> calls = List.of(
                pool.submit(() -> { ready.countDown(); ready.await(); return invoke(runtime, "x"); }),
                pool.submit(() -> { ready.countDown(); ready.await(); return invoke(runtime, "x"); }));
        int successes = 0;
        for (var call : calls) {
            try { if ("same".equals(call.get(5, TimeUnit.SECONDS))) successes++; }
            catch (java.util.concurrent.ExecutionException ignored) { }
        }
        pool.shutdownNow();
        assertThat(successes).isEqualTo(1);
        assertThat(runtime.finishAfterFailure().consumptions()).singleElement()
                .extracting(FunctionControlConsumption::used).isEqualTo(1L);
    }

    @Test
    void exhaustedControlKeepsFailureObservationForEvidenceProjection() {
        FunctionControlRuntime runtime = runtime(List.of(rule("one", null, "ok",
                FunctionControlRule.Behavior.RETURN, Duration.ZERO,
                new FunctionControlRule.Consumption(0, 1))));
        assertThat(invoke(runtime, "x")).isEqualTo("ok");
        assertThatThrownBy(() -> invoke(runtime, "x"))
                .extracting(ex -> ((FunctionControlException) ex).code())
                .isEqualTo(FunctionControlException.Code.CONTROL_EXHAUSTED);

        FunctionControlRunEvidence evidence = runtime.finishAfterFailure();
        assertThat(evidence.observations()).hasSize(2);
        assertThat(evidence.observations()).anySatisfy(observation ->
                assertThat(observation.errorFingerprint()).startsWith("sha256:"));
        assertProjects(evidence);
    }

    @Test
    void evidenceFingerprintBindsPlanAndConsumptionEvenWithNoObservations() {
        FunctionLibraryDeclaration first = new FunctionLibraryDeclaration(
                "f", false, Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT,
                Map.of(), Map.of("type", "string"));
        FunctionLibraryDeclaration second = new FunctionLibraryDeclaration(
                "f", false, Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT,
                Map.of(), Map.of("type", "integer"));
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(List.of(site()));
        CompiledFunctionControlPlan firstPlan = new FunctionControlCompiler().compile(inventory,
                Map.of("f", function("f")), List.of(first), List.of());
        CompiledFunctionControlPlan secondPlan = new FunctionControlCompiler().compile(inventory,
                Map.of("f", function("f")), List.of(second), List.of());
        FunctionControlRunEvidence firstEvidence = FunctionControlRuntime.forTestRun(firstPlan,
                Map.of("f", function("f")), ExpressionFunctionResolver.DIRECT,
                FunctionControlRuntime.logicalClock()).finish();
        FunctionControlRunEvidence secondEvidence = FunctionControlRuntime.forTestRun(secondPlan,
                Map.of("f", function("f")), ExpressionFunctionResolver.DIRECT,
                FunctionControlRuntime.logicalClock()).finish();
        assertThat(firstPlan.planFingerprint()).isNotEqualTo(secondPlan.planFingerprint());
        assertThat(firstEvidence.evidenceFingerprint()).isNotEqualTo(secondEvidence.evidenceFingerprint());
    }

    @Test
    void evidenceCarriesBindingFactsAndDowngradeWithoutPayload() {
        FunctionControlRule rule = new FunctionControlRule("pure-override",
                new FunctionControlRule.Selector("/root", "n", "f", 1, 1), null,
                FunctionControlRule.Behavior.RETURN, "secret", "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), true, 0);
        FunctionLibraryDeclaration declaration = new FunctionLibraryDeclaration(
                "f", true, Set.of(), FunctionEffect.PURE_COMPUTATION, Map.of(), Map.of());
        CompiledFunctionControlPlan plan = new FunctionControlCompiler().compile(
                new FunctionInvocationInventory(List.of(site())), Map.of("f", pureFunction("f")),
                List.of(declaration), List.of(rule));
        FunctionControlRuntime runtime = FunctionControlRuntime.forTestRun(plan,
                Map.of("f", pureFunction("f")), ExpressionFunctionResolver.DIRECT,
                FunctionControlRuntime.logicalClock());
        ExpressionFunction controlled = runtime.resolver().resolve(
                new FunctionCallSite("f", 1, 1), pureFunction("f"));
        controlled.apply(context(), "x");
        FunctionControlRunEvidence evidence = runtime.finish();
        assertThat(evidence.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.mode()).isEqualTo(FunctionControlMode.CONTROLLED);
            assertThat(binding.downgradeReason()).isEqualTo("PURE_FORCE_OVERRIDE");
            assertThat(binding.runtimeFingerprint()).startsWith("sha256:");
        });
        assertThat(evidence.toString()).doesNotContain("secret");
    }

    @Test
    void composeRequiresTheSameBaseResolverChain() {
        FunctionControlRuntime runtime = runtime(List.of());
        ExecutionServices other = new ExecutionServices(ExecutionServices.SYSTEM.timeSource(),
                ExecutionServices.SYSTEM.randomSource(), ExecutionServices.SYSTEM.idGenerator(),
                ExecutionServices.SYSTEM.identityProvider(), ExecutionServices.SYSTEM.featureFlagProvider(),
                ExecutionServices.SYSTEM.secretProvider(), (site, function) -> function);
        assertThatThrownBy(() -> runtime.compose(other))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.RUNTIME_BINDING_DRIFT");
    }

    private static Object invoke(FunctionControlRuntime runtime, Object argument) {
        ExpressionFunction function = runtime.resolver().resolve(
                new FunctionCallSite("f", 1, 1), function("f"));
        return function.apply(context(), argument);
    }

    private static void assertProjects(FunctionControlRunEvidence evidence) {
        TestRunControlEvidenceProjection projection = TestRunControlEvidenceProjection.from(
                "function-run", "", "", "sha256:" + "a".repeat(64),
                "sha256:" + "a".repeat(64), evidence.planFingerprint(), null, evidence);
        assertThat(projection.function().observations()).isNotEmpty();
    }

    private static FunctionInvocationContext context() {
        return new FunctionInvocationContext(new FunctionCallSite("f", 1, 1),
                new GraphContext(), ExecutionServices.SYSTEM, "/root", "n");
    }

    private static FunctionControlRuntime runtime(List<FunctionControlRule> rules) {
        return runtime(rules, FunctionControlRuntime.logicalClock());
    }

    private static FunctionControlRuntime runtime(List<FunctionControlRule> rules, TimeSource clock) {
        return runtime(rules, function("f"), ExpressionFunctionResolver.DIRECT, clock);
    }

    private static FunctionControlRuntime runtime(List<FunctionControlRule> rules,
                                                  ExpressionFunctionResolver resolver) {
        return runtime(rules, function("f"), resolver, FunctionControlRuntime.logicalClock());
    }

    private static FunctionControlRuntime runtime(List<FunctionControlRule> rules,
                                                  ExpressionFunction registered,
                                                  ExpressionFunctionResolver resolver) {
        return runtime(rules, registered, resolver, FunctionControlRuntime.logicalClock());
    }

    private static FunctionControlRuntime runtime(List<FunctionControlRule> rules,
                                                  ExpressionFunction registered,
                                                  ExpressionFunctionResolver resolver,
                                                  TimeSource clock) {
        FunctionLibraryDeclaration declaration = new FunctionLibraryDeclaration(
                "f", false, Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT, Map.of(), Map.of());
        CompiledFunctionControlPlan plan = new FunctionControlCompiler().compile(
                new FunctionInvocationInventory(List.of(site())), Map.of("f", registered),
                List.of(declaration), rules);
        return FunctionControlRuntime.forTestRun(plan, Map.of("f", registered), resolver, clock);
    }

    private static FunctionControlRule rule(String id, List<?> args, Object value,
                                             FunctionControlRule.Behavior behavior,
                                             Duration duration,
                                             FunctionControlRule.Consumption consumption) {
        return new FunctionControlRule(id,
                new FunctionControlRule.Selector("/root", "n", "f", 1, 1), args,
                behavior, value, behavior == FunctionControlRule.Behavior.THROW ? "controlled" : "",
                duration, consumption, false, 0);
    }

    private static FunctionInvocationSite site() {
        return new FunctionInvocationSite("/root", "n", "f", 1, 1);
    }

    private static ExpressionFunction function(String name) {
        return function(name, args -> "real-" + (args.length == 0 ? "" : args[0]));
    }

    private static ExpressionFunction function(String name, java.util.function.Function<Object[], Object> body) {
        return new ExpressionFunction() {
            @Override public String name() { return name; }
            @Override public Object apply(Object... args) { return body.apply(args); }
            @Override public String returnType(String... argTypes) { return "Any"; }
            @Override public boolean isPure() { return false; }
            @Override public Set<ExecutionServiceKind> requiredExecutionServices() {
                return Set.of(ExecutionServiceKind.TIME);
            }
        };
    }

    private static ExpressionFunction pureFunction(String name) {
        return new ExpressionFunction() {
            @Override public String name() { return name; }
            @Override public Object apply(Object... args) { return "real"; }
            @Override public String returnType(String... argTypes) { return "Any"; }
            @Override public boolean isPure() { return true; }
            @Override public Set<ExecutionServiceKind> requiredExecutionServices() { return Set.of(); }
        };
    }
}
