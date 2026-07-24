package com.leanowtech.bloge.gateway.integration.mirror;

/** Signals reuse of one aggregate request id for different immutable rehearsal semantics. */
public final class ScenarioRehearsalRunRequestConflictException
        extends RuntimeException {
    /** Creates the bounded conflict without reflecting request material. */
    public ScenarioRehearsalRunRequestConflictException() {
        super("Scenario rehearsal request id already identifies different inputs");
    }
}
