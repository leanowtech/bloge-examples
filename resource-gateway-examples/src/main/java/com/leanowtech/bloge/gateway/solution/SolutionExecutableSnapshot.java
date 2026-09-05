package com.leanowtech.bloge.gateway.solution;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;

import java.util.List;
import java.util.Objects;

/**
 * One version-fenced executable Solution closure detached from the mutable authoring registry.
 *
 * <p>The coordinates cover the top-level Solution and every Feature, Scenario and Instruction
 * loaded into {@link #contracts()}. An effect boundary checks them immediately before reserving an
 * external execution, then evaluates and dispatches only the frozen contracts.</p>
 */
public record SolutionExecutableSnapshot(
        SolutionEntityRegistry.RegisteredEntity solutionIdentity,
        PublishedSolutionSnapshot contracts,
        List<EntityCoordinate> coordinates) {

    /** Freezes the coordinate vector and rejects inconsistent top-level identities. */
    public SolutionExecutableSnapshot {
        solutionIdentity = Objects.requireNonNull(solutionIdentity, "solutionIdentity");
        contracts = Objects.requireNonNull(contracts, "contracts");
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
        if (!solutionIdentity.ref().equals(contracts.solution().solutionRef())
                || coordinates.isEmpty()) {
            throw new IllegalArgumentException("executable Solution snapshot is incomplete");
        }
    }

    /**
     * Checks that no covered authoring row changed while this snapshot was being materialized.
     * A later edit is safe after this check because execution no longer reads the registry.
     */
    public boolean isCurrent(AgentTddStateRepository states, String scopeKey) {
        Objects.requireNonNull(states, "states");
        return coordinates.stream().allMatch(coordinate -> states.find(
                        scopeKey, coordinate.storageKind(), coordinate.ref())
                .map(asset -> asset.revision() == coordinate.revision()
                        && coordinate.contractFingerprint().equals(
                        asset.data().path("contractFingerprint").asText()))
                .orElse(false));
    }

    /** Immutable persisted coordinate captured with one decoded entity contract. */
    public record EntityCoordinate(
            String storageKind, String ref, long revision, String contractFingerprint) {
        /** Normalizes required text and rejects a non-persisted coordinate. */
        public EntityCoordinate {
            storageKind = Objects.requireNonNull(storageKind, "storageKind");
            ref = Objects.requireNonNull(ref, "ref");
            contractFingerprint = Objects.requireNonNull(
                    contractFingerprint, "contractFingerprint");
            if (storageKind.isBlank() || ref.isBlank() || revision < 1
                    || contractFingerprint.isBlank()) {
                throw new IllegalArgumentException("executable entity coordinate is incomplete");
            }
        }
    }
}
