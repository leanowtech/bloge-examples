package com.leanowtech.bloge.gateway.testing.api;

import java.util.Map;

/**
 * Creates a durable certificate-generation floor for one exact deployment inventory.
 *
 * <p>The factory keeps the product runtime independent from a database implementation while making
 * durability mandatory whenever rotation is enabled. Implementations must initialize and verify
 * the supplied baseline before returning.</p>
 */
@FunctionalInterface
public interface ControlPlaneCertificateRotationFloorFactory {

    /**
     * Creates or reconstructs one durable floor.
     *
     * @param deploymentScopeId exact Resource Gateway deployment scope
     * @param initialTargets non-empty out-of-band baseline inventory
     * @return initialized durable floor
     */
    ControlPlaneCertificateRotationFloor create(
            String deploymentScopeId,
            Map<String, ControlPlaneCertificateRotationFloor.InitialTarget> initialTargets);
}
