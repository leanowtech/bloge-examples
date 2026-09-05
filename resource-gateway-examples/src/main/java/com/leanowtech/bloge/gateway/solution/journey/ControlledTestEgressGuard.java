package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.solution.FeatureEvaluationBackend;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fail-closed process boundary for one controlled business test service.
 *
 * <p>The controlled adapters never receive a runtime Feature backend, HTTP client or governed
 * Instruction channel. Infrastructure that needs an explicit negative capability can use the
 * denied adapters returned here; the attempt is rejected before delegation and remains observable
 * to the evidence boundary.</p>
 */
public final class ControlledTestEgressGuard {
    private final Probe probe;
    private final AtomicInteger deniedAttempts = new AtomicInteger();

    /** Creates the production guard with no optional infrastructure probe. */
    public ControlledTestEgressGuard() {
        this(guard -> { });
    }

    /** Creates a guard with a probe used to verify an integration boundary before each case. */
    public ControlledTestEgressGuard(Probe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Verifies the process boundary before controlled evaluation starts. */
    public void verifyBeforeCase() {
        probe.verify(this);
        if (deniedAttempts.get() > 0) throw denied();
    }

    /** Rejects an attempted HTTP, Feature or Instruction egress before it can delegate. */
    public void deny(String boundary) {
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Controlled egress boundary is required");
        }
        deniedAttempts.incrementAndGet();
        throw denied();
    }

    /** Returns a Feature backend that can only prove that real evaluation was blocked. */
    public FeatureEvaluationBackend deniedFeatureBackend() {
        return (feature, inputs, identity) -> {
            deny("FEATURE");
            throw new IllegalStateException("unreachable");
        };
    }

    /** Returns an Instruction channel that can only prove that real dispatch was blocked. */
    public InstructionDispatchChannel deniedInstructionChannel() {
        return (instruction, values, context) -> {
            deny("INSTRUCTION");
            throw new IllegalStateException("unreachable");
        };
    }

    /** Returns the number of prevented attempts without exposing payloads or destinations. */
    public int deniedAttempts() {
        return deniedAttempts.get();
    }

    private static SolutionContractException denied() {
        return new SolutionContractException("CONTROLLED_TEST_EGRESS_DENIED",
                "Controlled business tests cannot perform external calls.");
    }

    /** Optional infrastructure assertion executed before a controlled case. */
    @FunctionalInterface
    public interface Probe {
        /** Must call {@link ControlledTestEgressGuard#deny(String)} for any attempted egress. */
        void verify(ControlledTestEgressGuard guard);
    }
}
