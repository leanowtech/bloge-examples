package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that completes one claimed remote-worker job with an output payload.
 */
public record CompleteRemoteWorkerJobCommand(
        String itemId,
        String leaseToken,
        long expectedRevision,
        Object output
) {
    public CompleteRemoteWorkerJobCommand {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken must not be blank");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
    }
}
