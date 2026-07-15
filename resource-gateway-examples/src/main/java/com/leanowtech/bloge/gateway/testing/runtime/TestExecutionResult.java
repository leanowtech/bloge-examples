package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

/** Result shared by all testability adapters. Graph result is absent for preflight rejection. */
public record TestExecutionResult(
        EffectiveExecutionPlan plan,
        GraphResult graphResult,
        TestRunEvidence evidence
) {
    /** @return whether execution and all assertions/consumption policies passed */
    public boolean passed() {
        return evidence != null && evidence.status() == TestRunEvidence.Status.PASSED;
    }
}
