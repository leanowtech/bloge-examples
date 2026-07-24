package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/**
 * Append-only persistence boundary for payload-free Scenario aggregate lifecycle facts.
 */
public interface ScenarioRehearsalLifecycleAuditRepository {
    /**
     * Appends one new lifecycle fact using database-assigned sequence and time.
     *
     * @param event event with sequence zero and no occurrence time
     * @return persisted exact event
     */
    ScenarioRehearsalLifecycleAuditEvent append(
            ScenarioRehearsalLifecycleAuditEvent event);

    /**
     * Reads one aggregate lifecycle in append order inside exact enterprise scope.
     *
     * @param scope complete enterprise scope
     * @param requestId aggregate request identity
     * @param limit bounded maximum event count
     * @return oldest-to-newest event prefix
     */
    List<ScenarioRehearsalLifecycleAuditEvent> lifecycle(
            CapabilitySnapshot.Scope scope,
            String requestId,
            int limit);
}
