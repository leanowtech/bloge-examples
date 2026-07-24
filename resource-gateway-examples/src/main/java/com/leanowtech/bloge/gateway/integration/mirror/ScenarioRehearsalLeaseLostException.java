package com.leanowtech.bloge.gateway.integration.mirror;

/** Signals that an aggregate worker no longer owns terminal or checkpoint authority. */
public final class ScenarioRehearsalLeaseLostException
        extends RuntimeException {
    /** Creates the bounded stale-worker signal without exposing lease material. */
    public ScenarioRehearsalLeaseLostException() {
        super("Scenario rehearsal aggregate lease is no longer current");
    }
}
