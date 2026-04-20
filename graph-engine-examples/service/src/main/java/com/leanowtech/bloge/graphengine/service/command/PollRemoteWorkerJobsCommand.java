package com.leanowtech.bloge.graphengine.service.command;

import java.time.Duration;

/**
 * Command that polls and claims ready remote-worker jobs for one worker topic.
 */
public record PollRemoteWorkerJobsCommand(
        String workerId,
        String workerTopic,
        int limit,
        Duration leaseDuration
) {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);

    public PollRemoteWorkerJobsCommand {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (workerTopic == null || workerTopic.isBlank()) {
            throw new IllegalArgumentException("workerTopic must not be blank");
        }
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        leaseDuration = normalizeLeaseDuration(leaseDuration, "leaseDuration");
    }

    static Duration normalizeLeaseDuration(Duration leaseDuration, String fieldName) {
        if (leaseDuration == null) {
            return DEFAULT_LEASE_DURATION;
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return leaseDuration;
    }
}
