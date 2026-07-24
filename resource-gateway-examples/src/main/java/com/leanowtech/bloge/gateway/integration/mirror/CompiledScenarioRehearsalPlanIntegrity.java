package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/**
 * Canonical content-addressing boundary for compiled ScenarioPack execution licenses.
 */
public final class CompiledScenarioRehearsalPlanIntegrity {
    /** Maximum canonical bytes admitted for one compiled plan. */
    public static final int MAXIMUM_BYTES = 8 * 1024 * 1024;

    private CompiledScenarioRehearsalPlanIntegrity() {
    }

    /**
     * Seals one compiler-produced payload-free plan.
     *
     * @param mapper canonical protocol mapper
     * @param plan unsealed plan material
     * @return sealed plan
     */
    public static CompiledScenarioRehearsalPlan seal(
            ObjectMapper mapper, CompiledScenarioRehearsalPlan plan) {
        Objects.requireNonNull(mapper, "mapper");
        CompiledScenarioRehearsalPlan material =
                Objects.requireNonNull(plan, "plan").withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(mapper, material, MAXIMUM_BYTES));
    }

    /**
     * Recomputes and verifies one compiled plan.
     *
     * @param mapper canonical protocol mapper
     * @param plan sealed plan
     */
    public static void verify(
            ObjectMapper mapper, CompiledScenarioRehearsalPlan plan) {
        if (plan == null || plan.fingerprint().isBlank()
                || !plan.fingerprint().equals(seal(mapper, plan).fingerprint())) {
            throw new IllegalArgumentException(
                    "compiled scenario rehearsal plan fingerprint mismatch");
        }
    }

    /** @return exact COMPILED_REHEARSAL_PLAN reference for a sealed plan */
    public static MirrorArtifactRef reference(
            CompiledScenarioRehearsalPlan plan) {
        if (plan == null || plan.fingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "compiled rehearsal plan must be sealed before reference");
        }
        return new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                plan.planId(),
                plan.revision(),
                plan.fingerprint());
    }
}
