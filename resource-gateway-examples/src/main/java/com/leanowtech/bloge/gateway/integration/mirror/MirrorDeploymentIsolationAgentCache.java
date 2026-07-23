package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Atomic deployment-agent cache boundary.
 *
 * <p>One writer replaces complete verified generations through compare-and-set. Runtime readers
 * observe either the previous complete generation or its complete successor, never a mixture.</p>
 */
public interface MirrorDeploymentIsolationAgentCache {

    /**
     * Reads the complete current generation without remote I/O.
     *
     * @return independently verified current generation, or empty before bootstrap
     */
    Optional<MirrorDeploymentIsolationAgentSnapshot> current();

    /**
     * Atomically replaces the complete cache generation.
     *
     * @param expectedSnapshotFingerprint exact current fingerprint, or blank before bootstrap
     * @param candidate canonical next generation
     * @return committed detached generation
     */
    MirrorDeploymentIsolationAgentSnapshot replace(
            String expectedSnapshotFingerprint,
            MirrorDeploymentIsolationAgentSnapshot candidate);

    /**
     * Reports whether accepted state survives complete process restart.
     *
     * @return whether accepted state survives complete process restart
     */
    boolean durable();
}
