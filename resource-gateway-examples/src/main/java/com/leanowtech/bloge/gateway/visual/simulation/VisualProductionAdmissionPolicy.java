package com.leanowtech.bloge.gateway.visual.simulation;

/**
 * Visual-owned, immutable admission evidence for production simulation.
 *
 * <p>The configuration layer translates server deployment evidence into this narrow value object;
 * visual runtime code does not depend on gateway configuration types.</p>
 */
public record VisualProductionAdmissionPolicy(boolean productionDeployment, String environmentId) {
    public static final String DEFAULT_ENVIRONMENT_ID = "prod";

    public VisualProductionAdmissionPolicy {
        environmentId = normalize(environmentId);
        productionDeployment = productionDeployment || isProduction(environmentId);
    }

    /** Production-safe default when no profile or environment override is present. */
    public static VisualProductionAdmissionPolicy productionDefault() {
        return new VisualProductionAdmissionPolicy(true, DEFAULT_ENVIRONMENT_ID);
    }

    /** Explicit non-production policy for isolated visual runtime tests. */
    public static VisualProductionAdmissionPolicy nonProductionTest() {
        return new VisualProductionAdmissionPolicy(false, "test");
    }

    /** Creates policy evidence for focused tests without consulting request data. */
    public static VisualProductionAdmissionPolicy fromEvidence(boolean productionProfileActive,
                                                               String configuredEnvironment) {
        return new VisualProductionAdmissionPolicy(productionProfileActive, configuredEnvironment);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? DEFAULT_ENVIRONMENT_ID : value.trim();
    }

    private static boolean isProduction(String value) {
        return "prod".equalsIgnoreCase(value) || "production".equalsIgnoreCase(value);
    }
}
