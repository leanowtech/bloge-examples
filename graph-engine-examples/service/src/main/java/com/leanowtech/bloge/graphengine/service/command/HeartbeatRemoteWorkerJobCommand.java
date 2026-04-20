package com.leanowtech.bloge.graphengine.service.command;

import java.time.Duration;

/**
 * Command that renews the lease for one claimed remote-worker job.
 */
public record HeartbeatRemoteWorkerJobCommand(
        String itemId,
        String leaseToken,
        Duration leaseDuration
) {
    public HeartbeatRemoteWorkerJobCommand {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken must not be blank");
        }
        leaseDuration = PollRemoteWorkerJobsCommand.normalizeLeaseDuration(leaseDuration, "leaseDuration");
    }
}
