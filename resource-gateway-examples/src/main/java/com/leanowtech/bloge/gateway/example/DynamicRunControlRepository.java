package com.leanowtech.bloge.gateway.example;

import java.time.Instant;
import java.util.Optional;

/** Durable authority for controlled-run lifecycle state and owner leases. */
public interface DynamicRunControlRepository {

    Claim claim(DynamicRunIntent intent, String ownerId, Instant leaseExpiresAt);

    Optional<State> find(String requestId, Instant now);

    Optional<State> start(String requestId, Owner owner, Instant now, Instant leaseExpiresAt);

    Optional<State> observeExecutionId(String requestId, Owner owner, String executionId,
                                       Instant leaseExpiresAt);

    Optional<State> requestOwnerStop(String requestId, Owner owner, String status, String reasonCode,
                                     Instant now, Instant leaseExpiresAt);

    CommandResult requestCallerCancel(DynamicRunControlCommand command, Instant now);

    Optional<State> markUnconfirmed(String requestId, Owner owner, String reasonCode, Instant now);

    Optional<State> finish(String requestId, Owner owner, String status, String reasonCode, Instant now);

    Optional<State> renew(String requestId, Owner owner, Instant leaseExpiresAt);

    void purgeTerminalBefore(Instant cutoff);

    record Owner(String id, long epoch) {
        public Owner {
            id = id == null ? "" : id;
            epoch = Math.max(0, epoch);
        }
    }

    record State(
            DynamicRunControlView view,
            String fenceDigest,
            Owner owner,
            Instant leaseExpiresAt,
            long cancellationGraceMs,
            String recoveryDisposition
    ) {
        public State {
            view = view == null ? DynamicRunControlView.unmanaged() : view;
            fenceDigest = fenceDigest == null ? "" : fenceDigest;
            owner = owner == null ? new Owner("", 0) : owner;
            cancellationGraceMs = cancellationGraceMs <= 0
                    ? DynamicRunIntent.DEFAULT_CANCELLATION_GRACE_MS
                    : cancellationGraceMs;
            recoveryDisposition = recoveryDisposition == null ? "" : recoveryDisposition;
        }
    }

    record Claim(boolean accepted, String code, String message, State state) {
        public Claim {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
    }

    record CommandResult(boolean accepted, String code, String message, State state) {
        public CommandResult {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
    }
}
