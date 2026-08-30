package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;

import java.time.Instant;

/** Fencing token and expiry attached to a claimed command attempt. */
public record CommandLease(String commandId, String attemptToken, CommandKey key,
                           String requestFingerprint, Instant leaseUntil,
                           ExpectedRevision expectedRevision) {
    /** Validates the immutable lease coordinate. */
    public CommandLease {
        require(commandId, "commandId");
        require(attemptToken, "attemptToken");
        if (key == null || expectedRevision == null || leaseUntil == null) throw new IllegalArgumentException("lease fields are required");
        require(requestFingerprint, "requestFingerprint");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
