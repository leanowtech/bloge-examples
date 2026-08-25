package com.leanowtech.bloge.gateway.visual.simulation;

/**
 * Raised when visual graph simulation is invoked by a production deployment.
 *
 * <p>This exception intentionally carries no request, deployment, or business payload. The
 * transport layer owns its projection into a standard HTTP problem response.</p>
 */
public final class VisualSimulationProductionAdmissionException extends RuntimeException {
    public static final String CODE = "RG.PRODUCTION.VISUAL_SIMULATION_FORBIDDEN";
    public static final String TITLE = "Visual graph simulation is unavailable in a production deployment.";

    public VisualSimulationProductionAdmissionException() {
        super(TITLE);
    }
}
