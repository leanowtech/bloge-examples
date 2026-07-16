package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/** Computes and verifies nested content identities for durable test checkpoints. */
public final class DurableTestExecutionCheckpointIntegrity {

    private static final int MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper objectMapper;

    public DurableTestExecutionCheckpointIntegrity(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Seals unsealed fixture state and the complete composite closure after verifying provider state.
     * Existing non-empty fingerprints must already be correct; this method never blesses tampering.
     *
     * @param checkpoint unsealed or already valid checkpoint
     * @return fully content-addressed checkpoint
     */
    public DurableTestExecutionCheckpoint seal(DurableTestExecutionCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        requireValidExecutionServiceState(checkpoint.executionServiceState());
        FixtureConsumptionStateSnapshot fixtureState = checkpoint.fixtureConsumptionState();
        String fixtureFingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, fixtureState.fingerprintMaterial(),
                FixtureConsumptionStateSnapshot.MAX_CANONICAL_BYTES);
        if (!fixtureState.stateFingerprint().isEmpty()
                && !fixtureState.stateFingerprint().equals(fixtureFingerprint)) {
            throw new IllegalArgumentException("Invalid fixture-consumption state fingerprint");
        }
        FixtureConsumptionStateSnapshot sealedFixture =
                fixtureState.withStateFingerprint(fixtureFingerprint);
        DurableTestExecutionCheckpoint material = checkpoint
                .withFixtureConsumptionState(sealedFixture)
                .withCheckpointFingerprint("");
        String checkpointFingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, material.fingerprintMaterial(), MAX_CHECKPOINT_BYTES);
        if (!checkpoint.checkpointFingerprint().isEmpty()
                && !checkpoint.checkpointFingerprint().equals(checkpointFingerprint)) {
            throw new IllegalArgumentException("Invalid durable checkpoint fingerprint");
        }
        return material.withCheckpointFingerprint(checkpointFingerprint);
    }

    /**
     * Fails closed when any nested or aggregate fingerprint differs from canonical material.
     *
     * @param checkpoint checkpoint crossing a trusted persistence boundary
     */
    public void requireValid(DurableTestExecutionCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        requireValidExecutionServiceState(checkpoint.executionServiceState());
        String fixtureFingerprint = ProtocolFingerprint.ofBounded(objectMapper,
                checkpoint.fixtureConsumptionState().fingerprintMaterial(),
                FixtureConsumptionStateSnapshot.MAX_CANONICAL_BYTES);
        if (!fixtureFingerprint.equals(checkpoint.fixtureConsumptionState().stateFingerprint())) {
            throw new IllegalArgumentException("Invalid fixture-consumption state fingerprint");
        }
        String fingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, checkpoint.fingerprintMaterial(), MAX_CHECKPOINT_BYTES);
        if (!fingerprint.equals(checkpoint.checkpointFingerprint())) {
            throw new IllegalArgumentException("Invalid durable checkpoint fingerprint");
        }
    }

    private void requireValidExecutionServiceState(ExecutionServiceStateSnapshot snapshot) {
        String fingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, snapshot.fingerprintMaterial(),
                FixtureConsumptionStateSnapshot.MAX_CANONICAL_BYTES);
        if (!fingerprint.equals(snapshot.snapshotFingerprint())) {
            throw new IllegalArgumentException("Invalid execution-service state fingerprint");
        }
    }
}
