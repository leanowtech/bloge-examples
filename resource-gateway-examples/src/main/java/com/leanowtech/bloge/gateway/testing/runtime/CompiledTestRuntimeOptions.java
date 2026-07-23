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
        return options(compiled, recorder, MirrorResolutionObserver.noop());
    }

    /**
     * Binds one compiled plan to its test evidence and optional mirror-resolution observers.
     *
     * @param compiled exact compiled execution control
     * @param recorder run-scoped fixture cursor and trace recorder
     * @param mirrorObserver run-scoped mirror provenance sink
     * @return isolated execution options
     */
    public ExecutionOptions options(
            CompiledExecutionControl compiled,
            InvocationRecorder recorder,
            MirrorResolutionObserver mirrorObserver) {
        return options(compiled, recorder, mirrorObserver, null);
    }

    /**
     * Binds one compiled mirror generation to a run-scoped occurrence budget.
     *
     * <p>Ordinary test execution passes no budget and retains its existing behavior. A protected
     * mirror run supplies the exact limit sealed into its plan; admission occurs after frozen-site
     * verification and before fixture binding or operator execution.</p>
     *
     * @param compiled exact compiled execution control
     * @param recorder run-scoped fixture cursor and trace recorder
     * @param mirrorObserver run-scoped mirror provenance sink
     * @param invocationBudget mirror-only whole-run occurrence budget, or {@code null}
     * @return isolated execution options
     */
    public ExecutionOptions options(
            CompiledExecutionControl compiled,
            InvocationRecorder recorder,
            MirrorResolutionObserver mirrorObserver,
            MirrorInvocationBudget invocationBudget) {
        return options(compiled, recorder, mirrorObserver,
                invocationBudget, null, MirrorStateAccessObserver.noop());
    }

    /**
     * Binds one immutable session state head to every mirror resolver occurrence in this run.
     *
     * @param compiled exact compiled execution control
     * @param recorder run-scoped fixture cursor and trace recorder
     * @param mirrorObserver run-scoped mirror provenance sink
     * @param invocationBudget mirror-only whole-run occurrence budget, or {@code null}
     * @param sessionContext immutable session state head, or {@code null} for stateless runs
     * @return isolated execution options
     */
    public ExecutionOptions options(
            CompiledExecutionControl compiled,
            InvocationRecorder recorder,
            MirrorResolutionObserver mirrorObserver,
            MirrorInvocationBudget invocationBudget,
            MirrorResolver.SessionContext sessionContext) {
        return options(compiled, recorder, mirrorObserver,
                invocationBudget, sessionContext,
                MirrorStateAccessObserver.noop());
    }

    /**
     * Binds state access evidence collection to every Session resolver occurrence.
     *
     * @param compiled exact compiled execution control
     * @param recorder run-scoped fixture cursor and trace recorder
     * @param mirrorObserver run-scoped mirror provenance sink
     * @param invocationBudget mirror-only whole-run occurrence budget, or {@code null}
     * @param sessionContext immutable Session state head, or {@code null}
     * @param stateAccessObserver payload-free state access sink
     * @return isolated execution options
     */
    public ExecutionOptions options(
            CompiledExecutionControl compiled,
            InvocationRecorder recorder,
            MirrorResolutionObserver mirrorObserver,
            MirrorInvocationBudget invocationBudget,
            MirrorResolver.SessionContext sessionContext,
            MirrorStateAccessObserver stateAccessObserver) {
        CompiledExecutionControl requiredControl = Objects.requireNonNull(
                compiled, "compiled");
        InvocationRecorder requiredRecorder = Objects.requireNonNull(recorder, "recorder");
        MirrorResolutionObserver requiredObserver = Objects.requireNonNull(
                mirrorObserver, "mirrorObserver");
        MirrorStateAccessObserver requiredStateObserver =
                Objects.requireNonNull(
                        stateAccessObserver, "stateAccessObserver");
        return ExecutionOptions.builder()
                .operatorResolver(resolution -> resolveOperator(
                        resolution, requiredControl, requiredRecorder, requiredObserver,
                        invocationBudget, sessionContext,
                        requiredStateObserver))
                .executionServices(requiredControl.executionServices().services())
                .build();
    }

    private Object resolveOperator(
            OperatorResolutionRequest resolution,
            CompiledExecutionControl compiled,
            InvocationRecorder recorder,
            MirrorResolutionObserver mirrorObserver,
            MirrorInvocationBudget invocationBudget,
            MirrorResolver.SessionContext sessionContext,
            MirrorStateAccessObserver stateAccessObserver) {
        InvocationInventory.Entry entry = compiled.inventory().byEngineStructuralId()
                .get(resolution.site().structuralId());
        if (entry == null || entry.graph() != resolution.graph()) {
            throw new TestControlException(
                    "CONTROL_PLAN_RUNTIME_SITE_UNPLANNED", "CONTROL_PLAN",
                    "Runtime invocation was absent from the frozen inventory: "
                            + resolution.site().structuralId());
        }
        compiled.corpusPayloads().admitOccurrence();
        if (invocationBudget != null) {
            invocationBudget.admit();
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
        return doubleFactory.create(entry.node(), binding, control,
                entry.frozenOperator(), recorder, compiled.replayPayloads(),
                compiled.corpusPayloads(), mirrorObserver, sessionContext,
                stateAccessObserver);
    }
}
