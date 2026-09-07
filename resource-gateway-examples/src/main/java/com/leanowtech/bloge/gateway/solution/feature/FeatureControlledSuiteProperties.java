package com.leanowtech.bloge.gateway.solution.feature;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Rollout controls for suite-backed Feature verification. */
@Component
@ConfigurationProperties(prefix = "gateway.agent-tdd.feature-controlled-suite", ignoreUnknownFields = false)
public final class FeatureControlledSuiteProperties {
    private int minimumCoveragePercent = 100;
    private boolean legacySingleFixtureEnabled;
    private long reconciliationInitialDelayMs = 60_000;
    private long reconciliationFixedDelayMs = 21_600_000;

    /** @return minimum proved coverage required for a current suite evidence */
    public int getMinimumCoveragePercent() {
        return minimumCoveragePercent;
    }

    /** @param value required percentage in the inclusive range 1..100 */
    public void setMinimumCoveragePercent(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("minimumCoveragePercent must be in 1..100");
        }
        minimumCoveragePercent = value;
    }

    /** @return whether the explicitly temporary single-fixture fulfilment path is enabled */
    public boolean isLegacySingleFixtureEnabled() {
        return legacySingleFixtureEnabled;
    }

    /** @param enabled whether accountable engineers may use the legacy single-fixture path */
    public void setLegacySingleFixtureEnabled(boolean enabled) {
        legacySingleFixtureEnabled = enabled;
    }

    /** @return delay before the first protected-suite reconciliation pass */
    public long getReconciliationInitialDelayMs() {
        return reconciliationInitialDelayMs;
    }

    /** @param value positive delay before the first reconciliation pass */
    public void setReconciliationInitialDelayMs(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("reconciliationInitialDelayMs must be positive");
        }
        reconciliationInitialDelayMs = value;
    }

    /** @return fixed delay between completed protected-suite reconciliation passes */
    public long getReconciliationFixedDelayMs() {
        return reconciliationFixedDelayMs;
    }

    /** @param value positive delay between completed reconciliation passes */
    public void setReconciliationFixedDelayMs(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("reconciliationFixedDelayMs must be positive");
        }
        reconciliationFixedDelayMs = value;
    }
}
