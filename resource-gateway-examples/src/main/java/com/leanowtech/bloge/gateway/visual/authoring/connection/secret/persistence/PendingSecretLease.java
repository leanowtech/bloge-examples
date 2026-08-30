package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.time.Instant;
import java.util.Objects;

/** Exact command-attempt fence for a pending secret batch. */
public record PendingSecretLease(CommandLease commandLease, ConnectionRevisionCoordinate coordinate) {
    public PendingSecretLease {
        Objects.requireNonNull(commandLease, "commandLease");
        Objects.requireNonNull(coordinate, "coordinate");
        if (!coordinate.scope().equals(commandLease.key().scope())
                || !coordinate.connectionId().equals(commandLease.key().targetId())) {
            throw new IllegalArgumentException("lease coordinate is invalid");
        }
    }

    /** Provider/store expiry inherited from the command lease. */
    public Instant leaseUntil() { return commandLease.leaseUntil(); }

    /** Attempt token is a persistence fence and never a JSON property. */
    @JsonIgnore @Override public CommandLease commandLease() { return commandLease; }

    @Override public String toString() {
        return "PendingSecretLease[commandId=" + commandLease.commandId() + ", attemptNo="
                + commandLease.attemptNo() + ", connectionId=" + coordinate.connectionId()
                + ", revision=" + coordinate.revision() + "]";
    }
}
