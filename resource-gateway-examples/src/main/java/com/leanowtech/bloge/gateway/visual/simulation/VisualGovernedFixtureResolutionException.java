package com.leanowtech.bloge.gateway.visual.simulation;

/** Safe, payload-free failure raised by a governed Fixture simulation boundary. */
public class VisualGovernedFixtureResolutionException extends RuntimeException {
    private final int status;

    /** Creates a governed Fixture resolution failure. */
    public VisualGovernedFixtureResolutionException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** @return HTTP-compatible status for this safe failure */
    public int status() {
        return status;
    }
}
