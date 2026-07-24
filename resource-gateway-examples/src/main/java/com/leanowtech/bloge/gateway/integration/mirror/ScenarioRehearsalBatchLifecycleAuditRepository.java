package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/**
 * Append-only persistence boundary for payload-free Scenario batch lifecycle facts.
 */
public interface ScenarioRehearsalBatchLifecycleAuditRepository {
    /**
     * Appends one new lifecycle fact using database-assigned sequence and time.
     *
     * @param event event with sequence zero and no occurrence time
     * @return persisted exact event
     */
    ScenarioRehearsalBatchLifecycleAuditEvent append(
            ScenarioRehearsalBatchLifecycleAuditEvent event);

    /**
     * Reads one job lifecycle in append order inside exact enterprise scope.
     *
     * @param scope complete enterprise scope
     * @param jobId stable batch job identity
     * @param limit bounded maximum event count
     * @return oldest-to-newest event prefix
     */
    List<ScenarioRehearsalBatchLifecycleAuditEvent> lifecycle(
            CapabilitySnapshot.Scope scope,
            String jobId,
            int limit);
}
