package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.OperatorResolutionRequest;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.util.Objects;

/** Builds the one authoritative operator-resolution runtime for compiled test controls. */
public class CompiledTestRuntimeOptions {

    private final TestDoubleFactory doubleFactory;

    /**
     * Creates runtime options that lower resource and operator fixtures through the shared kernel.
     *
     * @param objectMapper protocol and test-double mapper
     * @param resourceRuntime optional protocol-aware resource fixture runtime
     */
    public CompiledTestRuntimeOptions(
            ObjectMapper objectMapper, ResourceFixtureRuntime resourceRuntime) {
        this(new TestDoubleFactory(
                Objects.requireNonNull(objectMapper, "objectMapper"), resourceRuntime));
    }

    CompiledTestRuntimeOptions(TestDoubleFactory doubleFactory) {
        this.doubleFactory = Objects.requireNonNull(doubleFactory, "doubleFactory");
    }

    /**
     * Binds one immutable compiled plan to one run-scoped invocation recorder.
     *
     * @param compiled exact compiled execution control
     * @param recorder run-scoped fixture cursor and trace recorder
     * @return execution options shared by fresh and cold-recovery test runs
     */
    public ExecutionOptions options(
            CompiledExecutionControl compiled, InvocationRecorder recorder) {
        CompiledExecutionControl requiredControl = Objects.requireNonNull(
                compiled, "compiled");
        InvocationRecorder requiredRecorder = Objects.requireNonNull(recorder, "recorder");
        return ExecutionOptions.builder()
                .operatorResolver(resolution -> resolveOperator(
                        resolution, requiredControl, requiredRecorder))
                .executionServices(requiredControl.executionServices().services())
                .build();
    }

    private Object resolveOperator(
            OperatorResolutionRequest resolution,
            CompiledExecutionControl compiled,
            InvocationRecorder recorder) {
        InvocationInventory.Entry entry = compiled.inventory().byEngineStructuralId()
                .get(resolution.site().structuralId());
        if (entry == null || entry.graph() != resolution.graph()) {
            throw new TestControlException(
                    "CONTROL_PLAN_RUNTIME_SITE_UNPLANNED", "CONTROL_PLAN",
                    "Runtime invocation was absent from the frozen inventory: "
                            + resolution.site().structuralId());
        }
        CompiledExecutionControl.ResolvedControl control = compiled.controls()
                .get(entry.site().invocationSiteId());
        var runtimeSite = entry.site().withCorrelationKey(resolution.site().correlationKey());
        if (control == null && !(entry.frozenOperator() instanceof Operator<?, ?>)) {
            return entry.frozenOperator();
        }
        var binding = recorder.bind(runtimeSite, resolution.graphContext());
        if (control == null) {
            return doubleFactory.observe(
                    entry.node(), binding, entry.frozenOperator(), recorder);
        }
        return doubleFactory.create(entry.node(), binding, control.rules(),
                entry.frozenOperator(), control.implicitDeny(), recorder,
                compiled.replayPayloads());
    }
}
