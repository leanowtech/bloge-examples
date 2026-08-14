package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.runtime.GovernedExecutionServices;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-shot planning boundary for freezing governed services into an execution control.
 *
 * <p>Callers outside the testing subsystem may inspect the payload-free binding projection, but
 * cannot obtain or reuse the stateful service providers. The prepared providers are bound to the
 * exact effective-plan fingerprint when {@link #assemble} is called.</p>
 */
public final class ExecutionControlPreparation {

    private final GovernedExecutionServices services;
    private boolean assembled;

    private ExecutionControlPreparation(GovernedExecutionServices services) {
        this.services = services;
    }

    /** Freezes deterministic providers from one validated fixture and invocation inventory. */
    public static ExecutionControlPreparation prepare(
            ObjectMapper mapper,
            FixtureBundle fixture,
            InvocationInventory inventory) {
        return new ExecutionControlPreparation(GovernedExecutionServices.prepare(
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(fixture, "fixture"),
                Objects.requireNonNull(inventory, "inventory")));
    }

    /** Returns the immutable, payload-free binding projection used in plan fingerprint material. */
    public List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings() {
        return services.bindings();
    }

    /**
     * Binds the frozen providers and assembles one executable control.
     *
     * @throws IllegalStateException when the preparation has already been consumed
     */
    public synchronized CompiledExecutionControl assemble(
            String planFingerprint,
            EffectiveExecutionPlan effectivePlan,
            Map<String, CompiledExecutionControl.ResolvedControl> controls,
            List<FixtureRule> rules,
            InvocationInventory inventory,
            ResolvedReplayPayloads replayPayloads,
            ResolvedCorpusPayloads corpusPayloads) {
        if (assembled) {
            throw new IllegalStateException("Execution-control preparation is already consumed");
        }
        services.bindToPlan(Objects.requireNonNull(planFingerprint, "planFingerprint"));
        assembled = true;
        return new CompiledExecutionControl(
                Objects.requireNonNull(effectivePlan, "effectivePlan"),
                controls,
                rules,
                Objects.requireNonNull(inventory, "inventory"),
                replayPayloads,
                corpusPayloads,
                services);
    }
}
