package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.time.Duration;
import java.util.Optional;

/** Durable cross-replica lease and exact-result authority for Proposal simulation. */
public interface CapabilityProposalSimulationRepository {
    Claim claim(Registration registration, String leaseOwner, Duration leaseDuration);

    boolean renew(Lease lease, Duration leaseDuration);

    boolean complete(Lease lease, StoredCapabilityProposalSimulation result);

    boolean release(Lease lease, String failureCode);

    Optional<State> find(
            CapabilitySnapshot.Scope scope, String proposalId, long proposalRevision);

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
            String simulationId,
            String proposalId,
            long proposalRevision,
            String requestFingerprint
    ) {
    }

    record Lease(
            CapabilitySnapshot.Scope scope,
            String proposalId,
            long proposalRevision,
            String simulationId,
            String leaseOwner,
            long leaseEpoch
    ) {
    }

    record State(
            Registration registration,
            Status status,
            String leaseOwner,
            long leaseEpoch,
            java.time.Instant leaseExpiresAt,
            StoredCapabilityProposalSimulation result,
            String lastFailureCode,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }

    record Claim(Outcome outcome, State state, Lease lease, long retryAfterSeconds) {
    }
}
