package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable cross-replica lease and exact-result authority for implementation conformance. */
public interface CapabilityImplementationConformanceRepository {
    Claim claim(Registration registration, String leaseOwner, Duration leaseDuration);

    boolean renew(Lease lease, Duration leaseDuration);

    boolean complete(Lease lease, StoredCapabilityImplementationConformance result);

    boolean release(Lease lease, String failureCode);

    Optional<State> find(
            CapabilitySnapshot.Scope scope, String bindingId, long bindingRevision);

    enum Outcome {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    enum Status {
        ACTIVE,
        COMPLETED
    }

    record Registration(
            CapabilitySnapshot.Scope scope,
            String conformanceId,
            String proposalId,
            long proposalRevision,
            MirrorArtifactRef implementationBindingRef,
            String requestFingerprint
    ) {
    }

    record Lease(
            CapabilitySnapshot.Scope scope,
            String conformanceId,
            String bindingId,
            long bindingRevision,
            String leaseOwner,
            long leaseEpoch
    ) {
    }

    record State(
            Registration registration,
            Status status,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            StoredCapabilityImplementationConformance result,
            String lastFailureCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record Claim(Outcome outcome, State state, Lease lease, long retryAfterSeconds) {
    }
}
