package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment;

import java.util.Map;

/**
 * Installed physical-attempt provider adapters available for signed inventory selection.
 *
 * <p>The catalog is an installation superset, not an admission authority. Only bindings present
 * in the current verified publication can be resolved, and every returned adapter remains fenced
 * to that publication generation.</p>
 */
@FunctionalInterface
public interface TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog {

    /**
     * Returns the immutable installed provider/deployment adapter map.
     *
     * @return installed adapters keyed by retained deployment identity
     */
    Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> adapters();
}
