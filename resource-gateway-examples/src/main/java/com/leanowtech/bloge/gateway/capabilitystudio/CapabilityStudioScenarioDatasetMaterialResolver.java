package com.leanowtech.bloge.gateway.capabilitystudio;

/**
 * Resolves protected execution material for one exact Dataset case.
 *
 * <p>Implementations belong to the controlled authoring/runtime boundary. This interface is
 * package-private on purpose: it is not a Controller-facing payload API.</p>
 */
@FunctionalInterface
interface CapabilityStudioScenarioDatasetMaterialResolver {

    /**
     * Resolves the Given, dependency controls, and assertions for one exact case.
     *
     * @param dataset complete payload-free Dataset projection
     * @param dataCase exact case coordinate selected from {@code dataset}
     * @return protected material, or {@code null} when the case is not materialized
     */
    CapabilityStudioScenarioDatasetMaterial.CaseMaterial resolve(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase);
}
