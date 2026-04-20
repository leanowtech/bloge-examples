package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that reports one claimed remote-worker job as failed.
 */
public record FailRemoteWorkerJobCommand(
        String itemId,
        String leaseToken,
        long expectedRevision,
        String error
) {
    public FailRemoteWorkerJobCommand {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("leaseToken must not be blank");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("error must not be blank");
        }
    }
}
