package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;

import java.time.Instant;

/**
 * Run-scoped sink for payload-free Session read and transition observations.
 *
 * <p>The observer receives only canonical fingerprints and exact protocol references. It must
 * never receive entity values or business-key components. Ordinary tests use {@link #noop()} and
 * retain their existing allocation and evidence behavior.</p>
 */
public interface MirrorStateAccessObserver {

    /**
     * Records one completed lookup against the frozen Session state head.
     *
     * @param request current invocation coordinates and payload-free request fingerprint
     * @param spec exact state read specification used by the resolver
     * @param businessKeyFingerprint canonical ordered business-key component fingerprint
     * @param outcome live, absent, or tombstoned result
     * @param stateRecordFingerprint entity or tombstone fingerprint; blank for absent
     * @param projectedOutputFingerprint projected output fingerprint; present only for live
     */
    void observed(
            MirrorResolver.Request request,
            StateReadSpec spec,
            String businessKeyFingerprint,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint);

    /**
     * Records one lookup together with the exact in-run Session head it observed.
     *
     * <p>The default preserves the v1 read-only observer contract. Read/write evidence journals
     * override this method to retain the additional payload-free state coordinates.</p>
     *
     * @param request current invocation coordinates
     * @param spec exact state read specification
     * @param observedStateRef exact Session head observed by the resolver
     * @param observedStateRevision committed state revision observed
     * @param observedWorldFingerprint exact business-world identity observed
     * @param observedLogicalClock exact deterministic logical time observed
     * @param businessKeyFingerprint hash of ordered key components
     * @param outcome live, absent, or tombstoned
     * @param stateRecordFingerprint entity or tombstone fingerprint; blank for absent
     * @param projectedOutputFingerprint output fingerprint; present only for live
     */
    default void observedAt(
            MirrorResolver.Request request,
            StateReadSpec spec,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String businessKeyFingerprint,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint) {
        observed(
                request, spec, businessKeyFingerprint,
                outcome, stateRecordFingerprint,
                projectedOutputFingerprint);
    }

    /**
     * Records one durably committed or exactly replayed graph virtual write.
     *
     * @param request current invocation coordinates and payload-free request fingerprint
     * @param spec exact write effect executed by the resolver
     * @param transition payload-free before/after, receipt, and event closure
     */
    default void transitioned(
            MirrorResolver.Request request,
            WriteEffectSpec spec,
            MirrorStateTransitionObservation transition) {
        // Read-only observers remain source-compatible.
    }

    /**
     * Records one terminal rejected, pre-commit-failed, or commit-outcome-unknown virtual write.
     *
     * <p>The observation contains the exact pre-attempt state head and only normalized failure
     * facts. An observer must preserve an unknown commit outcome as unknown until a durable
     * reconciliation authority proves the original command result.</p>
     *
     * @param request current invocation coordinates and payload-free request fingerprint
     * @param spec exact write effect selected by the resolver
     * @param failure payload-free terminal write-attempt observation
     */
    default void writeFailed(
            MirrorResolver.Request request,
            WriteEffectSpec spec,
            MirrorStateWriteAttemptObservation failure) {
        // Read-only observers remain source-compatible.
    }

    /** @return allocation-free observer for ordinary or stateless mirror execution */
    static MirrorStateAccessObserver noop() {
        return Noop.INSTANCE;
    }

    /** Neutral observer used when no Session state evidence is admitted. */
    enum Noop implements MirrorStateAccessObserver {
        INSTANCE;

        @Override
        public void observed(
                MirrorResolver.Request request,
                StateReadSpec spec,
                String businessKeyFingerprint,
                MirrorStateRunEvidence.AccessOutcome outcome,
                String stateRecordFingerprint,
                String projectedOutputFingerprint) {
            // Stateless and ordinary test runs do not produce Session state evidence.
        }
    }
}
