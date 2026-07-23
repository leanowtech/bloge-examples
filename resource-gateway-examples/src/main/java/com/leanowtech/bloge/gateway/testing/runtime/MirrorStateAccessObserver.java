package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;

/**
 * Run-scoped sink for payload-free Session state resolver observations.
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
