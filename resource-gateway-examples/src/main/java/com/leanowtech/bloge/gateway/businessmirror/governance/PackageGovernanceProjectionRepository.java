package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Objects;
import java.util.Optional;

/** Append-only ANEKE projection log with one monotonic head per exact Package scope. */
public interface PackageGovernanceProjectionRepository {
    /** Commits the next external generation or recovers an identical replay. */
    AppendResult append(DomainCapabilityPackageGovernanceProjection projection);

    /** Returns the current durable external projection for an exact Package scope. */
    Optional<DomainCapabilityPackageGovernanceProjection> findCurrent(
            CapabilitySnapshot.Scope scope, String packageId);

    /** Repository outcome distinguishes a new fact from idempotent recovery. */
    record AppendResult(
            DomainCapabilityPackageGovernanceProjection projection,
            boolean replayed) {
        public AppendResult {
            projection = Objects.requireNonNull(projection, "projection");
        }
    }

    /** Closed, payload-free rejection vocabulary for external generation fencing. */
    enum Reason {
        CANONICAL_INVALID,
        BOOTSTRAP_GENERATION_INVALID,
        GENERATION_ROLLBACK,
        GENERATION_FORK,
        GENERATION_GAP,
        STREAM_IDENTITY_MISMATCH,
        CONTENT_ADDRESS_CONFLICT,
        STORED_STATE_CORRUPT
    }

    /** Stable invariant failure whose message never includes projection material. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        public Violation(Reason reason) {
            super("Package governance projection repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
